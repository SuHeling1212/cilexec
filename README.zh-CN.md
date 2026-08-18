# CilExec

**CilExec** 是一个可持久化的脚本与进程管理引擎：在 **PostgreSQL 支撑的运行时**上执行 **FCL**（Follarce CilExec Language）脚本。**PostgreSQL 是唯一权威状态**。崩溃后，运行时从已提交的数据库行重建工作；尚未提交的事务会回滚，对应执行片段可能重放。
> **开发说明**：CilExec 在开发过程中使用了 AI，项目的架构、设计、审查和决策始终由人工主导。

> 为什么需要它：普通脚本运行时的进程状态保存在内存里，崩溃即丢失。CilExec 把每个执行切片放进一个显式数据库事务中提交，因此已提交的脚本状态、定时器、消息队列和外部效果记录都能跨重启存活，多租户隔离由数据库本身强制（行级安全 RLS）。

## 核心特性

- **零内存状态** —— 程序、完整延续（continuation）、调度租约、IPC、定时器、VFS、包绑定、副作用日志、终端输入和审计事件全部存于 PostgreSQL；数据库 advisory lock 保证同一数据库只有一个活跃运行时。
- **崩溃安全的延续** —— 每个已提交执行切片持久化完整解释器状态（变量、调用栈、程序计数器）；`state_version` + `execution_epoch` 围栏防止陈旧 worker 提交。
- **天然多租户** —— 每个用户对应一个稳定 PostgreSQL `NOLOGIN`、`NOINHERIT` 租户角色并强制 RLS；`SYSTEM_ADMIN` 是应用级超级用户，绝不获得集群或宿主特权。
- **内容寻址 VFS** —— 可变命名空间节点指向不可变的 SHA-256 文件或符号链接内容；目录和挂载是关系型元数据。跨用户管理操作经 SECURITY DEFINER API 并全程审计。
- **不可变包** —— 离线构建的 SQLite `package.db` 发布物按精确 SHA-256 标识；import 使用精确数据库文件哈希。
- **日志化外部副作用** —— FCL 发起的 HTTP、socket 和白名单命令通过持久效果行执行。中断时外部结果可能进入 `UNKNOWN`，再按远端查询、幂等重试或人工处理。定时器使用独立的持久定时器行。
- **持久化 IPC 与定时器** —— 直连/频道/主题/广播消息和定时器可跨重启唤醒暂停的进程。
- **可验证导出** —— 基于单个只读快照的 PostgreSQL → SQLite 逻辑导出，端到端哈希校验。
- **友好的终端** —— 交互式 REPL 的进程（变量、导入、函数、工作目录）跨登出/登录和运行时重启保持；另有无头协议供宿主脚本调用。
- **仅前向 Schema 升级** —— 0.0.1 的 V001 是发布基线；后续持久格式变更使用不可修改的 V002+，降级通过恢复备份完成。

## 快速开始

### 使用正式安装包

前置条件：Docker Engine（Linux）或 Docker Desktop（macOS/Windows）已经启动，并且
Docker Compose 插件可用。安装器不会安装 Docker。CilExec Runtime 镜像已包含在正式
安装包中；PostgreSQL 镜像未包含，首次安装时必须能够从镜像仓库下载。

Linux 根据 Docker 主机架构下载 `linux-amd64` 或 `linux-arm64` 安装包，然后运行：

```bash
chmod +x cilexec-<版本>-linux-<架构>.sh
./cilexec-<版本>-linux-<架构>.sh
cd ~/cilexec
./tools/Install.sh
```

第一条安装命令将文件解压到 `~/cilexec`（可通过 `INSTALL_DIR` 修改），校验 Docker
架构并加载内嵌 Runtime 镜像。`Install.sh` 随后创建密钥、启动 PostgreSQL、执行迁移、
启动共享 Runtime 并进入终端。

Windows 解压 `cilexec-<版本>-windows.zip` 后，在该目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Cilexec.ps1 install
```

完整 Windows 命令见 [`windows/README.md`](windows/README.md)。

### 从源码运行

使用 `Install.sh` 从源码安装或运行只需要 Docker 与 Docker Compose 插件；镜像构建会在
Docker 内提供 JDK 26 和 Maven 3.9+。若直接在宿主机运行 Maven，则需要自行安装 JDK 26
和 Maven 3.9+。

```bash
./tools/Install.sh            # 一条命令：密钥 + PostgreSQL + 迁移 + 终端
```

首次使用会要求设置管理员密码（默认用户名 `local`）。然后直接试 FCL：

```fcl
io.println("hello cilexec")
sum = 0; i = 1
while i <= 10 { sum = sum + i; i = i + 1 }
return sum
```

从宿主免交互执行单次提交（不进入交互终端）：

```bash
./tools/Headless.sh 'value = 41'
./tools/Headless.sh 'io.println(value + 1)'     # 42；同一宿主终端的调用共享同一持久 REPL 进程
```

常用宿主命令：

```bash
./tools/Install.sh                                      # 启动或复用 Runtime，并进入终端
./tools/Headless.sh 'io.println("hello")'               # 非交互执行一段 FCL
./tools/HostMove.sh /绝对路径/report.pdf /docs/report.pdf alice
./tools/Shell.sh                                        # 进入应用或 PostgreSQL 容器
./tools/Uninstall.sh                                    # 删除本安装实例及其数据库卷
```

退出交互终端请键入 `:exit`。这只断开当前终端，后台 Runtime 和进程继续运行。正式 Linux
安装包也可直接执行 `./cilexec-<版本>-linux-<架构>.sh --uninstall`；卸载会永久删除该安装
实例的 PostgreSQL 数据卷，执行前应先备份。

使用 Docker Desktop 时，请在 16 GB 内存的宿主上给其 Linux 虚拟机至少分配
10 GB 内存（Settings → Resources → Advanced）。Runtime JVM、PostgreSQL 和
`mvn verify` 集成套件各自需要数 GB；默认 7.8 GB 虚拟机下宿主可能内存耗尽，
Java 工具或 Runtime 可能被杀掉。

生产环境应使用已签名的正式镜像和制品，并遵循[发行验证](docs/release.md)及
[备份、恢复与凭据轮换手册](docs/production-backup-restore.md)。

不使用 Docker 时可直接运行 JAR（需自行准备 PostgreSQL 17.1+）：

```bash
java -jar target/cilexec-app.jar migrate
java -jar target/cilexec-app.jar terminal
```

## 核心概念

| 概念 | 说明 |
|---|---|
| **FCL** | 脚本语言：`//` 注释、`#` 长度运算符、保留字、函数、循环、映射/列表、包导入。完整参考：[docs/fcl-function-reference.md](docs/fcl-function-reference.md) |
| **进程与延续** | 进程是持久对象；每个已提交切片持久化完整解释器状态。未提交切片会回滚，并可能在恢复后重放 |
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
  fcl/            FCL 编译器、延续运行时、内置函数
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
```

## 构建与测试

```bash
mvn clean test        # 370+ 单元与生命周期测试
mvn clean verify      # 强制 PostgreSQL/崩溃恢复集成测试、质量门禁与 JAR
```

## 文档

- [FCL 函数与终端参考](docs/fcl-function-reference.md)
- [架构基线](docs/architecture-baseline.md)
- [Java 源码扩展](docs/java-extension-development.md)
- [包与市场](docs/package-market.md)
- [无头模式](docs/headless-mode.md)
- [宿主文件导入](docs/host-vfs-import.md)
- [终端与运维](docs/terminal-and-admin-plan.md)
- [发布流程](docs/release.md)

## 许可证

[MIT](LICENSE)
