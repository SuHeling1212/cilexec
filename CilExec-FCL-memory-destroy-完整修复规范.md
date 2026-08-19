# CilExec / FCL 当前版本 `memory.destroy` 完整语义与持久化修复规范

> 适用范围：当前 FCL 版本  
> 目标：修正 `memory.destroy` 的目标语义、数组/Map 元素删除语义，并保证删除结果严格进入 CilExec 的持久化状态。  
> 本文不引入指针，不引入引用语义，不引入写时复制（Copy-on-Write），也不实现 v0.0.2 的对象系统。

---

## 1. 核心定义

`memory.destroy(...)` 是 FCL 中统一的 **Memory 删除操作**。

它不区分“变量删除函数”“函数删除函数”“数组删除函数”等多套 API。

统一使用：

```fcl
memory.destroy(target)
```

其中 `target` 可以是：

```fcl
memory.destroy(a)
memory.destroy(hello)
memory.destroy(a[1])
memory.destroy(config["token"])
memory.destroy(users[0]["name"])
```

它的含义是：

> 从当前 FCL Memory 中真正删除 `target` 所表示的可删除对象或元素。

`memory.destroy` 不等价于：

```fcl
target = null
```

也不等价于：

```text
只让它暂时不可见
```

更不允许：

```text
本轮 JVM 中删除了，
下一次持久化、恢复、重启后又重新出现。
```

**成功提交以后，被删除目标必须从权威 FCL 状态中消失，并且后续持久化、恢复不得重新产生它。**

---

# 2. Memory 的统一模型

当前版本继续采用现有的 Memory 模型。

`memory.list()` 是用户查看当前可见 Memory 内容的统一入口。

Memory 中可以存在：

```text
变量
用户函数
导入函数
以后增加的其他可变 Memory 符号
```

这些内容不需要分别设计不同删除 API。

例如：

```fcl
a = 10

func hello() {
    io.println("hello")
}
```

Memory 中存在：

```text
a
hello
```

删除：

```fcl
memory.destroy(a)
memory.destroy(hello)
```

使用同一个函数。

FCL 已经禁止同一可见命名空间中产生需要歧义解析的重复名字，因此 `memory.destroy(name)` 不需要让用户说明：

```text
这是变量
还是函数
还是其他符号
```

解释器根据当前 Memory 中实际存在的目标解析即可。

---

# 3. `memory.list()` 与 `memory.destroy()` 的关系

规范定义：

> `memory.list()` 的默认可变视图决定顶层可删除 Memory root。

也就是说，正常情况下：

```fcl
memory.list()
```

能够列出的当前作用域可变变量和可变函数，可以作为：

```fcl
memory.destroy(...)
```

的顶层目标。

注意：

```fcl
memory.list({includeRuntime: true})
```

可能显示 built-in、Java extension 等 **不可变运行时定义**。

“显示出来”不意味着变成可删除对象。

例如：

```fcl
memory.destroy(io.print)
```

不得删除运行时 built-in。

同样，`includeParents` 只是扩展查看范围，不应该自动扩大当前作用域的修改权限。

因此应区分：

```text
Memory 可见性
Memory 所有权 / 可变性
```

`memory.destroy` 只允许破坏当前进程有权修改的 Memory 对象。

---

# 4. 当前版本继续使用值语义

这是本次修复必须保持不变的语言原则。

当前 FCL 的复合值在读取、赋值和普通函数参数路径中使用深拷贝。

本次修复 **禁止** 顺手把它改成共享引用语义。

例如：

```fcl
a = [1, 2, 3]
b = a
```

语言语义必须是：

```text
a = 一份数组值
b = 从 a 复制得到的另一份数组值
```

之后：

```fcl
b[0] = 100
```

结果：

```fcl
a == [1, 2, 3]
b == [100, 2, 3]
```

同样：

```fcl
a = {"x": 1}
b = a
b["x"] = 2
```

结果：

```fcl
a == {"x": 1}
b == {"x": 2}
```

---

## 4.1 本版本不实现写时复制

本版本暂时 **不考虑 Copy-on-Write / 写时复制优化**。

也就是说，不要为了性能在本次修复中加入：

```text
共享 backing storage
引用计数
延迟复制
修改时分裂
```

等机制。

当前已有的实际深拷贝策略继续保留。

本次工作只修：

