# IO 插件文档

IO 插件为 CilExec 脚本提供控制台输入输出功能。

## 概述

IO 插件实现了 `FunctionProvider` 接口，提供以下功能：
- 控制台输出（不换行、换行、格式化）
- 控制台输入（带提示符）
- 错误流输出

## 函数列表

### 输出函数

#### `print(...values)`
输出内容到控制台，不换行。

**参数：**
- `...values` - 要输出的值（可变参数，可选）

**返回值：**
- `["SUCCESS"]` - 输出成功

**示例：**
```fcl
print("Hello")
print("World")
# 输出: HelloWorld
```

---

#### `println(...values)`
输出内容到控制台，并换行。

**参数：**
- `...values` - 要输出的值（可变参数，可选）

**返回值：**
- `["SUCCESS"]` - 输出成功

**示例：**
```fcl
println("Hello")
println("World")
# 输出:
# Hello
# World
```

---

#### `printf(format, ...args)`
格式化输出到控制台。

**参数：**
- `format` - 格式化字符串（Java 格式）
- `...args` - 格式化参数（可变参数）

**返回值：**
- `["SUCCESS"]` - 输出成功
- `["ERROR", "INVALID_ARGUMENTS"]` - 参数错误
- `["ERROR", "ARGUMENT_MUST_BE_STRING"]` - 第一个参数必须是字符串
- `["ERROR", "FORMAT_ERROR"]` - 格式化错误

**示例：**
```fcl
name = "Alice"
age = 25
printf("Name: %s, Age: %d\n", name, age)
# 输出: Name: Alice, Age: 25
```

**支持的格式说明符：**
- `%s` - 字符串
- `%d` - 整数
- `%f` - 浮点数
- `%n` - 换行符
- `%%` - 百分号

---

### 输入函数

#### `input(prompt)`
从控制台读取一行输入，提示符在同一行显示。

**参数：**
- `prompt` - 提示字符串（可选）

**返回值：**
- `String` - 用户输入的内容（不含换行符）
- `["ERROR", "IO_ERROR"]` - IO 错误

**示例：**
```fcl
name = input("Enter your name: ")
# 显示: Enter your name: _（光标在此）
# 用户输入: Alice
# name = "Alice"
```

---

#### `inputLine(prompt)`
从控制台读取一行输入，提示符在新行显示。

**参数：**
- `prompt` - 提示字符串（可选）

**返回值：**
- `String` - 用户输入的内容（不含换行符）
- `["ERROR", "IO_ERROR"]` - IO 错误

**示例：**
```fcl
name = inputLine("Enter your name:")
# 显示:
# Enter your name:
# _（光标在此）
```

---

### 错误输出

#### `printErr(...values)`
输出内容到标准错误流。

**参数：**
- `...values` - 要输出的值（可变参数，可选）

**返回值：**
- `["SUCCESS"]` - 输出成功

**示例：**
```fcl
printErr("Error: File not found")
# 输出到 stderr: Error: File not found
```

---

## 完整示例

### 示例 1：简单的问候程序

```fcl
# 问候程序
println("=== 问候程序 ===")

print("请输入你的名字: ")
name = input()

print("请输入你的年龄: ")
ageStr = input()
age = int(ageStr)

printf("你好, %s! 你今年 %d 岁。\n", name, age)
println("程序结束")
```

**运行示例：**
```
=== 问候程序 ===
请输入你的名字: Alice
请输入你的年龄: 25
你好, Alice! 你今年 25 岁。
程序结束
```

---

### 示例 2：用户登录模拟

```fcl
# 用户登录模拟
println("=== 用户登录 ===")

username = input("用户名: ")
password = input("密码: ")

println("")
println("正在验证...")

if username == "admin" {
    println("登录成功!")
} else {
    printErr("错误: 用户名或密码不正确")
}
```

---

### 示例 3：计算器

```fcl
# 简单计算器
println("=== 简单计算器 ===")

num1Str = input("请输入第一个数字: ")
num2Str = input("请输入第二个数字: ")

num1 = int(num1Str)
num2 = int(num2Str)

sum = num1 + num2
diff = num1 - num2
product = num1 * num2

println("")
println("计算结果:")
printf("  %d + %d = %d\n", num1, num2, sum)
printf("  %d - %d = %d\n", num1, num2, diff)
printf("  %d * %d = %d\n", num1, num2, product)
```

---

## 错误码

| 错误码 | 说明 |
|--------|------|
| `INVALID_ARGUMENTS` | 参数数量不足或类型错误 |
| `ARGUMENT_MUST_BE_STRING` | 参数必须是字符串类型 |
| `FORMAT_ERROR` | 格式化字符串错误 |
| `IO_ERROR` | 输入输出错误 |

---

## 注意事项

1. **输入阻塞**：`input` 和 `inputLine` 函数会阻塞等待用户输入，直到用户按下回车键。

2. **空输入处理**：如果用户直接按回车，输入函数返回空字符串 `""`。

3. **类型转换**：输入函数返回的都是字符串，需要使用 `int()` 等函数进行类型转换。

4. **并发安全**：IO 插件使用标准输入输出流，在多进程环境下需要注意同步问题。

5. **日志记录**：IO 错误会被自动记录到系统日志中。

---

## 实现文件

- [IOFunctionProvider.java](../../src/main/java/com/follarce/plugin/IOFunctionProvider.java)
