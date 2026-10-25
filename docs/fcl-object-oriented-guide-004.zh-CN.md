# FCL v0.0.4 面向对象入门

本文面向第一次接触编程的人，说明当前 FCL v0.0.4 中的“类”和“对象”是什么、怎样写，以及数据在复制和重启后会怎样。这里描述的是当前已实现的行为；完整函数清单见 [FCL 函数参考](fcl-function-reference.md)。

## 文档适用版本

| 层级 | 当前值 |
| --- | --- |
| CilExec 产品版本 | `0.0.4` |
| FCL 语言版本 | `fcl-0.0.4` |
| FCL 程序与 Continuation 格式 | `3` |
| 数据库迁移 | `V004`（Schema 最高版本 `4`） |

## 1. 先说结论

FCL 是一门有类、对象、字段、构造方法、实例方法、封装和继承的面向对象语言。

它最重要的一条规则是：**名字就是它当前代表的内容，不是装着内容的盒子，也不是指向另一个东西的遥控器。**

```fcl
a = new Counter(10)
b = a
b.increment()

io.println(a.value) // 10
io.println(b.value) // 11
```

`a` 和 `b` 的类型相同、开始时内容相同，但它们是两个独立对象。修改 `b` 不会悄悄改变 `a`。

可以把类理解成“饼干模具”，把对象理解成“按模具做出的饼干”：

```text
Counter（模具、规则）
       │
       ├── new Counter(10) ──> a：value 是 10
       │
       └── a 赋给 b       ──> b：value 是 10 的独立副本
```

类定义方法和字段的规则；每个对象保存自己的字段内容，并按这些规则运行方法。

## 2. 最小的完整例子

下面是一个能计数的对象：

```fcl
class Counter {
    value = 0

    init(initial) {
        this.value = initial
    }

    func increment() {
        this.value++
        return this.value
    }
}

counter = new Counter(10)
counter.increment()
io.println(counter.value) // 11
```

逐句解释：

1. `class Counter { ... }` 定义一种叫 `Counter` 的对象。
2. `value = 0` 是字段：每个 Counter 都有一个自己的 `value`，默认从 `0` 开始。
3. `init(initial)` 是构造方法：创建对象时运行，接收传入的初始数字。
4. `this.value` 的意思是“当前这个对象自己的 value”。
5. `func increment()` 是方法：对象能做的一件事。
6. `new Counter(10)` 创建一个全新的对象，并把 `10` 传给 `init` 中的 `initial` 参数。

## 3. 类、对象、名字各是什么

| 名词 | 通俗解释 | 例子 |
| --- | --- | --- |
| 类（class） | 一种对象的说明书 | `Counter` |
| 对象（object） | 按说明书创建出来的具体东西 | `new Counter(10)` |
| 名字 | 程序中直接代表内容的名称 | `counter` |
| 字段（field） | 保存在对象内部的数据 | `counter.value` |
| 方法（method） | 对象能执行的操作 | `counter.increment()` |
| 构造方法（init） | 对象刚创建时执行的初始化操作 | `init(initial)` |

一个 FCL 文件可以在顶层声明多个类：

```fcl
class Counter { value = 0 }
class User { name = "" }

counter = new Counter()
user = new User()
```

类必须写在文件的顶层，不能写进函数、`if` 或 `while` 的大括号里。

## 4. 字段：对象自己的数据

字段写在类的大括号中，形式是“名字 = 默认值”。

```fcl
class User {
    name = "匿名用户"
    age = 0
}

user = new User()
io.println(user.name) // 匿名用户

user.name = "Alice"
user.age = 18
io.println(user.name) // Alice
```

每次 `new User()` 都会得到自己的一组字段：

```fcl
a = new User()
b = new User()
a.name = "Alice"
b.name = "Bob"
```

此时 `a.name` 是 `Alice`，`b.name` 是 `Bob`。字段不会在不同对象之间自动共用。

字段默认值也可以是数组或 Map；它们同样属于各自对象的独立状态：

```fcl
class Notebook {
    pages = []
    settings = {theme: "light"}
}
```

## 5. 创建对象和构造方法

创建对象固定使用 `new`：

```fcl
user = new User()
```

创建时发生的事情是：先准备所有字段的默认值，再调用参数数量匹配的 `init`，最后得到对象。

```fcl
class User {
    name = ""

    init(name) {
        this.name = name
    }
}

user = new User("Ada")
io.println(user.name) // Ada
```