```text
memory.destroy 的语义
目标定位
真实删除
持久化一致性
```

不要扩大成整个 FCL Value Runtime 的重构。

---

## 4.2 删除一个副本不影响另一个副本

因为 FCL 当前使用值语义：

```fcl
a = [1, 2, 3]
b = a
```

之后：

```fcl
memory.destroy(a[1])
```

结果必须是：

```fcl
a == [1, 3]
b == [1, 2, 3]
```

`b` 不受影响。

这是正确行为。

原因不是“存在两个引用指向同一对象”，而是：

```text
赋值已经生成两个逻辑上独立的值。
```

同样，如果：

```fcl
a = {"password": "A"}
b = a
memory.destroy(a)
```

只删除 Memory 中的 `a`。

`b` 是之前已经复制出的独立值，不能被一起删除。

---

# 5. FCL 不加入指针

本修复不得增加：

```text
&
*
pointer
address
reference<T>
对象地址
指针算术
```

等用户可见概念。

例如：

```fcl
memory.destroy(a[1])
```

只是一个特殊的 **可删除目标表达式**。

解释器知道：

```text
root = a
path = [1]
```

然后直接修改当前 Continuation 中的真实 `a`。

用户不需要获得任何指针或引用对象。

---

# 6. `memory.destroy` 不是普通值函数

这是实现中最重要的一条。

当前 FCL 普通表达式求值会对变量值进行 `deepCopy()`。

普通函数参数进入 `FclFunctionRegistry` 时还会再次做安全复制。

因此下面这种简单实现是错误的：

```fcl
memory.destroy(a[1])
```

如果先按普通函数参数求值，会发生：

```text
读取 a
↓
deepCopy(a)
↓
在副本中读取 [1]
↓
得到值 20
↓
再把 20 传给 memory.destroy
```

此时 Runtime 只知道：

```text
20
```

已经不知道它原本来自：

```fcl
a[1]
```

更不可能修改 Continuation 中真正保存的 `a`。

因此：

> `memory.destroy` 必须是语言级特殊操作 / intrinsic / 专用指令，不能仅作为普通 `FclFunctionRegistry` 函数处理。

---

# 7. 推荐语法

统一语法：

```fcl
memory.destroy(<delete-target>)
```

允许的 `<delete-target>`：

```text
顶层可变 Memory 符号
顶层可变变量 + 一个或多个索引
```

合法：

```fcl
memory.destroy(a)
memory.destroy(hello)
memory.destroy(a[0])
memory.destroy(a["name"])
memory.destroy(a[0]["name"])
memory.destroy(a[x][y][z])
```

---

## 7.1 不允许普通计算结果作为删除目标

以下不能作为目标：

```fcl
memory.destroy(1)
memory.destroy("hello")
memory.destroy(a + b)
memory.destroy([1, 2, 3])
memory.destroy(func())
```

因为这些表达式只产生一个临时值，没有明确属于 Memory 的可删除位置。

注意：

```fcl
memory.destroy(hello)
```

如果 `hello` 是 Memory 中的函数名，这是合法的。

而：

```fcl
memory.destroy("hello")
```

只是字符串 `"hello"`，不再表示“按字符串名字寻找 hello”。

新设计必须消除旧接口中：

```fcl
memory.destroy("name")
```

这种字符串寻址方式。

---

# 8. 删除普通变量

例如：

```fcl
a = 123
memory.destroy(a)
```

成功后：

```fcl
a
```

必须表现为变量不存在。

内部语义：

```text
从当前可变 scope 中 remove("a")
↓
取得被删除值
↓
递归释放被删除值拥有的可变容器内容
↓
确保它不再能从当前 Continuation 访问
↓
持久化新的 Continuation
```

不得实现成：

```fcl
a = null
```

因为：

```text
不存在变量
```

和：

```text
变量存在，值为 null
```

是两个不同状态。

---

# 9. 删除函数

函数与变量使用同一个 API：

```fcl
func hello() {
    io.println("hello")
}

memory.destroy(hello)
```

成功后：

```fcl
hello()
```

必须不能再解析为该函数。

不得增加：

```fcl
memory.destroyFunction("hello")
memory.removeFunction("hello")
```

等另一套函数删除 API。

---

## 9.1 函数删除必须持久化

当前 Runtime 已有 process-local disabled function 状态。

删除函数后必须保证：

