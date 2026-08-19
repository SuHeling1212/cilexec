# CilExec / FCL v0.0.2 语言设计与实现规范

## 1. 版本目标

FCL v0.0.2 的主要目标不是继续增加大量独立函数，而是补全语言本身最基础的对象系统。

v0.0.1 已经具备变量、函数、作用域、文件导入、运行时、进程、持久化等基础能力。

v0.0.2 在此基础上加入：

- 类
- 对象
- 字段
- 方法
- 构造方法
- `this`
- 访问控制
- 单继承
- `super`
- 方法重写
- 动态分派
- 基础方法重载
- 对象引用语义
- 对象生命周期
- 显式对象销毁
- 对象图持久化
- `import` / `include` 与访问控制的统一语义

本版本的核心原则是：

> FCL 应当拥有足够简单、明确、可预测的对象模型，而不是复制 Java、C++ 等语言复杂的类型系统。

---

# 2. Class

FCL v0.0.2 引入 `class`。

基本语法：

```fcl
class User {
}
```

类本身是一个可以被引用的语言定义。

例如：

```fcl
class User {
}

user = new User()
```

一个类可以包含：

- 字段
- 方法
- 构造方法
- `public` 成员
- `private` 成员

---

# 3. 对象创建

对象通过 `new` 创建。

```fcl
user = new User()
```

`new` 的行为：

1. 创建新的对象实例。
2. 为对象分配唯一对象身份。
3. 初始化对象字段。
4. 调用对应的 `init` 构造方法。
5. 返回对象引用。

每一次 `new` 都会产生一个新的对象。

例如：

```fcl
a = new User()
b = new User()
```

即使两个对象的内容完全一致：

```fcl
a != b
```

它们仍然是两个不同对象。

---

# 4. 字段

类中可以定义字段。

例如：

```fcl
class User {
    name = ""
    age = 0
}
```

使用：

```fcl
user = new User()

user.name = "Alice"
user.age = 18

io.println(user.name)
```

字段属于对象实例。

因此：

```fcl
a = new User()
b = new User()

a.name = "Alice"
b.name = "Bob"
```

`a.name` 与 `b.name` 相互独立。

---

# 5. 方法

类中可以定义方法。

```fcl
class User {
    name = ""

    func hello() {
        io.println("Hello")
    }
}
```

调用：

```fcl
user = new User()
user.hello()
```

方法属于对象实例。

---

# 6. this

实例方法内部可以使用 `this`。

`this` 永远指向当前正在执行方法所属的对象。

例如：

```fcl
class User {
    name = ""

    func setName(name) {
        this.name = name
    }

    func hello() {
        io.println(this.name)
    }
}
```

使用：

```fcl
user = new User()
user.setName("Alice")
user.hello()
```

输出：

```text
Alice
```

方法参数或局部变量与字段同名时，应当通过 `this` 明确访问字段。

例如：

```fcl
func setName(name) {
    this.name = name
}
```

其中：

```fcl
name
```

表示参数。

而：

```fcl
this.name
```

表示对象字段。

---

# 7. 构造方法 init

FCL 不单独引入与类同名的构造函数。

统一使用：

```fcl
init
```

例如：

```fcl
class User {
    name = ""

    init(name) {
        this.name = name
    }
}
```

使用：

```fcl
user = new User("Alice")
```

执行过程相当于：

```text
创建 User 对象
↓
调用 User.init("Alice")
↓
返回对象引用
```

---

# 8. 无参数构造

如果类没有显式声明 `init`，运行时视为存在默认无参数构造。

例如：

```fcl
class User {
    name = ""
}
```

可以直接：

```fcl
user = new User()
```

默认构造不会执行额外逻辑。

---

# 9. 构造方法重载

允许存在多个不同参数数量的 `init`。

例如：

```fcl
class User {
    name = ""
    age = 0

    init(name) {
        this.name = name
    }

    init(name, age) {
        this.name = name
        this.age = age
    }
}
```

以下两个调用均合法：

```fcl
a = new User("Alice")
b = new User("Bob", 18)
```

---

# 10. 方法重载

FCL v0.0.2 支持基础方法重载。

重载判断只依据：

> 方法名 + 参数数量

例如：

```fcl
class Test {
    func print(value) {
    }

    func print(a, b) {
    }
}
```

这是合法的。

调用：

```fcl
test.print(1)
test.print(1, 2)
```

分别匹配两个方法。

---

# 11. 不支持按类型重载

FCL 不采用 Java 风格的类型重载。

例如下面两个方法不能同时存在：

```fcl
func test(number) {
}

func test(string) {
}
```

因为从语言结构来看，它们都是：

```text
test / 1
```

即：

```text
方法名 = test
参数数量 = 1
```

FCL 不根据运行时参数类型选择重载。

这样可以避免动态语言中复杂且不可预测的重载规则。

---

# 12. 方法唯一标识

在运行时中，一个方法可以使用如下逻辑身份表示：

```text
类 + 方法名 + 参数数量
```

例如：

```text
User.hello/0
User.setName/1
User.setInfo/2
```

构造方法同样可以表示为：

```text
User.init/0
User.init/1
User.init/2
```

---

# 13. public

FCL v0.0.2 引入：

```fcl
public
```

例如：

```fcl
class User {
    public name = ""

    public func hello() {
    }
}
```

`public` 成员可以在类外访问。

---

# 14. private

FCL v0.0.2 引入：

```fcl
private
```

例如：

```fcl
class User {
    private password = ""

    private func validatePassword() {
    }
}
```

`private` 成员只能在定义它的类内部访问。

例如：

```fcl
class User {
    private password = ""

    func setPassword(value) {
        this.password = value
    }
}
```

允许：

```fcl
user.setPassword("123")
```

不允许：

```fcl
user.password
```

---

# 15. 默认访问级别

如果没有显式指定访问修饰符：

```fcl
func hello() {
}
```

或：

```fcl
name = ""
```

默认视为：

```fcl
public
```

因此：

```fcl
class User {
    name = ""

    func hello() {
    }
}
```

等价于：

```fcl
class User {
    public name = ""

    public func hello() {
    }
}
```

这样可以保持 FCL 的简洁性。

---

# 16. 类本身的访问控制

顶层类同样可以使用：

```fcl
public class User {
}
```

以及：

```fcl
private class InternalUser {
}
```

如果未声明：

```fcl
class User {
}
```

默认：

```fcl
public
```

---

# 17. 顶层函数访问控制

普通函数同样支持：

```fcl
public func hello() {
}
```

以及：

```fcl
private func internalHello() {
}
```

未声明访问级别：

```fcl
func hello() {
}
```

默认：

```fcl
public
```

---

# 18. 顶层变量访问控制

顶层变量也遵循相同规则。

例如：

```fcl
public version = "0.0.2"
private secret = "..."
```

未声明访问级别时默认 `public`。

---

# 19. 继承

v0.0.2 支持单继承。

语法：

```fcl
class Child extends Parent {
}
```

例如：

