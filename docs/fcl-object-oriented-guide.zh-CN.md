# FCL 0.0.5 对象指南

本指南面向第一次接触 FCL 0.0.5 的读者，说明两种对象：**用户类实例值**与**系统实体句柄**。
示例都只使用 0.0.5 当前接口。完整工厂与方法清单见
[0.0.5 函数与对象参考](fcl-function-reference.zh-CN.md)。
[0.0.4 对象指南](fcl-object-oriented-guide-004.zh-CN.md)描述已删除的旧接口，仅用于阅读旧代码。

## 两种对象

| | 用户类实例 | 系统实体句柄 |
| --- | --- | --- |
| 创建 | `new ClassName(...)` | `file.open`、`process.find`、`ipc.createChannel` 等工厂 |
| 赋值/传参/返回 | 深复制，得到独立副本 | 仍是同一句柄，两个名字指向同一实体 |
| 身份 | 无；相等性比较类名和字段 | 持久 kind + 稳定 id（加私有属性） |
| 生命周期 | 只要 continuation 中仍保存该值 | 直到持久实体被删除 |
| 权限 | 按类/private 成员的 FCL 规则检查 | **每次方法调用**都按数据库当前状态重查 |

值跨语句、函数调用或挂起时，差异最明显。

```fcl
class Counter {
    value = 0
    func increment() { value++ }
}

left = new Counter()
right = left          // 深复制：right 是独立副本
right.increment()
io.println(left.value)   // 0
io.println(right.value)  // 1
```

```fcl
note = file.create("/note.txt", "first")
same = note                  // 句柄复制：仍是同一持久文件
same.append(" second")
io.println(note.read())      // first second
```

句柄**不是**权限令牌，也不是缓存对象。它只保存实体类型、稳定身份字符串和可选私有属性
（例如租约 fencing token 或主题名），绝不保存 Java 指针、数据库连接或权限授予。
每次调用都会重新读取实体，并重新检查调用者身份、能力与数据库权限（RLS）边界。

## 调用方法

FCL 支持两种写法：

```fcl
h = file.open("/note.txt")
io.println(h.read())                        // 具名接收者
io.println(file.open("/note.txt").read())   // 链式表达式接收者
```

两者都分派到同一个 `File.read`，只是语法不同：

| 写法 | 分派方式 |
| --- | --- |
| `h.read()` | 名字 `h` 当前保存 `File` 句柄，解释器分派 `File.read`。 |
| `file.open(p).read()` | 先执行 `file.open`，再在结果句柄上分派 `read`。 |
| `a.child.method()` | `a` 是用户对象，解析字段路径后按类分派方法。 |
| `new Thing().method()` | 临时用户对象执行方法，返回值可用，但没有具名接收者可写回。 |

需要对同一实体调用多个方法、传给其它进程，或跨 `io.input()` 等待保留时，使用具名变量；
一次性操作用链式写法。

## 用工厂取得实体，而不是传路径参数

实体总是从工厂函数开始。0.0.4 那种每次调用都传路径、PID、哈希或投递 id 的方式已在 0.0.5 删除。

```fcl
// 文件与目录
f = file.open("/docs/report.txt")     // 已存在节点，返回 File 句柄
f = file.create("/docs/new.txt", "hello")
d = file.mkdir("/docs/archive")

// 可能不存在的路径
maybe = file.path("/docs/not-there.txt")
io.println(maybe.exists())            // false，不抛异常

// 进程
p = process.find(process.getPID())
io.println(p.status())

// Swap Pool 与变量
pool = swapPool.open("job-42")   // 池名是简单标识符，不是 VFS 路径
v = pool.variable("counter")
pool.add("counter", 1)

// 消息
channel = ipc.createChannel("work")
channel.send({"job": 1})
envelope = ipc.receive()
if envelope != null {
    ipc.delivery(envelope["deliveryId"]).consume()
}

// 包与私有数据
pkg = package.find("<包数据库文件 sha256>")
io.println(pkg.info())
space = pkg.data()
io.println(space.usage())

// 受权限限制的管理对象
me = user.find(user.getCurrentUser())
io.println(me.info())
```

工厂本身可能需要能力（例如 `package.find` 要求 `PACKAGE_IMPORT`），并要求实体存在；
只有显式 `file.path` 值和 `create` 工厂例外。

## 句柄跨保存与恢复存活

每个已提交执行片都会保存完整的 FCL continuation，包括变量中的句柄、调用帧参数和待定方法结果，
因此下面代码可以工作：

```fcl
note = file.create("/durable.txt", "before")
io.input("按回车让进程挂起> ")
note.append(" after")
io.println(note.read())   // before after
```