```text
当前执行中不可调用
下一 scheduler slice 不可调用
下一 terminal submission 不可调用
Runtime 重启后不可调用
数据库恢复 Continuation 后不可调用
```

也就是说，函数不能因为重新 link / compile / restore 又“复活”。

---

## 9.2 REPL 函数

如果函数来自 Terminal REPL 的持久化 library source：

```text
cilexec terminal library
```

则删除函数时还必须从该 library source 中移除对应函数声明。

否则下一次重新编译 terminal library 时，函数会重新出现。

所以 REPL 函数删除至少需要同时满足：

```text
1. 当前函数绑定失效
2. disabled function 状态正确
3. 持久化 library source 中不再包含该函数声明
```

---

## 9.3 Base Program / Package Function

对于来自 immutable Program 或 package artifact 的函数：

`memory.destroy` 删除的是：

> 当前进程 Memory 中的函数绑定 / 可调用性。

它不应该偷偷修改：

```text
程序源文件
package 数据库文件
不可变 Program artifact
package release
```

这些东西不是 `memory.list()` 中那个运行时 Memory 对象本身。

但是必须保证：

```text
即使 immutable artifact 仍保存原始代码，
被 destroy 的函数也不能在该进程恢复时重新进入可调用 Memory。
```

因此 disabled-function 状态是持久化语义的一部分。

---

# 10. 删除数组元素

例如：

```fcl
a = [10, 20, 30]

memory.destroy(a[1])
```

结果必须为：

```fcl
a == [10, 30]
```

数组长度：

```text
3 -> 2
```

元素位置：

```text
删除前：
0 -> 10
1 -> 20
2 -> 30

删除后：
0 -> 10
1 -> 30
```

也就是说：

> 数组元素使用真正的 `remove(index)` 语义，后续元素向左移动。

---

## 10.1 禁止数组空洞

不得得到：

```fcl
[10, null, 30]
```

因为 `null` 本身是合法 FCL 值。

也不得引入：

```text
<deleted>
<hole>
undefined slot
```

之类的新状态。

否则会使：

```text
#array
index
JSON
持久化
比较
打印
未来遍历
```

全部增加一套“空洞”语义。

当前版本不需要这种复杂度。

---

# 11. 删除 Map 元素

例如：

```fcl
m = {
    "name": "Alice",
    "age": 18
}

memory.destroy(m["name"])
```

结果：

```fcl
m == {
    "age": 18
}
```

语义是：

```text
真正删除 key
+
真正删除对应 value
```

不是：

```fcl
m["name"] = null
```

Map key 必须继续使用当前 FCL 的 key normalization 规则。

如果：

```text
1
1.0
```

在当前 Map 语义里属于同一个 key，则 `memory.destroy` 也必须使用相同规则。

---

# 12. 删除嵌套元素

必须支持和 indexed assignment 一致的嵌套目标。

例如：

```fcl
a = [
    {
        "name": "Alice",
        "age": 18
    }
]

memory.destroy(a[0]["name"])
```

结果：

```fcl
a == [
    {
        "age": 18
    }
]
```

再例如：

```fcl
a = [
    [1, 2, 3],
    [4, 5, 6]
]

memory.destroy(a[0][1])
```

结果：

```fcl
a == [
    [1, 3],
    [4, 5, 6]
]
```

---

## 12.1 删除只作用于最终目标

对于：

```fcl
memory.destroy(a[0]["name"])
```

不能删除：

```text
a
```

不能删除：

```text
a[0]
```

只能删除最终的：

```text
["name"]
```

中间路径只负责定位。

---

# 13. String 索引

当前版本建议：

```fcl
text = "abc"
memory.destroy(text[1])
```

报 FCL Runtime Error。

原因：

当前 String 不是可变容器。

允许删除 String 内单个 UTF-16 单元会额外引入：

```text
字符串可变
索引删除
重建字符串
Unicode 边界
```

等新语义。

这不属于本次修复。

但：

```fcl
memory.destroy(text)
```

如果 `text` 是普通可变变量，则当然可以删除整个变量。

---

# 14. 删除返回值

建议保留当前 API 的布尔风格：

```fcl
ok = memory.destroy(target)
```

规则：

```text
目标存在并成功删除 -> true
顶层目标不存在 -> false
顶层目标存在但不可变 -> false
```

对于非法路径：

