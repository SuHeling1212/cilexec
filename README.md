# CilExec

> **Disclaimer: This project is developed by AI (TREA, DeepSeek), including documentations. I take no responsibility for code quality or any issues that may arise.**

A single-binary, disk-based teaching simulated operating system implemented in Java. This is the **reconstructed version** (`Cilexec`) with comprehensive architecture improvements over the original.

## What is CilExec?

**Core Philosophy: Everything is a File, State Persistence**

CilExec takes Unix's "everything is a file" philosophy to the **extreme**:

- 💾 **Disk-based I/O** — The disk is the primary read/write device, all state persisted
- 📁 **All state as files** — System states and processes are stored as files on disk
- 🔄 **Transparent memory operations** — Runtime data is processed in memory but automatically synced to disk
- 🚫 **Not bootable** — Cannot be BIOS/booted as a real OS (but that's fine, it's just for teaching) XD

## Architecture

### Single Executable File

CilExec consists of **only one executable file** containing:

- ✅ Installation
- ✅ Startup
- ✅ Hardware interaction APIs
- ✅ Basic system kernel

### Why Java?

Because **the JVM is too useful!** Users can manipulate hardware by calling CilExec's APIs. Sure, efficiency decreases with disk I/O... but this is just a teaching system XD

### Process Architecture

Every FCL process runs in an independent Java virtual thread. The scheduler polls `/system/process/` every 50ms to discover new `.proc` files. Each completed FCL instruction atomically persists its program counter, variables, call stack, and lifecycle state, giving the system **natural crash recovery**.

### Process Lifecycle

The persisted process state machine is:

```text
NEW -> READY -> RUNNING -> READY / BLOCKED / PAUSED / TERMINATED / FAILED
```

Each `.proc` snapshot stores `ProcessState`, `BlockReason`, `ExitReason`, and `StateMessage`. The legacy boolean `Status` field remains readable for older snapshots but is not the lifecycle source of truth.

Process control semantics:

| Operation | Behavior |
|-----------|----------|
| `fork()` | Creates a child snapshot; the parent receives the child PID and the child receives `0` |
| `exec(path)` | Replaces the current process program while retaining its PID |
| `wait()` | Blocks until any child exits |
| `waitPID(pid)` | Blocks until the selected child exits |
| `pause(pid)` | Persists `PAUSED` without terminating the process |
| `continue(pid)` | Restores the state held before the pause |
| `kill(pid)` | Terminates the process and removes its process file |

Process files are committed through a temporary file and atomic rename. On restart, an interrupted valid `.proc.tmp` snapshot can be promoted when the primary `.proc` is missing or invalid.

## The "Benefits" of State Persistence

> *just kidding*

### 🛡️ "Better Null Safety!"

No direct memory manipulation = no pointers = no null pointer exceptions!

Actually... variables, arrays, and methods are defined within process files, but you may still access non-existent variables.

### 🔒 "Better Data Security!"

State persistence = no data loss from power outages!

**But seriously:** Thanks to the state persistence design, you can directly modify process data:

1. Save state (files)
2. Shut down the system
3. Open files on the host system
4. Make modifications
5. Restart CilExec
6. It automatically loads "memory" and continues working

## What's New in the Reconstructed Version

This version (`Cilexec`) is a ground-up rewrite of the original CilExec with:

- ✅ **Restructured plugin system** — Clean provider-based function architecture with namespace support
- ✅ **Fixed permission system** — `switchUser()` now correctly propagates user context to permission checks
- ✅ **ThreadLocal user context** — User identity persists across line executions, not reset per line
- ✅ **File ownership inheritance** — Newly created files inherit the creator's identity instead of defaulting to `local`
- ✅ **Clean output** — Debug markers (`[REG]`, `[UTIL]`, `[EXPR]`) removed for production use
- ✅ **All original features preserved** — FCL engine, VFS, process system, swap pool IPC, network utilities

## What It Actually Is

CilExec is a proof-of-concept project demonstrating core simulated operating system mechanisms in Java:

- Process management
- Virtual file system
- FCL script engine
- Permission framework
- Inter-process communication

## Version

**Current Version: 1.0-SNAPSHOT**

Reconstructed version with fundamental architecture improvements.

## Tech Stack

- Java 26
- Maven 3.8+
- Gson 2.10.1 (JSON processing)
- JUnit 5 (process integration tests)

## FCL Script Language

**`.fcl`** (Follarce CilExec Language) is the standard system script format.

### Features

| Feature | Syntax |
|---------|--------|
| Variable assignment | `x = 42` |
| Arithmetic | `+ - * / %` |
| String concat | `"Hello" + " " + "World"` |
| Comparison | `== != < > <= >=` |
| Boolean | `and or !` |
| Control flow | `if` / `while` / `break` / `return` |
| Arrays | `[1, 2, 3]` / `arr[0]` |
| Maps | `{"key": "value"}` |
| Functions | `func add(a, b) { return a + b }` |
| Import | `import "lib.fcl"` |
| Include | `include "util.fcl"` |
| Process | `fork()` / `exec("script.fcl")` |

### Built-in Functions

All functions can be called with or without namespace prefix:

| Namespace | Functions | Description |
|-----------|-----------|-------------|
| `io` | `print`, `println`, `input` | Standard I/O |
| `io` | `readFile`, `writeFile` | File read/write |
| `file` | `read`, `write`, `createFile`, `removeFile`, `append` | File operations (permission controlled) |
| `file` | `createDir`, `removeDir`, `rename`, `listdir`, `exists` | Directory operations |
| `file` | `lock`, `unlock`, `link` | File locking & linking |
| `user` | `createUser`, `removeUser`, `switchUser` | User management |
| `user` | `getCurrentUser`, `isLocal`, `getListOfUsers` | User queries |
| `util` | `print`, `println`, `input` | Utility I/O |
| `util` | `toJson`, `fromJson`, `typeOf`, `toString` | Type & JSON tools |
| `util` | `isArray`, `isMap`, `isNumber`, `isString`, `isBool` | Type checks |
| `util` | `exit` | Exit process |
| `process` | `fork`, `exec`, `kill`, `wait`, `waitPid` | Process control |
| `process` | `pause`, `continue`, `getPid`, `getProcessName` | Process info |
| `swapPool` | `create`, `read`, `write`, `delete`, `exists` | IPC swap pool |
| `swapPool` | `list`, `clear`, `waitFor`, `signal` | Swap pool sync |
| `network` | `fetch`, `download` | HTTP requests |
| `socket` | Socket functions | TCP/UDP communication |
| `math` | `abs`, `ceil`, `floor`, `round`, `max`, `min` | Math functions |
| `math` | `sin`, `cos`, `tan`, `sqrt`, `pow`, `log` | Math functions |
| `math` | `random`, `randInt` | Random numbers |
| `path` | `resolve`, `normalize`, `getParent`, `getFileName` | Path operations |
| `path` | `toRealPath`, `isAbsolute`, `exists` | Path queries |
| `system` | `kill`, `exec` | Privileged operations (local only) |

### Permission System

CilExec has a user-based permission model:

- **Owner** — File/directory creator, has read + write permissions
- **Others** — Other users, default read-only
- **local user** — Superuser, bypasses all permission checks

Each file and directory has a `.META` metadata file:

```json
{
  "Owner": "alice",
  "Permission": {
    "Owner": "read, write",
    "Others": "read"
  }
}
```

Switch users at runtime:

```fcl
switchUser("alice", "p")      // Switch to alice
switchUser("local", "local")  // Switch back to superuser
```

## Build and Run

```bash
# Compile
mvn compile

# Package
mvn package -DskipTests

# Run tests
mvn test

# Run
java -jar target/cilexec-1.0-SNAPSHOT.jar

# Or run directly
mvn dependency:copy-dependencies -q
java -cp "target/classes:target/dependency/*" com.follarce.Main
```

## Virtual File System (VFS)

```
cilexec_root/
  ├── system/
  │   ├── app/          ← System applications (.fcl scripts)
  │   ├── config/       ← Configuration (users.json, env.json, INIT.fcl)
  │   ├── process/      ← Process files (1.proc, 2.proc, ...)
  │   └── swap/         ← Swap pool (inter-process communication)
  └── user/
      ├── local/        ← local user (superuser home)
      ├── alice/        ← Regular user
      └── bob/          ← Regular user
```

## Project Structure

```
src/
├── main/
│   ├── java/com/follarce/
│   │   ├── Main.java                 # Program entry
│   │   ├── Constants.java            # System constants
│   │   ├── init/
│   │   │   ├── FileInit.java         # VFS initialization
│   │   │   └── ProcessInit.java      # Process system init
│   │   ├── process/
│   │   │   ├── ProcessRunner.java    # Script execution engine
│   │   │   └── Scheduler.java        # Process scheduler
│   │   ├── script/
│   │   │   ├── Lexer.java            # Tokenizer
│   │   │   ├── Parser.java           # AST builder
│   │   │   ├── NodeEvaluator.java    # Expression evaluator
│   │   │   ├── AstNode.java          # AST node
│   │   │   ├── NodeType.java         # Node type enum
│   │   │   ├── Token.java            # Token
│   │   │   └── TokenType.java        # Token type enum
│   │   ├── function/
│   │   │   ├── FunctionRegistry.java # Function registration & dispatch
│   │   │   ├── FunctionContext.java  # Call context
│   │   │   ├── FunctionProvider.java # Provider interface
│   │   │   ├── FileFunctionProvider.java
│   │   │   ├── IOFunctionProvider.java
│   │   │   ├── UtilFunctionProvider.java
│   │   │   ├── UserFunctionProvider.java
│   │   │   ├── ProcessFunctionProvider.java
│   │   │   ├── SwapFunctionProvider.java
│   │   │   ├── NetworkFunctionProvider.java
│   │   │   ├── SocketFunctionProvider.java
│   │   │   ├── MathFunctionProvider.java
│   │   │   ├── PathFunctionProvider.java
│   │   │   └── PrivilegedFunctionProvider.java
│   │   ├── util/
│   │   │   ├── FileUtil.java         # Virtual file system
│   │   │   ├── UserUtil.java         # User & permission management
│   │   │   ├── PathUtil.java         # Path resolution
│   │   │   ├── JsonUtil.java         # JSON utilities
│   │   │   ├── NetworkUtil.java      # Network utilities
│   │   │   └── SocketUtil.java       # Socket utilities
│   │   ├── exception/
│   │   │   ├── ProcessException.java
│   │   │   ├── RecoverableException.java
│   │   │   └── UnrecoverableException.java
│   │   └── log/
│   │       └── Logger.java           # Logging system
│   └── resources/
│       ├── INIT.fcl                  # INIT process startup script
│       └── tests/                    # FCL test scripts
└── test/
    └── java/com/follarce/process/    # Process integration tests
```

## Use Cases

- Simulated operating system teaching demonstrations
- Virtualization technology research
- Script engine development reference
- Embedded system prototyping

## License

This project is open-sourced under the [MIT License](LICENSE).