进程等待输入期间 Runtime 可能重启。恢复后 `note` 句柄仍指向同一 VFS 节点——不是路径，也不是副本。
如果期间文件被删除（例如被其他进程），下一次调用报 `StaleHandle`，绝不会静默指向同路径的新文件。

持久化句柄只保存逻辑身份。恢复 continuation 不会重新打开数据库连接或复用 Java 对象；
每次调用都重新查找。

## 改名、删除与失效句柄

```fcl
h = file.create("/notes/draft.txt", "text")
h.rename("draft-2.txt")
io.println(h.read())                     // text —— 改名后仍是同一节点

h.remove()
io.println(file.path("/notes/draft.txt").exists())   // false

// 之后同路径的新文件是新实体、新身份：
fresh = file.create("/notes/draft.txt", "new")
// 旧 h 仍然失效；只有 fresh 有效
```

规则：

- **改名保留身份。** 文件句柄寻址 VFS 节点 ID，改名不会使其失效。
- **删除使句柄失效。** 删除实体后所有旧句柄失效；即使之后同路径/同 PID 出现新实体也不会复活。
- **类型变化使句柄失效。** 若实体被替换成不同类型的实体，旧句柄按失效拒绝，而不是被重新解释。

## 每次调用都重新检查权限

句柄不携带权限。假设管理员打开了某个跨用户文件的句柄并保存，随后失去跨用户能力：
下一次方法调用会被拒绝，因为检查发生在现在，而不是创建句柄时。

租约同理：知道 fencing token 并不够，token 必须属于当前逻辑进程且仍是当前 token。
租约过期后续租报 `ExpiredLease`，其他进程可用更新的 token 取得锁。

```fcl
lease = file.open("/shared/counter.txt").lock(30000)
if lease == null {
    io.println("锁被占用，稍后重试")
} else {
    lease.renew(30000)    // 过期后报 ExpiredLease
    lease.release()
}
```

## 链式调用与挂起

```fcl
pid = process.find(process.getPID()).kill()   // 终止自身返回 true
```

方法调用可能导致进程挂起（例如 `Process.wait()`、`SwapVariable.waitFor()` 或链式
`ipc.receive()`）。挂起点之前已产生的值会记录在 continuation 中，崩溃重放不会重复执行
同一语句中更早的调用。挂起的方法自身通过正常等待机制恢复。请让外界可见效果可幂等重复：
崩溃后、`Delivery.consume()` 提交前，投递可能被重新预留，因此要把重复投递视为可能。

## 包私有数据

`package.find(hash).data()` 返回 `PackageSpace`。所有者可以读取、导出、导入、清空并查看
用量/配额。可能修改包内私有辅助文件的操作（`seedResource`、`append`、`mkdir`、`remove`、
`rename` 等）要求调用在同一精确包的已链接代码中执行，并具备 `package.data` 能力：

```fcl
// 仅已链接包代码内可用：
space = packageData.current()
workerPath = "runtime/worker.fcl"
if !space.exists(workerPath) {
    space.seedResource("worker.fcl", workerPath)
}
process.run(space.root() + workerPath, [])
```

私有数据不是 VFS 路径：任意 `file.*` 都无法到达，其他用户的私有空间也不可达。
每次调用都会重新检查该精确包数据库文件仍安装在当前用户名下。

## 相对 0.0.4 的变化

- 旧式 `file.read(path)`、`process.kill(pid)`、`ipc.consume(id)`、`package.info(hash)`
  等按路径/PID/哈希调用的函数**已删除**，不能从 FCL 调用，也不会出现在 `system.list()`。
  见[被取代的 API 表](fcl-function-reference.zh-CN.md#被取代的-004-api-与替代写法)。
- 程序与 continuation 使用格式 `5`，包数据库使用格式 `3`，数据库 schema 为 `V005`；
  旧数据一律拒绝，绝不转换。见[旧数据拒绝启动](fcl-function-reference.zh-CN.md#005-兼容性与旧数据拒绝启动)。
- 用户类实例仍是按值对象。只有实体句柄在复制时保持身份；`b link a` 仍表示名字跟随。

## 实用检查清单

1. 实体只取一次：`h = file.open(path)` 或 `p = process.find(pid)`。
2. 复用 `h`，不要反复传路径/PID。
3. 长时间运行的程序要显式处理 `StaleHandle` 与 `ExpiredLease`。
4. 链式调用方便一次性操作；具名句柄才是持久引用。
5. 不要认为句柄授予权限；Runtime 每次调用都检查。
6. 用 `system.list()` 确认当前 JAR 暴露的函数。