```text
数组下标越界
中间值不是可索引容器
非法 index 类型
```

应抛 FCL Runtime Error。

Map key 不存在可以返回：

```fcl
false
```

这样：

```text
destroy 的 bool 表示“是否真的删除了一个东西”
```

语义统一。

---

# 15. 真正删除：Runtime 要求

删除成功以后，被删除的目标必须立即从 **权威 FCL Runtime 状态** 中消失。

例如删除：

```fcl
a
```

则当前可执行 Continuation 中：

```text
scope
globalScope
相关当前作用域
```

不得继续保留 `a`。

删除：

```fcl
a[1]
```

则实际保存的数组必须已经执行：

```text
remove(1)
```

不能只是让 evaluator 临时看不到它。

---

## 15.1 被删除子树必须释放

如果被删除值本身是：

```fcl
[
    {"x": [1,2,3]},
    ...
]
```

那么它从父容器中 remove 后，应对该被删除值执行递归 release。

当前已有类似：

```text
Map -> 递归释放 key/value -> clear
List -> 递归释放 item -> clear
byte[] -> zero fill
char[] -> zero fill
```

的逻辑，可以继续利用。

目标是：

> 删除完成以后，这棵被删除的可变值树不应继续被当前 FCL Runtime 引用。

---

# 16. 值语义下“真正删除”的准确含义

必须避免误解。

例如：

```fcl
a = ["secret"]
b = a
memory.destroy(a)
```

因为：

```fcl
b = a
```

已经深拷贝，所以 `b` 是独立值。

删除 `a` 后：

```text
a -> 不存在
b -> ["secret"]
```

这是正确的。

`memory.destroy(a)` 不应该搜索整个进程中“内容相同”的值然后一起删除。

因此：

> `destroy` 删除的是目标位置所拥有的那一份逻辑值，而不是所有内容相等的副本。

---

# 17. 持久化是本功能的硬性语义

这是本次修复最高优先级要求。

CilExec 的进程状态不是一次性 JVM 内存状态。

FCL Continuation 会被持久化，并在：

```text
下一 scheduler slice
Runtime 重启
容器重启
进程恢复
```

时重新加载。

因此：

> 如果 `memory.destroy` 只修改 Java 对象，却没有修改最终写入数据库的权威 Continuation，那么这个实现就是错误的。

---

# 18. 当前持久化有两份必须保持一致的状态

当前 `FclPersistenceBridge` 会同时持久化：

```text
1. 完整 FCL Continuation envelope
2. normalized scope projection
```

因此删除以后，这两份数据都必须反映删除结果。

例如：

```fcl
a = [1,2,3]
memory.destroy(a[1])
```

提交后：

```text
Continuation envelope:
a = [1,3]

normalized scope:
a = [1,3]
```

绝对不能出现：

```text
envelope:
a = [1,3]

scope projection:
a = [1,2,3]
```

或者反过来。

否则 restore 时两套权威状态会产生冲突，甚至让被删除元素复活。

---

# 19. 正确的持久化执行顺序

`memory.destroy` 必须发生在当前 statement 的权威 Runtime 状态上。

正确顺序：

```text
读取已恢复的 FclContinuation
        ↓
执行 memory.destroy
        ↓
直接修改 continuation 中真实 scope / container / function state
        ↓
确认语句成功
        ↓
snapshot
        ↓
FclPersistenceBridge.persist(...)
        ↓
重新生成完整 envelope
        ↓
重新生成 normalized scope projection
        ↓
process continuation update
        ↓
scheduler state update
        ↓
同一数据库事务 COMMIT
```

不是：

```text
先 persist
再 destroy
```

也不是：

```text
destroy 一个 evaluator 副本
真正 continuation 不变
```

---

# 20. 事务原子性

删除必须遵守 CilExec 现有“一条持久化执行 slice = 一个事务”的原则。

## 20.1 成功

如果事务成功 commit：

```text
删除结果 + continuation + scheduler state
```

一起生效。

之后恢复不能看见被删除目标。

---

## 20.2 执行失败

如果删除表达式失败，例如：

```fcl
a = [1]
memory.destroy(a[999])
```

则该 statement 不能留下半删除状态。

---

## 20.3 Runtime 在 commit 前崩溃

如果：

```text
destroy 已修改 JVM 中的 continuation
↓
但数据库事务尚未 commit
↓
Runtime 崩溃
```

