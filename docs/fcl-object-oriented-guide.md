# FCL v0.0.3: Object-Oriented Programming for Beginners

This guide explains the object-oriented part of FCL as it exists in v0.0.3. It is written for
readers who have not programmed before. For the complete built-in function list, see the
[FCL function reference](fcl-function-reference.md). For the Chinese edition, see
[fcl-object-oriented-guide.zh-CN.md](fcl-object-oriented-guide.zh-CN.md).

## Versions covered by this guide

| Layer | Current value |
| --- | --- |
| CilExec product version | 0.0.3  |
| FCL language version | fcl-0.0.3 |
| FCL program and continuation format | 3 |
| Database migration | V003 (highest schema version: 3) |

## 1. The central idea

FCL is an object-oriented language with classes, objects, fields, constructors, methods,
encapsulation, and inheritance.

The most important rule is: **a name is the thing it currently represents. It is not a box
containing the thing, and it is not a hidden remote control for another thing.**

~~~fcl
a = new Counter(10)
b = a
b.increment()

io.println(a.value) // 10
io.println(b.value) // 11
~~~

a and b have the same type and start with the same contents, but they are separate objects.
Changing b does not silently change a.

A class is like a cookie cutter, and an object is one cookie made with it:

~~~text
Counter (the rules)
       |
       +-- new Counter(10) --> a: value is 10
       |
       +-- b = a           --> b: a separate Counter whose value is 10
~~~

The class defines fields and methods. Each object has its own field contents and follows those
rules when a method runs.

## 2. A smallest complete example

Here is an object that counts:

~~~fcl
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
~~~

1. class Counter defines a kind of object called Counter.
2. value = 0 is a field. Every Counter has its own value, initially 0.
3. init(initial) is the constructor. It runs while a new object is being made.
4. this.value means the value of the object currently running this method.
5. func increment() is a method: an action a Counter can perform.
6. new Counter(10) makes a fresh object. The 10 is passed to the constructor parameter named
   initial; this.value = initial then gives the object's field that number.

## 3. The words used in this guide

| Word | Plain-language meaning | Example |
| --- | --- | --- |
| class | The specification for a kind of object | Counter |
| object | One concrete thing made from a class | new Counter(10) |
| name | A program name that directly represents something | counter |
| field | Information inside an object | counter.value |
| method | An action an object can do | counter.increment() |
| constructor | Setup that runs while an object is created | init(initial) |

One FCL source file may define several classes at its top level:

~~~fcl
class Counter { value = 0 }
class User { name = "" }

counter = new Counter()
user = new User()
~~~

Classes must be declared at the file's top level. A class cannot be declared inside a function,
an if, or a while block.

## 4. Fields: each object's own information

Fields appear inside a class as name = defaultValue.

~~~fcl
class User {
    name = "Anonymous"
    age = 0
}

user = new User()
io.println(user.name) // Anonymous

user.name = "Alice"
user.age = 18
io.println(user.name) // Alice
~~~

Each new User() makes a separate set of fields:

~~~fcl
a = new User()
b = new User()
a.name = "Alice"
b.name = "Bob"
~~~

Here a.name is Alice and b.name is Bob. Fields are not automatically shared between objects.
Field defaults may also be arrays or Maps, and are likewise independent for every object:

~~~fcl
class Notebook {
    pages = []
    settings = {theme: "light"}
}
~~~

## 5. Creating objects and constructors

Use new to create an object:

~~~fcl
user = new User()
~~~

FCL prepares the default fields, runs an init with the matching number of arguments, and then
creates the finished object.

~~~fcl
class User {
    name = ""

    init(name) {
        this.name = name
    }
}

user = new User("Ada")
io.println(user.name) // Ada
~~~

"Ada" is passed, in order, to the constructor parameter named name. It is not put into a field
automatically. this.name = name explicitly gives the object's name field the constructor argument.

### Constructor argument counts must match

~~~fcl
class Point {
    x = 0
    y = 0

    init(x, y) {
        this.x = x
        this.y = y
    }
}

point = new Point(3, 5) // correct: two arguments
~~~

new Point(3) and new Point(3, 5, 7) fail because there is no constructor with that number of
arguments. If a class has no init, use new ClassName() without arguments.

A class may have more than one constructor when their argument counts differ:

~~~fcl
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

ada = new User("Ada")
bob = new User("Bob", 18)
~~~

## 6. Methods: what an object can do

Methods use func. Call one by writing it after an object name:

~~~fcl
class Lamp {
    isOn = false

    func turnOn() {
        this.isOn = true
    }

    func status() {
        if this.isOn {
            return "on"
        }
        return "off"
    }
}

lamp = new Lamp()
lamp.turnOn()
io.println(lamp.status()) // on
~~~

this is the object receiving the current method call. Therefore lamp.turnOn() changes lamp,
while otherLamp.turnOn() changes otherLamp.

Methods can receive arguments and return results:

~~~fcl
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
~~~

## 7. Increasing and decreasing a number

Numeric names, public fields, array elements, and Map elements support postfix ++ and --:

~~~fcl
class Counter {
    value = 0

    func increment() { this.value++ }
    func decrement() { this.value-- }
}
~~~

Only the postfix form exists. count++ is valid; ++count is not. An update expression has the
updated value:

~~~fcl
count = 1
after = count++ // count is 2 and after is 2
count--
~~~

## 8. Ordinary assignment makes a separate copy

Ordinary assignment means copy, then continue independently:

~~~fcl
a = new Counter(10)
b = a
~~~

From the program's point of view, the result is already:

~~~text
a: Counter(value = 10)
b: Counter(value = 10)
~~~

After b.value = 20, a.value is still 10 while b.value is 20. This is true for objects, arrays,
and Maps:

~~~fcl
numbers = [1, 2, 3]
other = numbers
other[0] = 99

// numbers[0] is 1; other[0] is 99
~~~

Arguments passed to a function, results returned from a function, and objects put into an array
or Map follow the same independent-copy rule. The Runtime may delay physical copying internally
with copy-on-write, but that is only an invisible performance optimization. FCL programs never
observe a default shared object, an object ID, or a reference count.

## 9. When two names must change together: link

copy = user makes a copy. When two names must deliberately represent the same current thing,
write a link:

~~~fcl
user = new User()
currentUser link user
~~~

This does not make an object reference or a pointer. It makes currentUser follow the name user.
user remains the original object; currentUser is an explicit linked name.

~~~fcl
count = 1
displayedCount link count
displayedCount++

// count is 2; displayedCount is also 2

count = 100
// displayedCount is also 100
~~~

The order matters: target link source. link is a standalone middle word:

~~~fcl
b link a // correct: b follows a
~~~

b linka and linkb a are not link expressions. linkb is just an ordinary name.

link works with numbers, arrays, Maps, and objects:

~~~fcl
items = [1, 2]
visibleItems link items
visibleItems[0] = 9
// items[0] is also 9
~~~

It also gives deletion one special rule. Destroying either name destroys the source name and
every name linked to it, directly or through another link:

~~~fcl
a = new User()
b link a
memory.destroy(b)

// neither a nor b exists now
~~~

This is not a dangling object or a memory error. It is the end of this explicit linked-name
group. The relationship is stored with the FCL continuation and restored after restart; it never
stores a memory address, JVM reference, internal object ID, or pointer.

## 10. A method changes its receiver after a copy

Although assignment copies an object, a method changes the object on which it is called:

~~~fcl
a = new Counter(1)
b = a

b.increment()
~~~

After the call, b.value is 2 and a.value is still 1. This includes nested objects:
order.customer.rename("Ada") changes order.customer, not another object copied from it earlier.

## 11. Reading and printing objects

Read a field with a dot:

~~~fcl
io.println(counter.value)
~~~

Printing an object directly shows its class shape, not a hidden object number or address:

~~~fcl
io.println(counter)
// Counter{...}
~~~

To show actual information, print public fields or write a method that returns text:

~~~fcl
class Counter {
    value = 0
    func description() { return "Counter(value=" + this.value + ")" }
}
~~~

Object equality compares the class and field contents, not a hidden identity number.

## 12. Encapsulation: public and private

Encapsulation lets an object protect details that callers should not change freely. Fields and
methods are public by default. Mark a field or method private to allow use only inside the class
that declared it:

~~~fcl
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
// account.balance is rejected because it is private
~~~

Private members cannot be read, written, or called outside the declaring class. A subclass also
cannot directly access a private member declared by its parent.

A class itself can be public class or private class. Unqualified classes are public. A private
class is not exported for users of an imported module.

## 13. Inheritance: extending an existing class

Use extends to make a class inherit from one parent class. FCL has single inheritance: each class
has at most one direct parent.

~~~fcl
class Person {
    name = ""

    init(name) {
        this.name = name
    }

    func label() {
        return "Person: " + this.name
    }
}

class Admin extends Person {
    init(name) {
        super(name)
    }

    func label() {
        return super.label() + " (admin)"
    }
}

user = new Admin("Ada")
io.println(user.label()) // Person: Ada (admin)
~~~

The child gets its parent's fields and accessible methods. super(...) calls a parent constructor;
super.method(...) calls the parent version of a method. If the child defines a method with the
same name and argument count, the child version overrides the parent version.

Multiple inheritance is not supported: class C extends A, B is invalid.

## 14. Method overloads

One class may have methods with the same name when they have different argument counts:

~~~fcl
class Message {
    func show(text) {
        io.println(text)
    }

    func show(title, text) {
        io.println(title + ": " + text)
    }
}
~~~

message.show("Hello") selects the first method. message.show("Notice", "Hello") selects the
second one.

FCL does not choose overloads by argument type. show(number) and show(text) cannot both exist
when they each take one argument. Constructors follow the same name-and-argument-count rule.

