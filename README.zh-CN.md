# CilExec

**CilExec** 是一个可持久化的脚本与进程管理引擎：在 **PostgreSQL 支撑的运行时**上执行 **FCL**（Follarce CilExec Language）脚本。**PostgreSQL 是唯一权威状态**。崩溃后，运行时从已提交的数据库行重建工作；尚未提交的事务会回滚，对应执行片段可能重放。
> **开发说明**：CilExec 在开发过程中使用了 AI，项目的架构、设计、审查和决策始终由人工主导。

> 为什么需要它：普通脚本运行时的进程状态保存在内存里，崩溃即丢失。CilExec 把每个执行切片放进一个显式数据库事务中提交，因此已提交的脚本状态、定时器、消息队列和外部效果记录都能跨重启存活，多租户隔离由数据库本身强制（行级安全 RLS）。

## 核心特性

- **零内存状态** —— 程序、完整延续（continuation）、调度租约、IPC、定时器、VFS、包绑定、副作用日志、终端输入和审计事件全部存于 PostgreSQL；数据库 advisory lock 保证同一数据库只有一个活跃运行时。
- **崩溃安全的延续** —— 每个已提交执行切片持久化完整解释器状态（变量、调用栈、程序计数器、系统实体句柄）；`state_version` + `execution_epoch` 围栏防止陈旧 worker 提交。
- **不可变可执行产物** —— 新程序同时保留可读 FCL 源码和独立的版本化 FCLB 指令产物；恢复时校验两者，直接执行原有指令表而不重新编译源码。
- **天然多租户** —— 每个用户对应一个稳定 PostgreSQL `NOLOGIN`、`NOINHERIT` 租户角色并强制 RLS；`SYSTEM_ADMIN` 是应用级超级用户，绝不获得集群或宿主特权。
- **内容寻址 VFS** —— 可变命名空间节点指向不可变的 SHA-256 文件或符号链接内容；目录和挂载是关系型元数据。跨用户管理操作经 SECURITY DEFINER API 并全程审计。
- **不可变包** —— 离线构建的 SQLite `package.db` 发布物按精确 SHA-256 标识；import 使用精确数据库文件哈希。
- **日志化外部副作用** —— FCL 发起的 HTTP、socket 和白名单命令通过持久效果行执行。中断时外部结果可能进入 `UNKNOWN`，再按远端查询、幂等重试或人工处理。定时器使用独立的持久定时器行。
- **持久化 IPC 与定时器** —— 直连/频道/主题/广播消息和定时器可跨重启唤醒暂停的进程。
- **可验证导出** —— 基于单个只读快照的 PostgreSQL → SQLite 逻辑导出，端到端哈希校验。
- **友好的终端** —— 交互式 REPL 的进程（变量、导入、函数、工作目录）跨登出/登录和运行时重启保持；全屏 FCL 程序可用 `term.render` 发布可丢弃的重绘帧，普通 `io.print` 仍走可恢复的效果日志；另有无头协议供宿主脚本调用。
- **显式的 0.0.5 前向格式** —— 程序、延续、包和数据库 Schema 各只接受一种当前格式。旧持久数据在启动时被**明确拒绝并说明原因**；绝不转换、删除或原地迁移。

## 快速开始

### 独立 JAR（macOS / Linux）—— 唯一受支持的发行方式

安装 Java 26 后，在实例应保存的目录中构建或放置 shaded `cilexec-app.jar`：

```bash
mvn package
java -jar target/cilexec-app.jar
```

不需要 Docker、预装 PostgreSQL、Compose 文件或数据库密码文件。JAR 内含 macOS 和 Linux
的 ARM64、AMD64 PostgreSQL 17.6 二进制。首次启动会在 JAR 同级创建 `cilexec-data/`、
初始化数据库及当前 Schema，并把当前 TTY 接入终端；默认隐藏常规启动日志，需要时设置
`CILEXEC_LOG_LEVEL=INFO`。以后重启复用该目录。启动后立即显示进度提示，首次初始化可能
需要几十秒。若从 `target/` 运行 JAR，`mvn clean` 会保留 `target/cilexec-data/`；
正式使用建议把 JAR 与数据放在独立目录并备份。默认终端用户名是 `local`，首次使用时设置
管理员密码。

JAR 必须独占自己的数据目录：请从专用目录运行，不要在仓库根目录运行；启动第二个实例时
把 JAR 复制到另一目录。0.0.5 **拒绝启动**在其他 CilExec 版本创建的 `cilexec-data/`
上，并说明旧数据需由运维自行归档或处理；运行时绝不修改旧数据。

仅当设置 `CILEXEC_DATABASE_URL` 时才使用外部 PostgreSQL；该模式仍需要外部数据库凭据及
显式 `migrate` 命令。旧外部数据库会先被格式检查拒绝，不会执行任何迁移。

### 从源码运行

从源码运行需要 JDK 26 与 Maven 3.9+；不需要 Docker。

```bash
mvn package
mkdir -p /tmp/cilexec-instance
cp target/cilexec-app.jar /tmp/cilexec-instance/
cd /tmp/cilexec-instance
java -jar cilexec-app.jar
```