数据库 rollback。

恢复后看到删除前状态是正确的。

因为：

> 这次 destroy 从来没有成功提交。

---

## 20.4 commit 成功后崩溃

如果数据库已经 commit：

```text
新的 continuation 已经 durable
```

随后 Runtime 崩溃，

恢复后必须看到删除后的状态。

不能复活目标。

---

# 21. “不会再进入内存”的严格定义

对于 CilExec 语言语义，本规范中的：

> 不会再进入内存

准确含义是：

```text
删除成功提交后，
该目标不再属于可恢复 FCL Memory，
恢复流程不能从 durable continuation 中重新构造它。
```

也就是说它不能重新出现在：

```text
scope
memory.list()
数组
Map
可调用函数集合
terminal persistent library
恢复后的 Continuation
```

中。

---

## 21.1 不承诺 JVM 物理 RAM 的取证级擦除

必须说明：

Java 的：

```text
String
Gson 临时对象
JDBC driver buffer
PostgreSQL client buffer
JVM heap 副本
```

不一定能够被应用程序逐字节安全擦除。

尤其 `String` 是不可变对象。

因此 `memory.destroy` 的语言语义不能宣称：

> JVM 物理 RAM 中曾经出现过的每一个字节立刻被清零。

对：

```text
byte[]
char[]
```

可以 best-effort zero fill。

对普通 FCL String 等值，只能保证：

```text
不再被权威 FCL 状态引用
不再被新的 continuation 持久化
不再从持久化恢复
```

如果未来需要“密码级安全擦除”，应该使用专门的 secret 类型，而不是普通 String。

---

# 22. “不会再保留在磁盘”的严格定义

本规范要求：

> 删除成功提交以后，**当前有效的 CilExec durable state** 不再保存被删除目标。

包括：

```text
当前 process continuation
当前 normalized scope projection
当前 terminal library（若删除的是 REPL 函数）
其他由该 Memory 对象生成的当前权威持久化表示
```

恢复当前状态时不得重新得到该值。

---

## 22.1 PostgreSQL 历史物理页 / WAL / backup 不属于普通 destroy 的保证

PostgreSQL 使用：

```text
MVCC
WAL
vacuum
backup
replica
```

因此普通事务更新不能保证：

> 某个值过去曾写入数据库后，所有旧磁盘页、WAL、备份中的历史字节立刻物理消失。

这不是 `memory.destroy` 在应用层能够保证的事情。

因此必须区分：

### CilExec durable-state hard delete

要求：

```text
当前权威状态不保存
恢复不复活
未来 checkpoint 不再写入
```

这是本功能必须保证的。

### Forensic secure erase

要求：

```text
旧 WAL
旧 MVCC page
backup
snapshot
filesystem block
```

全部物理擦除。

这属于完全不同的存储安全功能，不在本次 `memory.destroy` 范围内。

---

# 23. 推荐编译实现：专用 Destroy 指令

推荐新增专用 FCL instruction，而不是 hack 普通函数调用。

概念：

```java
record Destroy(
    String rootName,
    List<FclExpression> indices,
    int line
) implements FclInstruction {}
```

例如：

```fcl
memory.destroy(a)
```

编译：

```text
Destroy(
    rootName = "a",
    indices = []
)
```

---

```fcl
memory.destroy(a[0]["name"])
```

编译：

```text
Destroy(
    rootName = "a",
    indices = [0, "name"]
)
```

对于：

```fcl
memory.destroy(hello)
```

也是：

```text
Destroy(
    rootName = "hello",
    indices = []
)
```

Runtime 再根据 Memory 判断：

```text
hello 是变量？
hello 是 mutable function？
hello 不存在？
hello 是 immutable runtime function？
```

由于名字不重复，不需要用户说明类型。

---

# 24. Compiler 要求

Compiler 在看到：

```fcl
memory.destroy(...)
```

时必须验证参数是合法 delete-target。

不得先把参数编译成普通 Call argument。

建议复用 assignment target 解析逻辑。

当前已经支持：

```fcl
a[0]["x"] = 1
```

因此：

```fcl
memory.destroy(a[0]["x"])
```

应与赋值左侧使用尽量相同的：

```text
root + index path
```

表示。

---

# 25. Runtime 删除算法

## 25.1 顶层目标

对于：

```fcl
memory.destroy(a)
```