这里 `"Ada"` 不会自动存到哪里；它按位置传给 `init(name)` 中名为 `name` 的参数。`this.name` 才是对象字段，所以写 `this.name = name` 是把参数的内容放进字段。

### 构造参数数量必须匹配

```fcl
class Point {
    x = 0
    y = 0

    init(x, y) {
        this.x = x
        this.y = y
    }
}

point = new Point(3, 5) // 正确：两个参数
```

`new Point(3)` 或 `new Point(3, 5, 7)` 会报“没有匹配的构造方法”。如果类没有任何 `init`，可以使用无参数的 `new ClassName()`。

一个类也可以有多个 `init`，但只能通过**参数数量**区分：

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

a = new User("Ada")
b = new User("Bob", 18)
```

## 6. 方法：对象能做什么

方法用 `func` 定义，调用时把方法写在对象名后面：

```fcl
class Lamp {
    isOn = false

    func turnOn() {
        this.isOn = true
    }

    func status() {
        if this.isOn {
            return "已打开"
        }
        return "已关闭"
    }
}

lamp = new Lamp()
lamp.turnOn()
io.println(lamp.status()) // 已打开
```

方法中的 `this` 就是“正在接收这次调用的那个对象”。因此 `lamp.turnOn()` 修改的是 `lamp`；`otherLamp.turnOn()` 修改的是 `otherLamp`。

方法可以接收参数、计算并 `return` 一个结果：

```fcl
class Calculator {
    base = 0

    init(base) { this.base = base }

    func add(number) {
        return this.base + number
    }
}

calculator = new Calculator(10)
answer = calculator.add(5)
io.println(answer) // 15
```

## 7. `++` 与 `--`

数字字段可以用后缀 `++` 加一、`--` 减一：

```fcl
class Counter {
    value = 0
    func increment() { this.value++ }
    func decrement() { this.value-- }
}
```

它们也可用于普通数字名字、数组元素和 Map 元素。当前只有后缀写法，没有 `++count` 这种前缀写法。

## 8. 最重要的规则：赋值就是复制

FCL 中赋值的规则是“复制后各自独立”：

```fcl
a = new Counter(10)
b = a
```

从用户角度看，这一步已经完成了复制：

```text
a：Counter(value = 10)
b：Counter(value = 10)
```

之后：

```fcl
b.value = 20
```

结果是：

```text
a.value 是 10
b.value 是 20
```

这条规则不仅用于对象，也用于数组和 Map：

```fcl
numbers = [1, 2, 3]
other = numbers
other[0] = 99

// numbers[0] 是 1；other[0] 是 99
```

同样，传给函数的参数、从函数 `return` 回来的对象、放入数组或 Map 的对象，都会成为逻辑独立的对象。运行时可以在内部延迟真正的复制以节省资源，但这是透明优化：FCL 程序永远看不到“共享对象”“对象 ID”或“引用计数”。

因此，FCL 中没有默认的别名、悬空对象、手动释放对象或自动垃圾回收的用户概念。

## 9. 需要一起变化时：`link`

正常的 `copy = user` 会复制对象，之后两个对象互不影响。有时你确实希望两个名字始终代表同一个东西；这时明确写：

```fcl
user = new User()
currentUser link user
```

它的意思不是“两个对象碰巧指向同一块内存”，而是：`currentUser` 直接跟随名字 `user`。`user` 仍是原来的对象；`currentUser` 是一个显式的跟随名字。

```fcl
count = 1
displayedCount link count
displayedCount++

// count 是 2；displayedCount 也是 2

count = 100
// displayedCount 也是 100
```

`link` 只能写成中缀形式，并且两边必须是完整、独立的名字：

```fcl
b link a // 正确：b 跟随 a
```

`linkb`、`b linka`、`linkb a` 都不是 `link`。其中像 `linkb` 这样的普通名字仍可按普通名字规则使用，但它绝不会产生跟随关系。

`link` 对数字、数组、Map 和对象都有效：

```fcl
items = [1, 2]
visibleItems link items
visibleItems[0] = 9
// items[0] 也是 9
```

删除规则也很直接：`link` 后，两个名字代表同一个对象。删除其中任意一个名字时，
这个对象以及所有直接或间接跟随它的名字都会一起不再存在。

```fcl
a = new User()
b link a
memory.destroy(b)