```fcl
class Animal {
    func speak() {
        io.println("...")
    }
}

class Cat extends Animal {
}
```

此时：

```fcl
cat = new Cat()
cat.speak()
```

可以调用从 `Animal` 继承的方法。

---

# 20. 单继承

一个类最多只能直接继承一个父类。

合法：

```fcl
class Cat extends Animal {
}
```

不支持：

```fcl
class C extends A, B {
}
```

v0.0.2 不实现多继承。

---

# 21. 字段继承

子类继承父类字段。

例如：

```fcl
class Animal {
    name = ""
}

class Cat extends Animal {
}
```

则：

```fcl
cat = new Cat()
cat.name = "Mimi"
```

合法。

---

# 22. 方法继承

子类继承父类的可继承方法。

例如：

```fcl
class Animal {
    func sleep() {
        io.println("sleep")
    }
}

class Cat extends Animal {
}
```

调用：

```fcl
cat.sleep()
```

会执行：

```fcl
Animal.sleep()
```

---

# 23. private 与继承

父类中的 `private` 成员不会变成子类可以直接访问的成员。

例如：

```fcl
class Parent {
    private value = 1
}

class Child extends Parent {
    func test() {
        io.println(this.value)
    }
}
```

这里的：

```fcl
this.value
```

应当报访问权限错误。

`private` 只属于声明它的类。

---

# 24. 方法重写

子类可以声明与父类相同：

```text
方法名 + 参数数量
```

的方法，从而重写父类方法。

例如：

```fcl
class Animal {
    func speak() {
        io.println("animal")
    }
}

class Cat extends Animal {
    func speak() {
        io.println("meow")
    }
}
```

调用：

```fcl
cat = new Cat()
cat.speak()
```

输出：

```text
meow
```

---

# 25. 动态分派

FCL 的实例方法调用按照对象的真实类型进行。

例如：

```fcl
class Animal {
    func speak() {
        io.println("animal")
    }
}

class Cat extends Animal {
    func speak() {
        io.println("meow")
    }
}
```

如果变量最终引用的是一个 `Cat` 对象，则：

```fcl
animal = new Cat()
animal.speak()
```

依然执行：

```fcl
Cat.speak()
```

而不是：

```fcl
Animal.speak()
```

FCL 没有必要要求变量提前声明静态类型，因此方法分派本质上按照运行时对象类型决定。

---

# 26. super

子类可以通过：

```fcl
super
```

访问父类实现。

例如：

```fcl
class Animal {
    func speak() {
        io.println("animal")
    }
}

class Cat extends Animal {
    func speak() {
        super.speak()
        io.println("meow")
    }
}
```

输出：

```text
animal
meow
```

---

# 27. super 构造调用

子类构造方法可以调用父类构造方法：

```fcl
super(...)
```

例如：

```fcl
class Animal {
    name = ""

    init(name) {
        this.name = name
    }
}

class Cat extends Animal {
    init(name) {
        super(name)
    }
}
```

使用：

```fcl
cat = new Cat("Mimi")
```

---

# 28. super 的含义

在：

```fcl
super.method(...)
```

中，方法查找从当前类的直接父类开始，而不是从当前对象的实际类型重新动态查找。

因此 `super` 可以用于调用被当前类重写的方法。

---

# 29. 对象引用语义

FCL 对象采用引用语义。

例如：

```fcl
a = new User()
b = a
```

这里不会复制对象。

而是：

```text
a ─┐
   ├──> User Object
b ─┘
```

因此：

```fcl
b.name = "Alice"
```

随后：

```fcl
io.println(a.name)
```

也会得到：

```text
Alice
```

---

# 30. 对象不会因为赋值而自动复制

以下代码：

```fcl
b = a
```

只复制对象引用。

不会：

- 创建新对象
- 深复制字段
- 复制对象树
- 产生新的对象 ID

如果需要复制对象，未来可以通过库函数或专门机制实现。

这不属于 v0.0.2 的基础语言语义。

---

# 31. 对象身份

每个对象都有稳定的内部身份。

运行时可以使用：

```text
Object ID
```

表示。

Object ID 的具体二进制或数据库格式属于实现细节，不属于语言层公开语法。

但必须满足：

- 同一对象引用始终指向同一对象身份。
- 不同 `new` 创建不同身份。
- 持久化与恢复后对象身份关系不能被破坏。
- 共享引用必须能够被正确恢复。
- 循环引用必须能够被正确恢复。

---

# 32. 共享引用

例如：

```fcl
a = new User()
b = a
```

持久化之后不能恢复为：

```text
a -> User A
b -> User B
```

必须仍然是：

```text
a ─┐
   ├──> 同一个 User
b ─┘
```

这是 v0.0.2 对象持久化的重要要求。

---

# 33. 循环引用

对象允许循环引用。

例如：

```fcl
class Node {
    next = null
}

a = new Node()
b = new Node()

a.next = b
b.next = a
```

形成：

```text
A -> B
↑    ↓
└────┘
```

持久化系统必须能够保存这种结构。

恢复后循环关系不能丢失。

---

# 34. 对象图持久化

FCL 当前拥有进程与运行时状态持久化能力。

v0.0.2 加入对象以后，运行时状态不能继续简单地把每个变量值独立序列化。

需要保存：

```text
变量
 ↓
引用
 ↓
对象
 ↓
字段
 ↓
其他对象
```

即：

> 对象图。

对象图序列化必须区分：

- 普通值
- `null`
- 对象引用
- 对象实体

---

# 35. 对象恢复

恢复运行时状态时：

第一阶段创建对象实体。

第二阶段恢复字段。

第三阶段恢复对象之间的引用关系。

例如：

```text
Object 100
name = "A"
friend -> Object 101

Object 101
name = "B"
friend -> Object 100
```

恢复后仍应形成：

```text
100 <-> 101
```

不能递归复制对象。

---

# 36. 作用域生命周期

FCL 中普通局部变量继续遵循词法作用域。

例如：

```fcl
func test() {
    a = 1
}
```

函数结束之后：

```fcl
a
```

不再存在。

对象引用同样遵循该规则。

例如：

```fcl
func test() {
    user = new User()
}
```

函数结束后局部变量 `user` 被删除。

---

# 37. 离开作用域

离开作用域意味着：

> 该作用域中的变量绑定被销毁。

例如：

```fcl
{
    a = 1
    user = new User()
}
```

离开作用域后：

```text
a 变量消失
user 变量消失
```

但这里必须区分：

> 删除引用 ≠ 强制销毁对象。

如果还有其他引用指向对象，对象依然存在。

例如：

```fcl
outer = null

{
    user = new User()
    outer = user
}
```

离开作用域后：

```fcl
outer
```

仍然引用该对象。

因此对象不能因为局部变量 `user` 消失而被销毁。

---

# 38. FCL 不提供自动对象垃圾回收语义

FCL v0.0.2 不把：

> “没有任何变量引用对象时立即销毁对象”

定义成语言保证。