建议解析顺序：

```text
1. 当前 mutable variable 是否存在
2. 当前 mutable function 是否存在
3. 是否是 immutable builtin / extension
4. 不存在
```

由于命名规则不允许变量/函数发生有效冲突，不需要复杂 disambiguation。

---

## 25.2 删除变量

```text
value = currentScope.remove(name)
releaseValue(value)
return true
```

不得删除：

```text
reserved runtime state
ProcessInbox keys
cilexec.* internal keys
```

这些内部状态不能因为用户构造同名目标被破坏。

---

## 25.3 删除函数

```text
disableFunction(name)
↓
如果是 REPL library function：
    从持久化 library source 删除函数声明
↓
return true
```

运行时 immutable builtin / Java extension：

```text
return false
```

---

## 25.4 删除数组元素

先从真实 scope 取得 root。

不要：

```text
FclValues.deepCopy(root)
```

然后沿 index path 找到最后一级的父 List。

最终：

```java
Object removed = list.remove(index);
releaseValue(removed, seen);
return true;
```

---

## 25.5 删除 Map 元素

沿真实 root 找到最终父 Map。

执行：

```java
Object removed = map.remove(normalizedKey);
```

如果 key 不存在：

```text
return false
```

存在：

```text
releaseValue(removed)
return true
```

---

# 26. 嵌套路径必须操作真实容器

例如：

```fcl
a = [{"x": [1,2,3]}]
memory.destroy(a[0]["x"][1])
```

不能：

```text
读取 a[0]
↓
deepCopy
↓
读取 ["x"]
↓
deepCopy
↓
从副本删除 [1]
```

必须：

```text
从 scope 直接拿真实 a
↓
进入真实 List a
↓
进入真实 Map a[0]
↓
进入真实 List a[0]["x"]
↓
remove(1)
```

结果：

```fcl
a == [{"x": [1,3]}]
```

然后这一最终状态被持久化。

---

# 27. 不得破坏当前的 deep-copy 边界

特殊删除路径只允许用于：

```fcl
memory.destroy(...)
```

不能因为实现 destroy，就把：

```text
FclExpressionEvaluator Variable deepCopy
FclFunctionRegistry argument deepCopy
FclContinuation pending result deepCopy
snapshot deepCopy
```

全部去掉。

本次修复应做到：

```text
普通语言值流动 -> 继续 deepCopy
destroy target -> 特殊定位真实 Memory
```

两个机制并存。

---

# 28. 对未来 Object 的约束

虽然本次不实现 v0.0.2 Object，但当前规则必须为未来留下明确方向：

如果未来：

```fcl
a = new User()
b = a
```

仍然采用 FCL 的值语义，那么：

```text
b 是 a 的独立对象副本
```

之后：

```fcl
memory.destroy(a)
```

只能删除 `a` 对应的那一份对象。

`b` 不受影响。

本次修复不得提前引入：

```text
ObjectReference
共享 ObjectId
alias invalidation
```

等引用模型。

---

# 29. 必须新增的测试

## 29.1 删除普通变量

```fcl
a = 1
ok = memory.destroy(a)
```

断言：

```text
ok == true
a 不存在
```

---

## 29.2 不再支持字符串作为符号地址

```fcl
a = 1
memory.destroy("a")
```

必须不能删除变量 `a`。

它应该因为目标不是 delete-target 而编译失败或运行失败。

---

## 29.3 删除普通函数

```fcl
func hi() {
    return 1
}

ok = memory.destroy(hi)
```

断言：

```text
ok == true
hi 不再出现在 mutable function list
hi() 不可调用
```

---

## 29.4 删除函数后持久化恢复

删除 `hi`。

执行 checkpoint。

重新 restore process。

断言：

```text
hi 仍不可调用
```

---

## 29.5 REPL 函数删除后重启

Terminal 中定义：

```fcl
func hi() {
    return 1
}
```

删除：

```fcl
memory.destroy(hi)
```

Runtime 重启。

断言：

```text
hi 不重新出现
terminal library source 不再包含 hi declaration
```

---

## 29.6 删除数组中间元素

```fcl
a = [10,20,30]
memory.destroy(a[1])
```

断言：

```fcl
a == [10,30]
#a == 2
```

---

## 29.7 删除数组首元素

```fcl
a = [1,2,3]
memory.destroy(a[0])
```

结果：

