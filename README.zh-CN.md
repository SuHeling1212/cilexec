# CilExec

**CilExec** 是一个可持久化的脚本与进程管理引擎：在 **PostgreSQL 支撑的运行时**上执行 **FCL**（Follarce CilExec Language）脚本。**PostgreSQL 是唯一权威状态**——任何时候杀死引擎再重启，所有工作都会从已提交的数据库行中重建，不会丢失任何数据。

> 为什么需要它：普通脚本运行时的进程状态保存在内存里，崩溃即丢失。CilExec 把每条有语义的 FCL 语句放进一个显式数据库事务中提交，因此长时间运行的脚本、定时器、消息队列和外部副作用都能跨重启存活，多租户隔离由数据库本身强制（行级安全 RLS）。

## 核心特性

- **零内存状态** —— 程序、完整延续（continuation）、调度租约、IPC、定时器、VFS、包绑定、副作用日志、终端输入和审计事件全部存于 PostgreSQL；数据库 advisory lock 保证同一数据库只有一个活跃运行时。
- **崩溃安全的延续** —— 每条语句后持久化完整解释器状态（变量、调用栈、程序计数器）；`state_version` + `execution_epoch` 围栏防止陈旧 worker 提交。
- **天然多租户** —— 每个用户对应一个稳定 PostgreSQL LOGIN 角色并强制 RLS；`SYSTEM_ADMIN` 是应用级超级用户，绝不获得集群或宿主特权。
- **内容寻址 VFS** —— 文件/目录/符号链接/挂载以 SHA-256 标识的不可变对象存储；跨用户管理操作经 SECURITY DEFINER API 并全程审计。
- **不可变包** —— 离线构建的 SQLite `package.db` 发布物按精确 SHA-256 绑定；import 解析环境绑定或精确数据库哈希。
- **日志化外部副作用** —— HTTP、socket、定时器、宿主命令以挂起延续 + 持久效果行方式执行，崩溃后可恢复（幂等键、停滞效果回收、心跳保护 worker）。
- **持久化 IPC 与定时器** —— 直连/频道/主题/广播消息和定时器可跨重启唤醒暂停的进程。
- **可验证导出** —— 基于单个只读快照的 PostgreSQL → SQLite 逻辑导出，端到端哈希校验。
- **友好的终端** —— 交互式 REPL 的进程（变量、导入、函数、工作目录）跨登出/登录和运行时重启保持；另有无头协议供宿主脚本调用。
- **首个正式版前零迁移** —— schema 变更就地修改单一 Flyway 基线；旧格式直接废弃，不做迁移。

## 快速开始

依赖：JDK 26、Maven 3.9+、PostgreSQL 17.1+。

```bash
./Install.sh                    # 一条命令：密钥 + PostgreSQL + 迁移 + 终端
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
./Headless.sh 'value = 41'
./Headless.sh 'io.println(value + 1)'     # 42；同一宿主终端的调用共享同一持久 REPL 进程
```

不使用 Docker 时直接运行 JAR（需先有数据库）：

```bash
java -jar target/cilexec-app.jar terminal
java -jar target/cilexec-app.jar migrate
```

## 核心概念

| 概念 | 说明 |
|---|---|
| **FCL** | 脚本语言：`//` 注释、`#` 长度运算符、保留字、函数、循环、映射/列表、包导入。完整参考：[docs/fcl-function-reference.md](docs/fcl-function-reference.md) |
| **进程与延续** | 进程是持久对象；每条语句后持久化完整解释器状态，并从断点精确恢复 |
| **VFS** | 每用户内容寻址文件树（根为 `/`），管理员可见 `/Users/<name>`；宿主文件经 `host move` 导入 |
| **包** | 不可变 SQLite 数据库，声明能力、入口点与精确哈希依赖 |
| **副作用** | 日志化的外部操作（HTTP、socket、宿主命令），崩溃可恢复，按策略重试 |

## 架构一览

```
FCL 源码 → 编译器 → ProcessStatementExecutor（每个调度切片一条持久语句）
        → 调度 worker（有界、租约式 FIFO）→ PostgreSQL（唯一状态存储）
        → 效果 worker（日志化副作用）· 定时器 · IPC · 终端 · VFS
```

其余一切——连接池、线程、缓存——都是可丢弃的，启动时从已提交行重建。源码布局与设计决策见 [docs/architecture-baseline.md](docs/architecture-baseline.md)。

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
src/main/resources/db/baseline/   单一 Flyway 基线（角色、RLS、SQL 函数）
```

## 构建与测试

```bash
mvn clean test        # 270+ 单元、生命周期、崩溃恢复测试
mvn clean verify      # 打包 target/cilexec-app.jar
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