原因是这种行为会引入完整的自动垃圾回收语义，并使对象生命周期变得更加隐式。

FCL 更倾向于：

- 作用域自动清理变量
- 对象生命周期明确
- 对象可被显式销毁
- 运行时持久化系统能够明确记录对象状态

---

# 39. JVM GC 与 FCL 对象生命周期

FCL 运行在 JVM 上。

因此必须区分两个不同概念。

## FCL 层

负责：

- 对象身份
- 对象状态
- 引用关系
- 对象是否有效
- 对象是否已销毁

## JVM 层

负责：

- Java Heap
- Java 对象实际内存回收
- GC

即使 FCL 对象已经：

```text
destroyed
```

Java JVM 可能暂时仍然保存对应内部对象。

这不影响语言语义。

对于 FCL 来说，它已经不存在。

---

# 40. Memory.destroy

v0.0.2 提供显式销毁对象的能力。

建议接口：

```fcl
Memory.destroy(object)
```

例如：

```fcl
user = new User()

Memory.destroy(user)
```

执行之后该 FCL 对象进入销毁状态。

---

# 41. destroy 的语义

假设：

```fcl
a = new User()
b = a
```

然后：

```fcl
Memory.destroy(a)
```

因为：

```text
a
b
```

实际上都指向同一个对象，因此该对象整体被销毁。

之后：

```fcl
a.name
```

和：

```fcl
b.name
```

都应失败。

即：

> destroy 销毁对象，而不是销毁某个变量。

---

# 42. 悬空引用

对象被显式销毁后，原本指向它的引用可以暂时存在。

例如：

```fcl
a = new User()
b = a

Memory.destroy(a)
```

此时：

```text
a -> destroyed object
b -> destroyed object
```

任何试图访问对象的操作：

```fcl
a.name
b.hello()
```

均应产生明确运行时错误。

例如：

```text
Object has been destroyed
```

不允许静默返回 `null`。

---

# 43. destroy 不递归销毁字段对象

例如：

```fcl
class User {
    address = null
}

address = new Address()
user = new User()

user.address = address
```

然后：

```fcl
Memory.destroy(user)
```

只销毁：

```text
user 对象
```

不自动销毁：

```text
address 对象
```

因为：

```text
对象引用关系 ≠ 所有权关系
```

FCL v0.0.2 不引入所有权系统。

---

# 44. 删除字段引用

如果：

```fcl
user.address = null
```

只是删除：

```text
User -> Address
```

之间的引用。

不会销毁 `Address`。

同理：

```fcl
Memory.destroy(user.address)
```

才是显式销毁被引用的对象。

---

# 45. destroy null

对于：

```fcl
Memory.destroy(null)
```

建议定义为空操作。

这样可以方便资源清理代码。

但实现必须保持一致，不能在不同运行环境中表现不同。

---

# 46. 重复 destroy

对于：

```fcl
Memory.destroy(object)
Memory.destroy(object)
```

第二次调用建议保持幂等：

```text
不再次销毁
不改变其他状态
```

可以：

- 静默成功

或：

- 返回 false

但不建议因为重复释放直接导致运行时崩溃。

---

# 47. import

FCL 的 `import` 用于加载定义。

基本原则：

> import 导入定义，但不执行目标文件的顶层代码。

例如文件：

```fcl
public func hello() {
    io.println("hello")
}

io.println("loaded")
```

执行：

```fcl
import "test.fcl"
```

应当导入：

```fcl
hello()
```

但不执行：

```fcl
io.println("loaded")
```

---

# 48. include

`include` 同样加载文件中的定义。

但同时执行目标文件的顶层代码。

例如：

```fcl
include "test.fcl"
```

会：

1. 加载可见定义。
2. 执行顶层代码。

---

# 49. import 与 include

两者核心区别：

| 行为 | import | include |
|---|---:|---:|
| 加载 public 定义 | 是 | 是 |
| 执行顶层代码 | 否 | 是 |
| 加载 private 定义到调用文件 | 否 | 否 |

可以简化为：

```text
import  = 加载定义
include = 加载定义 + 执行文件
```

---

# 50. import 与 private

例如：

```fcl
public func hello() {
}

private func internal() {
}
```

另一个文件：

```fcl
import "test.fcl"
```

只能访问：

```fcl
hello()
```

不能访问：

```fcl
internal()
```

---

# 51. include 与 private

`include` 同样不能将 private 定义暴露给调用者。

例如：

```fcl
private func internal() {
}
```

不能因为：

```fcl
include "test.fcl"
```

而变成外部可调用函数。

`private` 的可见性不能被 `include` 绕过。

---

# 52. 含顶层 private 定义的文件

按照当前设计：

> 含有顶层 private 声明的文件允许 `import`，但不允许 `include`。

例如：

```fcl
private secret = 1

public func getSecret() {
    return secret
}
```

允许：

```fcl
import "module.fcl"
```

外部只能使用：

```fcl
getSecret()
```

但：

```fcl
include "module.fcl"
```

应当拒绝。

原因是 `include` 会执行并合并顶层执行环境，而 private 顶层状态必须拥有明确的文件级封装边界。

---

# 53. 类与 import

例如：

```fcl
public class User {
}
```

可以被：

```fcl
import "user.fcl"
```

导入。

随后：

```fcl
user = new User()
```

合法。

---

# 54. private class

例如：

```fcl
private class InternalUser {
}
```

不能被其他文件直接引用。

即使导入文件：

```fcl
import "user.fcl"
```

也不能：

```fcl
new InternalUser()
```

---

# 55. public class 中的 private 成员

类本身可以是公开的，但拥有私有成员。

例如：

```fcl
public class User {
    private password = ""

    public func setPassword(value) {
        this.password = value
    }
}
```

其他文件可以：

```fcl
user = new User()
user.setPassword("123")
```

不能：

```fcl
user.password
```

---

# 56. 运行时错误

v0.0.2 新增对象系统后，应至少具有以下错误类型或等价错误：

```text
Undefined class
Undefined field
Undefined method
Invalid constructor
Private member access
Private definition access
Invalid super access
Parent class not found
Inheritance cycle
Method overload conflict
Object destroyed
Invalid object access
```

错误信息需要明确指出：

- 类名
- 成员名
- 参数数量
- 出错位置

例如：

```text
error: Undefined method User.print/2 at 10:4
```

而不是只返回：

```text
error
```

---

# 57. 继承循环

以下代码必须拒绝：

```fcl
class A extends B {
}

class B extends A {
}
```

同样不能：

```fcl
class A extends A {
}
```

类加载阶段必须检测继承循环。

---

# 58. 重复方法

同一个类中不能声明两个：

```text
方法名相同
参数数量相同
```

的方法。

例如：

```fcl
func test(a) {
}

func test(b) {
}
```

应当产生定义冲突。

因为它们都是：

```text
test/1
```

---

# 59. 字段与方法名称

字段：

```fcl
user.name
```

方法：

```fcl
user.name()
```

语法上可以区分。

是否允许同一个类同时存在：