// a 不存在；b 也不存在
```

这不是悬空对象或内存错误，只是 `b` 所跟随的名字已经不存在。`link` 关系会随 Continuation 一起保存到 PostgreSQL，恢复后仍然成立；不会保存内存地址、JVM 引用或对象 ID。

## 10. 方法会修改谁

虽然赋值会复制对象，方法仍会修改接收者所在的位置：

```fcl
a = new Counter(1)
b = a

b.increment()
```

`b.increment()` 完成后，`b.value` 变成 `2`，`a.value` 仍是 `1`。

这也适用于嵌套对象：调用 `order.customer.rename("Ada")` 时，修改会写回 `order.customer` 这个对象，而不会影响此前从它复制出的其他对象。

## 11. 读取、打印和比较对象

读取字段使用点号：

```fcl
io.println(counter.value)
```

直接打印对象会显示它的类型轮廓，而不是内部的对象编号：

```fcl
io.println(counter)
// Counter{...}
```

因此，想查看具体数据时，应打印字段，或自行定义一个返回文本的方法：

```fcl
class Counter {
    value = 0
    func description() { return "Counter(value=" + this.value + ")" }
}
```

对象相等比较看类型和字段内容，不看某个隐藏编号。FCL 不把对象身份当作语言功能。

## 12. 封装：public 与 private

封装的目的，是让对象把不应由外部随意改动的内部数据保护起来。

字段和方法默认是 `public`，即类外可以使用。使用 `private` 可限制为只有声明它的类内部可访问：

```fcl
class Account {
    private balance = 0

    init(initial) {
        this.balance = initial
    }

    func deposit(amount) {
        this.balance = this.balance + amount
    }

    func getBalance() {
        return this.balance
    }
}

account = new Account(100)
account.deposit(50)
io.println(account.getBalance()) // 150
// account.balance 会因 private 而被拒绝
```

`private` 成员不能从类外读取、写入或调用；子类也不能直接访问父类声明的 `private` 成员。

类本身也可标记为 `public class` 或 `private class`。未写修饰词时是 `public`；`private class` 不会作为模块的公开内容提供给 `import` 的使用者。

## 13. 继承：在已有类上继续扩展

一个类可以用 `extends` 继承一个父类。FCL 当前支持**单继承**：每个类最多直接继承一个父类。

```fcl
class Animal {
    name = ""

    init(name) {
        this.name = name
    }

    func label() {
        return "动物：" + this.name
    }
}

class Cat extends Animal {
    init(name) {
        super(name)
    }

    func label() {
        return super.label() + "（猫）"
    }
}

cat = new Cat("Mimi")
io.println(cat.label()) // 动物：Mimi（猫）
```

子类会继承父类的字段和可访问方法。`super(...)` 在子类的构造方法中调用父类构造方法；`super.method(...)` 调用父类版本的方法。

当子类定义与父类相同“方法名 + 参数数量”的方法时，子类版本会覆盖父类版本。这叫方法重写。

FCL 不支持多继承：`class C extends A, B` 不是合法语法。

## 14. 方法重载

同一个类可以有同名但参数数量不同的方法：

```fcl
class Message {
    func show(text) {
        io.println(text)
    }

    func show(title, text) {
        io.println(title + ": " + text)
    }
}
```

`message.show("你好")` 选择第一个，`message.show("提示", "你好")` 选择第二个。

FCL **不按参数类型**选择方法。两个都是一个参数的 `show(number)` 和 `show(text)` 不能同时存在，因为它们都属于 `show/1`。

构造方法 `init` 也遵守相同的“名称 + 参数数量”规则。

## 15. import 与类

`import` 用于导入已安装的 FCL 包；`include` 用于在编译前把 VFS 中的源文件内容包含进来。它们只能出现在文件顶层。

对类而言，重要规则是：导入方只能使用被导出模块的公开内容；`private class` 不可作为导入方的公开类使用，`private` 字段和方法也不能被外部代码直接访问。

继承一个来自其他模块的公开类时，先通过该模块的导入方式让它处于可见范围；不是“自动找到”任意文件里的类。

## 16. `memory.list` 与 `memory.destroy`

`memory.list()` 可以查看当前 FCL 作用域中的名字和函数信息。为了兼容 API，它的返回结果仍使用 `variables` 这个字段名；它列出的是当前能看到的名字，不是一个全局“对象堆”。

```fcl
counter = new Counter(10)
io.println(memory.list())
```

FCL 只有一个删除 API：`memory.destroy(target)`。

```fcl
counter = new Counter(10)
copy = counter

