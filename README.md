# CilExec

> **Disclaimer: This project is developed by AI (TREA, DeepSeek), including documentations. I take no responsibility for code quality or any issues that may arise.**

A single-binary, disk-based teaching simulated operating system implemented in Java.

## What is CilExec?

**Core Philosophy: Everything is a File, State Persistence**

CilExec takes Unix's "everything is a file" philosophy to the **extreme**:

- 💾 **Disk-based I/O** - The disk is the primary read/write device, all state persisted
- 📁 **All state as files** - System states and processes are stored as files on disk
- 🔄 **Transparent memory operations** - Runtime data is processed in memory but automatically synced to disk
- 🚫 **Not bootable** - Cannot be BIOS/booted as a real OS (but that's fine, it's just for teaching) XD

## Architecture

### Single Executable File

CilExec consists of **only one executable file** containing:

- ✅ Installation
- ✅ Startup
- ✅ Hardware interaction APIs
- ✅ Basic system kernel

### Why Java?

Because **the JVM is too useful!** Users can manipulate hardware by calling CilExec's APIs. Sure, efficiency decreases with disk I/O... but this is just a teaching system XD

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

## What It Actually Is

CilExec is a proof-of-concept project demonstrating core simulated operating system mechanisms in Java:

- Process management
- File system
- Script engine
- Permission framework

## Version

**Current Version: 1.0.0-ALPHA-3**

This is the first stable release with comprehensive improvements in code quality, security, and testing.

### What's New in 1.0.0

- ✅ **Enhanced Security**: Path validation, permission checks, resource management
- ✅ **Better Exception Handling**: Distinguish between recoverable and unrecoverable exceptions
- ✅ **Comprehensive Testing**: Added 131 unit tests
- ✅ **Code Quality**: Null checks, constant extraction, reduced code duplication
- ✅ **Improved Documentation**: Updated error codes and examples

## Tech Stack

- Java 25
- Maven
- Gson (JSON processing)
- JUnit 5 (Testing)

## Script Format

**`.fcl`** (Follarce CilExec Language) is the standard system script format.

Example:

```fcl
# This is a comment
while true {
    # INIT process main loop
}
```

## Build and Run

```bash
# Compile
mvn clean compile

# Run tests
mvn test

# Package
mvn clean package

# Run the application
java -jar target/cilexec-1.0.0-ALPHA-3.jar

# Or run directly
mvn dependency:copy-dependencies -q
java -cp "target/classes:target/dependency/*" com.follarce.Main
```

## Testing

The project includes comprehensive unit tests:

```bash
# Run all tests
mvn test

```

Test coverage includes:
- FileUtil: 33 tests
- JsonUtil: 33 tests
- UserUtil: 25 tests
- Constants: 40 tests

## Project Nature

**This is a proof-of-concept simulated operating system kernel.**

It implements core simulated operating system mechanisms:

- ✅ Complete kernel functionality (processes, file system, script engine)
- ✅ User login system

## Documentation

- [Project Structure](doc/en/PROJECT_STRUCTURE.md) - Project structure and core features
- [Architecture](doc/en/ARCHITECTURE.md) - Architecture design principles
- [API Reference](doc/en/API_REFERENCE.md) - Java API and script language API
- [Examples](doc/en/EXAMPLES.md) - Usage examples
- [Plugin Development](doc/en/PLUGIN_DEVELOPMENT.md) - How to extend functionality
- [Error Codes](doc/en/ERROR_CODES.md) - Error code reference
- [Logging](doc/en/LOGGING.md) - Logging system documentation

## Project Structure

```
src/
├── main/
│   ├── java/com/follarce/
│   │   ├── Main.java                 # Program entry
│   │   ├── init/                     # System initialization
│   │   ├── process/                  # Process management
│   │   │   ├── ProcessRunner.java    # Script execution engine
│   │   │   ├── ProcessFunc.java      # Process operations
│   │   │   ├── SwapUtil.java         # Inter-process data exchange
│   │   │   └── exception/            # Exception handling (NEW)
│   │   ├── network/                  # Network functionality
│   │   ├── plugin/                   # Plugin system
│   │   └── basicUtil/                # Basic utilities
│   │       ├── FileUtil.java         # Virtual file system
│   │       ├── JsonUtil.java         # JSON utilities
│   │       ├── UserUtil.java         # Permission management
│   │       ├── Logger.java           # Logging
│   │       └── Constants.java        # Constants
│   └── resources/
│       └── INIT.fcl                  # Initial script
└── test/
    └── java/com/follarce/            # Unit tests (NEW)
```

## Use Cases

- Simulated operating system teaching demonstrations
- Virtualization technology research
- Script engine development reference
- Embedded system prototyping

## License

This project is open-sourced under the [MIT License](LICENSE).