```fcl
name
func name()
```

建议 v0.0.2 允许。

因为访问方式天然不同。

但运行时内部必须分别维护：

```text
field namespace
method namespace
```

---

# 60. null

对象变量可以为：

```fcl
null
```

例如：

```fcl
user = null
```

但：

```fcl
user.name
```

必须报错。

例如：

```text
Cannot access member of null
```

---

# 61. 对象比较

对象比较应依据对象身份。

例如：

```fcl
a = new User()
b = a
c = new User()
```

应满足：

```fcl
a == b
```

为：

```text
true
```

而：

```fcl
a == c
```

为：

```text
false
```

即使：

```fcl
a.name = "A"
c.name = "A"
```

它们仍是不同对象。

---

# 62. 对象作为参数

对象可以直接作为函数或方法参数。

例如：

```fcl
func rename(user, name) {
    user.name = name
}
```

调用：

```fcl
user = new User()
rename(user, "Alice")
```

传入的是对象引用。

因此函数内部修改对象会影响原对象。

---

# 63. 对象作为返回值

函数可以返回对象。

例如：

```fcl
func createUser() {
    return new User()
}
```

调用：

```fcl
user = createUser()
```

对象身份保持不变。

不会因为跨越函数边界而复制。

---

# 64. 对象作为字段

字段可以引用其他对象。

例如：

```fcl
class User {
    friend = null
}
```

使用：

```fcl
a = new User()
b = new User()

a.friend = b
```

形成：

```text
a -> b
```

这也是对象图持久化必须支持的基础情况。

---

# 65. 对象作为集合元素

如果当前 FCL 集合类型支持任意值，则对象同样可以作为集合元素。

例如：

```fcl
users = [new User(), new User()]
```

集合保存对象引用，而不是对象副本。

---

# 66. 进程持久化

FCL 进程挂起、等待输入或被系统恢复时，其作用域中的对象必须完整保存。

例如：

```fcl
user = new User("Alice")
io.read()
```

进程在 `io.read()` 等待期间被持久化。

恢复后：

```fcl
user.name
```

仍应为：

```text
Alice
```

---

# 67. 崩溃恢复

如果 CilExec 发生异常退出，而对象状态此前已经进入持久化检查点，则恢复之后应保持检查点时的对象图。

不能出现：

- 对象字段恢复一半
- 引用变成新对象
- 循环引用断裂
- 已销毁对象重新复活
- Object ID 冲突

---

# 68. 已销毁对象持久化

如果对象在持久化前已经：

```fcl
Memory.destroy(object)
```

运行时必须保存其销毁状态，或者从有效对象表中移除并保留足够信息用于识别悬空引用。

恢复以后：

```fcl
oldReference.method()
```

仍必须报：

```text
Object has been destroyed
```

不能重新创建该对象。

---

# 69. 对象与进程隔离

默认情况下，对象属于其所在运行时/进程的状态。

不同进程不能因为内部 Object ID 相同而自动共享对象。

因此内部对象身份至少在：

```text
进程上下文
```

中唯一。

如果未来需要跨进程对象，则应通过 IPC 或专门共享对象模型设计。

不属于 v0.0.2。

---

# 70. 与 IPC 的关系

v0.0.2 不要求 IPC 自动共享活对象。

对象通过 IPC 发送时，具体选择：

- 序列化值
- 复制对象
- 发送对象引用句柄

属于未来设计。

本版本不扩大对象语义到跨进程共享内存。

---

# 71. 与文件系统的关系

FCL 对象不是文件。

对象生命周期与 VFS 文件生命周期彼此独立。

例如：

```fcl
Memory.destroy(object)
```

不能自动删除该对象字段中保存路径所对应的文件。

同样：

```fcl
file.delete(path)
```

不能自动销毁引用该路径的对象。

---

# 72. 与数据库持久化的关系

数据库只是运行时实现对象持久化的介质。

语言层不应暴露：

- SQL 表结构
- PostgreSQL 行 ID
- Java 对象地址

FCL Object ID 与数据库主键可以在实现上相关，但不能把数据库实现直接暴露成语言语义。

---

# 73. 编译器修改

v0.0.2 编译器至少需要新增 AST / 字节码表示：

```text
ClassDefinition
FieldDefinition
MethodDefinition
ConstructorDefinition
AccessModifier
Extends
NewObject
MemberGet
MemberSet
MethodCall
ThisReference
SuperReference
SuperCall
DestroyObject
```

具体类名可根据现有代码风格调整。

---

# 74. 类元数据

运行时应维护类定义信息。

概念上至少包括：

```text
ClassDefinition {
    name
    access
    parent
    fields
    methods
    constructors
}
```

方法表按照：

```text
name + parameterCount
```

索引。

---

# 75. 对象运行时结构

概念上对象至少包含：

```text
ObjectInstance {
    objectId
    classId
    fields
    destroyed
}
```

其中：

```text
fields
```

保存字段值或其他对象引用。

---

# 76. 方法查找

调用：

```fcl
object.test(a, b)
```

运行时查找：

```text
test/2
```

流程：

```text
当前对象真实类
↓
寻找 test/2
↓
未找到
↓
父类
↓
继续寻找
↓
直到根类
```

如果始终未找到：

```text
Undefined method
```

---

# 77. private 方法查找

即使运行时能够找到一个 `private` 方法，也必须检查调用上下文。

例如：

```fcl
object.privateMethod()
```

调用者不属于定义该方法的类时，应拒绝。

因此：

> 可查找到 ≠ 可访问。

---

# 78. super 方法查找

调用：

```fcl
super.test()
```

查找必须从：

```text
当前声明类的父类
```

开始。

不能从当前对象实际类重新开始。

否则会再次调用当前重写方法并形成无限递归。

---

# 79. 构造流程

创建：

```fcl
new Child(...)
```

建议执行顺序：

```text
创建对象身份
↓
建立完整字段布局
↓
初始化父类字段默认值
↓
初始化子类字段默认值
↓
执行 Child.init(...)
↓
返回对象
```

如果 `Child.init()` 显式调用：

```fcl
super(...)
```

则执行对应父类构造逻辑。

---

# 80. 构造失败

如果构造过程中发生异常：

```fcl
new User(...)
```

不得返回一个半初始化对象。

该对象应进入不可访问状态，并从当前有效对象集合中移除。

如果对象已经进入持久化系统，则事务必须保证不会留下部分初始化状态。

---

# 81. this 的限制

`this` 只能在实例方法或实例构造方法中使用。

顶层代码：

```fcl
io.println(this)
```

应报错。

普通顶层函数：

```fcl
func test() {
    io.println(this)
}
```

同样应报错。

---

# 82. super 的限制

`super` 只能在：

```text
存在父类的类
```

内部使用。

以下情况应报错：

```fcl
class A {
    func test() {
        super.test()
    }
}
```

因为 `A` 没有父类。

---

# 83. 静态成员

v0.0.2 不加入静态字段和静态方法。

即不加入：

```fcl
static
```