memory.destroy(counter)
// counter 已不存在
// copy 仍是完整的 Counter 值，value 为 10
```

它也可删除数组或 Map 的一个元素：

```fcl
items = ["a", "b", "c"]
memory.destroy(items[1]) // 删除 "b"

settings = {theme: "dark"}
memory.destroy(settings["theme"])
```

它返回 `true` 表示确实删除了内容，`false` 表示名字或 Map 键原本不存在。它只接受一个名字，或“名字加方括号索引”；不能写 `memory.destroy(counter.value)`，也没有 `memory.unset`、`memory.delete` 或 `memory.release`。

删除一个名字不是“杀死一个对象实体”：因为 `copy = counter` 已经创建了独立副本，删除 `counter` 不会让 `copy` 失效。

## 17. 持久化和重启恢复

CilExec 的 FCL 进程会在每个已提交的执行片段后，把名字所代表的内容、对象字段、调用位置等完整运行状态保存到 PostgreSQL。对象保存的是：

```text
对象类型 + 每个字段的内容 + 字段中嵌套的数组 / Map / 对象
```

不会保存运行时内部的优化信息，例如对象 ID、内存地址、共享标记或引用计数。恢复后，程序继续看到的仍是独立对象。

例如：

```fcl
a = new Counter(10)
b = a
b.increment()
```

即使 Runtime 或 Docker 容器在后续已提交状态后被强制终止并恢复，语义仍是：`a.value` 为 `10`，`b.value` 为 `11`。不会因为重启把它们变成共享对象。

一个尚未提交的执行片段会回滚或被重新执行；所以外部世界操作（网络、命令等）使用 CilExec 的效果日志机制，不能依赖普通内存行为。对象字段本身则属于可恢复的 FCL 状态。

## 18. 当前 v0.0.4 已有和未有的内容

已实现：

- 顶层 `class`、对象创建 `new`、字段、`init`、实例方法和 `this`
- `public` / `private` 封装
- 单继承、`super(...)`、`super.method(...)`、重写和动态分派
- 按“方法名 + 参数数量”的基础重载
- 对象、数组、Map 的复制后独立规则，以及透明的写时复制优化
- 显式 `b link a` 名字跟随关系，及其持久化和恢复
- `++` / `--`、`memory.list()` 和唯一的 `memory.destroy(...)`
- 对象的 PostgreSQL 持久化与崩溃恢复

当前没有：

- 默认共享对象引用、对象 ID、悬空对象、用户可见引用计数
- 手工销毁对象、`memory.unset`、`memory.delete` 或自动把“无引用对象”作为语言概念删除
- 多继承、接口、抽象类、静态成员、泛型、反射
- 按参数类型的重载、前缀 `++count` / `--count`

## 19. 一张速查表

| 想做什么 | 写法 |
| --- | --- |
| 定义类 | `class User { name = "" }` |
| 创建对象 | `user = new User()` |
| 创建时传参 | `user = new User("Ada")` |
| 读写字段 | `user.name` / `user.name = "Ada"` |
| 调用方法 | `user.hello()` |
| 在方法里访问当前对象 | `this.name` |
| 复制对象 | `copy = user` |
| 让一个名字跟随另一个名字 | `currentUser link user` |
| 删除一个名字 | `memory.destroy(user)` |
| 删除数组 / Map 项 | `memory.destroy(items[0])` / `memory.destroy(config["key"])` |
| 继承 | `class Cat extends Animal { ... }` |
| 调父类构造方法 | `super(name)` |
| 调父类方法 | `super.label()` |
| 保护内部字段 | `private password = ""` |

如果只记住一句话：**FCL 的对象是真正有行为和封装的对象；`user` 就是这个对象本身，把它赋给 `copy` 后，`copy` 是另一个独立对象。**

## 20. 一键验收脚本

仓库提供 [fcl-oop-smoke-test.fcl](examples/fcl-oop-smoke-test.fcl)，会逐项验证构造方法、
字段和封装、普通复制、继承和 `super`、重写、重载、`link`、数组复制与 `memory.destroy`。

在项目根目录执行下面的一条命令即可。它会提示输入 `local` 的密码，把脚本写入
`local` 的 VFS 根目录 `/fcl-oop-smoke-test.fcl`，随后立即执行：

```bash
./tools/OopSmokeTest.sh
```

脚本已经写入后，也可以在任意 `local` FCL 终端重复执行：

```fcl
process.exec("/fcl-oop-smoke-test.fcl")
```

全部通过时，最后一行是：

```text
FCL OOP SMOKE TEST: ALL PASSED
```
