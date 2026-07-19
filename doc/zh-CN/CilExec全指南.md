# CilExec 全指南

> 一份关于 CilExec 模拟操作系统的完整参考手册。
> 涵盖系统架构、脚本语言、内置函数、进程系统、持久化恢复、安全模型与开发指南。
>
> **版本：** 1.0-SNAPSHOT
> **语言：** Java 26  /  Maven 3.8+  /  Gson 2.10.1



## 目录

### 第一部分：基础
1. [什么是 CilExec](#1-什么是-cilexec)
2. [快速开始](#2-快速开始)
3. [核心设计](#3-核心设计)

### 第二部分：VFS 虚拟文件系统
4. [VFS 概览](#4-vfs-概览)
5. [目录结构](#5-目录结构)
6. [元数据格式](#6-元数据格式)
7. [文件操作](#7-文件操作)

### 第三部分：FCL 脚本语言
8. [语言概览](#8-语言概览)
9. [数据类型](#9-数据类型)
10. [变量与赋值](#10-变量与赋值)
11. [运算符](#11-运算符)
12. [控制流](#12-控制流)
13. [函数定义](#13-函数定义)
14. [数据类型进阶](#14-数据类型进阶)
15. [import 与 include](#15-import-与-include)

### 第四部分：内置函数参考
16. [函数调用约定](#16-函数调用约定)
17. [io 命名空间](#17-io-命名空间)
18. [file 命名空间](#18-file-命名空间)
19. [user 命名空间](#19-user-命名空间)
20. [util 命名空间](#20-util-命名空间)
21. [process 命名空间](#21-process-命名空间)
22. [swapPool 命名空间](#22-swappool-命名空间)
23. [network 命名空间](#23-network-命名空间)
24. [socket 命名空间](#24-socket-命名空间)
25. [math 命名空间](#25-math-命名空间)
26. [path 命名空间](#26-path-命名空间)
27. [system 命名空间](#27-system-命名空间)

### 第五部分：进程系统架构
28. [进程生命周期](#28-进程生命周期)
29. [ProcessGeneration ─ 代次身份](#29-processgeneration--代次身份)
30. [ProcessRunner ─ 执行引擎](#30-processrunner--执行引擎)
31. [Scheduler ─ 调度器](#31-scheduler--调度器)
32. [StateManager ─ 状态持久化](#32-statemanager--状态持久化)

### 第六部分：持久化与崩溃恢复
33. [设计理念：零内存状态](#33-设计理念零内存状态)
34. [StatementAttempt ─ 指令执行尝试](#34-statementattempt--指令执行尝试)
35. [EffectPolicy ─ 副作用分类与回放](#35-effectpolicy--副作用分类与回放)
36. [ProcessInbox ─ 持久化消息收件箱](#36-processinbox--持久化消息收件箱)
37. [RecoveryManager ─ 启动恢复](#37-recoverymanager--启动恢复)
38. [LifecycleCleanup ─ 终态清理](#38-lifecyclecleanup--终态清理)

### 第七部分：进程间通信
39. [fork 与进程树](#39-fork-与进程树)
40. [exec ─ 代码替换](#40-exec--代码替换)
41. [kill / pause / continue](#41-kill--pause--continue)
42. [wait / waitPID ─ 等待子进程](#42-wait--waitpid--等待子进程)
43. [ForkLedger ─ Fork 持久化账本](#43-forkledger--fork-持久化账本)
44. [Swap Pool ─ IPC 交换池](#44-swap-pool--ipc-交换池)

### 第八部分：安全与权限
45. [用户模型](#45-用户模型)
46. [权限模型](#46-权限模型)
47. [用户事务](#47-用户事务)

### 第九部分：高级主题
48. [文件锁 v2](#48-文件锁-v2)
49. [EffectLedger ─ 副作用去重](#49-effectledger--副作用去重)
50. [路径别名 PathAliases](#50-路径别名-pathaliases)
51. [网络与 HTTP](#51-网络与-http)
52. [构建与运行](#52-构建与运行)
53. [测试](#53-测试)
54. [代码结构](#54-代码结构)
55. [附录](#55-附录)



## 1 什么是 CilExec

CilExec 是一个用 Java 实现的**教学用模拟操作系统**。它以单一可执行文件形式分发，不需要 BIOS 引导。

### 核心理念

**万物皆文件 + 零内存状态。** 一切系统状态都以文件形式存储在磁盘上。没有进程间共享的运行时内存状态——每个进程的变量、代码行号、调用栈、状态机全部都持久化在 `.proc` 文件中，崩溃后可以无损恢复。

### 能力范围

| 子系统 | 能力 |
|---|---|
| 进程管理 | fork, exec, kill, wait, waitPID, pause, continue |
| 虚拟文件系统 (VFS) | 文件读写、目录操作、权限控制、元数据 |
| 脚本引擎 (FCL) | 变量、算术、字符串、数组/Map、if/while、函数 |
| IPC | Swap Pool（消息队列）、process inbox（控制消息） |
| 权限框架 | 用户管理、基于 owner/others 的访问控制 |
| 网络 | HTTP fetch/download、TCP/UDP socket |
| 持久化 | 指令级 effect journal、进程快照、启动恢复 |

### 局限性

- 不可引导为真实操作系统
- 磁盘 I/O 是模拟的（无真实磁盘交互）
- 没有内存保护、虚拟内存或硬件抽象层
- 性能受限于文件 I/O 而非纯内存操作

---

## 2 快速开始

### 构建

```bash
mvn compile              # 编译
mvn package -DskipTests  # 打包为 uber-JAR
mvn test                 # 运行测试
```

### 运行

```bash
# 推荐方式
java -jar target/cilexec-1.0-SNAPSHOT.jar

# 或者从 classpath 运行
mvn dependency:copy-dependencies -q
java -cp "target/classes:target/dependency/*" com.follarce.Main

# 开发构建
bash build/run.sh
```

### 第一个脚本

创建 `/system/app/hello.fcl`（或通过 FCL 脚本写入）：

```fcl
println("Hello, CilExec!")
x = 42
y = x * 2
println("x = " + x + ", y = " + y)
```

---

## 3 核心设计

### 零内存状态（Zero Memory State）

CilExec 最根本的设计原则是：**系统没有任何跨进程、跨重启的运行时内存状态**。一切状态（进程变量、程序计数器、文件内容、用户数据、配置）都在磁盘上以文件形式存储。

这条原则的推论：

- 引擎可以被任意外部力量杀死（kill -9 或 JVM crash），重新启动后完全恢复
- 不存在"未保存的工作"
- 每条 FCL 指令执行完毕后，进程快照自动持久化
- 指令级别的 effect receipt 确保副作用不会重复执行

### FCL 脚本执行流水线

```
源码 (.fcl 文本)
  │
  ├── CodeLoader        ← 注释剔除、花括号分割、生成 BoundaryTable
  ├── StatementParser   ← 语句分割
  ├── Lexer             ← 词法分析 → Token 流
  ├── Parser            ← 语法分析 → AST（抽象语法树）
  └── NodeEvaluator     ← 遍历 AST 求值
```

### 线程模型

- **默认模式（虚拟线程）：** 每个进程运行在独立的 `Thread.ofVirtual()` 中
- **Scheduler：** 单独的线程，每 50ms 扫描 `/system/process/` 目录发现新进程
- **阻塞操作：** 使用 `LockSupport.parkNanos()`，不占用平台线程



## 4 VFS 概览

VFS（Virtual File System）是 CilExec 以真实操作系统目录树为基础构建的虚拟文件系统。所有文件存储在 `cilexec_root/` 目录下。

### 关键特性

- **文件所有者 (Owner)**：每个文件属于一个用户
- **权限控制 (Permission)**：基于 owner/others 的读写权限
- **元数据 (META)**：每个文件都有独立的 `.META` 元数据文件
- **符号链接 (Symlink)**：支持跨目录链接
- **文件锁 (Lock)**：支持跨进程文件锁

### 路径规则

所有 FCL 代码中的路径都是 VFS 路径，系统自动映射到底层真实文件系统：

```
FCL 路径          →  真实路径
/system/process/  →  cilexec_root/system/process/
/user/alice/      →  cilexec_root/user/alice/
```

---

## 5 目录结构

```
cilexec_root/
├── system/
│   ├── app/                    ← 系统应用程序 (.fcl 脚本)
│   │   └── INIT.fcl            ← INIT 进程脚本
│   ├── config/                 ← 系统配置
│   │   ├── init.json           ← 进程初始化配置
│   │   ├── env.json            ← 环境变量
│   │   ├── users.json          ← 用户凭据
│   │   └── local.json          ← 网络配置
│   ├── process/                ← 进程文件
│   │   ├── 1.proc              ← PID 1 (INIT) 的进程快照
│   │   ├── 2.proc              ← PID 2 的快照
│   │   └── inbox/              ← 进程消息收件箱（嵌套目录）
│   ├── swap/                   ← Swap Pool 交换池
│   │   ├── mypool.json         ← 一个交换池
│   │   └── ...                 ← 更多交换池
│   ├── fork/                   ← Fork 持久化账本
│   │   └── <hash>.ledger       ← 每条 fork 的账本记录
│   └── effects/                ← Effect Ledger 副作用记录
│       └── <hash(path)>/
│           └── write.ledger    ← 文件写入的 effect 收据
└── user/
    ├── local/                  ← local 用户主目录（超级用户）
    ├── alice/                  ← 用户 alice 的主目录
    └── bob/                    ← 用户 bob 的主目录
```

---

## 6 元数据格式

### 文件元数据

每个 VFS 文件使用特殊的元数据格式：

```
#<META>
{
  "Owner": "alice",
  "Permission": {
    "Owner": "read, write",
    "Others": "read"
  },
  "CreationTime": [2026, 7, 15, 14, 30, 0, 123],
  "LastEditTime": [2026, 7, 15, 14, 35, 42, 456],
  "Locked": false,
  "LockedBy": null,
  "LockedByGeneration": null,
  "LeaseUntilEpochMs": 0,
  "FencingToken": 0,
  "SymLink": null
}
<META>#
实际文件内容...
```

### 字段说明

| 字段 | 类型 | 说明 |
|---|---|---|
| Owner | String | 文件所有者用户名 |
| Permission.Owner | String | 所有者权限 (read, write, 或两者) |
| Permission.Others | String | 其他用户的权限 |
| CreationTime | int[] | 创建时间 [年, 月, 日, 时, 分, 秒, 毫秒] |
| LastEditTime | int[] | 最后修改时间 |
| Locked | Boolean | 是否被锁定 |
| LockedBy | Number | 锁定者的 PID |
| LockedByGeneration | String | 锁定者的 generation |
| LeaseUntilEpochMs | Number | 锁租约到期时间戳 (ms) |
| FencingToken | Number | 隔离令牌（防过期锁持有者写入） |
| SymLink | String\|null | 软链接目标路径 |

---

## 7 文件操作

### 读写文件

```fcl
// 读取文件内容
content = file.read("/user/alice/data.txt")

// 写入文件（覆盖）
file.write("/user/alice/data.txt", "新的内容")

// 追加内容
file.append("/user/alice/log.txt", "新的一行\n")

// 通过 io 命名空间读写（与 file 功能相似）
content = io.readFile("/user/alice/data.txt")
io.writeFile("/user/alice/data.txt", "新的内容")
```

### 文件 CRUD 操作

```fcl
// 创建文件
file.createFile("/user/alice", "new_file.txt")

// 删除文件
file.removeFile("/user/alice/data.txt")

// 创建目录
file.createDir("/user/alice", "subdir")

// 删除目录
file.removeDir("/user/alice/subdir")

// 重命名
file.rename("/user/alice/old.txt", "new.txt")

// 列目录
listing = file.listdir("/user/alice")
println(listing)

// 检查文件是否存在
exists = file.exists("/user/alice/data.txt")
```

### 文件锁

```fcl
// 获取文件锁（返回 {fencingToken, leaseUntilEpochMs}）
lockInfo = file.lock("/user/alice/shared.txt")

// 写入时使用 fencing token 保护
file.write("/user/alice/shared.txt", "安全写入", lockInfo.fencingToken)

// 续租锁
renewed = file.renewLock("/user/alice/shared.txt",
                         lockInfo.fencingToken, 5000)

// 释放锁
file.unlock("/user/alice/shared.txt", lockInfo.fencingToken)
```

### 符号链接

```fcl
// 创建软链接（link → target）
file.link("/user/alice", "/system/app/tool.fcl")

// 之后 /user/alice/tool.fcl → /system/app/tool.fcl
```



## 8 语言概览

FCL（Follarce CilExec Language）是 CilExec 的标准脚本语言。语法接近类 C 语言，但动态类型、括号分隔代码块。

### 基本结构

```fcl
// 这是一行注释
#  这也是注释

// 变量赋值
x = 42
name = "Alice"

// 数组
arr = [1, 2, 3]

// Map（关联数组）
person = {"name": "Alice", "age": 30}

// 函数
func add(a, b) {
    return a + b
}

// 调用
result = add(10, 20)
println(result)           // 30

// 控制流
if x > 10 {
    println("大")
} {
    println("小")
}

while x > 0 {
    println(x)
    x = x - 1
}
```

---

## 9 数据类型

### 基本类型

| 类型 | 示例 | 说明 |
|---|---|---|
| 整数 | `42`, `-7`, `0` | Java long 范围的整数 |
| 浮点数 | `3.14`, `-0.5` | 双精度浮点数 |
| 字符串 | `"hello"`, `"世界"` | UTF-8 字符串 |
| 布尔值 | `true` / `false` | 小写 |
| null | `null` | 空值 |

### 复合类型

| 类型 | 示例 |
|---|---|
| 数组 (List) | `[1, 2, 3]` |
| Map | `{"key": "value"}` |
| 多维数组 | `[[1, 2], [3, 4]]` |

### 类型检查函数

```fcl
util.typeOf(42)        // "java.lang.Long"   (或 Integer/Double)
util.isNumber(42)      // true
util.isString("hello") // true
util.isArray([1,2])    // true
util.isMap({})         // true
util.isBool(true)      // true
```

---

## 10 变量与赋值

### 基本赋值

```fcl
x = 42                // 数字
y = "hello"           // 字符串
z = true              // 布尔
w = [1, 2, 3]         // 数组
m = {"a": 1, "b": 2}  // Map
```

### 索引赋值

```fcl
arr = [10, 20, 30]
arr[0] = 99           // arr[0] 变为 99

map = {"key": "old"}
map["key"] = "new"    // map["key"] 变为 "new"
```

### 多级访问

```fcl
nested = {"outer": {"inner": "value"}}
println(nested["outer"]["inner"])   // "value"
```

---

## 11 运算符

### 算术运算符

| 运算符 | 含义 |
|---|---|
| `+` | 加法 / 字符串拼接 |
| `-` | 减法 |
| `*` | 乘法 |
| `/` | 除法 |
| `%` | 取模 |

### 比较运算符

| 运算符 | 含义 |
|---|---|
| `==` | 等于 |
| `!=` | 不等于 |
| `<` | 小于 |
| `>` | 大于 |
| `<=` | 小于等于 |
| `>=` | 大于等于 |

### 逻辑运算符

| 运算符 | 含义 |
|---|---|
| `and` | 逻辑与 |
| `or` | 逻辑或 |
| `!` | 逻辑非 |

### 示例

```fcl
result = (10 + 5) * 2              // 30
name = "Hello" + " " + "World"     // "Hello World"
check = x > 5 and y < 10           // true / false
```

---

## 12 控制流

### if / else

```fcl
if x > 10 {
    println("大于 10")
} {
    println("小于等于 10")
}
```

注意：`else` 分支紧跟在 `if` 代码块的 `}` 之后，用新的 `{ }` 包围。

### if / else if / else 链

```fcl
if x > 10 {
    println("大于 10")
} {
    if x > 5 {
        println("5 到 10 之间")
    } {
        println("小于等于 5")
    }
}
```

### while

```fcl
i = 0
while i < 10 {
    println(i)
    i = i + 1
}
```

### break

```fcl
i = 0
while true {
    if i >= 5 {
        break
    }
    println(i)
    i = i + 1
}
```

### return

```fcl
func find(list, target) {
    i = 0
    while i < len(list) {
        if list[i] == target {
            return true
        }
        i = i + 1
    }
    return false
}
```

---

## 13 函数定义

### 基本语法

```fcl
func 函数名(参数1, 参数2) {
    函数体
    return 返回值
}
```

### 示例

```fcl
// 定义阶乘函数
func factorial(n) {
    if n <= 1 {
        return 1
    } {
        return n * factorial(n - 1)
    }
}

// 调用
result = factorial(5)   // 120
```

### 闭包变量

函数内部可以访问外部作用域的变量：

```fcl
counter = 0
func increment() {
    counter = counter + 1
    return counter
}

println(increment())    // 1
println(increment())    // 2
```

---

## 14 数据类型进阶

### JSON 序列化/反序列化

```fcl
data = {"name": "Alice", "scores": [95, 87, 92]}

// 对象 → JSON 字符串
jsonStr = util.toJson(data)

// JSON 字符串 → 对象
parsed = util.fromJson(jsonStr)
```

### 类型转换

```fcl
str = util.toString(42)     // "42"
num = util.fromJson("42")   // 42
```

---

## 15 import 与 include

### import ─ 导入文件或包

```fcl
// 导入单个文件
import "lib/math"
import "lib/utils"

// 导入包内全部 FCL 文件
import json.*

// 使用命名空间导入，可在同一进程隔离不同包版本
import json-v1.* as json1
import json-v2.* as json2
value = json1.parse("{}")
```

单文件路径必须使用引号；包导入以 `.*` 结尾，支持相对路径和绝对路径。可选的
`as <identifier>` 只适用于包导入，它把包函数注册为 `identifier.function`，并隔离
该包的精确依赖图。

### include ─ 包含另一个脚本文件

```fcl
// 包含另一个 .fcl 文件的内容
include "/system/app/helpers.fcl"
include "/user/alice/my-lib.fcl"
```

`include` 从 VFS 路径读取 `.fcl` 文件，将其内容内联到当前脚本。



## 16 函数调用约定

### 带命名空间调用（推荐）

```fcl
result = namespace.functionName(arg1, arg2, ...)
```

例如：
```fcl
content = file.read("/path/to/file")
println(io.readFile("/path/to/file"))
user.createUser("alice", "password123")
pool = swapPool.create("mypool")
```

### 无命名空间调用（兼容旧写法）

对于 `process`、`io`、`util` 命名空间的部分函数，可以不写命名空间：

```fcl
pid = fork()           // 同 process.fork()
kill(5)                // 同 process.kill(5)
println("hello")       // 同 io.println("hello")
print("hi")            // 同 io.print("hi")
exit()                 // 同 util.exit()
```

---

## 17 io 命名空间

标准输入输出操作。

| 函数 | 参数 | 返回值 | 说明 | 副作用策略 |
|---|---|---|---|---|
| `io.print(x)` | 任意值 | 空字符串 | 输出到 stdout | PURE |
| `io.println(x)` | 任意值 | 空字符串 | 输出到 stdout 并换行 | PURE |
| `io.input()` | 无 | String | 读取 stdin 一行 | RECORDED |
| `io.input(prompt)` | String prompt | String | 显示提示后读取 | RECORDED |
| `io.readFile(path)` | String 路径 | String | 读取文件全部内容 | RECORDED |
| `io.writeFile(path, content)` | String 路径, 内容 | 空字符串 | 写入文件 | RECORDED |
| `io.readChar()` | 无 | String (单字符) | 读取 stdin 一个字符 | RECORDED |

### 示例

```fcl
io.println("请输入你的名字:")
name = io.input("名字: ")
io.println("你好, " + name + "!")

data = io.readFile("/user/alice/data.txt")
io.writeFile("/user/alice/backup.txt", data)
```

---

## 18 file 命名空间

文件与目录操作，带权限检查。

| 函数 | 参数 | 返回值 | 说明 | 副作用策略 |
|---|---|---|---|---|
| `file.read(path)` | String | String | 读取文件内容 | PURE |
| `file.write(path, content)` | String, String | 空字符串 | 覆盖写入文件 | LOCAL_TX |
| `file.write(path, content, token)` | String, String, Number | 空字符串 | 带 fencing token 保护写入 | LOCAL_TX |
| `file.append(path, content)` | String, String | 空字符串 | 追加到文件末尾 | LOCAL_TX |
| `file.append(path, content, token)` | String, String, Number | 空字符串 | 带 fencing token 保护追加 | LOCAL_TX |
| `file.createFile(dir, name)` | String, String | 空字符串 | 在目录中创建新文件 | LOCAL_TX |
| `file.removeFile(path)` | String | 空字符串 | 删除文件 | LOCAL_TX |
| `file.createDir(dir, name)` | String, String | 空字符串 | 创建目录 | LOCAL_TX |
| `file.removeDir(path)` | String | 空字符串 | 递归删除目录 | LOCAL_TX |
| `file.rename(path, newName)` | String, String | 空字符串 | 重命名文件 | LOCAL_TX |
| `file.listdir(path)` | String | String | 列出目录内容 | PURE |
| `file.link(dir, target)` | String, String | 空字符串 | 创建符号链接 | LOCAL_TX |
| `file.lock(path)` | String | Map | 获取文件锁 | LOCAL_TX |
| `file.lock(path, leaseMs)` | String, Number | Map | 获取带租约的锁 | LOCAL_TX |
| `file.renewLock(path, token)` | String, Number | Map | 续租文件锁 | LOCAL_TX |
| `file.renewLock(path, token, leaseMs)` | String, Number, Number | Map | 续租并更新租约时长 | LOCAL_TX |
| `file.unlock(path)` | String | 空字符串 | 释放文件锁 | LOCAL_TX |
| `file.unlock(path, token)` | String, Number | 空字符串 | 带 token 验证释放锁 | LOCAL_TX |
| `file.exists(path)` | String | Boolean | 检查路径是否存在 | PURE |
| `file.readMetaData(path)` | String | Map | 读取元数据 | PURE |

### 锁操作示例

```fcl
// 获取锁
lock = file.lock("/user/alice/accounts.txt", 10000)
println("fencing token: " + lock.fencingToken)

// 安全写入
file.write("/user/alice/accounts.txt", "新数据", lock.fencingToken)

// 续租
lock = file.renewLock("/user/alice/accounts.txt", lock.fencingToken, 5000)

// 释放
file.unlock("/user/alice/accounts.txt", lock.fencingToken)
```

锁返回值格式：
```json
{
  "fencingToken": 1234567890,
  "leaseUntilEpochMs": 1720000000000
}
```

---

## 19 user 命名空间

用户管理与身份切换。

| 函数 | 参数 | 返回值 | 说明 | 副作用策略 |
|---|---|---|---|---|
| `user.createUser(name, pass)` | String, String | String | 创建用户（仅 local） | LOCAL_TX |
| `user.removeUser(name, pass)` | String, String | String | 删除用户（仅 local） | LOCAL_TX |
| `user.switchUser(name, pass)` | String, String | String | 切换当前用户 | RECORDED |
| `user.validateUser(name, pass)` | String, String | Boolean | 验证用户凭据 | PURE |
| `user.getCurrentUser()` | 无 | String | 获取当前用户名 | PURE |
| `user.isLocal()` | 无 | Boolean | 当前是否为 superuser | PURE |
| `user.getListOfUsers()` | 无 | String | 获取用户列表（不含密码） | PURE |

### 示例

```fcl
// 以 superuser 身份创建用户
user.createUser("bob", "bob123")

// 切换到 bob
user.switchUser("bob", "bob123")
println(user.getCurrentUser())     // "bob"

// 换回 superuser
user.switchUser("local", "local")

// 检查权限
if user.isLocal() {
    println("我有超级权限")
}
```

---

## 20 util 命名空间

通用工具函数。

| 函数 | 参数 | 返回值 | 说明 | 副作用策略 |
|---|---|---|---|---|
| `util.print(x)` | 任意 | 空字符串 | 打印到 stdout | PURE |
| `util.println(x)` | 任意 | 空字符串 | 打印并换行 | PURE |
| `util.input()` | 无 | String | 读取 stdin 一行 | RECORDED |
| `util.input(prompt)` | String | String | 提示后读取 | RECORDED |
| `util.toJson(x)` | 任意 | String | 转为 JSON 字符串 | PURE |
| `util.fromJson(s)` | String | Object | 从 JSON 字符串解析 | PURE |
| `util.typeOf(x)` | 任意 | String | 返回 Java 类名 | PURE |
| `util.toString(x)` | 任意 | String | 转为字符串 | PURE |
| `util.isArray(x)` | 任意 | Boolean | 判断是否为数组 | PURE |
| `util.isMap(x)` | 任意 | Boolean | 判断是否为 Map | PURE |
| `util.isNumber(x)` | 任意 | Boolean | 判断是否为数字 | PURE |
| `util.isString(x)` | 任意 | Boolean | 判断是否为字符串 | PURE |
| `util.isBool(x)` | 任意 | Boolean | 判断是否为布尔值 | PURE |
| `util.exit()` | 无 | - | 退出当前进程 | RECORDED |
| `util.sleep(ms)` | Number | 空字符串 | 休眠毫秒 | PURE |
| `util.getTime()` | 无 | int[] | 当前时间 | PURE |

### util.getTime() 返回值

返回 `[年, 月, 日, 时, 分, 秒, 毫秒]` 的整数数组：

```fcl
now = util.getTime()
println(now[0])   // 2026
println(now[1])   // 7
```

---

## 21 process 命名空间

进程创建与控制。

| 函数 | 参数 | 返回值 | 说明 | 副作用策略 |
|---|---|---|---|---|
| `process.fork()` | 无 | Number (子PID) | 创建子进程 | LOCAL_TX |
| `process.exec(path)` | String | - | 替换当前进程代码 | LOCAL_TX |
| `process.exec(path, arg1, ...)` | String, ... | - | 替换代码并传参 | LOCAL_TX |
| `process.kill(pid)` | Number | Boolean | 终止指定进程 | LOCAL_TX |
| `process.wait()` | 无 | - | 等待任意子进程退出 | PURE |
| `process.waitPID(pid)` | Number | - | 等待特定子进程退出 | PURE |
| `process.pause(pid)` | Number | - | 挂起进程 | LOCAL_TX |
| `process.continue(pid)` | Number | - | 恢复进程 | LOCAL_TX |
| `process.getPID()` | 无 | Number | 获取当前 PID | PURE |
| `process.getPPID()` | 无 | Number | 获取父进程 PID | PURE |
| `process.getListOfChildProcess()` | 无 | String | 获取子进程列表 | PURE |

### fork 示例

```fcl
childPid = process.fork()
if childPid == 0 {
    // 子进程：PID=0 表示这是子进程
    process.exec("/system/app/worker.fcl")
} {
    // 父进程：childPid 是子进程的 PID
    println("创建了子进程 PID=" + childPid)
    process.waitPID(childPid)
    println("子进程已退出")
}
```

### kill 示例

```fcl
// 终止另一个进程
process.kill(42)

// 检查是否成功
result = process.kill(99)
```

---

## 22 swapPool 命名空间

进程间通信交换池。完整类型感知的消息队列。

| 函数 | 参数 | 返回值 | 说明 | 副作用策略 |
|---|---|---|---|---|
| `swapPool.create(name)` | String | String | 创建交换池 | LOCAL_TX |
| `swapPool.remove(name)` | String | String | 删除交换池（仅所有者） | LOCAL_TX |
| `swapPool.add(varname:value, pool)` | String, String | String | 添加变量 | LOCAL_TX |
| `swapPool.add(varname:value, pool, 选项...)` | String, String, ... | String | 带选项添加变量 | LOCAL_TX |
| `swapPool.get(varName, pool)` | String, String | Object | 获取变量值 | LOCAL_TX |
| `swapPool.removeVar(varName, pool)` | String, String | String | 删除变量 | LOCAL_TX |
| `swapPool.removeVar(varName, pool, token)` | String, String, Number | String | 带 token 验证删除 | LOCAL_TX |
| `swapPool.update(varName, pool, value)` | String, String, String | Object | 更新变量值 | LOCAL_TX |
| `swapPool.update(varName, pool, value, token)` | String, Str, Str, Num | Object | 带 token 验证更新 | LOCAL_TX |
| `swapPool.lock(varName, pool)` | String, String | Map | 锁定变量 | LOCAL_TX |
| `swapPool.lock(varName, pool, leaseMs)` | String, String, Number | Map | 带租约锁定变量 | LOCAL_TX |
| `swapPool.renewLock(varName, pool, token)` | String, String, Number | Map | 续租变量锁 | LOCAL_TX |
| `swapPool.renewLock(varName, pool, token, leaseMs)` | String, String, Number, Number | Map | 续租并更新租约 | LOCAL_TX |
| `swapPool.unlock(varName, pool, token)` | String, String, Number | 空字符串 | 释放变量锁 | LOCAL_TX |
| `swapPool.ls(pool)` | String | 空字符串 | 列出所有变量 | PURE |
| `swapPool.clear(pool)` | String | String | 清空交换池 | LOCAL_TX |
| `swapPool.exists(pool)` | String | Boolean | 检查交换池是否存在 | PURE |
| `swapPool.waitFor(varName, pool)` | String, String | - | 等待变量变更 | LOCAL_TX |
| `swapPool.signal(varName, pool)` | String, String | - | 唤醒等待者 | LOCAL_TX |
| `swapPool.list()` | 无 | String | 列出所有交换池 | PURE |

### 变量类型选项

添加变量时可指定类型和访问控制：

```fcl
// 基本添加（always 类型，可无限次读取）
swapPool.add("msg:Hello World", "mypool")

// sync 类型（读取一次后标记 changed=false）
swapPool.add("msg:Hello", "mypool", "type:sync")

// times(N) 类型（只能读取 N 次，之后自动删除）
swapPool.add("msg:secret", "mypool", "type:times(3)")

// 访问白名单（仅 PID 2 和 3 可读取）
swapPool.add("msg:private", "mypool", "whitelist:2,3")

// 组合选项
swapPool.add("msg:data", "mypool", "type:sync", "whitelist:2,3")
```

### 变量锁操作

```fcl
// 锁定变量
lockInfo = swapPool.lock("counter", "mypool", 10000)

// 安全更新
swapPool.update("counter", "mypool", "42", lockInfo.fencingToken)

// 释放锁
swapPool.unlock("counter", "mypool", lockInfo.fencingToken)
```

### Producer/Consumer 模式

```fcl
// Producer 进程
while true {
    swapPool.waitFor("req", "mypool")
    msg = swapPool.get("req", "mypool")
    // 处理 msg...
    swapPool.add("resp:" + result, "mypool", "type:sync")
    swapPool.signal("resp", "mypool")
}

// Consumer 进程
swapPool.add("req:do_work", "mypool", "type:sync")
swapPool.signal("req", "mypool")
swapPool.waitFor("resp", "mypool")
result = swapPool.get("resp", "mypool")
```

---

## 23 network 命名空间

HTTP 网络请求。

| 函数 | 参数 | 返回值 | 说明 | 副作用策略 |
|---|---|---|---|---|
| `network.fetch(url)` | String | String | GET 请求 | MANUAL_RECOVERY |
| `network.fetch(url, headers)` | String, Map | String | 带自定义头 GET | MANUAL_RECOVERY |
| `network.download(url)` | String | Map | 下载二进制文件 | MANUAL_RECOVERY |
| `network.download(url, path)` | String, String | String | 下载到 VFS 路径 | MANUAL_RECOVERY |
| `network.httpPost(url, body)` | String, String | String | POST 请求（携带 Idempotency-Key） | MANUAL_RECOVERY |

### 示例

```fcl
// GET 请求
result = network.fetch("https://api.example.com/data")

// POST 请求（自动携带 Idempotency-Key head）
response = network.httpPost("https://api.example.com/submit",
                             '{"key": "value"}')

// 下载文件到 VFS
network.download("https://example.com/file.bin",
                  "/user/alice/downloads/file.bin")
```

### MANUAL_RECOVERY 行为

由于 HTTP 请求的结果无法在 JVM 崩溃后确认，`network.*` 函数被标记为 `MANUAL_RECOVERY`。如果进程在执行网络调用期间崩溃：

1. 进程恢复后检测到 `IN_DOUBT` effect receipt
2. 进程进入 `BlockReason.EFFECT_RECOVERY` 状态
3. 必须由管理员显式确认结果

---

## 24 socket 命名空间

TCP/UDP Socket 通信。

| 函数 | 参数 | 说明 | 副作用策略 |
|---|---|---|---|
| socket 函数 | 地址、端口、数据 | 低层 TCP/UDP 操作 | MANUAL_RECOVERY |

Socket 操作的返回值无法在崩溃后确定，同样使用 MANUAL_RECOVERY 策略。

---

## 25 math 命名空间

数学函数。所有 math 函数都是 PURE（无副作用）。

| 函数 | 参数 | 说明 |
|---|---|---|
| `math.abs(x)` | Number | 绝对值 |
| `math.ceil(x)` | Number | 向上取整 |
| `math.floor(x)` | Number | 向下取整 |
| `math.round(x)` | Number | 四舍五入 |
| `math.max(a, b)` | Number, Number | 取最大值 |
| `math.min(a, b)` | Number, Number | 取最小值 |
| `math.sin(x)` | Number (弧度) | 正弦 |
| `math.cos(x)` | Number (弧度) | 余弦 |
| `math.tan(x)` | Number (弧度) | 正切 |
| `math.sqrt(x)` | Number | 平方根 |
| `math.pow(a, b)` | Number, Number | a 的 b 次幂 (a^b) |
| `math.log(x)` | Number | 自然对数 |
| `math.random()` | 无 | [0, 1) 随机浮点数 |
| `math.randInt(min, max)` | Number, Number | [min, max) 随机整数 |

```fcl
x = math.sqrt(16)           // 4.0
y = math.pow(2, 10)         // 1024.0
z = math.randInt(1, 100)    // 1 到 99 的随机整数
```

---

## 26 path 命名空间

路径操作函数。全部是 PURE（无副作用）。

| 函数 | 参数 | 说明 |
|---|---|---|
| `path.resolve(path)` | String | 规范化路径 |
| `path.normalize(path)` | String | 去除 `.` 和 `..` 的规范化 |
| `path.getParent(path)` | String | 获取父目录 |
| `path.getFileName(path)` | String | 获取文件名 |
| `path.toRealPath(path)` | String | 转真实文件系统路径 |
| `path.isAbsolute(path)` | String | 判断是否为绝对路径 |
| `path.exists(path)` | String | 检查路径是否存在 |

```fcl
parent = path.getParent("/user/alice/data.txt")  // "/user/alice"
name = path.getFileName("/user/alice/data.txt")  // "data.txt"
```

---

## 27 system 命名空间

特权操作系统操作。仅 local superuser 可用。

| 函数 | 参数 | 说明 | 副作用策略 |
|---|---|---|---|
| `system.kill(pid)` | Number | 强制终止进程 | MANUAL_RECOVERY |
| `system.exec(path)` | String | 特权级代码替换 | MANUAL_RECOVERY |
| `system.resolveEffect(pid, effectId, decision, result)` | Number, String, String, Object | 人工恢复外部副作用 | RECORDED |

### system.resolveEffect ─ 人工恢复

当进程因 `IN_DOUBT` 副作用阻塞时，管理员调用此函数决定结果：

```fcl
// 重试执行效果
system.resolveEffect(42, "stmt-3-effect-0", "retry", null)

// 确认效果已成功，提供返回值
system.resolveEffect(42, "stmt-3-effect-0", "confirm",
                      "server-response-text")

// 拒绝效果（认定失败），提供默认值继续执行
system.resolveEffect(42, "stmt-3-effect-0", "reject", "timeout")
```



## 28 进程生命周期

### 状态机

```
                     ┌─────────────────────────────────┐
                     │           NEW                   │
                     └─────────────┬───────────────────┘
                                   │ init() 完成
                                   ▼
                     ┌─────────────────────────────────┐
                     │          READY                  │
                     └─────────────┬───────────────────┘
                                   │ step() 开始
                                   ▼
                     ┌─────────────────────────────────┐
         ┌───────────│         RUNNING                 │──────────────┐
         │           └─────────────┬───────────────────┘              │
         │                         │                                   │
         │              ┌──────────┼──────────┐                        │
         │              │          │          │                        │
         ▼              ▼          ▼          ▼                        │
   ┌──────────┐  ┌──────────┐ ┌────────┐ ┌───────────┐               │
   │  READY   │  │ BLOCKED  │ │ PAUSED │ │TERMINATED │               │
   │ (时间片)  │  │ (wait /  │ │ (pause │ │ (正常/    │               │
   │          │  │  EFFECT_ │ │  消息) │ │  kill)    │               │
   │          │  │  RECOVERY│ │        │ │           │               │
   └──────────┘  └──────────┘ └────────┘ └───────────┘               │
                     │                        ▲                       │
                     │              (continue │消息)                  │
                     ▼                        │                       │
               ┌──────────┐                   │                       │
               │  READY   │ ──连续──→         │                       │
               └──────────┘                   │                       │
                                              │                       │
                     ┌────────────────────────┘                       │
                     │                                                │
                     ▼                                                ▼
               ┌─────────────────────────────────────────────────────────┐
               │                        FAILED                          │
               │                   (不可恢复异常)                          │
               └─────────────────────────────────────────────────────────┘
```

### .proc 文件结构

每个进程的状态保存为一个 JSON 文件：

```json
{
  "PID": 2,
  "Name": "worker",
  "Owner": "local",
  "ProcessGeneration": "a1b2c3d4-...",
  "EffectiveUser": "local",
  "PathAliases": { "home": "/user/local" },
  "ProcessState": "RUNNING",
  "BlockReason": "NONE",
  "ExitReason": "NONE",
  "StateMessage": null,
  "Priority": 0,
  "Status": true,
  "RunningTime": 15000,
  "CreatedByEffectId": "stmt-3-effect-0",
  "Parent": {
    "PID": 1,
    "Name": "INIT",
    "Generation": "parent-uuid-..."
  },
  "Child": {
    "5": {
      "PID": 5,
      "Name": "test-5",
      "Generation": "child-uuid-..."
    }
  },
  "ExitedChildren": {
    "3": {
      "PID": 3,
      "Generation": "exited-uuid",
      "ExitReason": "NONE"
    }
  },
  "ReapedChildren": { "3@exited-uuid": true },
  "Execution": {
    "SchemaVersion": 1,
    "NextAttemptOrdinal": 3,
    "ActiveAttempt": { ... },
    "AttemptLedger": { "stmt-1": "COMPLETED", "stmt-2": null }
  },
  "InboxState": {
    "AppliedMessageIds": {
      "msg-abc": 1
    }
  },
  "LifecycleCleanup": null,
  "Program": {
    "Data": { "x": 42, "y": 100 },
    "Code": {
      "Code": [
        "x = 42",
        "y = 100",
        "result = x + y",
        "println(result)"
      ],
      "runningCodeLine": 2,
      "BlockStack": []
    }
  }
}
```

### 状态字段说明

| 字段 | 可能的值 | 说明 |
|---|---|---|
| ProcessState | NEW / READY / RUNNING / BLOCKED / PAUSED / TERMINATED / FAILED | 当前状态 |
| BlockReason | NONE / WAIT_CHILD / EFFECT_RECOVERY | 阻塞原因 |
| ExitReason | NONE / KILLED / FAILED | 退出原因 |
| Status | Boolean (true=运行中) | 兼容旧格式的布尔运行标志 |

---

## 29 ProcessGeneration ─ 代次身份

### 为什么需要 Generation

PID 本身不可信。当一个进程退出后，相同 PID 可能立即被新 fork 的进程复用。在没有 generation 的情况下，旧进程的消息、锁和父子关系会被错误地应用到新进程。

### 设计

- `ProcessGeneration` 是 UUID v4 随机字符串
- 进程在首次 `ensureDefaults()` 时获得 generation
- fork 的子进程得到自己的新 generation
- exec 保留 generation
- 所有跨进程操作都要求 generation 匹配

### 生效范围

| 操作 | generation 检查 |
|---|---|
| `recordChildExit` | parentGeneration + childGeneration 必须均非 null |
| `reparentToInit` | childGeneration 为 null 则拒绝 |
| `postMessageToGeneration` | 磁盘快照 ProcessGeneration 必须匹配 |
| kill / pause / continue | 消息目标 generation 必须匹配 |
| 文件锁 | 锁归属 generation，不匹配则无效 |
| swap pool 锁 | lease 绑定 generation |
| 进程文件删除 | removeProcessFile 验证 expectedGeneration |
| Scheduler 替换 runner | 同一 PID 新 generation 则停旧启新 |

### 旧快照迁移

`RecoveryManager` 启动时处理：
- 缺少 generation → 自动分配新 UUID
- 缺少父子 generation → 双向验证后补齐或移除



## 30 ProcessRunner ─ 执行引擎

### 核心流程

```
new ProcessRunner(pid, processData)
    │
    ├── ProcessIdentity.ensureDefaults()    ← 补全 generation/EffectiveUser/PathAliases
    ├── 创建所有子组件（StateManager, CodeLoader, ExpressionEvaluator, ...）
    │
    └── init()
        ├── loadRuntimeState()              ← 从 processData 恢复代码、变量、行号
        ├── RUNNERS.put(pid, this)          ← 注册为 PID 的唯一 runner
        ├── 解析用户函数
        ├── 状态 NEW/RUNNING → READY
        └── persistState()
```

### step() ─ 单步执行

```
step()
  ├── processPendingMessages()     ← 扫描 inbox/*.msg，处理外部消息
  ├── executeLine()
  │   ├── 检查 currentLine >= codeLines.size() → terminateNormally
  │   ├── 跳过空行/注释
  │   ├── 花括号处理 → ControlFlow.handleClosingBraces
  │   └── dispatchStatement()
  │       ├── if / while           → 条件判断 + BoundaryTable 跳转
  │       ├── func ... { }         → 注册函数定义
  │       ├── import / include     → ImportManager
  │       ├── return / break       → ControlFlow
  │       ├── exec("path")         → 替换代码行（保留 PID/generation）
  │       ├── arr[i] = expr        → 索引赋值
  │       ├── var = expr           → 普通赋值 + attemptManager.invoke()
  │       ├── FORK 标记            → invokeEngineEffect + ipcHandler.handleFork()
  │       ├── KILL:/PAUSE:/CONTINUE → invokeControlEffect
  │       └── WAIT / WAITPID       → ipcHandler
  │
  └── persistState()               ← 原子写入 .proc 文件
```

### 虚拟线程运行循环

```
virtualThreadRun()
  while running && !state.isTerminal():
    step()
    if PAUSED || BLOCKED:
      LockSupport.parkNanos(timeout)
    next iteration
```

### settle() ─ 行号推进

每行执行完毕后调用 `settle(nextLine)`：
1. 设置 `currentLine = nextLine`
2. 调用 `persistState()` ─ 将完整的进程状态（变量 + 行号 + effect receipt）原子写入 `.proc`

---

## 31 Scheduler ─ 调度器

### 职责

- 每 50ms 扫描 `/system/process/` 目录
- 发现新的 `.proc` 文件 → 创建 ProcessRunner + 启动虚拟线程
- 检测 PID 复用（同一 PID 不同 generation）
- 终态文件的 lifecycle cleanup
- 进程全部终止时自动退出

### 关键行为

```
scanForNewProcesses()
  遍历 *.proc 文件：
  
  → PID = INIT (1) → 跳过（由 Main 手动注册）
  → 终态 + cleanup 标记 → reconcileLifecycle()
  → 已有 runner 但 generation 不同 → 停旧 runner，建新 runner
  → 已有 runner 但已 stop → 移除，建新 runner
  → 无 runner → new ProcessRunner() + init() + addProcess()
  
  cleanupRemovedProcesses()
  → 磁盘无 .proc 文件 → 连续 3 次 (150ms) 才确认死亡
  → 移除 runner
```

### 故障容忍

- 文件连续缺失计数：容忍 writeAtomic 原子重命名期间的瞬时读取失败
- 损坏快照：保留不变，不创建 runner
- `.proc.tmp` 作为恢复备选：`FileUtil.exists()` 自动提升

---

## 32 StateManager ─ 状态持久化

### 职责

- 将 `processData` Map 序列化为 JSON 并原子写入 `.proc` 文件
- 从文件加载状态
- 管理进程生命周期（cleanup、notifyParent、reparentChildren）

### 持久化流程

```
StateManager.save()
  ├── per-PID ReentrantLock (persistenceLock)
  ├── 验证 expectedGeneration == processData.ProcessGeneration
  │     └── 不匹配 → 抛出异常（防止 PID 复用的脏写）
  ├── 序列化 processData 为 JSON
  ├── 写入 .proc.tmp 临时文件
  ├── 原子重命名 .proc.tmp → .proc
  └── 解锁
```

### expectedGeneration 固定

`init()` 时设置 `stateManager.setExpectedGeneration(ownedGeneration)`。
此后所有 `save()` 调用都验证 generation —— 防止两个相同 PID 的 runner 竞态写同一个文件。

---

## 33 设计理念：零内存状态

### 核心原则

CilExec 的持久化设计遵循**零内存状态**原则：

1. **每条语句执行完毕后立即持久化** ─ 变量、行号、blockStack、所有元数据
2. **跨进程调用使用持久化消息** ─ 而不是内存对象引用
3. **进程崩溃后从磁盘恢复** ─ 不需要内存中的状态
4. **指令级 effect receipt** ─ 防止重放副作用

### 持久化时序

执行 `x = y + 1` 这一行时，磁盘操作序列：

```
1. begin()       → .proc ← ActiveAttempt { Ordinal: N, Statement: "x = y + 1" }
2. invoke()      → .proc ← ActiveAttempt { Effects: [{Id:..., Operation:..., Status:COMPLETED}] }
3. data["x"]=val  (内存操作)
4. commit()      → .proc ← AttemptLedger{"stmt-N": "COMPLETED"}, ActiveAttempt=null
5. settle()      → .proc ← runningCodeLine+1, Program.Data with updated "x"
```

每次 `[写 .proc]` 都是原子操作（临时文件 + 重命名），崩溃可能发生在任意两步之间。
恢复时根据当前磁盘快照中的 `ActiveAttempt` 和 `AttemptLedger` 决定重放或跳过。

---

## 34 StatementAttempt ─ 指令执行尝试

### 设计目标

- 每条 FCL 语句有一个全局唯一的 attempt ordinal
- 每个 attempt 中的每次副作用调用都有自描述的 effect receipt
- 崩溃后：已完成的 effect 不重复执行；不确定的 effect 标记为 IN_DOUBT

### 三阶段协议

```
PREPARED     – attempt 已记录、ordinal 已分配
   │
   ▼ (invoke: 执行副作用)
COMPLETED    – 副作用已完成确认
   │
   ▼ (commit: 写入 ledger)
AttemptLedger 持久化
```

或

```
PREPARED
   │
   ▼ (invoke: 崩溃或外部结果不明)
IN_DOUBT     – 无法确定是否发生
   │
   ▼ (system.resolveEffect)
retry / confirm / reject
```

### Execution 数据结构

```json
{
  "Execution": {
    "SchemaVersion": 1,
    "NextAttemptOrdinal": 3,
    "ActiveAttempt": {
      "Ordinal": 2,
      "Statement": "x = network.fetch(\"https://api.example\")",
      "LineNumber": 5,
      "Effects": [
        {
          "Id": "stmt-2-effect-0",
          "Operation": "network.fetch",
          "Policy": "MANUAL_RECOVERY",
          "Status": "IN_DOUBT",
          "Timestamp": 1720000000000,
          "ArgumentSummary": "https://api.example",
          "Result": null
        }
      ]
    },
    "AttemptLedger": {
      "stmt-1": "COMPLETED"
    }
  }
}
```

### 崩溃恢复表

| 崩溃时机 | 恢复行为 |
|---|---|
| begin() 后、invoke() 前 | PREPARED 还在，重新执行 |
| invoke() 完成后（COMPLETED receipt） | 读 receipt，直接返回保存的 result（不重复执行） |
| invoke() 过程中（IN_DOUBT receipt） | 抛出 EffectRecoveryRequiredException，进程 BLOCKED |
| commit() 前 | ActiveAttempt 仍在，其 Effects 列表用于判断 |
| commit() 后 | ActiveAttempt=null，新的 attempt 从 NextAttemptOrdinal 开始 |

---

## 35 EffectPolicy ─ 副作用分类与回放

### 四种策略

```java
PURE                 // 无副作用：不记录 effect receipt
RECORDED             // 纯内部副作用：按 receipt 去重重放
LOCAL_TRANSACTIONAL  // 内部事务型：effect ID 写入目标资源，双重去重
MANUAL_RECOVERY      // 外部不确定副作用：崩溃后阻塞，等管理员恢复
```

### 完整分类 (BuiltinFunctionCatalog)

| 策略 | 命名空间 | 函数 |
|---|---|---|
| **PURE** | math | abs, ceil, floor, round, max, min, sin, cos, tan, sqrt, pow, log, random, randInt |
| **PURE** | path | resolve, normalize, getParent, getFileName, toRealPath, isAbsolute, exists |
| **PURE** | io | print, println |
| **PURE** | util | print, println, toJson, fromJson, typeOf, toString, isArray, isMap, isNumber, isString, isBool, sleep, getTime |
| **PURE** | user | getCurrentUser, isLocal, getListOfUsers, validateUser |
| **PURE** | process | wait, waitPID, getPID, getPPID, getListOfChildProcess |
| **PURE** | file | read, exists, readMetaData, listdir |
| **PURE** | swapPool | ls, exists, list |
| **RECORDED** | io | readFile, writeFile, input, readChar |
| **RECORDED** | util | input, exit |
| **RECORDED** | user | switchUser |
| **LOCAL_TX** | file | write, append, createFile, removeFile, createDir, removeDir, rename, link, lock, renewLock, unlock |
| **LOCAL_TX** | process | fork, exec, kill, pause, continue |
| **LOCAL_TX** | user | createUser, removeUser |
| **LOCAL_TX** | swapPool | create, remove, add, get, removeVar, update, lock, renewLock, unlock, clear, waitFor, signal |
| **MANUAL_RECOVERY** | network | fetch, download, httpPost |
| **MANUAL_RECOVERY** | socket | 全部 socket 函数 |
| **MANUAL_RECOVERY** | system | kill, exec |

### 重放逻辑

每个 effect receipt 保存在 processData 或目标资源中：

- **RECORDED / LOCAL_TX**：effect ID + 参数摘要 → 已记录则返回保存的结果
- **MANUAL_RECOVERY**：effect ID → 已 COMPLETED 则返回结果；IN_DOUBT 则阻塞

### Effect ID 格式

```
stmt-{ordinal}-effect-{index}
```

例如 `stmt-2-effect-0` 表示第 2 个 attempt 中的第 0 个 effect。

---

## 36 ProcessInbox ─ 持久化消息收件箱

### 设计目的

进程控制消息（kill、pause、continue、字段更新）必须：

- **幂等地** 抵达正确的进程实例
- 在目标进程不在线时可靠存储
- JVM 崩溃后不丢失
- PID 复用后不误投

### 目录布局

```
/system/process/inbox/
├── deliveries/                          ← 全局 delivery 账本
│   ├── <sha256(msgId)>.delivery         ← 持久化投放记录
│   └── <sha256(msgId)>.lock             ← 发布并发锁
└── <pid>/                               ← 每个 PID
    └── <sha256(generation)>/            ← 按 generation 分层
        ├── sequence                     ← 单调递增序列
        └── <sha256(msgId)>.msg          ← 消息文件
```

### 消息结构

```json
{
  "schemaVersion": 1,
  "messageId": "uuid-or-custom",
  "sequence": 5,
  "targetPid": 42,
  "targetGeneration": "gen-uuid",
  "senderPid": 1,
  "senderGeneration": "init-gen",
  "field": "ProcessState",
  "value": "PAUSED",
  "publishTime": 1720000000000
}
```

### 发布流程

```
ProcessInbox.publish(targetPid, targetGeneration, messageId, senderPid,
                     senderGeneration, field, value)
  │
  ├── 1. 检查 delivery ledger → 存在匹配记录则复用
  ├── 2. 分配序列号 + 创建 ProcessMessage
  ├── 3. 原子写入 .delivery 账本
  └── 4. ensurePublished() → 原子写入 .msg 文件

postMessageToGeneration()
  ├── 检查快照 generation 是否匹配 → 不匹配则拒绝
  ├── 检查 AppliedMessageIds → 已应用则返回
  ├── ProcessInbox.publish()
  ├── 目标在 RUNNERS 中 → unpark
  └── 目标不在 RUNNERS → applyOfflineInbox(直接写 .proc)
```

### 幂等性保证

1. **delivery 账本**：同名 messageId + 相同 generation → 复用已有消息
2. **AppliedMessageIds**：目标进程持久化已应用的消息 ID 列表
3. **generation 隔离**：inbox 目录按 generation 拆分，旧 generation 的消息不会到达新实例

### 启动恢复中的补发

`ProcessInbox.recoverDeliveries()` 扫描 `deliveries/` 目录：
- 对每条 .delivery 记录，检查目标 inbox 中是否有对应 .msg
- 缺失 → 重新创建 .msg
- 已有 → 跳过

---

## 37 RecoveryManager ─ 启动恢复

### 调用时机

`Main.main()` → 在 Scheduler 启动前调用 `RecoveryManager.recoverAll()`

### 恢复步骤

```
recoverAll()
  │
  ├── 1. ProcessInbox.recoverDeliveries()
  │      ← 扫描 deliveries/，补发缺失的 inbox .msg
  │
  ├── 2. 扫描 /system/process/
  │      ← 收集所有 PID（含 .proc.tmp）
  │
  └── 3. 对每个 PID：
         │
         ├── FileUtil.exists() → 自动提升 .proc.tmp
         ├── Reservation 快照 → 检查预留者活跃性
         ├── ensureDefaults() → 补 generation/EffectiveUser/PathAliases
         ├── reconcileRelationshipGenerations() → 修复父子 generation
         ├── 终态 + cleanup 标记 → reconcileLifecycle()
         └── 非终态 → recoverInbox() → 应用离线消息
```

### 关系修复

旧快照缺少 `Parent.Generation` 和 `Child.{pid}.Generation`：

```
Parent: { PID: 5 }                     ← Generation 缺失
Child:  { "7": { PID: 7 } }           ← Generation 缺失

修复流程：
  读 PID 5 快照 → 其 Child 中有当前 PID?
    ├── 有 → Parent.Generation = PID 5 的 generation
    └── 无 → 移除 Parent.PID 和 Parent.Name

  读 PID 7 快照 → 其 Parent.PID == 当前 PID?
    ├── 是 → Child["7"].Generation = PID 7 的 generation
    └── 否 → 从 Child 中移除条目
```

此双向验证确保 PID 复用后的新进程不会继承旧关系。

---

## 38 LifecycleCleanup ─ 终态清理

### 触发路径

| 原因 | 触发方式 |
|---|---|
| 正常退出 | executeLine → 代码执行完 → terminateNormally → prepareLifecycleCleanup(false) |
| 异常退出 | handleException → UnrecoverableException → LifecycleCleanup |
| kill 消息 | postMessage → __Terminate → TERMINATED + LifecycleCleanup |
| 效果恢复拒绝 | system.resolveEffect("reject") → 决定进程终止 → LifecycleCleanup |

### 清理流程

```
reconcileLifecycle(pid, generation)
  │
  ├── 获取 cleanup gate（per-PID+generation，防止并发）
  ├── 检查快照 generation 是否匹配 → 不匹配则放弃
  ├── 检查 LifecycleCleanup 或 TerminationCleanup 标记 → 无标记则放弃
  │
  ├── reparentSnapshotChildren()
  │     └── 将所有活跃子进程收养给 INIT (PID 1)
  │
  ├── finalizeTerminalSnapshot()
  │     └── 通知父进程: recordChildExit(ppid, pid, reason)
  │
  ├── removeProcessFile(pid, generation)
  │     ├── 文件锁 → 验证 generation → 删除 .proc
  │     └── ProcessInbox.removeIncarnation() → 清理 inbox 目录
  │
  └── 释放 cleanup gate
```

### 并发安全

- `LIFECYCLE_CLEANUP_GATES` ─ per-(PID, generation) 全局锁
- cleanup 流程反复验证 generation
- `removeProcessFile` 在文件锁内进行 generation 双检



## 39 fork 与进程树

### fork 流程

```
handleFork(effectId)
  │
  ├── 1. 检查 ForkLedger（同名 effectId 是否已有记录）
  │     ├── 存在 + CREATED + 子进程不存在 → 记录为 missing fork
  │     ├── 存在 + CREATED + generation 不匹配 → UnknownEffectOutcome
  │     └── 存在 + CREATED + 匹配 → 恢复父子关系
  │
  ├── 2. 扫描 CreatedByEffectId==effectId 的子进程
  │     └── 找到 → 恢复 + ForkLedger.markCreated
  │
  ├── 3. 分配新 PID（原子文件创建）
  │     ├── ForkLedger.reserve(effectId, parentPid, parentGeneration,
  │     │                       childPid, childGeneration)
  │     ├── 复制父进程 processData → 子进程
  │     ├── 清除不应继承的字段（InboxState, LifecycleCleanup,
  │     │                            ExitedChildren, ReapedChildren）
  │     ├── 设置 Parent.Generation = 父进程 generation
  │     ├── 设置 CreatedByEffectId = effectId
  │     ├── 写入 .proc
  │     └── ForkLedger.markCreated()
  │
  └── 4. 父进程 Child 列表更新（含 Generation）
```

### 进程树关系

- **fork 后：** 父进程的 `Child` 列表添加子进程条目（含 Generation）
- **子进程退出：** 子进程从 `Child` 移到 `ExitedChildren`
- **父进程 wait()：** 从 `ExitedChildren` 消费，移到 `ReapedChildren`
- **父进程退出（子进程仍然活跃）：** 子进程被 **收养给 INIT (PID 1)**
  - 子进程 `Parent` 更新为 `{PID: 1, Name: "INIT", Generation: init-gen}`
  - INIT 的 `Child` 列表添加该子进程

### 完整示例

```fcl
// Parent (PID 1, INIT)
childPid = process.fork()

if childPid == 0 {
    // Child (PID 2)
    println("我是子进程, PID=" + process.getPID())
    println("父进程 PID=" + process.getPPID())

    grandchild = process.fork()
    if grandchild == 0 {
        // Grandchild (PID 3)
        util.sleep(500)
    } {
        // Child 等待 grandchild
        process.waitPID(grandchild)
    }
} {
    // Parent 等待 child
    println("创建了子进程 PID=" + childPid)
    process.waitPID(childPid)
    println("子进程已退出")
}
```

---

## 40 exec ─ 代码替换

```
exec(path [, arg1, arg2, ...])
```

用新脚本替换当前进程的所有代码行。保留：
- PID
- ProcessGeneration
- EffectiveUser
- PathAliases

丢弃：
- 当前所有变量（`Program.Data` 重置为空 Map）
- 当前行号（重置为 0）
- 调用栈（清除所有函数调用帧）
- blockStack（重置）

### 示例

```fcl
// 启动新程序替换当前进程
process.exec("/system/app/worker.fcl")

// 带参数
process.exec("/system/app/tool.fcl", "arg1", 42, "--verbose")
```

---

## 41 kill / pause / continue

这三个操作都通过 process inbox 发送持久化消息，而非直接修改目标进程。

### kill(pid)

```
kill(targetPid)
  → 发送 __Terminate 消息到 targetPid 的 inbox
  → 目标进程在 processPendingMessages() 中收到
  → running = false, ProcessState = TERMINATED
  → prepareLifecycleCleanup(true)
  → reconcileLifecycle()
```

如果目标进程不在线（已退出或未启动）：
- 直接修改磁盘上的 .proc 文件（`applyOfflineInbox`）
- 写入 TERMINATED + LifecycleCleanup 标记
- 调用 `reconcileLifecycle()` 完成 cleanup

### pause(pid) / continue(pid)

```
pause(targetPid)
  → 发送 ProcessState=PAUSED 消息
  → 保存 resumeState = 当前状态（未来恢复用）
  → 虚拟线程进入 parkNanos()

continue(targetPid)
  → 发送 ProcessState=READY 消息
  → 虚拟线程被 unpark
  → 恢复 resumeState
```

---

## 42 wait / waitPID ─ 等待子进程

### wait()

1. 扫描 `ExitedChildren` → 有未消费的退出事件则消费（不阻塞）
2. 没有 → 设置 `BlockReason=WAIT_CHILD`，虚拟线程 park

### waitPID(pid)

同上，但只消费指定 PID 的退出事件。

### 阻塞唤醒

- 子进程退出时调用 `recordChildExit` → 写入父进程的 `ExitedChildren` → `unparkProcess(ppid)`
- 父进程从 park 被唤醒 → 再次扫描 `ExitedChildren` → 消费退出事件

---

## 43 ForkLedger ─ Fork 持久化账本

### 目的

fork 的 effect ID 可能被重放，需要确保：
- 同一个 effect 不会创建两个子进程
- 子进程的精确 generation 可追溯

### 数据结构

```
/system/fork/<hash(effectId)>.ledger
```

```json

{
  "EffectId": "stmt-3-effect-0",
  "ParentPid": 42,
  "ParentGeneration": "parent-uuid",
  "ChildPid": 99,
  "ChildGeneration": "child-uuid",
  "State": "RESERVED"
}
```

### 两阶段

```
RESERVED  → PID 已分配、预留快照已写入
CREATED   → 子进程完整创建完成
```

### 恢复

- `RESERVED` → 检查预留快照，重新写入完整 .proc
- `CREATED` → 检查子进程 generation，恢复关系或标记冲突



## 44 Swap Pool ─ IPC 交换池

### 设计

交换池是存储在 `/system/swap/{name}.json` 中的带类型元数据的 JSON 文件。

### 数据结构

```json
{
  "name": "mypool",
  "OwnerPID": 1,
  "time": {
    "createTime": [2026, 7, 15, 14, 0, 0, 0],
    "lastEditTime": [2026, 7, 15, 14, 5, 30, 123]
  },
  "content": {
    "counter": {
      "value": 42,
      "type": "always",
      "addTime": [...],
      "editTime": [...],
      "changed": false,
      "lockVersion": 2,
      "locked": false,
      "lockedBy": null,
      "lockedByGeneration": null,
      "leaseUntilEpochMs": 0,
      "fencingToken": 0,
      "whitelist": [],
      "blacklist": [],
      "readCount": 0
    },
    "msg": {
      "value": "hello",
      "type": "sync",
      "changed": true,
      "readCount": 0
    }
  },
  "AppliedEffects": {
    "stmt-5-effect-0": "\"Pool created: mypool\"",
    "stmt-5-effect-1": "\"Variable added: counter\""
  }
}
```

### 变量类型

| 类型 | 读取行为 |
|---|---|
| `always` | 无限次读取，值不变 |
| `sync` | 第一次读取后 `changed=false`，等待 signal |
| `times(N)` | 最多读 N 次，N 次后自动删除 |

### 锁机制

- 绑定 generation + lease + fencing token
- 支持锁、续租、释放
- 变量更新时可附带 fencing token 验证

### 原子事务

每个 swap 操作都是原子事务：
- 读取 `AppliedEffects` → 检查 effect ID 已存在则返回保存的结果
- 执行变异
- 写入结果到 `AppliedEffects`
- 原子写入整个 pool 文件

### 访问控制

- **whitelist**：仅列表中的 PID 可读取（空=不限）
- **blacklist**：列表中的 PID 禁止读取
- **OwnerPID**：仅所有者和 local 用户可删除交换池



## 45 用户模型

### 用户存储

用户凭据保存在 `/system/config/users.json`：

```json
{
  "local": "hashed-password",
  "alice": "hashed-password"
}
```

**安全：** Others 权限为空（`"Others": ""`），防止密码哈希泄露。

### 用户主目录

每个用户有一个主目录 `/user/{username}/`，在用户创建时自动创建。

### EffectiveUser

每个进程独立拥有 `EffectiveUser` 字段，持久化在 `.proc` 中：

- 创建时默认 = `Owner` 值
- fork 继承父进程的 EffectiveUser
- exec 保留 EffectiveUser
- `switchUser()` 修改当前进程的 EffectiveUser

### 超级用户

`local` 用户是超级用户，绕过所有权限检查。

---

## 46 权限模型

### 角色

| 角色 | 说明 |
|---|---|
| **Owner** | 文件创建者，默认 read + write |
| **Others** | 非所有者用户，默认 read |
| **local** | 超级用户，全部权限 |

### 权限类型

| 权限 | 允许操作 |
|---|---|
| `read` | 读取文件内容、列出目录 |
| `write` | 写入、创建、删除、重命名 |
| `read, write` | 全部操作 |

### 权限检查

`FileUtil.validatePermission(path, operation, user)` 在被调用时检查：

- user == "local" → 直接允许
- 文件元数据中的 Owner == user → 按 Owner 权限判断
- 否则 → 按 Others 权限判断

### 额外安全约束

- `io.writeFile` 创建前检查父目录权限
- 非 local 用户不能创建 `/system/process/*.proc`
- `users.json` 的 Others 权限强制清空
- Scheduler 拒绝非 system owner 的快照

---

## 47 用户事务

### createUser

```
createUser(name, password, effectId)
  ├── 检查 effect receipt → 已创建则幂等返回
  ├── 创建主目录 /user/<name>/
  ├── 写入 users.json
  └── 按收敛顺序提交：先目录后配置
```

### removeUser

```
removeUser(name, password, effectId)
  ├── 检查 effect receipt
  ├── 删除 users.json 中的条目
  ├── 删除主目录
  └── 按收敛顺序提交：先配置后目录
```

### 收敛性

如果崩溃发生在两步之间，重新执行时可以安全地重试（已完成的步骤检测为 efect receipt 匹配）。

---

## 48 文件锁 v2

### 锁数据结构

```json
{
  "Locked": true,
  "LockedBy": 1,
  "LockedByGeneration": "uuid",
  "LeaseUntilEpochMs": 1720000100000,
  "FencingToken": 42
}
```

### 核心机制

| 机制 | 作用 |
|---|---|
| **generation** | PID 复用后旧锁自动失效 |
| **Lease** | 锁有过期时间，进程崩溃后其他进程可接管 |
| **FencingToken** | 单调递增 token，每次锁接管时 +1；写入时验证 token |
| **Renew** | 持锁进程可在租约到期前续租 |

### 生命周期

```
获取锁:    file.lock(path, pid, generation, leaseMs)
续租:      file.renewLock(path, pid, generation, token, leaseMs)
写入保护:  file.write(path, content, pid, generation, token)
释放锁:    file.unlock(path, pid, generation, token)
过期接管:  其他进程检测 LeaseUntil < now → 新 token+1 → 写入
```

### Fencing 保护

当进程 A 持有锁（token=5）但租约已过期：
1. 进程 B 检测过期 → 原子设置 token=6
2. 进程 A 之后尝试写入（带着 token=5）
3. 写入时验证：当前 FencingToken=6 != 5 → **拒绝写入**

---

## 49 EffectLedger ─ 副作用去重

### 文件写入去重

`FileUtil.writeOnce()` / `FileUtil.appendOnce()` 使用 effect ID：

```
/system/effects/<sha256(path)>/write.ledger
```

记录：
```json
{
  "stmt-3-effect-0": {
    "Type": "write",
    "Time": 1720000000000,
    "ArgumentHash": "sha256-of-content"
  }
}
```

### 重放逻辑

1. 查找 `effectId` 在 ledger 中 → 找到
2. 对比 `ArgumentHash`
   - 相同 → **跳过写入**（同一语句的两条相同内容 → 幂等）
   - 不同 → 数据竞争错误
3. 没找到 → 正常写入 + 记录到 ledger

### Tombstone 保护

文件被删除时记录 tombstone：
```json
{
  "stmt-2-effect-0": {
    "Type": "delete",
    "Time": 1720000000000,
    "Tombstone": true
  }
}
```

之后如果旧 effect 尝试重新写入：
- 检测到 tombstone → 拒绝（文件已被删除重建为不同实体）

---

## 50 路径别名 PathAliases

### 两级别名

| 级别 | 存储位置 | 生命周期 |
|---|---|---|
| **全局** | `/system/config/aliases.json` | 系统级、永久 |
| **进程** | `.proc` 的 `PathAliases` 字段 | 进程实例级 |

### 优先级

进程别名优先级 > 全局别名。

### 动态变量

- `$HOME` → 根据 EffectiveUser 解析为 `/user/<username>/`

### 继承

- fork → 子进程复制父进程的 PathAliases
- exec → 保留 PathAliases
- 恢复 → 从磁盘快照恢复

### 示例

```fcl
// 在进程快照中设置别名
alias = {"work": "/user/bob/projects", "data": "/user/bob/data"}

// 之后路径解析 @work/tool.fcl → /user/bob/projects/tool.fcl
content = io.readFile("@work/tool.fcl")
```

注意：FCL 中没有直接的 `setAlias()` 函数（别名通过进程快照的 `PathAliases` 字段持久化），但系统内部在解析路径时会查询此字段。

---

## 51 网络与 HTTP

### HTTP GET

```fcl
result = network.fetch("https://api.example.com/data")

// 带自定义头
headers = {"Authorization": "Bearer token123"}
result = network.fetch("https://api.example.com/data", headers)
```

### HTTP POST

```fcl
// POST 携带 Idempotency-Key header（effectId）
response = network.httpPost("https://api.example.com/submit",
                             '{"name": "test"}')
```

### 下载

```fcl
// 下载二进制文件
data = network.download("https://example.com/file.bin")

// 下载到 VFS 路径
network.download("https://example.com/file.bin",
                  "/user/alice/downloads/file.bin")
```

### 恢复行为

网络调用使用 `MANUAL_RECOVERY` 策略。如果进程在执行网络调用时崩溃：
- HTTP GET → 恢复时标记为 IN_DOUBT（无法确认服务端是否执行）
- HTTP POST → 虽然携带了 `Idempotency-Key`，但服务端可能不支持幂等，因此保守地标记 IN_DOUBT
- 进程进入 BLOCKED 状态

---

## 52 构建与运行

### Maven 命令速查

```bash
# 编译
mvn compile

# 打包 uber-JAR（含 Gson 依赖）
mvn package -DskipTests

# 清理编译
mvn clean compile

# 运行（from source）
mvn exec:java

# 运行（packaged JAR）
java -jar target/cilexec-1.0-SNAPSHOT.jar

# 运行（classpath）
mvn dependency:copy-dependencies -q
java -cp "target/classes:target/dependency/*" com.follarce.Main

# Dev build+run
bash build/run.sh
```

### 依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| Gson | 2.10.1 | JSON 序列化/反序列化 |
| JUnit 5 | (test scope) | 进程集成测试 |

---

## 53 测试

### 运行所有测试

```bash
mvn test
```

### 测试方案

共 60 个 JUnit 5 测试，全部使用 `@TempDir` 隔离 VFS：

| 测试类 | 测试数 | 覆盖范围 |
|---|---|---|
| PathContextTest | 4 | 路径别名解析和规范化 |
| FileLockTest | 7 | 文件锁获取、续租、过期接管、fencing |
| UserTransactionTest | 1 | createUser/removeUser 事务和去重 |
| SwapTransactionTest | 4 | swap pool CRUD、锁、事务、类型 |
| FileEffectTest | 1 | 文件写入 effect 去重 |
| ProcessPersistenceContextTest | 5 | EffectiveUser、PathAliases、fork 继承、外部 effect 恢复、fork 重放冲突 |
| ProcessOperationsIntegrationTest | 13 | fork、exec、kill、wait、waitPID、pause、continue |
| ProcessLifecycleEdgeTest | 10 | 孤儿收养、PID 复用、调度器恢复、并发终止、快照损坏 |
| StatementAttemptRecoveryTest | 3 | attempt begin/invoke/commit、receipt 重放 |
| ProcessInboxIntegrationTest | 8 | inbox 发布/幂等、delivery 补发、generation 隔离、旧关系剔除 |
| ProcessEffectCrashTest | 1 | 副作用窗口内强制 JVM 终止 + 恢复验证 |
| ProcessStabilityTest | 3 | 256 并发 fork、随机 SIGKILL、多轮重启恢复 |

---

## 54 代码结构

```
src/main/java/com/follarce/
├── Main.java                         ← 程序入口
├── Constants.java                    ← 系统常量
│
├── function/                         ← 内置函数
│   ├── FunctionProvider.java         ← Provider 接口
│   ├── FunctionRegistry.java         ← 注册表 + 查找
│   ├── FunctionContext.java          ← 调用上下文 (pid/gen/user/aliases/effect)
│   ├── BuiltinFunctionCatalog.java   ← 118 个函数的完整分类
│   ├── EffectPolicy.java             ← 副作用策略枚举
│   ├── UnknownEffectOutcomeException.java  ← 外部效果不确定异常
│   ├── FileFunctionProvider.java     ← file.*
│   ├── IOFunctionProvider.java       ← io.*
│   ├── UserFunctionProvider.java     ← user.*
│   ├── UtilFunctionProvider.java     ← util.*
│   ├── ProcessFunctionProvider.java  ← process.*
│   ├── SwapFunctionProvider.java     ← swapPool.*
│   ├── NetworkFunctionProvider.java  ← network.*
│   ├── SocketFunctionProvider.java   ← socket.*
│   ├── MathFunctionProvider.java     ← math.*
│   ├── PathFunctionProvider.java     ← path.*
│   └── PrivilegedFunctionProvider.java ← system.*
│
├── process/                          ← 进程引擎
│   ├── ProcessRunner.java            ← 执行引擎 + 虚拟线程
│   ├── Scheduler.java                ← 调度器
│   ├── StateManager.java             ← .proc 持久化
│   ├── StatementAttemptManager.java  ← 指令 attempt / effect receipt
│   ├── ProcessInbox.java             ← 持久化消息收件箱
│   ├── ProcessMessage.java           ← 消息数据记录
│   ├── ProcessIdentity.java          ← generation + execution schema
│   ├── ProcessState.java             ← 状态枚举
│   ├── BlockReason.java              ← 阻塞原因枚举
│   ├── IpcHandler.java               ← fork/exec/kill/wait/waitPid/pause/continue
│   ├── ForkLedger.java               ← fork 持久化账本
│   ├── RecoveryManager.java          ← 启动恢复
│   ├── CodeLoader.java               ← 代码加载 + 边界表
│   ├── ExpressionEvaluator.java      ← 表达式求值
│   ├── ControlFlow.java              ← if/while/break/return
│   ├── FunctionManager.java          ← 用户函数定义 + 调用栈
│   ├── ImportManager.java            ← import/include
│   ├── BoundaryTable.java            ← 花括号边界扫描
│   ├── RetryableEffectException.java ← IPC 重试异常
│   ├── EffectRecoveryRequiredException.java ← IN_DOUBT 阻塞异常
│   └── ExitReason.java               ← 退出原因枚举
│
├── script/                           ← FCL 语言引擎
│   ├── Lexer.java                    ← 词法分析
│   ├── Parser.java                   ← 语法分析
│   ├── AstNode.java                  ← AST 节点
│   ├── NodeEvaluator.java            ← AST 求值
│   ├── StatementParser.java          ← 语句分割
│   ├── Token.java                    ← 词法 Token
│   ├── TokenType.java                ← Token 类型枚举
│   ├── NodeType.java                 ← AST 节点类型枚举
│   ├── FunctionDef.java              ← 函数定义
│   ├── Instruction.java              ← 编译指令
│   ├── InstructionType.java          ← 指令类型枚举
│   └── StringEscape.java             ← 字符串转义
│
├── util/                             ← 基础设施
│   ├── FileUtil.java                 ← VFS 操作 (meta/body/lock/symlink/effect)
│   ├── PathUtil.java                 ← 路径解析 + 别名
│   ├── UserUtil.java                 ← 用户 CRUD + ThreadLocal
│   ├── JsonUtil.java                 ← JSON 序列化 + Gson 封装
│   ├── NetworkUtil.java              ← HTTP/Socket 工具
│   ├── EffectLedger.java             ← 文件副作用去重账本 + tombstone
│   └── SocketUtil.java               ← TCP/UDP 底层
│
├── init/                             ← 初始化
│   ├── FileInit.java                 ← VFS 目录树创建
│   └── ProcessInit.java              ← PID 1 创建
│
├── exception/                        ← 异常体系
│   ├── ProcessException.java         ← 基础异常
│   ├── RecoverableException.java     ← 可恢复（警告）
│   └── UnrecoverableException.java   ← 不可恢复（终止）
│
└── log/
    └── Logger.java                   ← 日志工具
```

---

## 55 附录

### A. 64 个控制消息字段

| 字段名 | 说明 | 值示例 |
|---|---|---|
| `ProcessState` | 设置进程状态 | `"PAUSED"`, `"READY"` |
| `Status` | 兼容旧格式 | `true`, `false` |
| `__Terminate` | 终止信号 | `"KILLED"` |
| `ChildExit.{pid}` | 记录子进程退出 | exit event Map |
| `Program.Data.{var}` | 设置变量 | 任意值 |
| `{任意路径}` | 嵌套路径更新 | 由 `setNestedField` 处理 |

### B. 常量表 (Constants)

| 常量 | 值 | 说明 |
|---|---|---|
| `SCHEDULER_TICK_MS` | 50 | 调度器扫描间隔 (ms) |
| `VIRTUAL_THREAD_BLOCK_CHECK_NS` | 50000000 | 虚拟线程阻塞轮询间隔 (50ms) |
| `PID_INIT` | 1 | INIT 进程 PID |
| `PRIORITY_HIGH` | 0 | 高优先级 |
| `PRIORITY_NORMAL` | 1 | 普通优先级 |
| `PRIORITY_LOW` | 2 | 低优先级 |
| `DEFAULT_PRIORITY` | 1 | 默认优先级 |
| `DEFAULT_USER_LOCAL` | "local" | 超级用户名 |
| `DEFAULT_FILE_LOCK_LEASE_MS` | 30000 | 文件锁默认租约 |
| `SYSTEM_PROCESS_PATH` | "/system/process/" | 进程文件目录 |
| `SYSTEM_PROCESS_INBOX_PATH` | "/system/process/inbox/" | Inbox 目录 |
| `SYSTEM_SWAP_PATH` | "/system/swap/" | Swap Pool 目录 |
| `SYSTEM_FORK_PATH` | "/system/fork/" | Fork 账本目录 |
| `SWAP_TYPE_ALWAYS` | "always" | | 无限制读取
| `SWAP_TYPE_SYNC` | "sync" | | 一次读取后锁定
| `SWAP_TYPE_TIMES_PREFIX` | "times" | | 有限次数类型前缀
| `PERM_READ` | "read" | | 读权限
| `PERM_WRITE` | "write" | 写权限
| `ERROR_MARKER` | "ERROR:" | 错误标记

### C. 异常体系

```
ProcessException (base)
├── RecoverableException           ← 设置 _warning，继续执行
├── UnrecoverableException        ← 设置 _error，进程 FAILED
├── RetryableEffectException      ← IPC 操作临时失败重试
├── EffectRecoveryRequiredException ← 进程 BLOCKED 等待管理员
└── UnknownEffectOutcomeException ← 外部效果不确定 → IN_DOUBT
```

### D. 测试配置

所有测试使用 `@TempDir Path root` 注解创建临时 VFS 根目录，不污染实际的 `cilexec_root/`。

典型的测试初始化：

```java
@BeforeEach
void initialize() {
    FileInit.init(root.toFile());       // 创建临时 VFS
    UserUtil.setCurrentUser("local");   // 作为 superuser 操作
}
```

### E. 完成通知

构建系统生成的 `build/run.sh` 和 `build/when_user_listen_this_they_will_come_like_a_dog.sh` 脚本用于开发工具链。



---

> 本文档由代码分析自动生成，覆盖 CilExec 1.0-SNAPSHOT 全部系统组件。
> 最后更新：2026-07-17
