# CilExec

> **Disclaimer: This project is developed by AI (Codex, DeepSeek), including documentations. I take no responsibility for code quality or any issues that may arise.**

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

Each `.proc` snapshot stores `ProcessState`, `BlockReason`, `ExitReason`, and `StateMessage`. Child exits are retained in `ExitedChildren` until consumed by `wait()` or `waitPID()`, so concurrent exits are not lost. The legacy boolean `Status` field remains readable for older snapshots but is not the lifecycle source of truth.

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

When a parent terminates, each active direct child is reparented to INIT in both process snapshots. Process files are committed through a temporary file and atomic rename. On restart, an interrupted valid `.proc.tmp` snapshot can be promoted when the primary `.proc` is missing or invalid; two invalid snapshots are preserved for diagnosis and rejected by the scheduler.

The JUnit suite includes normal process operations, lifecycle edge cases, 256 concurrent forks, PID reuse checks, random forced JVM termination, and repeated restart recovery. Run it with `mvn test`.

### Durable Effects and Recovery

Each process incarnation has an immutable `ProcessGeneration`; PID alone is never used to authorize lifecycle updates. Every stateful instruction creates a persisted attempt with effect receipts. Internal effects such as file, swap-pool, user, and process changes are replayed from their receipts instead of being executed twice. Effects whose external outcome cannot be determined, such as an interrupted HTTP POST, block with `EFFECT_RECOVERY` until an operator resolves the effect.

Process control messages are stored in a generation-scoped disk inbox. A durable delivery ledger binds each message ID to its original target incarnation, orders messages, and allows startup recovery to republish a message if the JVM stopped between ledger and inbox commits. Fork reservations, lifecycle cleanup, leases, and fencing tokens are also persisted. Recovery reconciles these records before scheduling any process.

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
| Import file | `import "lib.fcl"` |
| Import installed package or package directory | `import json.*` |
| Include | `include "util.fcl"` |
| Process | `fork()` / `exec("script.fcl")` |

Bare package imports first resolve the effective user's installed package root. If no installed
binding exists, package-directory imports remain compatible and recursively load every `.fcl`
file in deterministic path order. Relative directories are resolved from the running script's
directory; absolute VFS paths start at `/`:

```fcl
import json.*
import /user/alice/app/package/json.*
import ./vendor/json.*
import /system/app/package/json.*
```

An installed `.pack` is loaded directly from the verified immutable object store. Its exact
dependencies are loaded dependency-first from that package's private reference table.

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
| `package` | `build`, `install`, `remove`, `list`, `info`, `verify` | User-scoped package management |
| `package` | `resource`, `pin`, `unpin`, `gc`, `recover` | Resources, retention, and recovery |
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

## Host Shell

Starting the JAR opens the Java host shell. The shell runs in the same JVM as the scheduler,
but it is not an FCL process: it has no PID, creates no `.proc` snapshot, and is never restored
after a restart. Process queries read the current snapshots from disk, while process controls are
delivered through the durable process inbox. The runtime log is stored at
`cilexec_root/cilexec.log`, inside the VFS host boundary.

```text
help
ps
inspect <pid>
run <vfs-script> [--user <user>] [--name <name>] [--priority <low|normal|high>]
pause <pid>
continue <pid>
kill <pid>

package list [--user <user>]
package build <source> <output> [--user <user>]
package install <source> [--binding <name>] [--repository <path>] [--user <user>]
package remove|info|verify|pin|unpin <value> [--user <user>]
package gc
package recover

clear
exit
```

`exit` shuts down the scheduler and releases the VFS instance lock without marking active FCL
processes as terminated, so their last committed snapshots can resume on the next start. PID 1 is
protected from `pause`, `continue`, and `kill`; use `exit` to stop CilExec itself.

The host shell exclusively owns standard input. FCL `io.input`, `io.readChar`, and `util.input`
cannot consume shell commands. Interactive process input will require a future `attach <pid>`
terminal channel.

## Virtual File System (VFS)

```
cilexec_root/
  ├── system/
  │   ├── app/          ← System applications (.fcl scripts)
  │   │   ├── package/objects/ ← Immutable SHA-256 objects shared by users
  │   │   └── data/package/    ← Global index, refs, staging, and local repository
  │   ├── config/       ← Configuration (users.json, env.json, INIT.fcl)
  │   ├── process/      ← Process files (1.proc, 2.proc, ...)
  │   └── swap/         ← Swap pool (inter-process communication)
  └── user/
      ├── local/app/package/   ← local user's installed package root
      ├── alice/app/package/   ← Alice's independent installed package root
      ├── alice/app/data/package/ ← Alice's transactions and pins
      └── bob/app/package/     ← Bob's independent installed package root
```

## Package Manager

Build and inspect a deterministic package without starting the engine:

```bash
mvn package
java -cp target/cilexec-1.0-SNAPSHOT.jar com.follarce.pack.PackageCli \
  build packageTEST /tmp/demo-greeter-1.0.0.pack
java -cp target/cilexec-1.0-SNAPSHOT.jar com.follarce.pack.PackageCli \
  inspect /tmp/demo-greeter-1.0.0.pack
```

From FCL, package paths are VFS paths. A regular user may only build from, install from, or
write packages inside that user's home:

```fcl
built = package.build(
    "/user/alice/app/demo-greeter-source",
    "/user/alice/app/demo-greeter-1.0.0.pack"
)
installed = package.install("/user/alice/app/demo-greeter-1.0.0.pack")
packages = package.list()

import demo-greeter.*
message = greet("FCL")
```

Two versions can be installed under different bindings and safely used in the same process by
giving each import an FCL namespace:

```fcl
package.install("/user/alice/app/demo-greeter-1.0.0.pack", "greeter-v1")
package.install("/user/alice/app/demo-greeter-2.0.0.pack", "greeter-v2")

import greeter-v1.* as greeter1
import greeter-v2.* as greeter2

oldMessage = greeter1.greet("FCL")
newMessage = greeter2.greet("FCL")
```

Aliases must be ordinary FCL identifiers, cannot use a built-in provider namespace, and cannot
be rebound to another package in the same process. Each aliased import also isolates its exact
dependency graph. The unaliased form remains available for backward compatibility.

Dependencies use exact SemVer coordinates and `sha256:` integrity values. Missing dependency
archives are discovered beside the package being installed, in an optional repository directory
passed as the third `package.install` argument, or in `/system/app/data/package/repository/`.
Version ranges and network repositories are intentionally not part of package format v1.

See [the Chinese package-manager guide](doc/zh-CN/包管理器.md) and the
[`packageTEST` fixture](packageTEST/README.md) for the complete format.

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