原因：

- 不是基础对象模型必须能力。
- 会引入类初始化时序。
- 会增加持久化语义复杂度。
- 可以以后单独设计。

---

# 84. interface

v0.0.2 不加入：

```text
interface
```

继承系统只实现基础单继承。

---

# 85. abstract

v0.0.2 不加入：

```text
abstract class
abstract method
```

未来如有实际需求再设计。

---

# 86. 泛型

v0.0.2 不加入泛型。

不实现：

```text
List<T>
Map<K, V>
```

FCL 当前保持动态类型语言的简单模型。

---

# 87. 运算符重载

v0.0.2 不加入运算符重载。

不能通过类定义修改：

```text
+
-
*
/
==
```

等运算符的基础语义。

---

# 88. 析构函数

v0.0.2 不加入：

```text
destructor
finalizer
__del__
```

等自动析构机制。

需要销毁对象时使用：

```fcl
Memory.destroy(...)
```

这样生命周期更加明确。

---

# 89. 自动垃圾回收语言语义

v0.0.2 不保证：

```text
最后一个引用消失
↓
对象立即销毁
```

这种行为。

JVM 可以进行自己的物理内存 GC。

但 FCL 语言层不会把 JVM GC 当成对象生命周期的一部分。

---

# 90. 多继承

v0.0.2 不支持：

```text
class C extends A, B
```

未来如果需要代码复用，应优先考虑组合、接口或 trait 类机制，而不是直接增加传统多继承。

---

# 91. 反射

v0.0.2 不加入完整反射系统。

例如暂不提供：

```text
获取所有类
获取所有字段
动态调用方法
修改 private 字段
```

等能力。

---

# 92. 对象复制

v0.0.2 不定义语言级：

```text
clone
copy
deepCopy
```

如果需要，可以作为标准库函数单独实现。

---

# 93. 兼容原则

v0.0.2 应尽可能保持 v0.0.1 程序兼容。

已有：

```fcl
func hello() {
}
```

仍然合法。

已有：

```fcl
a = 1
```

仍然合法。

没有使用新语法的程序不应因为对象系统加入而改变行为。

---

# 94. 关键字

v0.0.2 可能新增以下关键字：

```text
class
new
public
private
extends
this
super
init
```

如果 `init` 实现为特殊方法名而非语法关键字，则可以不进入 Lexer 关键字集合。

`Memory.destroy` 属于命名空间函数，不需要成为关键字。

---

# 95. 示例：基础类

```fcl
class User {
    name = ""
    age = 0

    init(name, age) {
        this.name = name
        this.age = age
    }

    func hello() {
        io.println("Hello " + this.name)
    }
}

user = new User("Alice", 18)
user.hello()
```

---

# 96. 示例：访问控制

```fcl
class User {
    public name = ""
    private password = ""

    public func setPassword(password) {
        this.password = password
    }

    private func checkPassword(password) {
        return this.password == password
    }
}
```

允许：

```fcl
user.name
user.setPassword("123")
```

禁止：

```fcl
user.password
user.checkPassword("123")
```

---

# 97. 示例：继承

```fcl
class Animal {
    name = ""

    init(name) {
        this.name = name
    }

    func speak() {
        io.println("...")
    }
}

class Cat extends Animal {
    init(name) {
        super(name)
    }

    func speak() {
        io.println("Meow")
    }
}

cat = new Cat("Mimi")

io.println(cat.name)
cat.speak()
```

输出：

```text
Mimi
Meow
```

---

# 98. 示例：super

```fcl
class Parent {
    func hello() {
        io.println("Parent")
    }
}

class Child extends Parent {
    func hello() {
        super.hello()
        io.println("Child")
    }
}

child = new Child()
child.hello()
```

输出：

```text
Parent
Child
```

---

# 99. 示例：引用语义

```fcl
class Box {
    value = 0
}

a = new Box()
b = a

b.value = 100

io.println(a.value)
```

输出：

```text
100
```

---

# 100. 示例：显式销毁

```fcl
class User {
    name = ""
}

a = new User()
b = a

Memory.destroy(a)

io.println(b.name)
```

应产生类似：

```text
error: Object has been destroyed
```

---

# 101. 示例：循环引用

```fcl
class Node {
    value = 0
    next = null
}

a = new Node()
b = new Node()

a.value = 1
b.value = 2

a.next = b
b.next = a
```

该对象图必须能够正常：

- 持久化
- 恢复
- 访问

而不能因为递归序列化导致栈溢出。

---

# 102. v0.0.2 测试要求

至少应增加以下测试。

## Class

- 可以声明空类。
- 可以创建对象。
- 每次 `new` 创建不同对象。
- 未定义类无法实例化。

## Field

- 字段默认值正确。
- 字段可以读取。
- 字段可以修改。
- 不同实例字段互不影响。

## Method

- 方法可以调用。
- `this` 正确。
- 参数正确传递。
- 返回值正确。

## Constructor

- 默认构造正常。
- `init/0` 正常。
- `init/1` 正常。
- 多构造重载正常。
- 参数数量错误时报错。

## Access

- public 字段可以访问。
- private 字段无法外部访问。
- private 方法无法外部调用。
- private 成员可以在类内部访问。
- 默认权限为 public。

## Inheritance

- 字段继承。
- 方法继承。
- 方法重写。
- 多层继承。
- `super.method()`。
- `super(...)`。
- 循环继承检测。

## Overload

- 同名不同参数数量合法。
- 同名同参数数量非法。
- 正确选择 `/0`、`/1`、`/2`。

## Reference

- 赋值保持同一对象。
- 修改共享对象可被另一个引用观察。
- 对象作为参数保持引用。
- 对象作为返回值保持引用。

## Destroy

- 对象可以销毁。
- 销毁后字段访问失败。
- 销毁后方法调用失败。
- 所有共享引用同时失效。
- 重复 destroy 行为稳定。
- destroy 不递归销毁字段对象。

## Persistence

- 单对象保存恢复。
- 对象字段保存恢复。
- 多对象保存恢复。
- 共享引用保存恢复。
- 循环引用保存恢复。
- 多层对象图保存恢复。
- 已销毁对象状态保存恢复。

## Import

- public class 可以 import。
- private class 无法外部访问。
- public function 可以 import。
- private function 无法外部访问。
- import 不执行顶层代码。
- include 执行顶层代码。
- include 不暴露 private。
- 带顶层 private 定义的文件不能 include。

---

# 103. v0.0.2 完成标准

只有以下能力全部完成，才应认为 v0.0.2 对象系统完成：

- [ ] `class`
- [ ] `new`
- [ ] 字段
- [ ] 方法
- [ ] `this`
- [ ] `init`
- [ ] 构造方法重载
- [ ] 方法重载
- [ ] `public`
- [ ] `private`
- [ ] 默认 public
- [ ] `extends`
- [ ] 单继承
- [ ] 字段继承
- [ ] 方法继承
- [ ] 方法重写
- [ ] 动态分派
- [ ] `super.method()`
- [ ] `super(...)`
- [ ] 引用语义
- [ ] 对象身份
- [ ] 共享引用
- [ ] 循环引用
- [ ] `Memory.destroy`
- [ ] 悬空引用检测
- [ ] 对象持久化
- [ ] 对象图恢复
- [ ] 崩溃恢复兼容
- [ ] `import` 权限处理
- [ ] `include` 权限处理
- [ ] 对应单元测试
- [ ] 对应集成测试

