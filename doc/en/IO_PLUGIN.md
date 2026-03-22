# IO Plugin Documentation

The IO plugin provides console input/output functionality for CilExec scripts.

## Overview

The IO plugin implements the `FunctionProvider` interface and provides the following features:
- Console output (without newline, with newline, formatted)
- Console input (with prompt)
- Error stream output

## Function List

### Output Functions

#### `print(...values)`
Prints content to the console without a newline.

**Parameters:**
- `...values` - Values to print (varargs, optional)

**Return Value:**
- `["SUCCESS"]` - Print successful

**Example:**
```fcl
print("Hello")
print("World")
# Output: HelloWorld
```

---

#### `println(...values)`
Prints content to the console with a newline.

**Parameters:**
- `...values` - Values to print (varargs, optional)

**Return Value:**
- `["SUCCESS"]` - Print successful

**Example:**
```fcl
println("Hello")
println("World")
# Output:
# Hello
# World
```

---

#### `printf(format, ...args)`
Formatted print to the console.

**Parameters:**
- `format` - Format string (Java format)
- `...args` - Format arguments (varargs)

**Return Value:**
- `["SUCCESS"]` - Print successful
- `["ERROR", "INVALID_ARGUMENTS"]` - Invalid arguments
- `["ERROR", "ARGUMENT_MUST_BE_STRING"]` - First argument must be a string
- `["ERROR", "FORMAT_ERROR"]` - Format error

**Example:**
```fcl
name = "Alice"
age = 25
printf("Name: %s, Age: %d\n", name, age)
# Output: Name: Alice, Age: 25
```

**Supported Format Specifiers:**
- `%s` - String
- `%d` - Integer
- `%f` - Floating point
- `%n` - Newline
- `%%` - Percent sign

---

### Input Functions

#### `input(prompt)`
Reads a line from the console, with the prompt on the same line.

**Parameters:**
- `prompt` - Prompt string (optional)

**Return Value:**
- `String` - User input (without newline)
- `["ERROR", "IO_ERROR"]` - IO error

**Example:**
```fcl
name = input("Enter your name: ")
# Display: Enter your name: _ (cursor here)
# User input: Alice
# name = "Alice"
```

---

#### `inputLine(prompt)`
Reads a line from the console, with the prompt on a new line.

**Parameters:**
- `prompt` - Prompt string (optional)

**Return Value:**
- `String` - User input (without newline)
- `["ERROR", "IO_ERROR"]` - IO error

**Example:**
```fcl
name = inputLine("Enter your name:")
# Display:
# Enter your name:
# _ (cursor here)
```

---

### Error Output

#### `printErr(...values)`
Prints content to the standard error stream.

**Parameters:**
- `...values` - Values to print (varargs, optional)

**Return Value:**
- `["SUCCESS"]` - Print successful

**Example:**
```fcl
printErr("Error: File not found")
# Output to stderr: Error: File not found
```

---

## Complete Examples

### Example 1: Simple Greeting Program

```fcl
# Greeting program
println("=== Greeting Program ===")

print("Please enter your name: ")
name = input()

print("Please enter your age: ")
ageStr = input()
age = int(ageStr)

printf("Hello, %s! You are %d years old.\n", name, age)
println("Program finished")
```

**Sample Run:**
```
=== Greeting Program ===
Please enter your name: Alice
Please enter your age: 25
Hello, Alice! You are 25 years old.
Program finished
```

---

### Example 2: User Login Simulation

```fcl
# User login simulation
println("=== User Login ===")

username = input("Username: ")
password = input("Password: ")

println("")
println("Verifying...")

if username == "admin" {
    println("Login successful!")
} else {
    printErr("Error: Invalid username or password")
}
```

---

### Example 3: Calculator

```fcl
# Simple calculator
println("=== Simple Calculator ===")

num1Str = input("Enter first number: ")
num2Str = input("Enter second number: ")

num1 = int(num1Str)
num2 = int(num2Str)

sum = num1 + num2
diff = num1 - num2
product = num1 * num2

println("")
println("Results:")
printf("  %d + %d = %d\n", num1, num2, sum)
printf("  %d - %d = %d\n", num1, num2, diff)
printf("  %d * %d = %d\n", num1, num2, product)
```

---

## Error Codes

| Error Code | Description |
|------------|-------------|
| `INVALID_ARGUMENTS` | Insufficient arguments or wrong type |
| `ARGUMENT_MUST_BE_STRING` | Argument must be a string |
| `FORMAT_ERROR` | Format string error |
| `IO_ERROR` | Input/output error |

---

## Notes

1. **Input Blocking**: The `input` and `inputLine` functions block and wait for user input until the user presses Enter.

2. **Empty Input Handling**: If the user presses Enter directly, the input functions return an empty string `""`.

3. **Type Conversion**: Input functions always return strings. Use `int()` or other functions for type conversion.

4. **Concurrency Safety**: The IO plugin uses standard input/output streams. Be aware of synchronization issues in multi-process environments.

5. **Logging**: IO errors are automatically logged to the system log.

---

## Implementation File

- [IOFunctionProvider.java](../../src/main/java/com/follarce/plugin/IOFunctionProvider.java)
