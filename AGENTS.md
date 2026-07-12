# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Compile
mvn compile

# Package uber-JAR (with dependencies)
mvn package -DskipTests

# Clean compile
mvn clean compile

# Run (from source)
mvn exec:java

# Run (packaged JAR)
java -jar target/cilexec-1.0-SNAPSHOT.jar

# Run (classpath)
mvn dependency:copy-dependencies -q
java -cp "target/classes:target/dependency/*" com.follarce.Main

# Dev build+run
bash build/run.sh
```

**Note:** No test framework is declared in pom.xml — there are no test files in the source tree. `mvn test` does nothing.

## High-Level Architecture

Cilexec is a **process management & scripting engine** that runs FCL (Follarce CilExec Language) scripts. It simulates an OS-like process environment on top of the host filesystem.

### Core Design Principle: "Zero Memory State"

The system has **no in-memory runtime state that survives crashes**. Everything — process state, file contents, user data, config — is stored as files on disk under a `cilexec_root/` VFS directory. Process state is written to `.proc` files after each instruction. This means the engine can be killed and restarted without data loss.

### Key Packages

| Package | Purpose |
|---|---|
| `com.follarce.Main` | Entry point: init VFS → register 11 function providers → create PID 1 → start scheduler |
| `com.follarce.Constants` | All system constants (tick rates, priorities, VFS paths, permissions, defaults) |
| `com.follarce.process` | **Scheduler** (priority round-robin), **ProcessRunner** (FCL interpreter), **StateManager** (`.proc` persistence), **CodeLoader** + **BoundaryTable** + **ControlFlow** (code parsing & execution flow), **ExpressionEvaluator** (lexer→parser→evaluator pipeline), **IpcHandler** (fork/exec/kill), **FunctionManager** (user function call stack), **ImportManager** (import/include) |
| `com.follarce.script` | **Lexer/Parser/AstNode/NodeEvaluator** (expression evaluation), **StatementParser** (statement splitting), **Token/TokenType/NodeType** (types), **FunctionDef/Instruction/InstructionType** (compilation units), **StringEscape** |
| `com.follarce.function` | **FunctionProvider** interface (namespace + call), **FunctionRegistry** (providers + user functions), 11 provider implementations across `file`, `io`, `util`, `user`, `process`, `swapPool`, `network`, `socket`, `math`, `path`, `system` namespaces |
| `com.follarce.util` | **FileUtil** (VFS — metadata+body format, permissions, locks, symlinks, 787 lines), **UserUtil** (user CRUD, ThreadLocal auth), **PathUtil** (path resolution, `.proc` path conversion), **JsonUtil** (Gson wrapper), **NetworkUtil** / **SocketUtil** |
| `com.follarce.init` | **FileInit** (create VFS tree + config files from classpath resources), **ProcessInit** (create PID 1 `.proc` file) |
| `com.follarce.exception` | **ProcessException** (base), **RecoverableException** (sets `data._warning`, continues), **UnrecoverableException** (sets `data._error`, kills process — factory methods for syntaxError, undefinedVariable, divisionByZero, etc.) |
| `com.follarce.log` | **Logger** — simple `PrintWriter`-based logger with levels |

### FCL Script Engine Pipeline

```
Source code → CodeLoader (strip comments, brace splitting, BoundaryTable scan)
           → ProcessRunner.dispatchStatement() (pattern match: if/while/func/import/return/etc.)
           → ExpressionEvaluator (Lexer → Token → Parser → AstNode → NodeEvaluator)
           → StateManager (write state to .proc after each line)
```

### Threading Model

Two modes controlled by `Constants.USE_VIRTUAL_THREADS` (default: `true`):

- **Virtual thread mode** (Java 21+, default): Scheduler only discovers new `.proc` files. Each process runs in its own `Thread.ofVirtual().start()`. Blocked processes use `LockSupport.parkNanos()`. Write contention handled by per-PID `ReentrantLock` (ProcessFileLock).
- **Legacy single-threaded mode**: One scheduler thread runs a priority round-robin loop (3 queues: HIGH/NORMAL/LOW). One process per tick, 50ms sleep.

### Permission System

- Owner-based: each file has `Owner` + `Permission.Owner` + `Permission.Others`
- `local` user is superuser (bypasses all checks)
- User identity tracked via `ThreadLocal<String>` in `UserUtil`
- Permissions validated in `FileUtil.validatePermission()` and per-provider `checkPerm()`

### Adding New Functionality

To add a new namespace of FCL functions:
1. Implement `FunctionProvider` (interface with `getNamespace()` + `call(name, args, context)`)
2. Register in `Main.main()` via `FunctionRegistry.registerProvider()`
3. Follow the pattern of existing providers — validate permissions first, throw `RecoverableException` / `UnrecoverableException` for errors

## Important Design Details

- **VFS metadata format:** Files use `#<META>\n{JSON}\n<META>#\n{content}` — metadata stores owner, permissions, timestamps, lock state, symlink target
- **JSON serialization:** Gson 2.10.1 is the only external dependency
- **Process state files:** Written as JSON to `cilexec_root/system/process/{pid}.proc` — contain code, variables, program counter, call stack, metadata
- **IPC:** Processes communicate through a "swap pool" — named byte-array slots in `cilexec_root/system/swap/`
- **Scheduler tick:** `SCHEDULER_TICK_MS = 50` (20 times/sec), each process runs one line per tick (`PROCESS_TICK_MS = 10` virtual time slice)
- **Import/include:** Import systems via `import("system")`, include files via `include("lib/util")`
- **Configuration files in VFS:** `init.json` (processes), `local.json` (network), `users.json` (credentials), `env.json` (environment)