---

# 104. 不属于 v0.0.2

以下功能明确延后：

- 静态成员
- 接口
- 抽象类
- 泛型
- trait
- mixin
- 多继承
- 运算符重载
- 完整反射
- 自动析构函数
- 语言级自动垃圾回收
- 所有权系统
- 借用检查
- 对象深复制
- 跨进程共享对象
- 完整类型注解系统

这些功能不应阻塞 v0.0.2 发布。

---

# 105. 设计原则总结

FCL v0.0.2 的对象系统遵循以下原则。

### 简单

避免加入 Java/C++ 风格过度复杂的对象系统。

### 明确

对象赋值就是引用。

对象销毁就是销毁。

作用域结束就是变量离开作用域。

不同概念不互相混淆。

### 可持久化

对象不是临时 JVM 状态。

它必须成为 CilExec 可恢复运行时状态的一部分。

### 可预测

不根据复杂类型规则进行方法选择。

方法重载只使用：

```text
方法名 + 参数数量
```

### 生命周期可控

FCL 不把对象什么时候“突然被 GC”暴露成语言语义。

显式对象销毁使用：

```fcl
Memory.destroy(object)
```

### 保持语言规模

v0.0.2 的目标是完成：

> 基础对象模型。

而不是一次性完成一门成熟语言未来所有可能拥有的特性。

---

# 106. v0.0.2 的定位

v0.0.1 可以理解为：

> FCL 已经可以执行程序。

v0.0.2 则意味着：

> FCL 开始真正拥有自己的对象模型。

它不是一次简单增加几个语法糖的更新。

因为加入对象以后：

```text
Parser
Compiler
Bytecode
Runtime
Scope
Persistence
Recovery
Import
Access Control
Method Dispatch
Memory Model
```

都会发生变化。

因此 v0.0.2 是 FCL 语言结构上的第一个大型更新。

最终目标不是让 FCL 变成 Java，也不是让它变成 Python。

而是形成一套属于 FCL 自己的、足够小但语义完整的对象系统。

---

# 107. try / catch

FCL v0.0.2 加入基础异常捕获机制：

```fcl
try {
    statements
} catch (e) {
    statements
}
```

语义：

1. 执行 `try` 中的代码。
2. 如果正常完成，则跳过 `catch`。
3. 如果发生可捕获的 FCL 异常，则立即中止 `try` 中剩余语句。
4. Runtime 寻找与该 `try` 对应的 `catch`。
5. 将异常值绑定到 `catch` 参数。
6. 执行 `catch`。
7. `catch` 正常结束后继续执行后续代码。

例如：

```fcl
try {
    io.println("A")
    somethingWrong()
    io.println("B")
} catch (e) {
    io.println("C")
}

io.println("D")
```

如果 `somethingWrong()` 抛出异常，则输出：

```text
A
C
D
```

`B` 不会执行。

---

# 108. catch 参数

`catch` 后的参数表示捕获到的异常。

例如：

```fcl
try {
    user.name
} catch (e) {
    io.println(e.message)
}
```

`e` 只是变量名。

因此也允许：

```fcl
catch (error) {
    io.println(error.message)
}
```

或其他合法变量名。

---

# 109. catch 变量作用域

异常变量只存在于对应的 `catch` 作用域中。

例如：

```fcl
try {
    somethingWrong()
} catch (e) {
    io.println(e.message)
}

io.println(e)
```

最后一行应产生：

```text
Undefined variable: e
```

但异常可以主动保存到外部变量：

```fcl
error = null

try {
    somethingWrong()
} catch (e) {
    error = e
}

io.println(error.message)
```

这是合法的。

---

# 110. 异常传播

异常不仅可以被当前函数中的 `catch` 捕获，也可以沿函数调用链向上传播。

例如：

```fcl
func c() {
    somethingWrong()
}

func b() {
    c()
}

func a() {
    b()
}

try {
    a()
} catch (e) {
    io.println(e.message)
}
```

异常传播过程：

```text
somethingWrong()
       ↓
      c()
       ↓
      b()
       ↓
      a()
       ↓
外层 try/catch
```

如果当前函数没有能够处理异常的 `catch`，异常继续向调用者传播。

---

# 111. 未捕获异常

如果异常一直传播到进程顶层仍然没有被捕获，则成为：

```text
Uncaught Exception
```

Runtime 按当前 FCL 进程失败语义处理。

例如：

```text
error in PID 12:
UndefinedMethod: Undefined method User.print/2
    at ...
```

此时当前进程进入失败状态。

---

# 112. catch 内再次发生异常

`catch` 本身不是安全区域。

例如：

```fcl
try {
    a()
} catch (e) {
    b()
}
```

如果：

```text
a() 抛出异常
```

则进入 `catch`。

如果随后：

```text
b() 再次抛出异常
```

这个新的异常必须继续向外传播。

它不能重新被当前正在执行的这个 `catch` 捕获。

例如：

```fcl
try {
    try {
        a()
    } catch (e) {
        b()
    }
} catch (e) {
    io.println("outer")
}
```

如果 `a()` 和 `b()` 都产生异常，则最终由外层 `catch` 捕获 `b()` 的异常。

---

# 113. 可捕获异常与系统故障

`try/catch` 只能处理：

> FCL 程序级异常。

例如：

```text
NullAccess
UndefinedFunction
UndefinedMethod
InvalidConstructor
PrivateAccess
ObjectDestroyed
InvalidArgument
FileNotFound
```

等运行时程序错误。

`try/catch` 不允许捕获：

* 进程被强制终止
* Runtime 自身崩溃
* JVM Fatal Error
* CilExec Kernel Failure
* 系统 Shutdown
* 无法继续保证运行时一致性的数据库故障
* 其他系统级终止事件

因此实现层必须区分：

```text
FCL Exception
```

和：

```text
Runtime / Kernel Failure
```

只有前者可以进入 FCL `catch`。

---

# 114. Exception

`catch` 捕获到的值属于 Runtime 原生异常类型：

```text
Exception
```

第一版结构定义为：

```text
Exception {
    readonly type
    readonly message
    readonly stack
}
```

其中：

```text
type: String
message: String
stack: List<StackFrame>
```

---

# 115. Exception 不是普通 FCL 对象

虽然异常在语法上可以：

```fcl
e.type
e.message
e.stack
```

看起来类似普通对象，但它在 Runtime 内部不应实现成普通：

```text
ObjectInstance
```

而应实现成独立的 Runtime Value。

概念上的 FCL Value 体系：