## 15. import, include, and classes

import brings in an installed FCL package. include places the source of a VFS file into the
current source before compilation. Both may appear only at the file's top level.

An importing program can use the imported module's public classes. A private class is not
available to importers, and private fields and methods cannot be called from outside their class.
To inherit from a public class in another module, first import that module so the parent class is
visible; FCL does not search arbitrary files automatically.

## 16. memory.list and memory.destroy

memory.list() shows the names and function information visible in the current FCL scope. Its
returned field is still called variables for API compatibility. It is not a global heap or a list
of every object that exists anywhere.

~~~fcl
counter = new Counter(10)
io.println(memory.list())
~~~

FCL has one deletion API: memory.destroy(target).

~~~fcl
counter = new Counter(10)
copy = counter

memory.destroy(counter)
// counter no longer exists; copy is still a complete Counter with value 10
~~~

It can also delete an array or Map element:

~~~fcl
items = ["a", "b", "c"]
memory.destroy(items[1]) // removes "b"

settings = {theme: "dark"}
memory.destroy(settings["theme"])
~~~

It returns true when something was removed and false for a missing name or Map key. It accepts
only a name or a name followed by an index in brackets. memory.destroy(counter.value) is not
valid. There is no memory.unset, memory.delete, or memory.release.

Destroying a normal name does not destroy a copy made by ordinary assignment. The linked-name
group rule described in section 9 is the explicit exception.

## 17. Persistence and recovery after restart

After every committed execution slice, CilExec saves the complete FCL continuation to PostgreSQL:
what names represent, object fields, the current call position, and other execution state. An
object is saved as:

~~~text
object class + contents of every field + nested arrays / Maps / objects
~~~

The database does not save temporary runtime optimizations such as copy-on-write sharing, memory
addresses, object IDs, or reference counts. After recovery, objects behave exactly as if the
Runtime had never stopped.

~~~fcl
a = new Counter(10)
b = a
b.increment()
~~~

If the Runtime or Docker container is forcibly stopped after this state is committed and then
restarted, a.value remains 10 and b.value remains 11. They do not become shared simply because
of recovery. A link relationship is deliberately saved as a name-to-name relation, so it is also
restored correctly.

An execution slice that has not committed either rolls back or may be replayed. Operations that
affect the outside world, such as network and command effects, therefore use CilExec's durable
effect journal rather than ordinary in-memory behavior.

## 18. What v0.0.3 includes and does not include

Included:

- Top-level class, new, fields, init, instance methods, and this
- public / private encapsulation
- Single inheritance, super(...), super.method(...), overriding, and dynamic dispatch
- Basic overloading by method name plus argument count
- Independent copy semantics for objects, arrays, and Maps, with transparent copy-on-write
- Explicit target link source name links, including persistence and recovery
- ++ / --, memory.list(), and the sole deletion API memory.destroy(...)
- PostgreSQL persistence and crash recovery for objects

Not included:

- Default shared object references, object IDs, dangling objects, or user-visible reference
  counting
- A general manual object-memory-management system, memory.unset, memory.delete, or automatic
  deletion as a language-level unreferenced-object concept
- Multiple inheritance, interfaces, abstract classes, static members, generics, or reflection
- Overloading by parameter type, or prefix ++count / --count

## 19. Quick reference

| Goal | Write |
| --- | --- |
| Define a class | class User { name = "" } |
| Make an object | user = new User() |
| Pass creation data | user = new User("Ada") |
| Read or write a field | user.name / user.name = "Ada" |
| Call a method | user.hello() |
| Refer to the current object in a method | this.name |
| Copy an object | copy = user |
| Make one name follow another | currentUser link user |
| Remove a name | memory.destroy(user) |
| Remove an array or Map item | memory.destroy(items[0]) / memory.destroy(config["key"]) |
| Inherit | class Cat extends Animal { ... } |
| Call a parent constructor | super(name) |
| Call a parent method | super.label() |
| Protect an internal field | private password = "" |

The one sentence to remember is: **FCL objects have their own behavior and encapsulation;
user is the object itself, and copy = user makes copy a separate object.**

## 20. One-command smoke test

The repository includes [fcl-oop-smoke-test.fcl](examples/fcl-oop-smoke-test.fcl). It checks
constructors, fields and encapsulation, ordinary copying, inheritance and super, overriding,
overloading, link, array copying, and memory.destroy.

From the repository root, run:

~~~bash
./tools/OopSmokeTest.sh
~~~

It asks for the local password, writes the script to /fcl-oop-smoke-test.fcl in that user's VFS
root, then immediately runs it. Once it is in the VFS, it can be run again from any local FCL
terminal:

~~~fcl
process.exec("/fcl-oop-smoke-test.fcl")
~~~

When every check passes, the final line is:

~~~text
FCL OOP SMOKE TEST: ALL PASSED
~~~
