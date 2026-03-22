# CilExec

> **Disclaimer: This project is developed by AI (TREA, DeepSeek), including this documentation. I take no responsibility for code quality or any issues that may arise.**

A single-binary, disk-based teaching operating system implemented in Java.

## What is CilExec?

**Core Philosophy: Everything is a File, Nothing is Memory**

CilExec takes Unix's "everything is a file" philosophy to the **extreme**:

- ❌ **No memory concept** - Completely removed
- 💾 **Disk-only I/O** - The disk is the ONLY read/write device
- 📁 **All state as files** - System states and processes are stored as files on disk
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

## The "Benefits" of No Memory

### 🛡️ "Best Null Safety!" 
> *just kidding*

No memory = no pointers = no null pointer exceptions! 

Actually... variables, arrays, and methods are defined within process files, but you may still access non-existent variables.

### 🔒 "Best Data Security!"
> *just kidding*

No memory = no data loss from power outages!

**But seriously:** Thanks to the memory-less design, you can directly modify "memory" data:
1. Save state (files)
2. Shut down the system
3. Open files on the host system
4. Make modifications
5. Restart CilExec
6. It automatically loads "memory" and continues working

## What It Actually Is

CilExec is a proof-of-concept project demonstrating core operating system mechanisms in Java:
- Process management
- File system
- Script engine
- Permission framework

## Tech Stack

- Java 25
- Maven
- Gson (JSON processing)

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
# Package with Maven
mvn clean package

# Or use the provided script
./package.sh

# Run
java -jar target/cilexec-1.0.2-SNAPSHOT.jar
```

## Project Nature

**This is a proof-of-concept operating system kernel.**

It implements core operating system mechanisms but is not a complete usable system:

- ✅ Complete kernel functionality (processes, file system, script engine)
- ❌ No Shell/command-line interface
- ❌ No user login system
- ❌ No system tools (ls, cat, echo, etc.)

## Documentation

- [Project Structure](doc/en/PROJECT_STRUCTURE.md) - Project structure and core features
- [Architecture](doc/en/ARCHITECTURE.md) - Architecture design principles
- [API Reference](doc/en/API_REFERENCE.md) - Java API and script language API
- [Examples](doc/en/EXAMPLES.md) - Usage examples
- [Plugin Development](doc/en/PLUGIN_DEVELOPMENT.md) - How to extend functionality
- [Error Codes](doc/en/ERROR_CODES.md) - Error code reference
- [Logging](doc/en/LOGGING.md) - Logging system documentation

## Use Cases

- Operating system teaching demonstrations
- Virtualization technology research
- Script engine development reference
- Embedded system prototyping

## License

This project is open-sourced under the [MIT License](LICENSE).