首次使用会要求设置管理员密码（默认用户名 `local`）。然后直接试 FCL：

```fcl
io.println("hello cilexec")
sum = 0; i = 1
while i <= 10 { sum = sum + i; i = i + 1 }
return sum

// 0.0.5 实体对象：先取得对象，再调用方法。
note = file.create("/notes/hello.txt", "first")
note.append(" second")
io.println(note.read())   // first second
```

键入 `:exit` 只断开当前终端，共享 Runtime 和后台进程继续运行；用 `:shutdown`
（需管理员密码）停止 Runtime。请用 `:shutdown` 或 SIGTERM 正常停止，不要强制 kill：
若 JAR 被杀后 PostgreSQL 仍在运行，下次启动会报告其 PID 并要求先停止，而不是等待超时。

生产环境应把 JAR 与其 `cilexec-data/` 目录一起保存，备份前先停止 Runtime，并遵循
[备份、恢复与凭据轮换手册](docs/production-backup-restore.md)。Docker 镜像发行方式及其
安装脚本自 0.0.5 起已废弃，正常使用不再需要，也不在验收范围内。

## 核心概念

| 概念 | 说明 |
|---|---|
| **FCL** | 脚本语言：`//` 注释、`#` 长度运算符、保留字、函数、循环、映射/列表、类、包导入以及实体对象方法调用。完整参考：[docs/fcl-function-reference.zh-CN.md](docs/fcl-function-reference.zh-CN.md) |
| **进程与延续** | 进程是持久对象；每个已提交切片持久化完整解释器状态（含系统实体句柄）。未提交切片会回滚，并可能在恢复后重放 |
| **VFS** | 每用户内容寻址文件树（根为 `/`），管理员可见 `/Users/<name>`；宿主文件经 `host move` 导入 |
| **包** | 不可变 SQLite 数据库，声明能力、入口点与精确哈希依赖 |
| **副作用** | 日志化的外部操作（HTTP、socket、宿主命令），崩溃可恢复，按策略重试 |

## 架构一览

```
FCL 源码 → 编译器 → ProcessStatementExecutor（每个执行切片一个事务）
        → 调度 worker（有界、租约式 FIFO）→ PostgreSQL（唯一状态存储）
        → 效果 worker（日志化副作用）· 定时器 · IPC · 终端 · VFS
```

非终端切片执行一个解释器步骤；终端切片最多批处理 4096 步或 20 毫秒，并在挂起、指令、完成或失败时结束。其余一切——连接池、线程、缓存——都是可丢弃的，启动时从已提交行重建。源码布局与设计决策见 [docs/architecture-baseline.md](docs/architecture-baseline.md)。

## 源码地图

```text
src/main/java/com/follarce
  app/            启动、停机、应用命令
  fcl/            FCL 编译器、延续运行时、内置函数、实体句柄
  application/    数据库感知内置函数、语句执行器、REPL
  persistence/    JDBC 仓库、事务、SQLite 包读取器
  scheduler/      有界 FIFO/租约 worker
  effect/         日志化外部副作用 worker
  ipc/ timer/     持久消息与定时器
  vfs/            内容寻址 VFS 用例
  package_manager/ market/   不可变包与市场客户端
  exporter/       可验证 PostgreSQL → SQLite 导出
  terminal/       交互与无头控制面
  auth/ audit/ health/ config/ extension/   安全、运维与扩展面
src/main/resources/db/baseline/   已冻结的 V001 模块（角色、RLS、SQL 函数）
src/main/resources/db/contracts/  不可变的 V002+ 契约/围栏 SQL
```

## 构建与测试

```bash
mvn test                  # 单元与生命周期测试（不需要 Docker）
mvn package -DskipTests   # 构建 target/cilexec-app.jar
mvn verify                # 可选：PostgreSQL/崩溃恢复集成测试（需要 Docker）
```

集成套件需要 Docker，不属于独立 JAR 的验收路径；正常开发用 `mvn test` 加独立 JAR
冒烟测试即可。

## 文档

- [FCL 函数与对象参考](docs/fcl-function-reference.zh-CN.md) · [English](docs/fcl-function-reference.md)
- [FCL 0.0.5 对象指南](docs/fcl-object-oriented-guide.zh-CN.md) · [English](docs/fcl-object-oriented-guide.md)
- [FCL 0.0.4 参考（历史）](docs/fcl-function-reference-004.md)
- [架构基线](docs/architecture-baseline.md)
- [FCLB 可执行产物](docs/fcl-executable-artifact.md)
- [Java 源码扩展](docs/java-extension-development.md)
- [包与市场](docs/package-market.md)
- [可恢复的 FCL 贪吃蛇](docs/snake-game.zh-CN.md)
- [无头模式](docs/headless-mode.md)
- [宿主文件导入](docs/host-vfs-import.md)
- [终端与运维](docs/terminal-and-admin-plan.md)
- [发布流程](docs/release.md)

## 许可证

[MIT](LICENSE)
