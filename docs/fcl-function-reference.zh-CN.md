# FCL 0.0.5 函数与对象参考

本文档描述**当前** CilExec 0.0.5 的 FCL 接口，内容来自 `src/main/java/com/follarce` 中的函数注册、实体对象方法注册和终端命令解析器。
[0.0.4 参考](fcl-function-reference-004.md)记录的是不兼容的旧接口，仅作为历史资料保留。

运行实例中可用 `system.list()` 查看当前 JAR 精确注册的限定名，用
`memory.list({"includeRuntime":true})` 查看当前进程变量与内置函数元数据。

| 项目 | 0.0.5 取值 |
| --- | --- |
| CilExec 产品版本 | `0.0.5` |
| FCL 源语言版本 | `fcl-0.0.5` |
| FCL 程序与 continuation 格式 | `5` |
| 包数据库（`.db`）格式 | `3` |
| 数据库 schema（Flyway） | `V005`（schema 版本 5） |
| 旧式实体 API | **已删除**，见[被取代的 0.0.4 API](#被取代的-004-api-与替代写法) |

## 目录

- [快速开始](#快速开始)
- [如何查看实际接口](#如何查看实际接口)
- [FCL 语法要点](#fcl-语法要点)
- [两种对象：值与系统实体句柄](#两种对象值与系统实体句柄)
- [系统实体对象](#系统实体对象)
  - [文件、目录、路径与租约](#文件目录路径与租约file)
  - [进程](#进程process)
  - [Swap Pool](#swap-poolswappool)
  - [消息 IPC](#消息-ipc)
  - [包、私有数据与配额](#包私有数据与配额package)
  - [用户、Program 与终端会话](#用户program-与终端会话userprogramterminal)
- [被取代的 0.0.4 API 与替代写法](#被取代的-004-api-与替代写法)
- [终端冒号命令](#终端冒号命令不是-fcl-函数)
- [通用数据与权限规则](#通用数据与权限规则)
- [纯计算命名空间](#纯计算命名空间)
- [I/O、进程内存与环境变量](#io进程内存与环境变量)
- [持久实体命名空间](#持久实体命名空间)
- [系统与管理命名空间](#系统与管理命名空间)
- [网络与套接字](#网络与套接字)
- [包与市场](#包与市场)
- [0.0.5 兼容性与旧数据拒绝启动](#005-兼容性与旧数据拒绝启动)
- [快速排查](#快速排查)
- [实现位置](#实现位置)

## 快速开始

`[]` 表示可选参数，`<...>` 表示必填参数。相对路径相对于持久工作目录
（`env.get("PWD")`，由 `:cd` 改变）；以 `/` 开头的是 VFS 绝对路径。

```fcl
// 通过一次链式调用读取并修改文件。
note = file.create("/demo/note.txt", "first")
note.append(" second")
io.println(note.read())          // first second

// 打开已存在节点并查看持久元数据。
node = file.open("/demo/note.txt")
io.println(node.size())
io.println(node.metadata())

// 创建目录对象并列出内容。
dir = file.mkdir("/demo/sub")
io.println(dir.list())

// 按持久身份控制进程。
io.println(process.find(process.getPID()).status())

// 使用 swap pool 变量句柄。
pool = swapPool.create("demo")
value = pool.variable("count")
pool.add("count", 1)
io.println(value.get())
```

实体只能通过工厂函数取得（`file.open`、`process.find` 等）。工厂检查一次访问权限；
**之后每次方法调用都会重新检查数据库状态和权限**。句柄是持久身份，不是缓存副本，也不是口令。

## 如何查看实际接口

| 调用 | 作用 |
| --- | --- |
| `system.list()` | 返回当前 Runtime 认可的全部可见限定函数名与别名；被删除的旧拼写不在其中。 |
| `memory.list()` | 返回当前 FCL 进程的 `{variables, functions}`。 |
| `memory.list({"includeRuntime":true})` | 额外包含不可变内置函数、别名与 Java 扩展函数元数据。 |
| `memory.list({"includeParents":true})` | 额外包含当前作用域可见的词法根变量。 |
| `system.extensions()` | 返回编译期封装的 Java 扩展（`id`、`version`、`description`）。 |
| `util.which(name)` | Runtime/扩展函数返回 `0`；已绑定包导出/入口返回包数据库 SHA-256；源码内函数或未知名称返回 `null`。 |

函数查找规则：

- 完全限定名（`file.open`、`package.find`）无歧义，推荐使用。
- 裸名（如 `open`）仅当恰有一个函数使用该名时可解析；多个内置函数共享裸名时，
  运行时报错并列出限定名候选。
- 用户函数和导入的包函数优先于同名内置函数。
- 被删除的 0.0.4 名称内部仍用于对象方法转发，但**不能从 FCL 调用**；
  `system.invoke` 也无法访问。

## FCL 语法要点

FCL 编译为解释器指令表。每个执行片是一个数据库事务和一个恢复检查点。进程每次执行片最多
批量执行 4096 个解释器步骤或 20 ms，遇到挂起、指令、完成或失败即停止。崩溃会回滚整个未提交
执行片，可从前一个检查点重放。

- **注释。** 只有 `//` 行注释；`#` 与注释无关。
- **长度运算符。** `#` 是唯一的一元长度运算符。字符串返回 UTF-16 码元数，数组/Map 返回元素
  数，其他单值（包括系统句柄）返回 `1`，`#null` 为 `0`。
- **保留字。** `func`、`if`、`else`、`while`、`break`、`continue`、`return`、
  `import`、`include`、`as`、`and`、`or`、`not`、`public`、`private`、
  `true`、`false`、`null`、`class`、`extends`、`new`、`this`、`super`、
  `link`、`try`、`catch` 不能作为标识符、函数名或参数名；违反是编译错误。
- **字符串转义。** 只允许 `\n`、`\t`、`\r`、`\\`、`\"`；其他转义是编译错误。
- **多行字符串。** 字符串字面量可以跨行；字面换行成为值的一部分（行尾 `\r\n`/`\r`
  规范为 `\n`）。交互终端在字符串未闭合时继续读取。
- **语句与块。** 语句以换行或 `;` 结束；支持 `if` / `else if` / `else`、
  `while`、`break`、`continue`、`return`。没有 `for`，也没有 `throw`/`finally`。
- **顶层 return。** `return` 可用于顶层，其值成为程序结果；REPL 提交和 `package.run`
  包装器依赖此规则。
- **异常。** `try { ... } catch (e) { ... }` 捕获确定性 FCL 失败（`UndefinedFunction`、
  `UndefinedMethod`、`StaleHandle`、算术错误等 `FclRuntimeException`）。`e` 是
  catch 局部不可变值，含 `e.type`、`e.message`、`e.stack`；每个栈帧含
  `function`、`source`、`line`、`column`。内核故障、强制终止和数据库一致性失败不可捕获。
- **数字规则。** `+`、`-`、`*` 与一元负号在整数溢出时报错，不会静默回绕。
  字符串与数字做排序比较是错误；布尔值不可排序；`==`、`!=`、`and`、`or`、`not` 仍可用。
- **真值。** `0`、`null`、`false`、`""`、空数组、空 Map 为假；其他为真。
- **后缀更新。** 数字变量、public 字段和数组/Map 元素支持后缀 `++`/`--`；没有前缀形式。
  更新表达式的结果是更新后的值；非数字、private 值或系统句柄会被拒绝。
- **值显示。** 字符串拼接、`text.join`、`util.toString` 共用一条规则：顶层字符串原样，
  数字/布尔用普通文本，`null` 渲染为 `null`，容器用 FCL 字面量语法（内部 JSON 风格引号），
  系统句柄渲染为 `Kind{identity}`。宿主 Java 表示永不泄漏。
- **字符串索引。** 长度、索引和文本切片/搜索位置都按 Java UTF-16 码元计数；补充平面字符占两位。
- **函数作用域。** 每次调用有全新参数和局部变量；基础程序函数从其程序顶层作用域解析名称，
  导入模块函数只解析本模块持久全局。任何函数都读不到调用者局部变量；根绑定和调用帧跨挂起/恢复持久化。
- **模块可见性。** `public func` 导出模块函数（无限定符的 `func` 默认如此）；
  `private func` 仅模块内；`public class` 可导入，`private class` 不导出。
- **import/include 位置。** 只能放顶层。`import` 接受已安装包数据库的完整 SHA-256，
  可带私有别名；`include` 在编译前拼接 VFS 源文件。
- **表达式方法调用。** 命名变量中的系统句柄可写 `receiver.method(args)`；任意表达式结果可写
  `expression.method(args)`，例如 `file.open("/note.txt").read()` 或
  `process.find(41).kill()`。对临时用户类对象调用方法会在该临时对象上执行，结果可用，
  但没有可写回的具名接收者。

## 两种对象：值与系统实体句柄

FCL 有**两套对象模型**，混淆它们是 0.0.5 最常见的错误。

| 模型 | 创建方式 | 赋值、参数、返回 | 身份 |
| --- | --- | --- | --- |
| 用户类实例 | `new ClassName(...)` | 深复制，得到独立值 | 无持久身份；相等性比较类名与字段 |
| 系统实体句柄 | `file.open` 等实体工厂 | 保留同一句柄身份 | 持久身份（`kind` 加 id）；相等性比较句柄记录 |

```fcl
class Counter { value = 0
    func increment() { value++ }
}
a = new Counter()
b = a                 // 复制：b 是独立 Counter
b.increment()
io.println(a.value)   // 0
io.println(b.value)   // 1

note = file.create("/note.txt", "first")
same = note           // 句柄：同一文件身份
same.append(" second")
io.println(note.read()) // first second
```

由此得到：

- 复制句柄仍指向同一实体。`same.read()` 与 `note.read()` 访问同一节点、池、进程或投递。
- 句柄随每个已提交执行片写入 continuation，因此能跨终端登出、重连、Runtime 重启和崩溃恢复存活。
- 句柄只保存 `kind`、稳定身份字符串和可选私有属性（例如租约 fencing token 或主题名），
  **不保存 Java 指针、权限授予或缓存的权限**。
- 句柄会**失效**。删除实体会使全部旧句柄失效；之后在同一路径/PID 新建的实体不会让旧句柄复活。
- 每次方法调用都重新检查当前调用者能力、当前所有者、数据库权限/RLS 边界以及实体当前状态。
- `b link a` 是持久化的名字跟随关系：链接到句柄名只跟随名称，不复制实体，也不产生额外授权。

完整入门说明见[对象指南](fcl-object-oriented-guide.zh-CN.md)。

### 接收者分派规则

词法分析把点号连接的标识符读作**一个 token**，因此有两种写法：

| 写法 | 解析形式 | 分派 |
| --- | --- | --- |
| `h.read()`（`h` 是名字） | 命名空间式调用 `h.read` | 先看名字 `h` 当前是否保存系统句柄；是则按句柄类型分派方法，否则退回普通限定函数/用户类规则。 |
| `file.open(p)` | 命名空间式调用 `file.open` | 内置命名空间工厂。 |
| `a.child.method()`（`a` 是用户对象） | 带成员路径的命名空间式调用 | 解析用户对象字段路径，在该接收者上按类分派。 |
| `file.open(p).read()`、`pool.variable(n).get()`、`pkg.data().read(x)` | 表达式方法调用 | 先求值目标；系统句柄按类型分派，用户对象按类分派。 |
| `expression.field` | 成员读取 | 读取用户对象字段；系统句柄没有可写字段。 |

两种形式对系统句柄等价。一次性的链式操作用链式写法；需要再次使用或传递句柄时使用命名变量。
对临时用户类对象调用的方法会执行且返回值可用，但没有具名接收者可写回。

## 系统实体对象

每个工厂都返回持久句柄。下表列出完整注册接口。

| 工厂 | 句柄类型与方法 | 说明 |
| --- | --- | --- |
| `file.open(path [, targetUser])` | `File`：`read()`、`readChunk(offset, maximumBytes)`、`size()`、`metadata()`、`write(text)`、`append(text)`、`rename(newName)`、`remove()`、`lock(milliseconds)`；`Directory`：`list()`、`clear()`、`metadata()`、`rename(newName)`、`remove()` | 节点必须已存在；句柄类型随节点类型。`lock` 返回 `FileLease` 或 `null`。 |
| `file.path(path)` | `Path`：`exists()` | 路径值，不代表已存在节点。 |
| `file.create(path [, content])` | `File`，具备全部 File 方法 | 在当前用户 VFS 创建文件；路径已存在时失败。 |
| `file.mkdir(path)` | `Directory`，具备全部 Directory 方法 | 在当前用户 VFS 创建目录。 |
| `process.find(pid)` | `Process`：`status()`、`wait()`、`pause()`、`continue()`、`kill()`、`send(payload [, expiresAt])`、`removeFinished()` | 绑定公开 PID 背后的稳定进程 UID；每次调用重查控制权限。 |
| `swapPool.create(path)` / `swapPool.open(path)` | `SwapPool`：`get(name)`、`add(name, value)`、`list()`、`clear()`、`remove()`、`variable(name)` | `create` 在池已存在时失败，`open` 在池不存在时失败；池属于当前用户。 |
| `SwapPool.variable(name)` | `SwapVariable`：`get()`、`update(value [, token])`、`remove([token])`、`lock(milliseconds)`、`signal()`、`waitFor()` | `lock` 返回 `SwapLease` 或 `null`。 |
| `SwapVariable.lock(ms)` | `SwapLease`：`renew(milliseconds)`、`release()` | 租约句柄携带 fencing token；过期续租失败。 |
| `ipc.createChannel(name)` / `ipc.channel(id)` | `Channel`：`send(payload [, expiresAt])`、`subscribe()`、`remove()` | 频道身份是 UUID；方法要求当前用户拥有的 ACTIVE 频道。 |
| `ipc.createTopic(name)` / `ipc.topic(name)` | `Topic`：`publish(payload [, expiresAt])`、`broadcast(payload [, expiresAt])`、`subscribe()`、`remove()` | 主题身份稳定；句柄同时保存名称。 |
| `ipc.delivery(deliveryId)` | `Delivery`：`consume()` | 只有接收该投递的进程才能寻址它。 |
| `package.find(sha256OrCoordinate)` / `package.install(vfsPath)` | `Package`：`info()`、`verify()`、`run([entrypoint])`、`resource(path)`、`pin()`、`uninstall()`、`data()` | 身份是已安装数据库文件的精确 SHA-256。 |
| `Package.data()` | `PackageSpace`：`root()`、`info()`、`usage()`、`list([path])`、`read(path)`、`write(path, text)`、`export(vfsPath)`、`import(vfsPath)`、`clear()`、`quota()`、`exists(path)`、`readChunk(path, offset, maximumBytes)`、`seedResource(resourcePath, destinationPath)`、`append(path, text)`、`mkdir(path)`、`remove(path)`、`rename(source, destination)`、`size(path)` | 所有者级方法供包所有者使用；包内私有方法要求在同一包代码中执行。 |
| `packageData.current()` | 返回调用包对应的 `PackageSpace` | 要求已链接的已安装包代码及 `package.data` 能力。 |
| `package.quotaFor(user, sha256)` | `PackageQuota`：`set(bytes)`、`remove()` | 仅管理员；`user` 是用户名或 UUID。 |
| `user.find(usernameOrUuid)` / `user.create(username, password [, adminCredentials])` | `User`：`info()`、`disable()`、`remove()` | 普通用户只能找到自己；跨用户操作要求管理员身份。 |
| `program.find(programId)` | `Program`：`remove()` | 仅管理员；仍被引用的 Program 不能删除。 |
| `terminal.find(sessionId)` | `TerminalSession`：`remove()` | 仅管理员；只可删除 CLOSED 会话。 |

### 所有句柄的逐调用语义

1. **类型与身份在创建时固定。** `util.typeOf(handle)` 返回类型名（`File`、`Process` 等），
   `util.toString(handle)` 返回 `Kind{identity}`，`#handle` 为 `1`。
2. **每次调用都做身份检查。** 实现重新读取实体，确认其仍存在、类型仍与句柄类型一致，
   并在执行前重查调用者能力和数据库权限。
3. **失效是显式的。** 被删除的节点/进程/主题/包产生 `StaleHandle`（或 `Unknown ...`）
   运行时错误并说明条件，绝不会静默指向另一个实体。
4. **复制共享身份。** 赋值、传参、存入数组/Map、返回都保持同一目标；两个句柄的 `==`
   比较类型、身份和保存的属性。
5. **租约受 fencing 保护。** `FileLease`/`SwapLease` 携带 fencing token；过期后续租失败
   （方法调用报 `ExpiredLease`）。非对象锁函数仍返回原始 `{fencingToken, leaseUntil}` Map。
6. **边界处复制数据。** 注册表对方法参数和结果做深复制，但其中的系统句柄仍保持身份。

### 文件、目录、路径与租约（`file`）

```fcl
note = file.create("/notes/today.txt", "line 1\n")
note.append("line 2\n")
io.println(note.size())
io.println(note.metadata())

same = note
same.append("line 3\n")            // note.read() 也能看到新内容

note.rename("today-renamed.txt")    // 句柄仍指向同一节点
io.println(file.open("/notes/today-renamed.txt").read())

lease = note.lock(30000)            // FileLease 或 null
if lease != null {
    lease.renew(30000)
    lease.release()
}

dir = file.mkdir("/notes/archive")
io.println(dir.list())
io.println(file.path("/notes/archive/old.txt").exists())   // false
```

| 调用 | 权限与行为 |
| --- | --- |
| `file.open(path [, targetUser])` | 要求 `VFS_READ`；跨用户目标额外要求管理员身份。节点必须存在；按节点类型返回 `File`/`Directory`。 |
| `file.create(path [, content])` | 要求 `VFS_WRITE`。在调用者 VFS 创建文件，路径已存在则失败；第二参数是初始文本（默认空串）。 |
| `file.mkdir(path)` | 要求 `VFS_WRITE`。在调用者 VFS 创建一个目录。 |
| `file.path(path)` | 创建 `Path` 值；调用 `exists()` 前不访问数据库。 |
| `File.read()` | 要求 `VFS_READ`。整文件 UTF-8 读取，上限 16 MiB；更大文件用 `readChunk`。 |
| `File.readChunk(offset, maximumBytes)` | 要求 `VFS_READ`。`offset` 非负，`maximumBytes` 0–4 MiB；返回解码后的 UTF-8 区间。 |
| `File.size()` | 要求 `VFS_READ`。返回逻辑字节数。 |
| `File.metadata()` / `Directory.metadata()` | 要求 `VFS_READ`。返回节点 Map：`nodeId`、`ownerId`、`name`、`type`、`revisionEnabled`、`updatedAt`，存在对象时含 `objectHash`。 |
| `File.write(text)` | 要求 `VFS_WRITE`。整体替换内容，返回节点 id。 |
| `File.append(text)` | 要求 `VFS_WRITE`。追加 UTF-8 文本；管理员跨用户追加会加载旧内容，且拒绝已超过 16 MiB 的文件。 |
| `File.rename(newName)` | 要求 `VFS_WRITE`。在同一目录内改名；名称不得含斜杠、`.`、`..` 或控制字符。返回 `true`。**节点身份和所有句柄在改名后仍然有效。** |
| `Directory.rename(newName)` | 目录节点同规则。 |
| `File.remove()` / `Directory.remove()` | 要求 `VFS_WRITE`。删除节点。根、挂载点、版本化文件和非空目录会被拒绝且不删除任何内容；返回 `true`，旧句柄随后失效。 |
| `Directory.list()` | 要求 `VFS_READ`。返回子节点元数据 Map 列表。 |
| `Directory.clear()` | 要求 `VFS_WRITE`。递归删除目录内部条目并保留目录；目标必须是已存在非根目录；遇到版本化文件或挂载点会整体中止。返回删除条数。 |
| `File.lock(milliseconds)` | 要求 `VFS_WRITE`。取得属于逻辑进程（进程 UID 加执行 epoch）的文件租约，返回带 fencing token 的 `FileLease`；锁被占用时返回 `null`。 |
| `FileLease.renew(milliseconds)` | 同一进程续租；租约过期报 `ExpiredLease`，节点被删除报 `StaleHandle`。 |
| `FileLease.release()` | 释放租约；返回仓储迁移结果。 |
| `Path.exists()` | 要求 `VFS_READ` 或 `VFS_WRITE`；返回路径当前是否可解析，不创建句柄目标。 |

单个 VFS 文件至少支持 1 GiB。大文件用重复 `append` 写入，用 `size()` 和
`readChunk(...)` 分段读取，不要把整个大文件当成一个 FCL 字符串读入。符号链接读取会跟随
（少于 16 层；环路报错）。`file.link(linkPath, targetPath)` 仍是命名空间函数：
创建内容为目标路径的符号链接节点。

### 进程（`process`）

```fcl
handle = process.find(process.getPID())
io.println(handle.status())          // RUNNING / READY / WAITING_* / TERMINATED / FAILED
handle.send({"ping": true})          // 向该进程发送持久直接消息

child = process.fork()               // 父进程收到子 PID；子进程以 0 继续
if child != 0 {
    io.println(process.find(child).wait())   // 子进程结束后返回 {pid, status}
}
```

| 调用 | 行为 |
| --- | --- |
| `process.find(pid)` | 要求进程控制能力（own 或 any，或管理员）。返回绑定进程 UID 的 `Process`，不是可复用的公开 PID。 |
| `Process.status()` | 返回当前状态名。 |
| `Process.wait()` | 目标已结束则立即返回 `{pid, status}`；否则挂起调用者，并在目标结束时返回该 Map。 |
| `Process.pause()` / `Process.continue()` | 暂停/恢复可控进程，返回是否发生迁移；RUNNING 进程不能改变自己的调度状态。 |
| `Process.kill()` | 终止目标。终止自身等价于 `util.exit()`，返回 `true`。 |
| `Process.send(payload [, expiresAt])` | 向句柄背后的进程发送一条持久直接消息；`expiresAt` 是可选 ISO-8601 时刻。 |
| `Process.removeFinished()` | 仅管理员。显式删除 TERMINATED/FAILED 进程及其持久状态；RUNNING、挂起和等待中的进程永不删除。返回是否删除。 |

对应普通命名空间操作仍保留：`process.args()`、`process.arg(i)`、`process.getPID()`、
`process.getPPID()`、`process.listChildren()`、`process.list()`、`process.fork()`、
`process.run(path, args)`、`process.exec(path [, args])`、`process.wait()`，
以及管理员 `process.clearFinished()`。

### Swap Pool（`swapPool`）

```fcl
pool = swapPool.create("job-42")
counter = pool.variable("counter")
pool.add("counter", 1)                // SwapPool.add(name, value)
io.println(pool.list())               // 池内变量

lease = counter.lock(10000)           // SwapLease 或 null
if lease != null {
    counter.update(2, lease)          // update(value [, fencingToken])
    lease.renew(10000)
    lease.release()
}

counter.signal()
counter.waitFor()                     // 等待信号后返回
pool.remove()                         // 删除整个池
```

| 调用 | 行为 |
| --- | --- |
| `swapPool.create(name)` | 创建当前用户的池；已存在则失败。返回 `SwapPool`。 |
| `swapPool.open(name)` | 打开已存在池；未知名称失败。返回 `SwapPool`。 |
| `SwapPool.get(name)` | 读取一个变量。默认 ALWAYS 值可读直到删除；SYNC 读取后清空变更标志；TIMES(n) 递减可读次数，到零后不可读。变量被锁定时返回 `null`，需先释放租约。 |
| `SwapPool.add(name, value)` | 以 ALWAYS 保留方式添加/更新变量，返回仓储结果值。 |
| `SwapPool.list()` | 列出本池变量；列出全部池的旧式调用已删除，请自行保存池路径或维护索引。 |
| `SwapPool.clear()` | 清空池内变量并保留池。 |
| `SwapPool.remove()` | 删除整个池。 |
| `SwapPool.variable(name)` | 返回 `SwapVariable` 句柄。 |
| `SwapVariable.get()` | 消费/读取变量值；当前不可读时返回 `null`。 |
| `SwapVariable.update(value [, leaseOrToken])` | 更新变量值；锁定时传入 `SwapLease` 句柄（或其数字 fencing token）。 |
| `SwapVariable.remove([leaseOrToken])` | 删除变量；锁定时传入 `SwapLease` 句柄（或其数字 fencing token）。 |
| `SwapVariable.lock(milliseconds)` | 取得属于逻辑进程的变量租约；返回 `SwapLease` 或 `null`。 |
| `SwapVariable.signal()` | 发送变量信号。 |
| `SwapVariable.waitFor()` | 等待信号，收到时返回 `true`。 |
| `SwapLease.renew(milliseconds)` | 续租；过期后报 `ExpiredLease`。 |
| `SwapLease.release()` | 释放锁。 |

池名不是 VFS 路径：必须匹配 `[A-Za-z0-9_-]{1,128}`（不能含斜杠）。变量名必须匹配
`[A-Za-z_][A-Za-z0-9_.-]{0,127}`。非法名称在访问数据库前以
`InvalidSwapPoolName` / `InvalidSwapVariableName` 失败。

Swap Pool 锁属于逻辑进程（进程 UID），而非某个调度时间片：只要租约未过期且使用当前
fencing token，同一进程再次运行时可以续租或释放。其他进程即使知道变量名也不能使用该 token。

### 消息 IPC

```fcl
channel = ipc.createChannel("work")
channel.subscribe()
channel.send({"job": 1})

topic = ipc.createTopic("events")
topic.subscribe()
topic.publish({"type": "started"})
topic.broadcast({"type": "tick"})

envelope = ipc.receive()          // 或 ipc.poll() 非阻塞预留
if envelope != null {
    delivery = ipc.delivery(envelope["deliveryId"])
    delivery.consume()
}
ipc.purge("2026-01-01T00:00:00Z", 1000)
```

| 调用 | 行为 |
| --- | --- |
| `ipc.createChannel(name)` | 创建持久频道，返回身份为生成 UUID 的 `Channel` 句柄。 |
| `ipc.channel(channelId)` | 打开已存在且属于当前用户的 ACTIVE 频道；未知或已删除频道失败。 |
| `ipc.createTopic(name)` | 创建持久主题，返回身份为主题 id、私有属性保存名称的 `Topic` 句柄。 |
| `ipc.topic(name)` | 按名称打开已存在且属于当前用户的 ACTIVE 主题。 |
| `ipc.delivery(deliveryId)` | 仅当投递接收者是当前进程时返回 `Delivery` 句柄。 |
| `ipc.receive()` | 阻塞直到收到属于当前进程的消息，返回 envelope Map。 |
| `ipc.poll()` | 立即预留并返回下一个可用 envelope，没有则返回 `null`。 |
| `ipc.purge(olderThan [, limit])` | 删除早于 ISO-8601 时刻的、属于当前用户的终态/过期消息。默认 1000，最大 10000。 |
| `Channel.send(payload [, expiresAt])` | 向一个活动频道消费者发送。 |
| `Channel.subscribe()` | 当前进程订阅该频道。 |
| `Channel.remove()` | 删除频道。 |
| `Topic.publish(payload [, expiresAt])` | 向活动主题订阅者发布。 |
| `Topic.broadcast(payload [, expiresAt])` | 向活动主题订阅者广播。 |
| `Topic.subscribe()` | 当前进程订阅该主题。 |
| `Topic.remove()` | 删除主题。 |
| `Delivery.consume()` | 将收到的投递标记为 CONSUMED；仅接收者进程可操作。 |

envelope 含 `deliveryId`、`messageId`、`kind`、`payloadType`，以及
`payloadJson` 或 `payloadObjectHash`。消费后的消息行仍持久；用 `ipc.purge` 释放保留的
载荷引用。未过期的 PENDING 投递永不被清理。数据库保证提交的 CONSUMED 迁移至多发生一次；
`ipc.poll()` 是“预留/确认”两步：如果 Runtime 在预留后、`Delivery.consume()` 前崩溃，
启动会重置未消费预留，消息可能再次投递。因此要把重复投递视为可能，只有在外界可见工作
可以安全重复或完成后再消费。

### 包、私有数据与配额（`package`）

```fcl
pkg = package.find("<64 位包数据库 sha256>")
io.println(pkg.info())
io.println(pkg.verify())
io.println(pkg.run())                // 创建子进程；返回 pid/processUid/...

space = pkg.data()
io.println(space.usage())
space.write("settings.json", "{}")
io.println(space.read("settings.json"))

// 仅已链接包代码内可用（package.data 能力）：
mine = packageData.current()
io.println(mine.exists("cache/x.txt"))
io.println(mine.root())
```

| 调用 | 行为 |
| --- | --- |
| `package.find(sha256OrCoordinate)` | 要求 `PACKAGE_IMPORT`。接受已安装数据库文件的完整 SHA-256，或已安装 release 的 `namespace/name/version` 坐标；返回身份为数据库文件 SHA-256 的 `Package` 句柄。 |
| `package.install(vfsPath)` | 从 VFS 的 `.db` 安装；包身份是其字节 SHA-256。创建安装记录、精确依赖闭包和私有数据空间，返回 `Package` 句柄。 |
| `Package.info()` | 要求 `PACKAGE_IMPORT` 及安装存在。返回坐标、哈希、类型、依赖、入口、导出和能力。 |
| `Package.verify()` | 重新计算数据库对象哈希，返回信息 Map 及 `valid`/`hashMatches` 布尔值。 |
| `Package.run([entrypoint])` | 要求 `PACKAGE_BIND` 和 `PROCESS_CREATE`。创建运行指定入口（默认 `run`）的子进程，返回 `{pid, processUid, programId, coordinate, entrypoint}`。 |
| `Package.resource(path)` | 读取包数据库中一个已声明的不可变文本资源。 |
| `Package.pin()` | 校验 release 仍已安装并返回元数据；不创建或修改进程绑定。 |
| `Package.uninstall()` | 要求 `PACKAGE_IMPORT` 和 `PACKAGE_BIND`。删除当前用户的安装、私有数据、进程绑定、缓存和无引用载荷；被依赖的安装会按坐标报告，需先卸载。普通用户文件和已导出归档永不删除。返回摘要 Map。 |
| `Package.data()` | 返回该包私有数据的 `PackageSpace`。 |
| `PackageSpace.root()` | 返回私有空间 URI `package-data://<database-file-sha256>/`；要求包代码。 |
| `PackageSpace.info()` / `usage()` | 用量、配额、文件数与时间戳。 |
| `PackageSpace.list([path])` | 列出直接子项；路径是字面量，不是模式。 |
| `PackageSpace.read(path)` | 以 UTF-8 文本读取一个私有文件。 |
| `PackageSpace.write(path, text)` | 创建或 CAS 替换私有文件；包所有者可用。 |
| `PackageSpace.export(vfsPath)` | 将私有空间的确定性 SQLite 归档写入用户 VFS；目标必须不存在。 |
| `PackageSpace.import(vfsPath)` | 将导出的归档合并进私有空间。 |
| `PackageSpace.clear()` | 删除全部私有条目但保留空空间。 |
| `PackageSpace.quota()` | 返回有效配额字节数。 |
| `PackageSpace.exists/readChunk/seedResource/append/mkdir/remove/rename/size` | 包代码私有操作。要求句柄身份与当前执行包一致：只能从已链接的已安装包代码、且具备 `package.data` 能力时调用，不能从顶层用户 FCL 调用。 |
| `packageData.current()` | 返回调用包的 `PackageSpace`；要求已链接包来源。 |
| `package.quotaFor(user, sha256)` | 仅管理员。返回该用户与该已安装包的 `PackageQuota` 句柄。 |
| `PackageQuota.set(bytes)` | 设置管理员配额覆盖；不能低于当前用量。 |
| `PackageQuota.remove()` | 删除管理员覆盖。 |

私有数据默认配额是每用户、每个精确包 256 MiB。精确版本隔离是刻意设计：版本迁移必须显式
`export` + `import`。包私有数据不是普通 VFS 路径，任意 `file.*` 路径都无法到达。

### 用户、Program 与终端会话（`user`/`program`/`terminal`）

```fcl
me = user.find(user.getCurrentUser())
io.println(me.info())

created = user.create("alice", "correct horse battery staple")
admin = user.create("ops", "another secret", ["local", "administrator password"])
alice = user.find("alice")
alice.disable()
alice.remove()

program.find("<program-uuid>").remove()
terminal.find("<terminal-session-uuid>").remove()
```

| 调用 | 行为 |
| --- | --- |
| `user.find(usernameOrUuid)` | 查找可见用户。普通用户只看到自己，管理员看到全部。返回绑定用户 UUID 的 `User` 句柄；未知或不可见名称报错。 |
| `user.create(username, password [, adminCredentials])` | 创建账户并返回 `User` 句柄。不带第三参数时使用普通能力配置（自注册）。创建管理员需传 `[administratorUsername, administratorPassword]`，数据库在同一原子操作中重新校验该管理员当前有效的 `SYSTEM_ADMIN`。 |
| `User.info()` | 返回 `{userId, username, status, credentialVersion}`。 |
| `User.disable()` | 停用账户但不删除数据；要求管理员身份。 |
| `User.remove()` | 在一个事务中永久删除账户、其拥有的全部行以及 NOLOGIN PostgreSQL 租户角色；要求管理员身份，管理员不能删除自己。 |
| `program.find(programId)` | 仅管理员。校验 Program 行后返回 `Program` 句柄。 |
| `Program.remove()` | 删除不可变 Program 并使其源/FCLB 对象不可达。若仍有任意状态的进程引用它，或另一 Program 导入它，则不删除任何内容并返回引用报告。 |
| `terminal.find(sessionId)` | 仅管理员。返回 `TerminalSession` 句柄。 |
| `TerminalSession.remove()` | 删除一个 CLOSED 终端会话及其已提交输入行和附件；OPEN 会话永不删除。 |

## 被取代的 0.0.4 API 与替代写法

0.0.5 **删除**了每次调用都传路径、PID、频道 id、主题名、投递 id 或包哈希的旧实体函数。
它们不能从 FCL 调用，`system.list()`、`util.which()`、`system.invoke()` 也不会暴露；
调用下列名称会得到 `UndefinedFunction`。

| 已删除的 0.0.4 API | 0.0.5 替代写法 |
| --- | --- |
| `file.read`、`file.readChunk`、`file.size`、`file.exists`、`file.list`、`file.readMetaData` | `file.open(path)` 后调用 `read()`、`readChunk(o,n)`、`size()`、`list()`、`metadata()`；纯存在性判断用 `file.path(path).exists()` |
| `file.write`、`file.append`、`file.createFile`、`file.createDir`、`file.remove`、`file.clear`、`file.rename`、`file.lock`、`file.renewLock`、`file.unlock` | 用 `file.create`/`file.mkdir` 工厂，再调用对应节点方法；`lock()` 返回带 `renew()`/`release()` 的 `FileLease` |
| `io.readFile`、`io.writeFile`（旧 `file.read`/`file.write` 别名） | `file.open(path).read()`、`file.create(path, text)` 或 `file.open(path).write(text)` |
| `process.kill`、`process.pause`、`process.continue`、`process.waitPID`、`process.removeFinished`、`system.kill` | `process.find(pid)` 后调用 `kill()`、`pause()`、`continue()`、`wait()`、`removeFinished()` |
| `swapPool.get`、`add`、`update`、`list`、`clear`、`remove`、`exists`、`lock`、`renewLock`、`unlock`、`signal`、`waitFor` | `swapPool.open/create(path)` 取得池句柄，调用 `get/add/list/clear/remove`、`variable(name)`，再调用变量方法与租约 |
| `ipc.sendDirect`、`sendChannel`、`publishTopic`、`broadcast`、`subscribeChannel`、`subscribeTopic`、`removeChannel`、`removeTopic`、`consume` | `ipc.channel(id)`/`ipc.createChannel(name)`、`ipc.topic(name)`/`ipc.createTopic(name)` 后调用 `send/publish/broadcast/subscribe/remove`；`ipc.delivery(id).consume()` |
| `package.info`、`verify`、`run`、`resource`、`pin`、`uninstall` | `package.find(hash)`/`package.install(path)` 后调用对应 `Package` 方法 |
| `package.dataInfo`、`dataList`、`dataRead`、`dataExport`、`dataImport`、`clearData`、`dataQuota`、`setDataQuota`、`removeDataQuota` | `package.find(hash).data()` 后调用 `info/usage/list/read/export/import/clear/quota`；管理员配额用 `package.quotaFor(user, hash).set/remove` |
| `packageData.root`、`exists`、`read`、`readChunk`、`write`、`seedResource`、`append`、`mkdir`、`list`、`remove`、`clear`、`rename`、`size`、`usage` | `packageData.current()` 后调用对应 `PackageSpace` 方法（私有方法要求包代码）；所有者级数据管理用 `package.find(hash).data()` |
| `user.validateUser`、`user.disable`、`user.remove` | `user.find(nameOrId)` 后调用 `info()`、`disable()`、`remove()` |
| `program.remove`、`terminal.remove` | `program.find(id).remove()`、`terminal.find(id).remove()` |

保留的命名空间操作（没有单一实体接收者的服务函数）：`file.link`；
`process.args/arg/getPID/getPPID/list/listChildren/fork/run/exec/wait/clearFinished`；
`swapPool.create/open`；`ipc.createChannel/createTopic/receive/poll/purge`；
`package.build/install/list/recover/find/quotaFor`；`packageData.current`；
`user.getCurrentUser/isLocal/list/create/switchUser/find`；`program.find`；`terminal.find`；
以及下面全部 `math`、`util`、`array`、`text`、`path`、`env`、`term`、`io`、
`memory`、`network`、`socket`、`market`、`audit`、`timer`、`storage`、`system` 服务。

## 终端冒号命令（不是 FCL 函数）

| 命令 | 作用 |
| --- | --- |
| `:help` | 显示终端帮助。 |
| `:cd <vfs-path>` | 改变持久工作目录。 |
| `:pwd` | 打印持久工作目录。 |
| `:ls [vfs-path]` | 列出 VFS 目录（不要用 shell 的 `ls`）。 |
| `:clear`（`:cls`） | 清屏。 |
| `:logout` | 回到登录提示但不丢失 REPL 状态。 |
| `:exit`（`:quit`） | 只关闭当前终端连接。 |
| `:shutdown` | 交互输入管理员密码后停止共享 Runtime。 |

终端提示符下其他输入都按 FCL 执行。进程在 `io.input()` 等待输入时，用 `::` 前缀发送原样输入。

## 通用数据与权限规则

| 项目 | 规则 |
| --- | --- |
| 字符串 | 双引号 UTF-8，可跨行；转义仅 `\n`、`\t`、`\r`、`\\`、`\"`。 |
| 数组 / Map | `[...]`、`{key: value}`；`#` 返回长度。 |
| 路径 | 相对路径基于进程持久 `PWD`；`/` 是 VFS 根。`file.*` 永远不出现宿主路径。 |
| 能力 | `PROCESS_CREATE`、`PROCESS_CONTROL_OWN`、`PROCESS_CONTROL_ANY`、`VFS_READ`、`VFS_WRITE`、`VFS_MOUNT_HOST`、`PACKAGE_IMPORT`、`PACKAGE_BIND`、`EFFECT_REQUEST`、`TERMINAL_ATTACH`、`AUDIT_READ`、`SYSTEM_ADMIN`。普通终端用户只有窄默认集；管理员持有 `SYSTEM_ADMIN`，满足全部检查。 |
| 逐调用检查 | 句柄不授予权限。每次方法都会重读实体、检查所有者/身份、重查所需能力，并依赖数据库强制行级安全保证租户边界。 |
| 深复制 | 普通值、数组、Map、用户类实例在赋值/传参/返回边界复制；系统句柄保持身份。 |
| 不可变上限 | 整文件 `read` 上限 16 MiB；`readChunk` 单次上限 4 MiB；单个 VFS 文件至少支持 1 GiB。大数据用 `append` 和分块读取。 |
| 删除 | `memory.destroy(target)` 是 FCL 删除进程值的唯一方式。实体删除通过实体 `remove()`（或显式命名空间清理）完成，绝不删除无关用户文档。 |
| 审计 | 管理员与资源控制动作会入审计日志；`audit.purge` 是审计历史缩短的唯一方式，且不会自动清理。 |

## 纯计算命名空间

这些函数只计算值，没有持久实体接收者。

### `math`

| 调用 | 作用 |
| --- | --- |
| `math.sin/cos/tan(x)` | 三角函数。 |
| `math.sqrt(x)` | 平方根；`x` 必须非负。 |
| `math.log(x)` | 自然对数；`x` 必须为正。 |
| `math.abs(x)` | 绝对值。 |
| `math.round/floor/ceil(x)` | 四舍五入/向下/向上取整。 |
| `math.pow(base, exponent)` | 幂；结果非有限时报错。 |
| `math.max(a,b)` / `math.min(a,b)` | 较大/较小值。 |
| `math.pi()` / `math.e()` | 圆周率 / 自然常数。 |
| `math.random()` / `math.random(lower, upper)` | 随机浮点数，或 `[lower, upper)` 内随机整数。 |

### `util`

| 调用 | 作用 |
| --- | --- |
| `util.toJson(value)` / `util.fromJson(text)` | JSON 往返；JSON 整数保持精确（`long`/`BigInteger`），绝不退化为有损 double。 |
| `util.typeOf(value)` | FCL 类型名；句柄返回其 kind。 |
| `util.isArray/isMap/isNumber/isString/isBool(value)` | 类型判断。 |
| `util.toString(value)` / `util.string(value)` | FCL 显示文本；句柄渲染为 `Kind{identity}`。 |
| `util.length(value)` | 等价于 `#value`。 |
| `util.getTime()` | Runtime 当前 Unix 毫秒时间戳。 |
| `util.which(functionName)` | Runtime/扩展函数返回 `0`；已绑定包函数返回包数据库 SHA-256；否则 `null`。 |
| `util.print/println/input` | `io.print/println/input` 的别名。 |
| `util.sleep(milliseconds)` | 挂起进程指定毫秒后恢复。 |
| `util.exit([result])` | 正常结束当前进程，可返回结果值。 |

### `path`

| 调用 | 作用 |
| --- | --- |
| `path.normalize(path)` / `path.resolve(path)` | 规范化 `.`、`..` 和重复斜杠。 |
| `path.getFileName(path)` | 最后一段。 |
| `path.getParentPath(path)`（别名 `path.getParent`） | 父路径。 |
| `path.isAbsolute(path)` | 是否以 `/` 或 `\` 开头。 |
| `path.join(parts...)` | 拼接并规范化；无参数返回 `/`。 |
| `path.setAlias/removeAlias/getAlias/listAliases` | 进程本地路径别名。 |

### `array` 与 `text`

| 调用 | 作用 |
| --- | --- |
| `array.insert(values, index, value)` | 返回插入后的新数组。 |
| `array.removeAt(values, index)` | 返回删除该元素后的新数组。 |
| `text.slice(value, start [, end])` | 按 UTF-16 码元切片。 |
| `text.split(value, delimiter)` | 分割并保留尾部空项。 |
| `text.join(values, delimiter)` | 用 FCL 显示规则拼接。 |
| `text.indexOf/lastIndexOf(value, search [, start])` | 搜索；不存在返回 `-1`。 |
| `text.commonPrefixLength/commonSuffixLength(a,b)` | 公共前缀/后缀长度。 |
| `text.repeat(value, count)` | 重复字符串。 |
| `text.replace(value, search, replacement)` | 替换全部匹配。 |

### `term`

`term` 返回 ANSI 控制文本，本身不打印。

| 调用 | 作用 |
| --- | --- |
| `term.color/paint(color, value)` | 前景色（`black red green yellow blue magenta cyan white`）。 |
| `term.red/green/yellow/blue/magenta/cyan/white(value)` | 颜色快捷函数。 |
| `term.bg(color, value)` / `term.color256(i, value)` / `term.bg256(i, value)` | 背景样式。 |
| `term.trueColor(r,g,b,value)` / `term.bgTrueColor(r,g,b,value)` | 24 位颜色。 |
| `term.bold/dim/inverse/underline/strikethrough(value)` | 文本样式。 |
| `term.reset()`、`term.clear()`、`term.eraseLine()` | 控制序列。 |
| `term.cursorUp/Down/Forward/Back(n)`、`term.cursorTo(row, column)` | 光标移动。 |
| `term.hideCursor()` / `term.showCursor()` | 光标可见性。 |
| `term.displayWidth(value)` / `term.truncate(value, width)` | 终端显示宽度与安全截断。 |
| `term.getSize()`（别名 `term.size`） | `{width, height}`；尺寸变化通过 `io.readKey` 的 resize 事件到达。 |
| `term.sanitize(value)` | 替换控制字符并展开制表符。 |
| `term.render(frame)` | 为所属终端排队一帧一次性 ANSI 输出；没有终端路由时失败。 |
| `term.alternate/mouse/paste/focus(active)` | 切换对应终端模式序列。 |

## I/O、进程内存与环境变量

### `io`

| 调用 | 作用 |
| --- | --- |
| `io.print(value)` / `io.println(value)` | 不换行 / 换行打印。 |
| `io.input([prompt])` | 等待一行输入；等待期间提示符变为 `pid:?`。 |
| `io.readChar()` | 等待并返回第一个字符。 |
| `io.readKey([timeoutMs[, coalesceText]])` | 读取一个结构化输入事件：`key`、`mouse`、`paste`、`paste_rejected`、`focus`、`resize`、`raw`、`repeat` 或 `timeout`。布尔形式 `io.readKey(true)` 启用文本合并且不创建定时器。 |

### `memory`

| 调用 | 作用 |
| --- | --- |
| `memory.list([options])` | 返回 `{variables, functions}` 快照；`{includeRuntime:true}` 加入内置/别名/扩展，`{includeParents:true}` 加入词法根值。 |
| `memory.destroy(target)` | 删除当前作用域变量或数组/Map 元素，返回是否删除；永不删除实体、函数、祖先或运行时键。 |

### `env`

变量名大小写不敏感并以大写保存；值上限 64 KiB。`PWD`、`USER`、`USER_ID`、`PID`
是只读动态值。

| 调用 | 作用 |
| --- | --- |
| `env.get(name [, targetUser])` | 读取变量（用户值优先于共享默认值）。 |
| `env.set(name, value [, targetUser])` / `env.remove(name [, targetUser])` | 管理当前用户变量。 |
| `env.list([targetUser])` | 返回完整有效环境。 |
| `env.getShared/listShared/setShared/removeShared` | 共享变量；写入要求管理员身份。 |
| `env.getSharedPolicy/setSharedPolicy(mode, names)` | 共享变量名策略；设置仅管理员。 |

## 持久实体命名空间

这些操作保持普通函数形式，因为它们是没有单一实体接收者的服务；仍使用当前进程身份与能力。

| 命名空间 | 可见调用 |
| --- | --- |
| `file` | `file.open`、`file.create`、`file.mkdir`、`file.path`、`file.link(linkPath, targetPath)`（创建符号链接节点；其余 `file.*` 已由对象 API 取代）。 |
| `process` | `process.args()`、`process.arg(index)`、`process.getPID()`、`process.getPPID()`、`process.list()`、`process.listChildren()`、`process.fork()`（父进程得到子 PID，子进程从 0 运行）、`process.run(sourcePath [, args])`（非持久 FCL 计算任务，不是可恢复进程对象）、`process.exec(commandOrPath [, args])`、`process.wait(pid)`、`process.clearFinished()`、`process.find(pid)`。 |
| `swapPool` | `swapPool.create(path)`、`swapPool.open(path)`。 |
| `ipc` | `ipc.createChannel(name)`、`ipc.channel(id)`、`ipc.createTopic(name)`、`ipc.topic(name)`、`ipc.delivery(id)`、`ipc.receive()`、`ipc.poll()`、`ipc.purge(olderThan [, limit])`。 |
| `package` | `package.build(sourcePath, outputPath)`、`package.install(vfsPath)`、`package.list()`、`package.recover()`、`package.find(hashOrCoordinate)`、`package.quotaFor(user, hash)`。 |
| `packageData` | 仅 `packageData.current()`。 |
| `user` | `user.getCurrentUser()`、`user.isLocal()`、`user.list()`、`user.create(...)`、`user.find(idOrName)`、`user.switchUser(...)`（始终不可用：持久进程身份不能原地更换）。 |
| `program` | `program.find(programId)`。 |
| `terminal` | `terminal.find(sessionId)`。 |

## 系统与管理命名空间

| 调用 | 作用 |
| --- | --- |
| `system.list()` | 全部可见限定函数名/别名。 |
| `system.extensions()` | 编译期封装 Java 扩展：`id`、`version`、`description`。 |
| `system.exec(command)` | 管理员：运行一条宿主允许列表命令（字符串或参数数组，不经 shell）；未配置 `CILEXEC_FCL_EXEC_ALLOWLIST` 时禁用。 |
| `system.invoke(qualifiedFunction [, arguments])` | 管理员：按名调用已注册函数；不能指向自身、用户定义、包导出或源导入函数。 |
| `system.resolveEffect(...)`、`system.reset(...)` | 从 FCL 永久不可用；请使用管理员控制平面。 |
| `timer.purge(before [, limit])` | 管理员：删除早于指定时刻的 FIRED/CANCELLED 定时器历史；SCHEDULED/CLAIMED 永不触碰。 |
| `audit.purge(before [, limit])` | 管理员：缩短审计历史的唯一方式；清理动作本身也会审计。 |
| `storage.purgeUnreferenced([limit])` | 管理员：回收从所有持久根不可达至少一小时的对象存储行；不直接删除 VFS 文件、包、进程或 Program 行。 |
| `user.list()` | 管理员：列出可见用户。 |

## 网络与套接字

外部操作会挂起 FCL 进程，完成后再恢复。

| 调用 | 作用 |
| --- | --- |
| `network.httpGet(url [, headers])`、`network.httpPost(url, body [, headers])`、`network.download(url)`、`network.webget(...)`、`network.webpost(...)` | 经持久外部效果子系统的 HTTP 请求，受网络目标策略约束。 |
| `socket.bind(host, port)` / `socket.accept(handle)` | 管理员拥有的监听套接字。 |
| `socket.connect(host, port)` | 出站套接字，受网络目标策略约束。 |
| `socket.send(handle, data)` / `socket.receive(handle [, maximumBytes])` / `socket.close(handle)` | 套接字 I/O。 |

网络目标策略阻止回环、链路本地和元数据端点，并应用 Runtime 配置的允许/拒绝规则与 DNS
固定规则；只有 `CILEXEC_NETWORK_TARGET_ALLOWLIST` 配置的宿主才是允许目标。

## 包与市场

| 调用 | 作用 |
| --- | --- |
| `package.build(sourcePath, outputPath)` | 从源目录构建一个不可变 SQLite 包数据库。 |
| `package.install(vfsPath)` | 从 VFS 安装 `.db` 并返回 `Package` 句柄。 |
| `package.list()` | 列出本 Runtime 已知 release。 |
| `package.recover()` | 核对安装记录与包载荷。 |
| `market.*` | 内置市场客户端：`origin`、`configure`、`update`、`install`、`list`、`info`、`search`、`run`、`help`、`download`。市场安装委托包生命周期，因此需要相应包能力。 |

## 0.0.5 兼容性与旧数据拒绝启动

0.0.5 与更早 CilExec 版本**故意不兼容**：

| 持久状态 | 0.0.5 行为 |
| --- | --- |
| FCL 程序 / continuation | 只读取格式 `5`（源语言 `fcl-0.0.5`）；旧工件被拒绝，没有转换器。 |
| 包数据库 | 只接受包格式 `3`；旧 `.db` 在构建/导入/读取时被拒绝。 |
| 包源语言 | 只有 `fcl-0.0.5` 包可链接；0.0.5 之前的包不兼容导入（旧实体 API 已删除）。 |
| JAR 同目录 `cilexec-data/` | Runtime 在启动 PostgreSQL 或迁移**之前**检查数据目录。已存在但没有 `0.0.5` 标记（或标记不同）的目录会被拒绝并说明原因；永不删除、改名、重建或迁移。 |
| 外部 PostgreSQL | 迁移/启动格式检查会在任何迁移之前读取 Flyway 历史。schema < 5、未知非空数据库、更新 schema 或失败的迁移历史都会被拒绝并说明原因；绝不原地升级。 |
| 包私有数据 / VFS / 用户 / 进程 | 只初始化全新的 0.0.5 数据库/数据目录。旧数据必须由用户自己的旧版工具导出，或原样保留。 |

升级方式：在新的目录中启动 0.0.5 JAR（或使用全新数据库）。旧数据保持原样，由运维决定归档或处理。

## 快速排查

| 症状 | 原因与修复 |
| --- | --- |
| `UndefinedFunction: file.read` | 旧实体 API 已删除；改用 `file.open(path).read()`，见删除对照表。 |
| `UndefinedMethod: File.foo` / `InvalidReceiver` | 该句柄类型没有这个方法；检查实体表。 |
| `StaleHandle` | 实体已被删除（或句柄来自另一实体）；用工厂重新获取。 |
| `ExpiredLease` | 租约已过期；重新 `lock(milliseconds)`。 |
| 恢复后 `Undefined variable: x` | 该值只在非持久作用域存在；在代码中重新创建。 |
| 包被能力检查拒绝 | 源码使用了未在 `package.json` 声明的能力；声明后重新构建。 |
| JAR 拒绝启动并提到 0.0.5 | 数据目录或数据库属于其他版本；使用新的数据目录/数据库。 |
| `Database schema ... outside supported range` | 外部数据库不是 0.0.5 创建的；用全新数据库启动。 |

## 实现位置

- 函数注册与分派：`FclFunctionRegistry`、`FclExpressionEvaluator`、`FclRuntime`；
  实体方法注册在 `FclFileRuntimeFunctions`、`FclProcessRuntimeFunctions`、
  `FclPackageRuntimeFunctions`、`FclUserRuntimeFunctions`、`FclCoreRuntimeFunctions`。
- 句柄值类型与持久化：`FclSystemHandle`、`FclValues`、`FclContinuationCodec`。
- 程序/continuation 格式：`FclProgramCodec`、`FclContinuation`。
- 启动格式检查：`LocalPostgres`、`DatabaseFormatFence`、`SchemaVerifier`、`FlywayMigrator`。
- 测试：`FclHandleDispatchTest`、`FclSystemHandleTest`、`FclCodecAndRegistryTest`、
  `PackageCapabilityPolicyTest`、`LocalPostgresDataFormatTest`、`DatabaseFormatFenceTest`。