```fcl
[2,3]
```

---

## 29.8 删除数组末元素

```fcl
a = [1,2,3]
memory.destroy(a[2])
```

结果：

```fcl
[1,2]
```

---

## 29.9 不产生 null hole

```fcl
a = [1,2,3]
memory.destroy(a[1])
```

断言：

```text
a.size == 2
不存在 null hole
```

---

## 29.10 删除 Map key

```fcl
m = {"a":1,"b":2}
memory.destroy(m["a"])
```

断言：

```fcl
m == {"b":2}
```

---

## 29.11 Map key 不存在

```fcl
m = {"a":1}
ok = memory.destroy(m["missing"])
```

断言：

```fcl
ok == false
m == {"a":1}
```

---

## 29.12 嵌套 Map

```fcl
a = [{"name":"Alice","age":18}]
memory.destroy(a[0]["name"])
```

结果：

```fcl
[{"age":18}]
```

---

## 29.13 嵌套数组

```fcl
a = [[1,2,3],[4,5,6]]
memory.destroy(a[0][1])
```

结果：

```fcl
[[1,3],[4,5,6]]
```

---

## 29.14 深层混合结构

```fcl
a = [{"x":[1,2,3]}]
memory.destroy(a[0]["x"][1])
```

结果：

```fcl
[{"x":[1,3]}]
```

---

## 29.15 值语义不受破坏

```fcl
a = [1,2,3]
b = a

memory.destroy(a[1])
```

断言：

```fcl
a == [1,3]
b == [1,2,3]
```

这是非常重要的回归测试。

---

## 29.16 整个变量删除不影响已复制变量

```fcl
a = {"x":1}
b = a

memory.destroy(a)
```

断言：

```text
a 不存在
b == {"x":1}
```

---

## 29.17 String index 不可删除

```fcl
a = "abc"
memory.destroy(a[1])
```

必须报错。

---

## 29.18 数组越界

```fcl
a = [1]
memory.destroy(a[5])
```

必须报错，并且：

```fcl
a == [1]
```

---

## 29.19 immutable runtime function

```fcl
ok = memory.destroy(io.print)
```

断言：

```text
ok == false
io.print 仍可用
```

---

## 29.20 Reserved runtime state

任何形式都不得允许用户 destroy：

```text
cilexec.*
ProcessInbox internal values
terminal session state
library bookkeeping itself
```

除非通过明确支持的高层语义（例如删除一个 REPL 函数时更新 library）。

---

# 30. 持久化专项测试

普通功能测试不够。

必须专门测试数据库中的 durable representation。

---

## 30.1 Envelope 检查

执行：

```fcl
a = [1,2,3]
memory.destroy(a[1])
```

提交后读取：

```text
cilexec.fcl.continuation envelope
```

解码。

断言：

```fcl
a == [1,3]
```

并确认 envelope 中不再存在作为 `a[1]` 的 `2`。

---

## 30.2 Normalized scope 检查

同一测试中读取：

```text
Continuation.ScopeFrame.variables
```

断言：

```fcl
a == [1,3]
```

必须和 envelope 一致。

---

## 30.3 Restore 检查

从数据库保存的 Continuation：

```text
FclPersistenceBridge.restore(...)
```

断言：

```fcl
a == [1,3]
```

---

## 30.4 Runtime restart 集成测试

流程：

```text
创建进程
↓
a = [...]
↓
destroy
↓
等待 transaction commit
↓
关闭 Runtime
↓
重新启动
↓
恢复 process
↓
读取 memory
```

断言被删除目标没有恢复。

---

## 30.5 Crash-before-commit

模拟：

```text
destroy 已执行
persist/update transaction 未 commit
异常 / rollback
```

重新加载。

断言旧状态仍然存在。

这是正确行为。

---

## 30.6 Crash-after-commit

模拟 transaction 已成功 commit，Runtime 随后停止。

重新加载。

断言删除后的状态存在。

---

# 31. 需要重点检查的当前代码

本修复至少涉及或需要审核：

```text
FclCompiler
FclInstruction
FclExpression / assignment target parsing
FclRuntime
FclExpressionEvaluator
FclRuntimeFunctions.registerMemory
FclValues
FclContinuation
FclContinuationCodec
FclPersistenceBridge
ProcessStatementExecutor
TerminalReplService
ProcessStatementExecutorTest
FCL function reference docs
```

