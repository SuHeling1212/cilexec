# CilExec 项目精髓与架构设计文档

> **目标**: 完整提取 CilExec 的所有设计理念、架构精髓、组件交互和核心实现细节，为重构提供完整蓝图。
> **创建时间**: 2026-06-15
> **代码行数**: ~6500+ 行 Java

---

## 目录

1. [核心哲学](#1-核心哲学)
2. [全局架构总览](#2-全局架构总览)
3. [虚拟文件系统 (VFS)](#3-虚拟文件系统-vfs)
4. [元数据系统](#4-元数据系统)
5. [进程系统](#5-进程系统)
6. [FCL 脚本引擎](#6-fcl-脚本引擎)
7. [插件函数系统](#7-插件函数系统)
8. [交换池 (Swap Pool) IPC 机制](#8-交换池-swap-pool-ipc-机制)
9. [权限模型](#9-权限模型)
10. [用户系统](#10-用户系统)
11. [网络子系统](#11-网络子系统)
12. [初始化序列](#12-初始化序列)
13. [异常处理系统](#13-异常处理系统)
14. [日志系统](#14-日志系统)
15. [常量管理](#15-常量管理)
16. [FCL 语言完整规范](#16-fcl-语言完整规范)
17. [已知架构问题](#17-已知架构问题)
18. [重构建议](#18-重构建议)

---

## 1. 核心哲学

### 1.1 "一切皆文件" 的极端实现

CilExec 将 Unix 的"一切皆文件"理念推向极致：

```
传统 OS:   内存为主, 磁盘为持久化
CilExec:   磁盘为主, 内存仅做运行时缓存
```

**核心原则**:
- **文件即内存**: 所有进程状态、变量、代码都存储在文件中
- **操作即文件 I/O**: 进程调度 = 读/写进程 JSON 文件
- **状态持久化内置**: 系统天然具备断电恢复能力（因为所有状态都在磁盘上）
- **IPC 即文件共享**: 进程间通信 = 通过交换池文件读写

### 1.2 设计的"好处"（设计者自嘲）

| 声称的好处 | 实际含义 |
|-----------|---------|
| "更好的空安全" | 没有指针，但有未定义变量异常 |
| "更好的数据安全" | 断电不丢数据，但文件可能被乱改 |
| "更好的调试" | 可以直接编辑宿主机上的进程文件 |
| "更好的性能" | 每个操作都做磁盘 I/O... 这是教学系统 |

### 1.3 定位

- **目的**: 教学演示模拟操作系统
- **运行方式**: Java JAR 单文件运行
- **宿主机**: 任何支持 JVM 25 的系统
- **虚拟文件系统 (VFS)**: 宿主机文件系统上的一个目录树

---

## 2. 全局架构总览

### 2.1 整体结构图

```
┌─────────────────────────────────────────────────────────────┐
│                      Main.java                              │
│  (入口: 初始化 → 启动调度器 → shutdown hook 收尾)            │
└──────────────────────┬──────────────────────────────────────┘
                       │
          ┌────────────┼────────────┬────────────┬───────────┐
          ▼            ▼            ▼            ▼           ▼
   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
   │ FileInit │ │ UserInit │ │ProcInit  │ │ Socket   │ │Plugin    │
   │ VFS 初始化│ │ 用户初始化 │ │进程系统初始化│ │初始化    │ │注册函数  │
   └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘
                                             │
                                    ┌────────┴────────┐
                                    ▼                  ▼
                              ┌──────────┐      ┌──────────┐
                              │Network   │      │Function  │
                              │Util      │      │Registry  │
                              │SocketUtil│      │(9个提供者)│
                              └──────────┘      └──────────┘
                                                    │
                  运行时体系                          │
          ┌─────────────────────────────────────────┘
          │
          ▼
   ┌──────────────────────────────────────────────────────┐
   │                 Scheduler Thread                      │
   │  每隔 100ms 扫描 /system/process/ 目录                │
   │  新进程 → 创建 ProcessRunner → 启动线程               │
   │  已删除进程 → 关闭 ProcessRunner                      │
   └──────────────────────────────────────────────────────┘
          │
          ▼ (每个 PID 一个线程)
   ┌──────────────────────────────────────────────────────┐
   │              ProcessRunner (Runnable)                │
   │  ┌──────────────────────────────────────────────┐   │
   │  │ 主循环 (每 10ms 执行一行):                      │   │
   │  │  loadFromFile() → executeLine() → saveToFile()│   │
   │  │  词法解析 → 语法解析 → 表达式求值               │   │
   │  │  控制流: if/while/break/return/func             │   │
   │  └──────────────────────────────────────────────┘   │
   └──────────────────────────────────────────────────────┘
          │
          ▼  (函数调用委托)
   ┌──────────────────────────────────────────────────────┐
   │             FunctionRegistry                         │
   │  遍历所有 FunctionProvider → 调用匹配函数             │
   │  支持命名空间解析 (swapPool.create)                   │
   └──────────────────────────────────────────────────────┘
```

### 2.2 启动流程

```
Main.main()
  ├── Runtime.addShutdownHook(Logger.logShutdown + Logger.close)
  ├── Logger.logStartup()
  ├── FileInit.init()
  │     ├── 检查 VFS 目录结构是否存在
  │     ├── 创建目录: /system/{app,config,process,swap}, /user/local/app
  │     ├── 创建 .META 元数据文件 (每个目录)
  │     ├── 创建 init.json, local.json, users.json, env.json, INIT.fcl
  │     └── 从 classpath 复制 INIT.fcl (当前为空文件)
  ├── registerFunctionProviders()
  │     └── 注册 9 个 FunctionProvider
  ├── ProcessInit.init()
  │     ├── 检查 PID 1 (INIT) 是否存在
  │     ├── 不存在 → 创建 INIT 进程文件, 启动 ProcessRunner 线程
  │     ├── 存在且运行中 → 启动对应 ProcessRunner
  │     └── 启动 Scheduler 线程 (定时扫描新进程)
  ├── SocketUtil.init()
  │     └── 初始化 socket 监听
  ├── Logger.logShutdown()
  └── Logger.close()
```

### 2.3 一次"执行行"的完整流程

```
ProcessRunner.run()
  └── executeLine() [每 10ms 调用一次]
        ├── loadFromFile()         ← 从磁盘读取进程 JSON
        ├── 读取当前行 codeLines[currentLine]
        ├── 解析语句类型:
        │     ├── func → 跳过 (函数定义, 已预解析)
        │     ├── import → 导入另一个 .fcl 文件
        │     ├── if → handleIf()
        │     ├── while → handleWhile()
        │     ├── return → handleReturn()
        │     ├── break → handleBreak()
        │     ├── fork() → executeStatement → ProcessFunc.fork()
        │     ├── exec() → executeStatement → ProcessFunc.exec()
        │     ├── assignment (x = expr) → handleAssignment()
        │     └── evaluate(line) → 通用表达式求值
        ├── 更新 currentLine
        └── saveToFile()           → 写回磁盘
```

---

## 3. 虚拟文件系统 (VFS)

### 3.1 目录结构

```
cilexec_root/                   ← VFS 根目录 (位于 JAR 同目录)
  ├── system/
  │   ├── app/                  ← 系统应用程序
  │   ├── config/
  │   │   ├── init.json         ← VFS 根路径配置 {root: "..."}
  │   │   ├── users.json        ← 用户配置 {currentUser, users: {...}}
  │   │   ├── env.json          ← 环境变量和路径别名
  │   │   └── INIT.fcl          ← INIT 进程启动脚本
  │   ├── process/              ← 进程文件 (1.json, 2.json, ...)
  │   └── swap/                 ← 交换池 (pool_name.json)
  └── user/
      └── local/
          ├── local.json        ← local 用户信息
          └── app/              ← 用户应用程序
```

### 3.2 路径解析

```
输入路径 → PathUtil.resolvePath() → normalizePath() → 真实路径

resolvePath():
  - ~ → /user/local
  - $HOME → /user/local
  - $SYSTEM → /system
  - 支持链式替换

normalizePath():
  - 处理 .. 和 . (使用栈)
  - 反斜杠统一为正斜杠
  - 白名单校验每个路径组件 (仅允许字母/数字/_-.)
  - 拒绝空组件和以 . 开头的名称

安全校验 (validateFile()):
  - normalized.contains("..") 检查 (冗余)
  - canonicalPath.startsWith(canonicalRoot) 双重校验
  - 符号链接解析 + 链式链接检测 + 循环检测
  - 调用者身份权限检查
  - 文件/目录锁检查 + 自动解锁崩溃进程
```

### 3.3 链接系统 (Link)

类似于符号链接，但实现为普通文件 + 元数据：

- `FileUtil.Link(dirPath, targetPath)` 创建链接文件
- 链接文件的元数据中 `Link` 字段指向目标路径
- `resolveLink()` 递归解析，支持链式链接
- 循环检测使用 `visited` Set

### 3.4 文件锁定系统

- 每个文件/目录元数据中有 `locked` 对象:
  ```json
  "locked": {
    "isLocked": false,
    "lockedBy": null
  }
  ```
- `lock()` 设置 isLocked=true, lockedBy=当前PID
- `unlock()` 需要是锁持有者或 local 用户
- `checkAndValidateLock()` 自动检测并释放崩溃进程的锁

### 3.5 文件操作 API

| 函数 | 功能 | 参数 |
|------|------|------|
| `read(path)` | 读取文件体 | path (如 `/system/process/1.json`) |
| `write(path, content)` | 写入文件 | 保留元数据, 更新 body |
| `append(path, content)` | 追加内容 | 保留元数据, body 后添加 |
| `createFile(path, name)` | 创建文件 | 自动生成元数据 |
| `removeFile(path)` | 删除文件 | 检查锁 |
| `createDirectory(path, name)` | 创建目录 | 自动生成 .META |
| `removeDirectory(path)` | 删除空目录 | 检查锁 |
| `Rename(path, newName)` | 重命名 | 检查锁 |
| `Link(path, sourcePath)` | 创建链接 | 检查锁 |
| `lock(path)` / `unlock(path)` | 文件锁定 | |
| `readFileMetaData(path)` | 读元数据 | 返回 JSON |
| `writeFileMetaData(path, content)` | 写元数据 | 保留 body |
| `getListOfFileAndDirectory(path)` | 目录列表 | 过滤 .META |
| `readDirectoryMetaData(path)` | 读目录元数据 | |
| `writeDirectoryMetaData(path, content)` | 写目录元数据 | |
| `createDirectoryMetaData(path)` | 创建目录元数据 | |

---

## 4. 元数据系统

### 4.1 文件格式

所有 VFS 文件使用统一的元数据+正文格式:

```
#<META>
{JSON 元数据}
<META>#
{正文内容}
```

### 4.2 元数据字段

文件元数据:
```json
{
  "Time": {
    "createTime": [2026, 6, 15, 10, 30, 0, 0],
    "lastEditTime": [2026, 6, 15, 10, 30, 0, 0],
    "lastOpenTime": [2026, 6, 15, 10, 30, 0, 0]
  },
  "Owner": "local",
  "Permission": {
    "Owner": "read, write",
    "Others": "read"
  },
  "locked": {
    "isLocked": false,
    "lockedBy": null
  },
  "Size": [0, "B"],
  "Link": "/path/to/target"   // 仅链接文件
}
```

目录元数据 (.META 文件):
```json
{
  "Time": { /* 同上 */ },
  "Owner": "local",
  "Permission": { /* 同上 */ },
  "locked": { /* 同上 */ }
}
```

### 4.3 时间表示

时间使用 int[7] 数组: `[Year, Month, Day, Hour, Minute, Second, Millisecond]`

### 4.4 大小表示

大小使用 `Object[]` 数组: `[数值, "单位"]`，单位可以是 B/KB/MB/GB

---

## 5. 进程系统

### 5.1 进程文件格式

每个进程由一个 JSON 文件表示，存放于 `/system/process/{PID}.json`:

```json
{
  "Name": "INIT",
  "Owner": "local",
  "isLocal": true,
  "PID": 1,
  "Path": "",
  "Status": true,
  "startTime": [2026, 6, 15, 10, 30, 0, 0],
  "RunningTime": 0,
  "Parent": {},           // 或 { "Name": "parent", "PID": 1, "Path": "..." }
  "Child": {              // PID -> 子进程信息映射
    "2": { "Name": "...", "PID": 2, "Path": "..." }
  },
  "Program": {
    "Data": {
      "x": 42,
      "arr": [1, 2, 3],
      "__current_script": "/path/to/script.fcl"
    },
    "Code": {
      "Code": ["line1", "line2", "..."],
      "runningCodeLine": 5,
      "BlockStack": [
        { "type": "WHILE", "startLine": 3, "condition": "x < 10" }
      ]
    },
    "returnValue": null
  }
}
```

关键字段：
- `Status`: `true`=运行, `false`=暂停/停止
- `runningCodeLine`: 当前执行行号
- `Data`: 进程变量字典
- `Code.Code`: 代码行列表
- `BlockStack`: 块堆栈 (用于 while/if 控制流持久化)
- `Child`: 子进程列表 (PID -> 信息映射)
- `Parent`: 父进程信息

### 5.2 进程调度器 (Scheduler)

```
Scheduler Thread (每 100ms 执行):
  1. 列出 /system/process/ 目录下所有 .json 文件
  2. 解析每个文件名获取 PID
  3. 对新出现的 PID: 创建 ProcessRunner, 启动线程
  4. 对已消失的 PID: shutdown() 对应 runner
```

- 基于目录扫描的调度器，不是抢占式
- 使用 ConcurrentHashMap 存储 PID -> ProcessRunner 映射
- 调度器本身是 daemon 线程

### 5.3 ProcessRunner 执行模型

```
每个进程一个线程, 每 10ms 执行一行代码:

run():
  while (running):
    executeLine()
    Thread.sleep(PROCESS_TICK_MS = 10ms)

executeLine():
  1. loadFromFile()    ← 从磁盘加载最新状态
  2. 如果 currentLine >= size → running=false, save
  3. 跳过空行/注释/{/}
  4. 遇到 }: 根据 BlockStack 判断是否循环回 while 开头
  5. fork() 特殊处理: 先保存, 再执行 fork
  6. exec() 特殊处理: 执行后重新 loadFromFile
  7. 其他语句: executeStatement()
  8. saveToFile()      ← 写回磁盘
```

**重要**: 每次 executeLine() 都做完整的读-执行-写磁盘操作。

### 5.4 fork() 实现

```java
fork(parentPid):
  1. 读取父进程 JSON 文件
  2. allocatePid() → 扫描 /system/process/, 返回 maxPid+1
  3. 深拷贝父进程 JSON
  4. 修改子进程: PID, Parent, Child={}, runningCodeLine+1
  5. 写入子进程文件: createFile + write
  6. 更新父进程的 Child 列表
  7. 返回子 PID (调度器会在下次扫描时自动启动子进程)
```

**特点**:
- 子进程从 fork() 调用的下一行开始执行
- 子进程是全新的 ProcessRunner 线程
- 子进程的 Owner 从 users.json 获取（不是继承父进程的）

### 5.5 exec() 实现

```java
exec(path, params):
  1. 读取目标脚本文件内容
  2. 读取当前进程 JSON
  3. 重置: Path, startTime, RunningTime, Program.Data, Program.Code
  4. 解析特殊参数: -user username (设置执行用户)
  5. 参数存入 Data.argv/argc
  6. 写回进程文件 (下次 executeLine 自动加载新代码)
```

### 5.6 kill() 实现

```java
kill(pid):
  1. 检查权限
  2. 孤儿进程收养: 将目标进程的子进程过继给 INIT
  3. 从父进程的 Child 列表中移除
  4. 清理进程拥有的 Socket
  5. 删除进程文件
```

### 5.7 wait/waitPID 实现

```java
waitProcess():
  while (true):
    for (每个子进程):
      检查 Status → 如果 false 或文件不存在
      从 Child 列表移除
      返回 SUCCESS
    Thread.sleep(100ms)
    reload children (重新读取进程文件)
```

---

## 6. FCL 脚本引擎

### 6.1 FCL 语言概述

FCL (Follarce CilExec Language) 是自定义的脚本语言，语法类似简化版 JavaScript。

**语法特性**:
- 动态类型 (Number/Boolean/String/Array/Map)
- 基于行的解释执行 (不是 AST 解释，而是逐行解析)
- 控制流: `if`, `while`, `break`, `return`
- 函数定义: `func name(params) { ... }`
- 批量导入函数: `import "path/to/script.fcl"`
- 函数调用: `funcName(args...)`
- 命名空间函数: `namespace.funcName(args...)`

### 6.2 控制流分析

#### if 语句
```fcl
if (condition) {
    // body
}
```
- 条件成立: push IF 到 blockStack
- 条件不成立: 跳过直到匹配的 }

#### while 语句
```fcl
while (condition) {
    // body
}
```
- 每次关闭 } 时重新检查条件
- 条件成立: currentLine 跳回 while 行
- 条件不成立: pop blockStack, 继续执行

#### break 语句
- 从当前行向前扫描 } 计数器
- 跳出 while 循环体

#### return 语句
- 设置 returnValue
- 跳过函数体直到 }

### 6.3 函数系统

函数分为两类：

1. **内置插件函数**: 通过 FunctionRegistry 注册
2. **用户定义函数**: 在 FCL 中用 `func` 关键字定义

用户函数处理:
```
parseFunctionDefinitions():
  - 扫描所有代码行
  - 正则匹配 "func name(params) {"
  - 花括号深度追踪提取函数体
  - 存入 Map<String, FunctionDef>

函数调用时:
  - 保存当前 data/codeLines/currentLine
  - 设置函数参数到 data
  - 切换到函数体代码行
  - 执行直到 return 或函数体结束
  - 恢复上下文
```

### 6.4 表达式求值 (双引擎架构)

ProcessRunner 有两个表达式求值系统:

**引擎 1: ProcessRunner.evaluate() (主引擎)**
- 处理字面量: 数字、字符串、布尔、数组 `[]`、映射 `{}`
- 调用预处理函数调用: `preprocessFunctionCalls()`
- 处理 # 长度运算符: `#arrayVar`
- 处理索引访问: `arr[0]`, `map["key"]`
- 变量查找: `data.get(expr)`
- 最终委托给引擎 2

**引擎 2: Lexer + Parser + NodeEvaluator (运算符引擎)**
- Lexer: 将表达式字符串转为 Token 流
  - Token 类型: NUMBER, STRING, BOOLEAN, IDENTIFIER, OPERATOR, 括号, 逗号, 冒号
  - 运算符优先级: or(1) < and(2) < not(3) < 比较(4-5) < 加减(6) < 乘除(7)
- Parser: Token → AST (递归下降, 运算符优先级解析)
  - AST 类型: NUMBER, STRING, BOOLEAN, IDENTIFIER, UNARY, BINARY, INDEX, FUNCTION_CALL, ARRAY, MAP
- NodeEvaluator: AST → 结果
  - 二元运算: and/or/==/!=/</>/<=/>=/+/-/*/%/ (带类型检查和除法零检查)
  - 智能 +: 数字相加, 字符串拼接

**函数调用预处理**: `preprocessFunctionCalls()` 在表达式求值前扫描并替换函数调用:
- 找到 `identifier(` 模式
- 提取函数调用表达式
- 委托给 `handleFunctionCall()`
- 将结果替换回表达式字符串

### 6.5 变量系统

- 所有变量存储在 `data` (Map<String, Object>)
- 赋值: `x = 42` → `data.put("x", 42)`
- 索引赋值: `arr[0] = 1` → `handleIndexAssignment()`
- 系统保留变量以 `_` 开头 (如 `_error`, `_warning`)
- 用户变量不能以 `_` 开头

---

## 7. 插件函数系统

### 7.1 架构

```
FunctionProvider (接口)
  ├── FileFunctionProvider     → 文件操作 (read/write/createDir/removeFile/...)
  ├── ProcessFunctionProvider  → 进程操作 (fork/exec/kill/wait/getPID/...)
  ├── SwapFunctionProvider     → 交换池 (createSwapPool/swapPoolAdd/swapPoolGet/...)
  ├── UserFunctionProvider     → 用户管理 (createUser/switchUser/...)
  ├── UtilFunctionProvider     → 工具函数 (print/input/toJson/typeOf/...)
  ├── NetworkFunctionProvider  → 网络 (httpGet/httpPost/...)
  ├── SocketFunctionProvider   → Socket (socketConnect/socketSend/...)
  ├── MathFunctionProvider     → 数学 (sin/cos/sqrt/random/...)
  ├── PathFunctionProvider     → 路径 (resolvePath/getEnvVar/...)
  └── IOFunctionProvider       → I/O (print/println/input/...)
```

### 7.2 调用流程

```java
FunctionRegistry.call("swapPool.create", args, context):
  1. 解析命名空间: "swapPool" + "create"
  2. 遍历所有 providers
  3. 先用全名 "swapPool.create" 调用 → SwapFunctionProvider 匹配
  4. 如果失败, 用短名 "create" 调用
  5. 返回第一个非错误结果
```

### 7.3 FunctionContext

```java
class FunctionContext {
    int pid;           // 当前进程 ID
    int ppid;          // 父进程 ID
    String currentUser; // 当前用户
}
```

每个 ProcessRunner 创建时初始化 FunctionContext，传递给插件函数。

### 7.4 错误约定

插件函数返回约定:
- 成功: 返回具体值 (String/Number/Boolean/Map/List)
- 失败: 返回 `String[]` 或 `Object[]`，第一个元素为 `"ERROR"`
- `FunctionRegistry.isErrorResult()` 检查第一个元素是否为 "ERROR"

---

## 8. 交换池 (Swap Pool) IPC 机制

### 8.1 概念

交换池是进程间的数据交换机制。每个交换池是一个 JSON 文件，存放在 `/system/swap/{name}.json`。

### 8.2 数据结构

```json
{
  "name": "pool_name",
  "time": {
    "createTime": [2026, 6, 15, 10, 30, 0, 0],
    "lastEditTime": [...],
    "lastOpenTime": [...]
  },
  "OwnerPID": 1,
  "content": {
    "1": {
      "varName": {
        "addTime": [...],
        "editTime": [...],
        "type": "always",
        "whitelist": [],
        "blacklist": [],
        "value": "some_value",
        "locked": false,
        "lockedBy": null
      }
    }
  }
}
```

### 8.3 变量类型

| 类型 | 含义 |
|------|------|
| `always` | 永久可用, 无限制 |
| `sync` | 同步变量 (有 changed/readers 机制) |
| `times(N)` | 只能读取 N 次后自动删除 |

### 8.4 访问控制

- 每个变量可以有 whitelist (白名单 PID) 和 blacklist (黑名单 PID)
- 所有者 (OwnerPID) 和 local 用户可以管理
- 变量可以单独锁定 (locked/lockedBy)

### 8.5 操作 API

| 函数 | 功能 |
|------|------|
| `swapPool.create(name)` | 创建交换池 |
| `swapPool.remove(name)` | 删除交换池 |
| `swapPool.add("varName:value", poolName, params)` | 添加变量 |
| `swapPool.get(varName, poolName)` | 获取变量 (带 type 处理) |
| `swapPool.remove(varName, poolName)` | 删除变量 |
| `swapPool.lock(varName, poolName)` | 锁定变量 |
| `swapPool.unlock(varName, poolName)` | 解锁变量 |
| `swapPool.update(varName, poolName, newValue)` | 更新变量值 |
| `swapPool.getAll(poolName)` | 获取所有变量 |

---

## 9. 权限模型

### 9.1 用户身份

- `local` 用户: 超级管理员, 拥有所有权限
- 普通用户: 通过 users.json 管理

### 9.2 文件权限

文件元数据中的 Permission:
```json
"Permission": {
  "Owner": "read, write",
  "Others": "read"
}
```
- `checkFilePermission(path, operation)`: 检查当前用户对路径的权限
- `validatePermission(path, operation)`: 返回带详情的 PermissionResult

### 9.3 进程权限

- `checkProcessPermission(pid)`: 检查当前用户是否为进程的 Owner
- 被 kill / Pause / Continue 的进程必须属于当前用户

### 9.4 线程本地用户

```java
ThreadLocal<String> currentProcessUser:
  - ProcessRunner.executeLine() 设置
  - UserUtil.getCurrentUser() 读取
  - 允许同一进程内函数调用权限检查
```

---

## 10. 用户系统

### 10.1 配置存储

users.json:
```json
{
  "currentUser": "local",
  "users": {
    "local": {
      "password": "local",
      "isLocal": true,
      "home": "/user/local",
      "created": [2026, 6, 15, 10, 30, 0, 0]
    }
  }
}
```

### 10.2 用户管理 API

| 函数 | 功能 |
|------|------|
| `createUser(username, password, isLocal)` | 创建用户 (自动创建 home 目录) |
| `removeUser(username, password)` | 删除用户 (不能删除 local) |
| `switchUser(username, password)` | 切换当前用户 |
| `validateUser(username, password)` | 验证密码 |
| `getCurrentUser()` | 获取当前用户 |
| `isLocal()` | 检查是否 local 用户 |
| `getListOfUsers()` | 获取用户列表 |

---

## 11. 网络子系统

### 11.1 NetworkUtil

提供 HTTP 客户端功能:
- `httpGet(url)`: HTTP GET 请求
- `httpPost(url, data)`: HTTP POST 请求
- 基于 java.net.HttpURLConnection

### 11.2 SocketUtil

提供 TCP Socket 通信:
- `socketConnect(host, port)`: 连接远程 Socket
- `socketSend(connId, data)`: 发送数据
- `socketReceive(connId)`: 接收数据
- `socketClose(connId)`: 关闭连接
- `socketBind(port)`: 绑定监听端口 (服务器)
- `socketAccept(port)`: 接受连接 (服务器)

内部维护连接映射: ID → SocketConnection

---

## 12. 初始化序列

### 12.1 FileInit.createDirectories()

创建 VFS 目录树并写入 .META 元数据:

```
/system/, /system/app/, /system/config/, /system/process/, /system/swap/
/user/, /user/local/, /user/local/app/
```

每个目录的 .META 文件包含:
- Time (createTime/lastEditTime/lastOpenTime)
- Owner = "local"
- Permission (Owner: "read, write", Others: "read")
- locked (isLocked: false)

### 12.2 FileInit.createFiles()

创建 4 个关键配置文件:
- `system/config/init.json`: VFS 根路径
- `user/local/local.json`: local 用户信息
- `system/config/users.json`: 用户列表 (含 local)
- `system/config/env.json`: 环境变量 + 路径别名

### 12.3 FileInit.copyInitFile()

从 classpath 复制 INIT.fcl 到 VFS:
- 当前为空文件 (BUG: 需要恢复有意义的 INIT 脚本)

### 12.4 ProcessInit.createInitProcess()

创建 PID 1 (INIT) 进程:
- 名称 "INIT", Owner 来自 users.json
- 代码从 INIT.fcl 读取 → 空文件时 fallback 到 `while true {}`
- 启动第一个 ProcessRunner 线程

---

## 13. 异常处理系统

### 13.1 异常等级

```
ProcessException (RuntimeException)
  ├── RecoverableException   → 可恢复 (不终止进程)
  │     ├── fileLocked()
  │     ├── resourceUnavailable()
  │     ├── networkTimeout()
  │     └── rateLimitExceeded()
  └── UnrecoverableException → 不可恢复 (终止进程)
        ├── syntaxError()
        ├── undefinedVariable()
        ├── divisionByZero()
        ├── arrayIndexOutOfBounds()
        ├── typeError()
        ├── fileNotFound()
        └── unknownFunction()
```

### 13.2 ExceptionContext

包含异常发生的完整上下文:
- processId, lineNumber, filePath, currentLine, operation
- additionalInfo (Map, 可扩展)
- toDetailedString() 输出格式化的调试信息

### 13.3 异常处理流程

```java
handleException(e, operation):
  if (ProcessException):
    if (recoverable):
      记录到 data._warning, 继续执行
    else:
      记录到 data._error, running=false
  else:
    包装为 UnrecoverableException, running=false
  saveToFile(!running)
```

---

## 14. 日志系统

### 14.1 实现

- 基于 PrintWriter + FileWriter (追加模式)
- 日志文件: `{JAR目录}/cilexec.log`
- 支持自定义路径: `setLogPath()`
- 支持日志级别: DEBUG < INFO < WARN < ERROR

### 14.2 日志格式

```
[2026-06-15 10:30:00] [INFO] Message
[2026-06-15 10:30:00] [ERROR] Error message
java.lang.Exception stack trace
```

---

## 15. 常量管理

Constants.java 中定义所有魔数:

| 类别 | 常量 | 值 |
|------|------|-----|
| 调度 | PROCESS_TICK_MS | 10ms |
| 调度 | SCHEDULER_SLEEP_MS | 100ms |
| 网络 | DEFAULT_TIMEOUT | 10000ms |
| 网络 | BUFFER_SIZE | 8192 |
| 路径 | SYSTEM_PROCESS_PATH | /system/process/ |
| 路径 | USER_HOME_PREFIX | /user/ |
| 用户 | DEFAULT_USER_LOCAL | "local" |
| 用户 | DEFAULT_PASSWORD_LOCAL | "local" |

---

## 16. FCL 语言完整规范

### 16.1 词法规则

```
数字:    -?\d+(\.\d+)?
字符串:  "..." (支持 \n, \t, \r, \", \\)
布尔:    true | false
标识符: [a-zA-Z_][a-zA-Z0-9_.]*
运算符: + - * / % = ! < > | and or not
分隔符: ( ) [ ] { } , :
注释:   // 或 #
```

### 16.2 语法

```
程序 → 语句*
语句 → 表达式语句 | 控制流语句 | 函数定义 | import 语句

表达式语句 → 赋值 | 函数调用 | 字面量
控制流语句 → if | while | break | return

if → "if" "(" 表达式 ")" "{" 语句* "}"
while → "while" "(" 表达式 ")" "{" 语句* "}"
break → "break"
return → "return" 表达式?

函数定义 → "func" 标识符 "(" 参数列表? ")" "{" 语句* "}"
import → "import" 字符串

赋值 → 标识符 "=" 表达式
     → 标识符 "[" 表达式 "]" "=" 表达式

函数调用 → 标识符 "(" 参数列表? ")"
命名空间函数 → 标识符 "." 标识符 "(" 参数列表? ")"

字面量 → 数字 | 字符串 | 布尔 | 数组 | 映射
数组 → "[" 表达式列表? "]"
映射 → "{" 键值对列表? "}"

运算符优先级 (从低到高):
  or
  and
  not (一元)
  == != < > <= >=
  + -
  * / %
```

### 16.3 内置函数总览

| 命名空间 | 函数 | 说明 |
|---------|------|------|
| 文件 | `read(path)` | 读取文件 |
| 文件 | `write(path, content)` | 写文件 |
| 文件 | `createFile(path, name)` | 创建文件 |
| 文件 | `removeFile(path)` | 删除文件 |
| 文件 | `createDir(path, name)` | 创建目录 |
| 文件 | `removeDir(path)` | 删除目录 |
| 文件 | `rename(path, newName)` | 重命名 |
| 文件 | `listdir(path)` | 列目录 |
| 文件 | `link(dir, target)` | 创建链接 |
| 文件 | `lock(path)` / `unlock(path)` | 锁定/解锁 |
| 进程 | `fork()` | 创建子进程 |
| 进程 | `exec(path, params)` | 执行新程序 |
| 进程 | `kill(pid)` | 终止进程 |
| 进程 | `wait()` / `waitPID(pid)` | 等待子进程 |
| 进程 | `Pause(pid)` / `Continue(pid)` | 暂停/继续 |
| 进程 | `getPID()` / `getPPID()` | 获取 PID |
| 进程 | `getListOfChildProcess()` | 子进程列表 |
| 交换池 | `swapPool.*` | 见 8.5 节 |
| 用户 | `createUser/removeUser/switchUser/...` | 用户管理 |
| 网络 | `httpGet/httpPost` | HTTP 请求 |
| Socket | `socketConnect/socketSend/...` | TCP 通信 |
| 数学 | `sin/cos/sqrt/random/abs/round/...` | 数学运算 |
| 工具 | `print/println/input` | 控制台 I/O |
| 工具 | `toJson/fromJson` | JSON 转换 |
| 工具 | `typeOf/isArray/isMap/isNumber/...` | 类型检查 |

---

## 17. 已知架构问题

### 17.1 性能问题

1. **每行执行 3 次磁盘 I/O**
   - `executeLine()` 每次调用: loadFromFile(读) → 执行 → saveToFile(写)
   - 每行 = 至少 1 次读写, 每 10ms 一次
   - 重构: 可考虑批量化, 或引入缓存 + 脏页机制

2. **进程文件反序列化开销**
   - JsonUtil.readJson() 使用 Gson, 每次需要解析完整 JSON
   - 进程文件包含完整代码行列表, 大脚本时开销大

### 17.2 并发问题

3. **无文件级锁**
   - 多个线程操作同一进程文件时竞态
   - swap pool 操作同样无锁
   - Fork/wait 中的 reads/writes 无同步

4. **ThreadLocal PID vs 文件状态不一致**
   - `ProcessFunc.getPID()` 返回 ThreadLocal 值
   - 但进程文件可能被其他线程修改

### 17.3 安全/正确性

5. **花括号匹配过于简单**
   - `handleBreak()` 使用 `line.equals("{")`/`line.equals("}")`
   - `parseFunctionDefinitions()` 使用 `line.contains("{")`/`line.contains("}")`
   - 不支持同一行多个花括号（如 `} else {`）
   - 不支持字符串内的花括号

6. **fork() 的 Owner 继承错误**
   - 子进程 Owner 使用 `UserInit.getCurrentUserFromFile()` 而非继承父进程
   - 可能的权限逃逸

7. **waitProcess() 无超时**
   - `while(true)` 循环没有总体超时限制
   - 子进程永不结束则父进程永远阻塞

8. **UserUtil.getProcessOwner() 绕过 FileUtil API**
   - 直接使用 `java.io.File` + `Files.readAllBytes()`
   - 跳过了 VFS 路径校验和安全检查

### 17.4 代码质量问题

9. **FileUtil.createDirectoryMetaData() 与 FileInit.createDirectoryMeta() 重复**
   - 两份几乎相同的目录元数据创建代码

10. **PathUtil.extractMetaContent() 与 FileUtil.extractMetaContent() 重复**
    - 两个方法实现类似但语义不同

11. **ProcessRunner 中两个 handleFunctionCall**
    - `handleFunctionCall()` (行 1093) 和 `handleFunctionCallInEvaluator()` (行 1393)
    - 几乎相同的函数调用处理逻辑

12. **FunctionRegistry.isErrorResult() 死代码**
    - `String[]` 分支永远不可达（`String[]` 是 `Object[]` 的子类型）

13. **JsonUtil 返回类型不一致**
    - 成功返回 `Map/List/String/Number/Boolean`
    - 失败返回 `String[]`
    - 调用者必须 instanceof 判断

### 17.5 资源管理

14. **Logger 重复关闭**
    - shutdown hook 和 main() 末尾各调用一次
    - PrintWriter 关闭后不置 null

15. **InterruptedException 处理不完善**
    - `ProcessRunner.run()` 中 break 但不设置 running=false

16. **INIT.fcl 为空**
    - 资源文件已被清空, INIT 进程启动空循环

---

## 18. 重构建议

### 18.1 架构级重构

1. **引入内存缓存层**
   - 进程状态在内存维护, 定期同步到磁盘
   - 写入使用异步刷盘 + WAL (Write-Ahead Log)
   - 减少每行执行的磁盘 I/O

2. **统一进程文件访问**
   - 创建 `ProcessRepository` 抽象访问层
   - 封装 read/write/sync 操作
   - 携带版本号实现乐观锁

3. **重写花括号匹配**
   - 统一使用 `BraceMatcher` 工具类
   - 逐字符扫描, 跳过字符串字面量和注释
   - 支持同一行多个花括号

4. **标准化返回类型**
   - 创建 `Result<T>` 泛型类统一成功/失败
   - 替代 `String[]` 混合返回方式
   - 或使用 `Option<T>`/`Either<L,R>` 模式

### 18.2 模块化重构

5. **分离脚本引擎**
   - 将 Lexer/Parser/NodeEvaluator 封装为独立模块
   - 支持独立测试

6. **提取权限管理器**
   - 将 UserUtil + FileUtil 中的权限检查合并
   - 统一 AOP 方式注入权限检查

7. **提取进程调度器**
   - 独立 Scheduler 接口
   - 支持不同调度策略 (轮询/优先级/时间片)

### 18.3 代码质量

8. **消除重复代码**
   - `createDirectoryMetaData` × 2
   - `extractMetaContent` × 2 (语义不同, 彻底统一)
   - `handleFunctionCall` × 2 (DRY)

9. **修复已知 Bug**
   - fork() Owner 继承
   - waitProcess 超时
   - InterruptedException 处理
   - Logger 双重关闭
   - INIT.fcl 为空

10. **增加测试覆盖**
    - 当前无单元测试文件
    - ProcessRunner 核心逻辑无测试
    - 花括号匹配、fork、exec 等关键路径

### 18.4 建议的新架构

```
┌─────────────────────────────────────────────────────────┐
│                    ProcessRepository                     │
│  内存缓存 + 延迟写 + 版本控制                            │
│  getProcess(pid) → 先查缓存, 再读文件                    │
│  saveProcess(process) → 标记脏页, 定期刷盘              │
└─────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────┐
│                    Scheduler                              │
│  调度器 + 进程状态机 (NEW/READY/RUNNING/WAITING/TERMINATED)│
│  时间片轮转调度                                          │
└─────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────┐
│                    ScriptEngine                           │
│  Lexer → Parser → AST → Interpreter                      │
│  完全基于 AST 的全解释执行 (非逐行)                       │
│  支持闭包、匿名函数                                       │
└─────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────┐
│                    PermissionManager                      │
│  RBAC 权限模型                                           │
│  AOP 注解驱动权限检查                                    │
└─────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────┐
│                    Result<T>                              │
│  统一返回类型: Success(val) | Error(code, msg)           │
│  替代全系统 String[] 混用                                │
└─────────────────────────────────────────────────────────┘
```

---

> **文档总结**: CilExec 是一个有趣的教学模拟 OS，核心设计"一切皆文件，状态全持久化"非常大胆。当前实现约 6500+ 行 Java 代码，涉及 VFS、进程管理、脚本引擎、IPC、权限、网络等子系统。主要问题集中在并发控制、代码重复、花括号匹配和返回类型一致性上。重构方向应优先解决性能（减少磁盘 I/O）和正确性（花括号匹配、fork Owner），再考虑提升模块化和可测试性。