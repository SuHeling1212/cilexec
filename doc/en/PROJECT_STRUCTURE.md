# Project Structure

```
src/
├── main/
│   ├── java/com/follarce/
│   │   ├── Main.java                 # Program entry point
│   │   ├── init/                     # System initialization
│   │   │   ├── FileInit.java         # File system initialization
│   │   │   ├── ProcessInit.java      # Process system initialization
│   │   │   └── UserInit.java         # User management
│   │   ├── process/                  # Process management
│   │   │   ├── ProcessRunner.java    # Script execution engine
│   │   │   ├── ProcessFunc.java      # Process operation functions
│   │   │   ├── SwapUtil.java         # Inter-process data exchange
│   │   │   └── exception/            # Exception handling (v1.0.0 NEW)
│   │   │       ├── ExceptionContext.java    # Exception context
│   │   │       ├── ProcessException.java    # Process exception base
│   │   │       ├── RecoverableException.java # Recoverable exception
│   │   │       └── UnrecoverableException.java # Unrecoverable exception
│   │   ├── network/                  # Network functionality
│   │   │   ├── NetworkUtil.java      # Network download utilities
│   │   │   ├── NetworkFunctionProvider.java # Network function provider
│   │   │   ├── SocketUtil.java       # Socket utilities
│   │   │   └── SocketFunctionProvider.java  # Socket function provider
│   │   ├── plugin/                   # Plugin system
│   │   │   ├── FunctionProvider.java # Function provider interface
│   │   │   ├── FunctionContext.java  # Function call context
│   │   │   ├── FunctionInfo.java     # Function information descriptor
│   │   │   ├── FunctionRegistry.java # Function registry center
│   │   │   ├── FileFunctionProvider.java    # File operation functions
│   │   │   ├── ProcessFunctionProvider.java # Process management functions
│   │   │   ├── UserFunctionProvider.java    # User management functions
│   │   │   ├── UtilFunctionProvider.java    # Utility functions
│   │   │   └── MathFunctionProvider.java    # Mathematical function library
│   │   └── basicUtil/                # Basic utility classes
│   │       ├── FileUtil.java         # Virtual file system
│   │       ├── JsonUtil.java         # JSON utilities
│   │       ├── TimeUtil.java         # Time utilities
│   │       ├── UserUtil.java         # Permission management
│   │       ├── Logger.java           # Logging utilities
│   │       ├── Constants.java        # Constant definitions
│   │       └── EnvVarUtil.java       # Environment variable utilities
│   └── resources/
│       └── INIT.fcl                  # Initial script
└── test/                             # Unit tests (v1.0.0 NEW)
    └── java/com/follarce/
        └── basicUtil/
            ├── FileUtilTest.java     # File system tests
            ├── JsonUtilTest.java     # JSON utilities tests
            ├── UserUtilTest.java     # Permission management tests
            └── ConstantsTest.java    # Constants tests
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

## v1.0.0 Improvements

### Security Enhancements

- ✅ **Enhanced Path Validation**: Whitelist validation to prevent path traversal attacks
- ✅ **Improved Resource Management**: Fixed Socket resource leaks using try-with-resources
- ✅ **Unified Permission Checks**: Provides detailed error context information

### Code Quality Improvements

- ✅ **Better Exception Handling**: Distinguish between recoverable and unrecoverable exceptions with detailed stack traces
- ✅ **Null Pointer Checks**: Added 50+ null checks at critical locations
- ✅ **Constant Extraction**: Extracted magic numbers into configuration constants
- ✅ **Code Refactoring**: Reduced 100+ lines of duplicate code

### Test Coverage

- ✅ **Unit Test Framework**: Integrated JUnit 5
- ✅ **Test Cases**: 131 unit tests with 100% pass rate
- ✅ **Test Coverage**: Full coverage of core functionality

### Documentation Updates

- ✅ **Error Codes Documentation**: Added PATH_TRAVERSAL_DETECTED error code
- ✅ **Project Structure**: Updated directory structure documentation
- ✅ **README**: Added version information and testing instructions
