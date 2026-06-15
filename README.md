# CilExec — 模拟操作系统

一个基于 Java 的教学演示模拟操作系统，将 Unix "一切皆文件" 的理念推向极致。

## 概述

CilExec 是一个运行在 JVM 之上的模拟操作系统。它拥有自己的虚拟文件系统（VFS）、进程系统、用户权限管理、FCL 脚本语言以及网络通信能力。

```
┌─────────────────────────────────────────────────┐
│                 CilExec System                   │
│  ┌──────────┐  ┌──────────┐  ┌───────────────┐ │
│  │  VFS     │  │  Process  │  │  FCL Engine   │ │
│  │  文件系统 │  │  进程调度  │  │  脚本解释器    │ │
│  └──────────┘  └──────────┘  └───────────────┘ │
│  ┌──────────┐  ┌──────────┐  ┌───────────────┐ │
│  │  User    │  │  Network  │  │  Function     │ │
│  │  权限管理  │  │  网络通信  │  │  插件系统     │ │
│  └──────────┘  └──────────┘  └───────────────┘ │
└─────────────────────────────────────────────────┘
```

## 快速开始

### 构建要求

- Java 17+
- Maven 3.8+

### 构建与运行

```bash
# 构建
mvn package -DskipTests

# 运行
java -jar target/recilexec-1.0-SNAPSHOT.jar
```

首次启动时会自动创建虚拟文件系统 (`cilexec_root/`) 并启动 INIT 进程 (PID 1)。

### 停止

按 `Ctrl+C` 安全关闭系统。

## 架构

### 虚拟文件系统 (VFS)

所有状态存储在宿主机文件系统的 `cilexec_root/` 目录下：

```
cilexec_root/
  ├── system/
  │   ├── app/          ← 系统应用程序（.fcl 脚本）
  │   ├── config/       ← 配置文件（users.json, env.json 等）
  │   ├── process/      ← 进程文件（1.json, 2.json, ...）
  │   └── swap/         ← 交换池（进程间通信）
  └── user/
      ├── local/        ← local 用户（超级用户）
      ├── alice/        ← 普通用户
      └── bob/          ← 普通用户
```

每个文件和目录都附有 `.META` 元数据，包含 Owner、Permission、时间戳等信息。

### FCL 脚本语言

FCL (CilExec Language) 是系统的内置脚本语言，支持：

- 变量赋值：`x = 42`
- 算术运算：`+ - * / %`
- 字符串操作：`"Hello" + " " + "World"`
- 比较与布尔运算：`== != < > <= >= and or !`
- 控制流：`if` / `while` / `break` / `return`
- 数组：`[1, 2, 3]` / `arr[0]`
- 映射：`{"key": "value"}`
- 函数定义：`func add(a, b) { return a + b }`
- 内置函数调用（见下文）
- 导入：`import "lib.fcl"`
- 文件包含：`include "util.fcl"`
- 进程操作：`fork()` / `exec("script.fcl")`

### 内置函数

所有内置函数通过命名空间调用（也可省略命名空间直接调用短名）：

| 命名空间 | 函数 | 说明 |
|----------|------|------|
| `io` | `print`, `println`, `input` | 标准输入输出 |
| `io` | `readFile`, `writeFile` | 文件读写 |
| `file` | `read`, `write`, `createFile`, `removeFile`, `append` | 文件操作（受权限控制） |
| `file` | `createDir`, `removeDir`, `rename`, `listdir`, `exists` | 目录操作 |
| `file` | `lock`, `unlock`, `link` | 文件锁定与链接 |
| `user` | `createUser`, `removeUser`, `switchUser` | 用户管理 |
| `user` | `getCurrentUser`, `isLocal`, `getListOfUsers` | 用户查询 |
| `util` | `print`, `println`, `input` | 工具类 I/O |
| `util` | `toJson`, `fromJson`, `typeOf`, `toString` | 类型与 JSON 工具 |
| `util` | `isArray`, `isMap`, `isNumber`, `isString`, `isBool` | 类型检查 |
| `util` | `exit` | 退出进程 |
| `process` | `fork`, `exec`, `kill`, `wait`, `waitPid` | 进程控制 |
| `process` | `pause`, `continue`, `getPid`, `getProcessName` | 进程信息 |
| `swapPool` | `create`, `read`, `write`, `delete`, `exists` | 交换池（IPC）|
| `swapPool` | `list`, `clear`, `waitFor`, `signal` | 交换池同步 |
| `network` | `fetch`, `download` | HTTP 请求 |
| `socket` | 套接字通信函数 | TCP/UDP 通信 |
| `math` | `abs`, `ceil`, `floor`, `round`, `max`, `min` | 数学函数 |
| `math` | `sin`, `cos`, `tan`, `sqrt`, `pow`, `log` | 数学函数 |
| `math` | `random`, `randInt` | 随机数 |
| `path` | `resolve`, `normalize`, `getParent`, `getFileName` | 路径操作 |
| `path` | `toRealPath`, `isAbsolute`, `exists` | 路径查询 |
| `system` | `kill`, `exec` | 系统特权操作（仅 local）|

### 权限系统

CilExec 拥有基于用户的权限模型：

- **Owner**：文件/目录的创建者，拥有读和写权限
- **Others**：其他用户，默认只有读权限
- **local 用户**：超级用户，自动绕过所有权限检查

每个文件和目录通过 `.META` 文件的 `Owner` 和 `Permission` 字段配置权限。

示例权限配置：
```json
{
  "Owner": "alice",
  "Permission": {
    "Owner": "read, write",
    "Others": "read"
  }
}
```

#### 使用 switchUser 切换用户

```
switchUser("alice", "p")    // 切换到 alice（需密码）
switchUser("local", "local")  // 切换回超级用户
```

### 进程系统

- 每个进程对应 `/system/process/{pid}.json` 文件
- Scheduler 每 100ms 扫描进程目录，管理进程生命周期
- 每个 ProcessRunner 以独立线程运行，每 10ms 执行一行 FCL 代码
- 进程状态持久化在 JSON 文件中，天然具备断电恢复能力
- `fork()` 创建子进程，`exec()` 替换当前进程的代码
- 进程间通过交换池（Swap Pool）进行通信

### INIT 进程

PID 1 是系统的 INIT 进程，在系统启动时自动创建。INIT 进程执行 `/system/config/INIT.fcl` 脚本。
如果 INIT 进程终止且没有其他非守护线程，JVM 将退出。

## 本地开发

```bash
# 编译
mvn compile

# 运行测试
mvn test

# 打包
mvn package -DskipTests
```

## 许可

MIT License
