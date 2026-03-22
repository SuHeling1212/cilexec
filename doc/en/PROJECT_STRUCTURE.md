# Project Structure

```
src/main/java/com/follarce/
├── Main.java                 # Program entry point
├── init/                     # System initialization
│   ├── FileInit.java         # File system initialization
│   ├── ProcessInit.java      # Process system initialization
│   └── UserInit.java         # User management
├── process/                  # Process management
│   ├── ProcessRunner.java    # Script execution engine
│   ├── ProcessFunc.java      # Process operation functions
│   └── SwapUtil.java         # Inter-process data exchange
├── plugin/                   # Plugin system
│   ├── FunctionProvider.java # Function provider interface
│   ├── FunctionContext.java  # Function call context
│   ├── FunctionInfo.java     # Function information descriptor
│   ├── FunctionRegistry.java # Function registry center
│   ├── FileFunctionProvider.java    # File operation functions
│   ├── ProcessFunctionProvider.java # Process management functions
│   ├── UserFunctionProvider.java    # User management functions
│   └── UtilFunctionProvider.java    # Utility functions
└── basicUtil/                # Basic utility classes
    ├── FileUtil.java         # Virtual file system
    ├── JsonUtil.java         # JSON utilities
    ├── TimeUtil.java         # Time utilities
    ├── UserUtil.java         # Permission management
    ├── Logger.java           # Logging utilities
    └── Constants.java        # Constant definitions
```

## Core Features

### 1. Virtual File System (VFS)

- File and directory creation, reading, writing, deletion
- Metadata management (time, owner, permissions, lock status)
- Symbolic link support
- File/directory locking mechanism

### 2. Process Management

- Unix-like process model
- fork/exec/wait/kill operations
- Process tree structure (parent-child relationships)
- Automatic orphan process adoption (by INIT process)
- Process state persistence

### 3. Script Engine

Built-in interpreter supports:
- Variable types: int, string, array, map
- Control flow: if, while
- Function definition and invocation
- Script import
- Built-in system calls

### 4. Swap Pool System

Inter-process data sharing mechanism:
- Create/delete swap pools
- Add, retrieve, update, delete variables
- Access control (whitelist/blacklist)
- Variable types: always, times(n), sync

### 5. Permission Framework

- Owner-based permission checking
- Local user (superuser) mode
- File operation permission validation
- Process operation permission validation