```text
FCL Value
├── Number
├── String
├── Boolean
├── Null
├── List
├── Map
├── ObjectReference
└── Exception
```

这样异常机制不会依赖普通对象创建、构造方法、方法分派和对象生命周期系统。

---

# 116. ExceptionValue

Runtime 内部建议使用类似：

```text
ExceptionValue {
    type
    message
    stack
}
```

的结构保存异常。

具体 Java 类型名称属于实现细节。

例如可以叫：

```text
FclExceptionValue
```

或其他符合现有项目风格的名称。

语言规范只保证其行为，不规定 Java 类名。

---

# 117. e.type

`type` 是异常的稳定机器可读类型。

例如：

```text
NullAccess
UndefinedFunction
UndefinedMethod
InvalidConstructor
PrivateAccess
ObjectDestroyed
FileNotFound
InvalidArgument
```

例如：

```fcl
try {
    file.read(path)
} catch (e) {
    if (e.type == "FileNotFound") {
        io.println("File does not exist")
    }
}
```

必须遵循原则：

```text
type = 给程序判断
message = 给人阅读
```

程序不应该依赖异常消息字符串判断异常种类。

---

# 118. e.message

`message` 保存人类可读错误描述。

例如：

```text
Cannot access member 'name' of null
```

或者：

```text
Undefined method User.print/2
```

使用：

```fcl
io.println(e.message)
```

可以直接显示错误。

错误消息未来允许调整措辞，因此：

```fcl
if (e.message == "...")
```

不属于稳定 API。

---

# 119. e.stack

`stack` 保存异常产生时的 FCL 调用栈。

不能只保存已经格式化好的字符串。

内部应该保存结构化 StackFrame。

例如：

```text
[
    {
        function: "User.test",
        source: "/app/test.fcl",
        line: 15,
        column: 8
    },
    {
        function: "<main>",
        source: "/app/main.fcl",
        line: 7,
        column: 4
    }
]
```

这样 FCL 可以：

```fcl
io.println(e.stack[0].function)
io.println(e.stack[0].source)
io.println(e.stack[0].line)
```

---

# 120. StackFrame

`Exception.stack` 中的每一个元素属于只读 StackFrame 值。

第一版结构：

```text
StackFrame {
    readonly function
    readonly source
    readonly line
    readonly column
}
```

类型：

```text
function: String
source: String
line: Integer
column: Integer
```

StackFrame 同样属于 Runtime 原生只读值，而不是普通 FCL ObjectInstance。

---

# 121. 异常栈生成时间

异常栈必须在：

> 异常真正发生时

立即生成。

不能等到进入 `catch` 时才生成。

例如：

```fcl
func c() {
    somethingWrong()
}

func b() {
    c()
}

func a() {
    b()
}

try {
    a()
} catch (e) {
}
```

`e.stack` 必须包含类似：

```text
somethingWrong
c
b
a
```

而不是只包含：

```text
catch
```

---

# 122. 同一个异常值传播

异常沿调用链传播时，应传播同一个：

```text
ExceptionValue
```

不能每经过一个函数就重新创建新的异常。

流程：

```text
异常发生
↓
创建 ExceptionValue
↓
捕获 type
↓
捕获 message
↓
捕获 stack
↓
沿调用栈传播同一个 ExceptionValue
↓
找到 catch
↓
绑定给 catch 参数
```

这样可以保证异常原始位置和调用栈保持不变。

---

# 123. Exception 不可变

Exception 必须是：

```text
immutable
```

即：

```fcl
e.type
e.message
e.stack
```

允许读取。

但是：

```fcl
e.type = "NothingWrong"
```

```fcl
e.message = "No error"
```

```fcl
e.stack = []
```

必须拒绝。

异常表示已经发生的事实，不允许程序修改异常历史。

---

# 124. StackFrame 不可变

同样：

```fcl
e.stack[0].line = 100
```

不得修改原始调用栈。

StackFrame 必须是只读值。

---

# 125. Exception 生命周期

Exception 不属于普通对象生命周期。

因此：

```fcl
Memory.destroy(e)
```

应当拒绝。

Exception：

* 不拥有普通 Object ID
* 不进入普通对象 destroy 生命周期
* 不允许显式销毁
* 不依赖 JVM 对应 ObjectInstance 的对象图管理

但是 Exception 可以像普通值一样：

* 保存到变量
* 传给函数
* 从函数返回
* 放入 List
* 放入 Map
* 被进程持久化
* 被进程恢复

---

# 126. Exception 持久化

如果进程在异常值已经被保存到变量后进入持久化：

```fcl
lastError = null

try {
    somethingWrong()
} catch (e) {
    lastError = e
}

process.wait(...)
```

恢复后：

```fcl
lastError.type
lastError.message
lastError.stack
```

必须保持与持久化前一致。

因此 `ExceptionValue` 和 `StackFrameValue` 必须进入 FCL Continuation / Runtime Value 的序列化体系。

---

# 127. Exception 与对象图的区别

普通对象：

```text
ObjectReference
↓
Object ID
↓
ObjectInstance
```

Exception：

```text
ExceptionValue
↓
直接保存异常数据
```

Exception 不需要普通对象身份，也不参与共享对象生命周期。

如果同一个 ExceptionValue 被多个变量引用，Runtime 可以保持同值语义，但语言层不需要暴露 Exception Object ID。

---

# 128. Exception 格式化

直接：

```fcl
io.println(e)
```

时 Runtime 应提供默认可读格式。

例如：

```text
UndefinedMethod: Undefined method User.print/2
    at User.test (/app/test.fcl:15:8)
    at <main> (/app/main.fcl:7:4)
```

这只是展示形式。

程序需要结构化处理时应使用：

```fcl
e.type
e.message
e.stack
```

---

# 129. 第一版 Exception 最小接口

v0.0.2 正式保证：

```text
Exception {
    readonly type: String
    readonly message: String
    readonly stack: List<StackFrame>
}
```

以及：

```text
StackFrame {
    readonly function: String
    readonly source: String
    readonly line: Integer
    readonly column: Integer
}
```

这是 v0.0.2 的稳定异常接口。

---

# 130. 未来可扩展字段

未来版本可以在保持兼容的情况下增加：

```text
cause
data
code
location
```

其中：

### cause

表示导致当前异常的底层异常。

### data

保存某类异常特有的结构化信息。

例如：

```text
UndefinedMethod.data = {
    class: "User",
    method: "print",
    argumentCount: 2
}
```

### code

提供更加稳定或数字化的错误代码。

### location

提供异常发生位置的快捷访问。

它通常可以等价于：

```fcl
e.stack[0]
```

这些字段不属于 v0.0.2 必须实现内容。

---

# 131. throw

`throw` 与自定义异常属于异常系统下一阶段。

v0.0.2 可以暂时只实现 Runtime 异常的捕获：

```fcl
try {
} catch (e) {
}
```

未来可加入：

```fcl
throw ...
```

以及：

```fcl
throw e
```

重新传播已有异常。

如果未来支持：

```fcl
throw e
```