---

# 32. 推荐实施顺序

为了降低风险：

### 第一步：增加 Destroy target 编译结构

只让 Compiler 能识别：

```fcl
memory.destroy(a)
memory.destroy(a[0])
```

先不要删除旧实现。

---

### 第二步：Runtime 实现真实目标删除

实现：

```text
variable
function
List element
Map entry
nested container
```

---

### 第三步：接入 releaseValue

删除完成后，对实际被移除的值做递归 release。

---

### 第四步：删除字符串寻址旧接口

移除：

```fcl
memory.destroy("name")
```

作为符号删除方式。

---

### 第五步：持久化测试

必须先证明：

```text
envelope
scope projection
restore
restart
```

全部正确。

---

### 第六步：文档更新

把函数参考改为新的统一语义。

---

# 33. 禁止在本次修复中顺手做的事情

本次修复明确不做：

```text
指针
引用类型
共享对象 identity
写时复制
Copy-on-Write
引用计数
自动 GC
v0.0.2 Object
try/catch
ExceptionValue
大规模 FclRuntimeFunctions 拆分类
修改 package immutable model
修改 Program identity / hash model
```

这些都应该独立设计和独立测试。

---

# 34. 最终语义总表

| 表达式 | 结果 |
| --- | --- |
| `memory.destroy(a)` | 删除当前可变变量 `a` |
| `memory.destroy(hello)` | 若 `hello` 是可变函数，则删除其 Memory 绑定 |
| `memory.destroy(a[1])` | 删除 List 第 1 项，后续元素左移 |
| `memory.destroy(m["x"])` | 删除 Map 的 `"x"` 键和值 |
| `memory.destroy(a[0]["x"])` | 删除嵌套结构中的最终目标 |
| `memory.destroy("a")` | 不再表示变量名 `a`；不是合法符号删除方式 |
| `memory.destroy(io.print)` | immutable runtime function，不删除 |
| `memory.destroy(text[1])` | 当前版本不支持 String 内部删除 |
| 删除后 checkpoint | 新 durable state 不再包含目标 |
| Runtime 重启 | 被删除目标不得恢复 |
| `b = a` 后 destroy `a` | `b` 是独立深拷贝，不受影响 |

---

# 35. 最终设计原则

本次修复必须严格遵守以下原则：

### 原则一：只有一个删除 API

```fcl
memory.destroy(...)
```

变量、函数、数组元素、Map 元素统一使用它。

---

### 原则二：FCL 当前仍然是值语义

```fcl
b = a
```

意味着 `b` 获得独立值。

本版本继续真实 deep-copy，不做写时复制。

---

### 原则三：destroy 操作真实 Memory，而不是值副本

`memory.destroy(a[1])` 必须删除 Continuation 中实际数组的元素。

不能删除 evaluator 产生的临时副本。

---

### 原则四：数组是真正 remove

```fcl
[1,2,3]
```

删除 `[1]` 后：

```fcl
[1,3]
```

长度变小，后续元素移动。

---

### 原则五：删除不是 null

删除以后目标不存在。

不得使用 `null` 或 hole 模拟删除。

---

### 原则六：持久化是语义的一部分

删除成功 commit 后：

```text
当前 Runtime
Continuation envelope
normalized scopes
下一 slice
Runtime restart
process recovery
```

都必须一致地认为目标已经不存在。

---

### 原则七：绝不允许恢复复活

如果一个成功提交的 `memory.destroy` 在 Runtime 重启以后让目标重新出现，

这不是“小 bug”，而是：

> **违反 CilExec 持久化语义的严重正确性 Bug。**

---

### 原则八：区分 durable-state 删除与磁盘取证级擦除

`memory.destroy` 必须保证：

```text
当前有效数据库状态不再保存
未来 checkpoint 不再写入
恢复不再产生
```

但 PostgreSQL 的旧 MVCC page、WAL、backup 物理擦除属于另外一套安全存储问题。

---

## 一句话规范

> **`memory.destroy(target)` 直接删除当前进程 Memory 中的真实目标；目标可以是可变符号或其数组/Map 子元素。删除必须修改权威 Continuation，并在同一事务内持久化；成功提交后目标永远不能由 CilExec 的当前 durable state 再次恢复。FCL 继续使用真实深拷贝值语义，本版本不引入指针、共享引用或写时复制。**
