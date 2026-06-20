# Plugin Development Guide

CilExec uses a plugin system to extend script functions. Developers can add new script functions by implementing the `FunctionProvider` interface.

## Quick Start

### Method 1: Add to Existing Provider (Recommended)

If the new function relates to existing functionality, modify the existing Provider directly:

#### 1. Modify `UtilFunctionProvider.java`

```java
public class UtilFunctionProvider implements FunctionProvider {
    
    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            // ... existing functions ...
            
            // Add new function
            case "math.add":
                if (args.length < 2) return error("INVALID_ARGUMENTS");
                return ((Number) args[0]).intValue() + ((Number) args[1]).intValue();
        }
        return null;
    }
    
    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[]{
            // ... existing function info ...
            
            // Add new function info
            new FunctionInfo("math.add", "Add two numbers", 
                new String[]{"a: int", "b: int"}, "int", "Math")
        };
    }
}
```

#### 2. Done!

No other modifications needed; immediately usable in scripts:

```fcl
sum = math.add(10, 20)  # sum = 30
```

### Method 2: Create New Provider

If the new functionality is relatively independent, consider creating a new Provider:

#### 1. Create `src/main/java/com/follarce/plugin/RandomFunctionProvider.java`

```java
package com.follarce.plugin;

/**
 * Random number function provider
 */
public class RandomFunctionProvider implements FunctionProvider {

    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            case "random":
                return handleRandom(args);
            default:
                return null;
        }
    }

    private Object handleRandom(Object[] args) {
        int min = 0;
        int max = 100;

        if (args.length >= 1 && args[0] instanceof Number) {
            max = ((Number) args[0]).intValue();
        }

        if (args.length >= 2 && args[1] instanceof Number) {
            min = ((Number) args[0]).intValue();
            max = ((Number) args[1]).intValue();
        }

        if (min >= max) {
            return new String[]{"ERROR", "INVALID_RANGE"};
        }

        return (int) (Math.random() * (max - min) + min);
    }

    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[]{
            new FunctionInfo(
                "random",
                "Generate random number",
                new String[]{"min: int (optional)", "max: int (optional)"},
                "int",
                "Random"
            )
        };
    }

    @Override
    public String getProviderName() {
        return "RandomFunctionProvider";
    }
}
```

#### 2. Register in `Main.java`

```java
private static void registerFunctionProviders() {
    // ... existing Providers ...
    
    // Register new Provider
    FunctionRegistry.register(new RandomFunctionProvider());
}
```

#### 3. Done!

Use in scripts:

```fcl
num1 = random()         # 0-99
num2 = random(10)       # 0-9
num3 = random(5, 15)    # 5-14
```

## Development Standards

### Function Naming Convention

```
file.read           # File operations
process.fork        # Process operations
user.create         # User operations
math.add            # Mathematical functions
str.upper           # String functions
random.randint      # Random number functions
```

### Return Value Standards

```java
// Success - return data directly
return result;

// Error - return standard error format
return new String[]{"ERROR", "ERROR_CODE"};

// Common error codes
"INVALID_ARGUMENTS"         // Invalid arguments
"INSUFFICIENT_PERMISSION"   // Insufficient permission
"FILE_DOES_NOT_EXIST"       // File does not exist
"INVALID_RANGE"             // Invalid range
```

### Parameter Check Template

```java
case "myFunc":
    // Check parameter count
    if (args.length < 2) {
        return new String[]{"ERROR", "INVALID_ARGUMENTS"};
    }
    
    // Check parameter types
    if (!(args[0] instanceof String)) {
        return new String[]{"ERROR", "ARGUMENT_MUST_BE_STRING"};
    }
    if (!(args[1] instanceof Number)) {
        return new String[]{"ERROR", "ARGUMENT_MUST_BE_NUMBER"};
    }
    
    // Execute logic
    String str = (String) args[0];
    int num = ((Number) args[1]).intValue();
    return doSomething(str, num);
```

## FunctionContext Description

`FunctionContext` provides environment information during calls:

```java
public class FunctionContext {
    public int getPid();           // Get current process ID
    public int getPpid();          // Get parent process ID
    public String getCurrentUser(); // Get current user
    public boolean isLocal();      // Check if local user
}
```

Usage example:

```java
@Override
public Object call(String name, Object[] args, FunctionContext context) {
    switch (name) {
        case "getMyPid":
            return context.getPid();  // Return caller's PID
    }
    return null;
}
```

## Debugging Tips

### 1. Use Logger

```java
import com.follarce.basicUtil.Logger;

Logger.debug("Function called: " + name);
Logger.info("Operation success");
Logger.error("Error: " + e.getMessage());
```

### 2. View Registered Functions

```java
// Temporarily add in Main.java
System.out.println(FunctionRegistry.generateDocumentation());
```

### 3. Test Scripts

```fcl
# test.fcl
result = myFunc(10, 20)
expected = 30
if result == expected {
    # test passed
} else {
    # test failed
}
```

## Summary

| Operation | Effort | Use Case |
|-----------|--------|----------|
| Modify Existing Provider | 2 minutes | Add a few related functions |
| Create New Provider | 5 minutes | Add a set of new functionality |

**Core Principle: Only modify Providers, leave other code untouched!**