必须传播原有 ExceptionValue，不重新生成 stack。

---

# 132. finally

v0.0.2 暂不要求实现：

```fcl
finally {
}
```

未来可以支持：

```fcl
try {
} catch (e) {
} finally {
}
```

`finally` 无论：

* try 正常结束
* try 发生异常
* catch 正常结束

都执行。

但该语义会影响：

* return
* break
* continue
* throw
* 进程挂起
* Runtime continuation

因此不应阻塞 v0.0.2。

---

# 133. 类型化 catch

v0.0.2 暂不支持：

```fcl
catch (FileNotFound e) {
}
```

以及多个：

```fcl
catch (FileNotFound e) {
}

catch (PermissionDenied e) {
}
```

第一版统一：

```fcl
catch (e) {
}
```

如果程序需要区分：

```fcl
catch (e) {
    if (e.type == "FileNotFound") {
        ...
    }
}
```

这样可以避免 v0.0.2 立即引入完整异常类型继承系统。

---

# 134. try/catch 与进程挂起

由于 FCL 支持 Continuation 和进程持久化，因此：

```fcl
try {
    process.wait(...)
} catch (e) {
    ...
}
```

如果进程在 `try` 内部挂起，恢复后必须仍然知道：

```text
当前执行位置属于哪个 try
对应哪个 catch
catch 跳转位置
当前异常处理上下文
```

因此 try/catch 信息必须成为 continuation 状态的一部分，不能只存在于 Java 调用栈中。

---

# 135. try/catch 与恢复

例如：

```fcl
try {
    a()
    process.wait(...)
    b()
} catch (e) {
    io.println(e.message)
}
```

进程在：

```fcl
process.wait(...)
```

处持久化。

恢复以后 `b()` 如果产生异常，仍然必须进入原来的：

```fcl
catch (e)
```

不能因为发生了一次 suspend / restore 就丢失异常处理区域。

---

# 136. try/catch 与 FCL Continuation

编译器或运行时必须保存异常处理区域信息。

概念上可以表示：

```text
ExceptionHandler {
    tryStart
    tryEnd
    catchTarget
    catchVariable
}
```

具体实现可以：

* 编译成异常处理表
* 编译成显式 handler stack
* 保存到 FclContinuation

具体结构属于实现细节。

必须保证语义：

```text
异常发生位置
↓
寻找最内层有效 handler
↓
恢复作用域
↓
绑定 ExceptionValue
↓
跳转 catch
```

---

# 137. 作用域展开

异常传播过程中，已经离开的作用域必须正常清理。

例如：

```fcl
try {
    {
        a = new User()
        somethingWrong()
    }
} catch (e) {
}
```

发生异常以后：

```text
内部 block scope
```

必须被退出。

其中局部变量绑定按正常作用域规则销毁。

异常不能因为跳转到 `catch` 而留下已经失效的局部作用域。

---

# 138. try 内 return

如果：

```fcl
func test() {
    try {
        return 1
    } catch (e) {
        return 2
    }
}
```

`try` 没有异常，则函数直接返回：

```text
1
```

不会执行 `catch`。

---

# 139. catch 内 return

允许：

```fcl
func test() {
    try {
        somethingWrong()
    } catch (e) {
        return null
    }
}
```

发生异常后，`catch` 可以像普通 block 一样执行：

```text
return
```

---

# 140. 嵌套 try/catch

FCL 必须支持：

```fcl
try {
    try {
        somethingWrong()
    } catch (e) {
        ...
    }
} catch (e) {
    ...
}
```

异常总是优先寻找：

> 当前执行位置对应的最内层有效 catch。

只有内层未处理或内层处理过程中产生新异常，才继续向外传播。

---

# 141. v0.0.2 异常测试要求

至少增加以下测试。

## 基础 try/catch

* try 正常完成时 catch 不执行。
* try 抛异常时 catch 执行。
* 异常发生后 try 剩余语句不执行。
* catch 完成后继续后续代码。

## Exception

* `e.type` 正确。
* `e.message` 正确。
* `e.stack` 正确。
* stack frame 顺序正确。
* source 正确。
* line 正确。
* column 正确。
* function 正确。

## Scope

* `e` 只存在于 catch 内。
* catch 可以保存异常到外部变量。
* 异常传播时旧 scope 被正确清理。

## Propagation

* 同函数捕获。
* 跨一层函数传播。
* 跨多层函数传播。
* 无 catch 时成为未捕获异常。
* 内层 catch 优先于外层 catch。
* catch 中的新异常传播到外层。

## Immutability

以下必须失败：

```fcl
e.type = ...
e.message = ...
e.stack = ...
e.stack[0].line = ...
```

## Memory

以下必须失败：

```fcl
Memory.destroy(e)
```

## Persistence

* Exception 可以持久化。
* Exception 恢复后 type 不变。
* message 不变。
* stack 不变。
* try 中 suspend 后恢复仍可捕获异常。
* 多层 try 中 suspend 后恢复 handler 顺序正确。

## System Failure

确认：

* FCL Exception 可以捕获。
* Kernel Failure 不可捕获。
* Process Kill 不可捕获。
* Runtime Fatal Failure 不可捕获。

---

# 142. v0.0.2 完成标准补充

原 v0.0.2 完成清单追加：

* [ ] `try`
* [ ] `catch`
* [ ] catch 局部异常变量
* [ ] 异常传播
* [ ] 嵌套 try/catch
* [ ] 未捕获异常处理
* [ ] Runtime `ExceptionValue`
* [ ] `e.type`
* [ ] `e.message`
* [ ] `e.stack`
* [ ] `StackFrame`
* [ ] Exception 不可变
* [ ] StackFrame 不可变
* [ ] Exception 禁止 `Memory.destroy`
* [ ] Exception 持久化
* [ ] try/catch continuation 持久化
* [ ] suspend / restore 后异常捕获正确
* [ ] FCL Exception 与 Kernel Failure 明确区分
* [ ] 对应单元测试
* [ ] 对应集成测试

---

# 143. v0.0.2 异常系统最终定位

v0.0.2 的异常系统保持最小设计：

```text
try
catch
ExceptionValue
```

不在本版本强制实现：

```text
throw
finally
typed catch
异常继承
自定义 Exception class
checked exception
throws 声明
```

其核心目标是：

> 允许 FCL 程序安全处理 Runtime 产生的程序级异常，同时保持异常本身可检查、可传播、可持久化，并且不会让程序捕获 CilExec 内核级故障。

最终基础模型：

```text
Runtime 产生异常
        ↓
创建不可变 ExceptionValue
        ↓
记录 type / message / stack
        ↓
沿 FCL 调用栈传播
        ↓
寻找最内层 try/catch
      ↙          ↘
   找到           没找到
    ↓               ↓
绑定 e          Uncaught Exception
    ↓               ↓
执行 catch       进程失败
```

这样，`try/catch` 就成为 FCL 控制流体系的一部分，而不是简单套在 Java Exception 外面的一层语法糖。
