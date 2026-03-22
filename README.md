
# CilExec

> **Disclaimer: This project is developed by AI (TREA, DeepSeek), including this documentation. I take no responsibility for code quality or any issues that may arise.**

A virtual operating system kernel implemented in Java.

## Project Introduction

CilExec is a proof-of-concept project demonstrating how to implement core operating system mechanisms in Java. It provides a complete virtualization environment including process management, file system, script engine, and permission framework.

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

## Project Structure

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
├── network/                  # Network functionality
│   ├── NetworkUtil.java      # Network download utilities
│   ├── NetworkFunctionProvider.java # Network function provider
│   ├── SocketUtil.java       # Socket utilities
│   └── SocketFunctionProvider.java  # Socket function provider
├── plugin/                   # Plugin system
│   ├── FunctionProvider.java # Function provider interface
│   ├── FunctionContext.java  # Function call context
│   ├── FunctionInfo.java     # Function information descriptor
│   ├── FunctionRegistry.java # Function registry center
│   ├── FileFunctionProvider.java    # File operation functions
│   ├── ProcessFunctionProvider.java # Process management functions
│   ├── UserFunctionProvider.java    # User management functions
│   ├── UtilFunctionProvider.java    # Utility functions
│   └── MathFunctionProvider.java    # Mathematical function library
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

## Architecture Design Principles

### "Zero Memory State" Design Principle

CilExec's core design principle is **all system state is persisted to the file system, with no business state kept in memory**. This brings the following advantages:

- **Extreme fault tolerance**: Power outages or kill -9 won't lose state
- **Constant memory footprint**: Independent of process count or data volume
- **Completely transparent state**: View files directly to understand system state
- **Recoverability**: Restart and resume execution from files

### Architecture Limitations

#### Fully Memory-Less Modules ✅

| Module | Implementation | State Storage Location |
|--------|----------------|----------------------|
| Virtual File System (FileUtil) | All file operations directly read/write disk | `/system/files/` |
| Process Management (ProcessFunc) | Process state saved as JSON | `/system/process/*.json` |
| Swap Pool (SwapUtil) | Variable data persisted to files | `/system/swap/*.json` |
| User System (UserUtil) | User info stored in config file | `/system/config/users.json` |

#### Modules Limited by Technical Constraints ⚠️

**Socket Network Functionality (SocketUtil)**

**Reason**:
1. Java Socket objects cannot be serialized (`java.net.Socket` does not implement `Serializable`)
2. Socket connections are operating system kernel resources, not pure Java objects
3. The OS kernel does not persist TCP connection state; all sockets are forcibly closed when a process terminates
4. The TCP protocol itself is connection-oriented with state; after disconnection, the three-way handshake must be re-established

**Impact**:
- All socket connections are lost after system restart
- Socket ID generator is in memory, may produce duplicate IDs after restart

**Mitigation**:
- Socket metadata (ID, configuration) still saved to `/system/sockets/*.json`
- Actual connection objects must be maintained in memory
- Automatically clean up all sockets on process exit

**Educational Value**:
This serves as an excellent teaching case for understanding the distinction between "persistable state" and "temporary runtime resources." Some resources (network connections, file handles, threads) are inherently temporary and cannot be persisted.

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

## API Reference

### Usage Methods

CilExec provides two ways to use its functionality:

1. **Script Language Call** - Call functions directly in CilExec scripts
2. **Java API Call** - Call utility class methods directly in Java code

---

### Java API

If you want to use CilExec functionality in Java code, you can directly call the following classes:

#### FileUtil - Virtual File System

```java
// Read file
String[] result = FileUtil.read("/path/to/file.txt");
if ("SUCCESS".equals(result[0])) {
    String content = result[1];
}

// Write file
String[] result = FileUtil.write("/path/to/file.txt", "content");

// Create file
String[] result = FileUtil.createFile("/path/to/", "filename.txt");

// Create directory
String[] result = FileUtil.createDirectory("/path/to/", "dirname");

// List directory
String[] result = FileUtil.getListOfFileAndDirectory("/path/to/");

// Delete file
String[] result = FileUtil.removeFile("/path/to/file.txt");

// Delete directory
String[] result = FileUtil.removeDirectory("/path/to/dir/");

// Rename
String[] result = FileUtil.Rename("/path/to/old", "newname");

// Create link
String[] result = FileUtil.Link("/path/to/linkdir/", "/path/to/source");

// Lock/unlock file
String[] result = FileUtil.lock("/path/to/file.txt");
String[] result = FileUtil.unlock("/path/to/file.txt");

// Read/write file metadata
String[] result = FileUtil.readFileMetaData("/path/to/file.txt");
String[] result = FileUtil.writeFileMetaData("/path/to/file.txt", jsonContent);

// Read/write directory metadata
String[] result = FileUtil.readDirectoryMetaData("/path/to/dir/");
String[] result = FileUtil.writeDirectoryMetaData("/path/to/dir/", jsonContent);
String[] result = FileUtil.createDirectoryMetaData("/path/to/dir/");

// Get VFS root directory
String vfsRoot = FileUtil.getVfsRoot();

// Function dispatch (for script engine internal use)
Object result = FileUtil.call("read", new Object[]{"/path/to/file.txt"});
```

#### ProcessFunc - Process Management

```java
// Set current PID (must set before calling)
ProcessFunc.setCurrentPid(pid);

// Get current PID
int pid = ProcessFunc.getPID();

// Get parent process PID
int ppid = ProcessFunc.getPPID();

// Create child process
int childPid = ProcessFunc.fork();

// Execute program
String[] result = ProcessFunc.exec("/path/to/script.txt", new String[]{"arg1", "arg2"});

// Terminate process
String[] result = ProcessFunc.kill(pid);

// Wait for child process
String[] result = ProcessFunc.waitProcess();
String[] result = ProcessFunc.waitPID(childPid);

// Pause/resume process
String[] result = ProcessFunc.Pause(pid);
String[] result = ProcessFunc.Continue(pid);

// Get process list
Object children = ProcessFunc.getListOfChildProcess();  // Map<String, Integer>
Object all = ProcessFunc.getListOfProcess();            // Map<String, Integer>

// Function dispatch (for script engine internal use)
Object result = ProcessFunc.call("fork", new Object[]{});
```

#### SwapUtil - Swap Pool

```java
// Create/delete swap pool
String[] result = SwapUtil.createSwapPool("poolName");
String[] result = SwapUtil.removeSwapPool("poolName");

// Add variable
String[] result = SwapUtil.swapPoolAdd("varName:value", "poolName", new String[]{"always"});
String[] result = SwapUtil.swapPoolAdd("varName:value", "poolName", new String[]{"times(3)"});

// Get variable
Object value = SwapUtil.swapPoolGet("varName", "poolName");

// Delete variable
String[] result = SwapUtil.swapPoolRemove("varName", "poolName");

// Lock/unlock variable
String[] result = SwapUtil.swapPoolLock("varName", "poolName");
String[] result = SwapUtil.swapPoolUnlock("varName", "poolName");

// Update variable
String[] result = SwapUtil.swapPoolUpdate("varName", "poolName", "newValue");

// Get all variables (owner only)
Object allVars = SwapUtil.swapPoolGetAll("poolName");  // Map<String, Object>
```

#### NetworkUtil - Network Download

```java
// Download file to specified directory (filename auto-extracted from URL)
String[] result = NetworkUtil.webget("https://example.com/image.png", "/user/local/downloads/");
if ("SUCCESS".equals(result[0])) {
    String filename = result[1];  // "image.png"
}

// Custom timeout (30 seconds)
String[] result = NetworkUtil.webget("https://example.com/file.zip", "/user/local/downloads/", 30000);
```

#### JsonUtil - JSON Processing

```java
// Parse JSON
Object obj = JsonUtil.readJson(jsonString);  // Returns Map/List/String/Number/Boolean

// Convert to JSON
String json = JsonUtil.toJson(object);

// Validate JSON
boolean valid = JsonUtil.isValidJson(jsonString);
```

#### TimeUtil - Time Utilities

```java
// Get current time [year, month, day, hour, minute, second, millisecond]
int[] time = TimeUtil.getTime();
// time[0] = year, time[1] = month, ..., time[6] = millisecond
```

#### UserUtil - User Permissions

```java
// Set/get current user
UserUtil.setCurrentUser("username");
String user = UserUtil.getCurrentUser();

// Check if is local user
boolean isLocal = UserUtil.isLocal();

// Permission check
boolean canAccess = UserUtil.checkFilePermission("/path/to/file", "read");
boolean canManage = UserUtil.checkProcessPermission(pid);
```

#### UserInit - User Management

```java
// Get user list
Map<String, Object> users = UserInit.getListOfUsers();

// Create user
String[] result = UserInit.createUser("username", "password", false);

// Delete user (requires password verification)
String[] result = UserInit.removeUser("username", "password");

// Check if user exists
boolean exists = UserInit.userExists("username");

// Validate user password
boolean valid = UserInit.validateUser("username", "password");

// Get current logged-in user
String currentUser = UserInit.getCurrentUser();

// Switch user (requires password verification)
String[] result = UserInit.switchUser("username", "password");

// Check if current user is local
boolean isLocal = UserInit.isLocal();

// Get user info
Map<String, Object> userInfo = UserInit.getUserInfo("username");
```

#### ProcessRunner - Script Executor

```java
// Create process runner
ProcessRunner runner = new ProcessRunner(pid);

// Run in separate thread
new Thread(runner).start();

// Check status
boolean running = runner.isRunning();
int currentPid = runner.getPid();

// Stop process
runner.stop();

// Single-step execution (for manual control)
runner.executeLine();
```

#### ProcessInit - System Initialization

```java
// Initialize the entire process system (typically called in Main)
ProcessInit.init();

// Get runner for specific PID
ProcessRunner runner = ProcessInit.getRunner(pid);

// Shutdown system
ProcessInit.shutdown();
```

#### FileInit - File System Initialization

```java
// Initialize file system (create directory structure, config files, etc.)
FileInit.init();
```

#### UserInit - User System Initialization

```java
// User system doesn't require explicit initialization
// users.json is automatically created in FileInit.init()
```

#### SwapUtil - Process Exit Cleanup

```java
// Clean up sync variables in swap pool when process exits (automatically called)
SwapUtil.onProcessExit(pid);
```

---

### Script Language API

File operation functions callable in scripts:

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `read(path)` | `path`: file path | `String[]` | Read file content, returns `["SUCCESS", content]` or `["ERROR", code]` |
| `write(path, content)` | `path`: file path, `content`: content | `String[]` | Write file (overwrites existing content) |
| `append(path, content)` | `path`: file path, `content`: content | `String[]` | Append content to file (adds new line at end) |
| `createFile(path, name)` | `path`: directory path, `name`: filename | `String[]` | Create new file |
| `removeFile(path)` | `path`: file path | `String[]` | Delete file |
| `createDir(path, name)` | `path`: parent directory path, `name`: directory name | `String[]` | Create directory |
| `removeDir(path)` | `path`: directory path | `String[]` | Delete empty directory |
| `listdir(path)` | `path`: directory path | `String[]` | List directory contents, returns `["SUCCESS", "item1/", "item2", ...]` |
| `rename(path, newName)` | `path`: original path, `newName`: new name | `String[]` | Rename file or directory |
| `link(path, sourcePath)` | `path`: link directory, `sourcePath`: source path | `String[]` | Create symbolic link |
| `lock(path)` | `path`: file path | `String[]` | Lock file |
| `unlock(path)` | `path`: file path | `String[]` | Unlock file |
| `readMeta(path)` | `path`: file path | `String[]` | Read file metadata (JSON format) |
| `writeMeta(path, content)` | `path`: file path, `content`: JSON metadata | `String[]` | Write file metadata |

### Process Management API

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `getPID()` | None | `int` | Get current process ID |
| `getPPID()` | None | `int` | Get parent process ID |
| `fork()` | None | `int` | Create child process; parent returns child PID, child returns 0 |
| `exec(path, params)` | `path`: program path, `params`: parameter array | `String[]` | Execute new program replacing current process |
| `kill(pid)` | `pid`: process ID | `String[]` | Terminate specified process |
| `wait()` | None | `String[]` | Wait for any child process to end |
| `waitPID(pid)` | `pid`: child process ID | `String[]` | Wait for specified child process to end |
| `Pause(pid)` | `pid`: process ID | `String[]` | Pause process |
| `Continue(pid)` | `pid`: process ID | `String[]` | Resume paused process |
| `getListOfChildProcess()` | None | `Map<String, Integer>` | Get child process list |
| `getListOfProcess()` | None | `Map<String, Integer>` | Get all process list (requires local permission) |

### Swap Pool API

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `swapPool.create(name)` | `name`: pool name | `String[]` | Create swap pool |
| `swapPool.remove(name)` | `name`: pool name | `String[]` | Delete swap pool |
| `swapPool.add(varSpec, poolName, params)` | `varSpec`: "name:value", `poolName`: pool name, `params`: parameter array | `String[]` | Add variable to swap pool |
| `swapPool.get(varName, poolName)` | `varName`: variable name, `poolName`: pool name | `Object` | Get variable from swap pool |
| `swapPool.removeVar(varName, poolName)` | `varName`: variable name, `poolName`: pool name | `String[]` | Delete variable from swap pool |
| `swapPool.lock(varName, poolName)` | `varName`: variable name, `poolName`: pool name | `String[]` | Lock variable |
| `swapPool.unlock(varName, poolName)` | `varName`: variable name, `poolName`: pool name | `String[]` | Unlock variable |
| `swapPool.update(varName, poolName, newValue)` | `varName`: variable name, `poolName`: pool name, `newValue`: new value | `String[]` | Update variable value |
| `swapPool.getAll(poolName)` | `poolName`: pool name | `Map<String, Object>` | Get all variables in pool (owner only) |

**swapPool.add parameter options:**
- `"always"` - Variable persists indefinitely (default)
- `"times(n)"` - Variable auto-deletes after being read n times
- `"sync"` - Synchronized variable, notifies readers when changed
- `"whitelist{pid1,pid2,...}"` - Whitelist access control
- `"blacklist{pid1,pid2,...}"` - Blacklist access control

### User Management API

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `createUser(username, password, isLocal)` | `username`: username, `password`: password, `isLocal`: whether local user | `String[]` | Create new user |
| `removeUser(username, password)` | `username`: username, `password`: password | `String[]` | Delete user (requires password verification) |
| `userExists(username)` | `username`: username | `boolean` | Check if user exists |
| `validateUser(username, password)` | `username`: username, `password`: password | `boolean` | Validate user password |
| `switchUser(username, password)` | `username`: username, `password`: password | `String[]` | Switch current user |
| `getCurrentUser()` | None | `String` | Get current logged-in user |
| `isLocal()` | None | `boolean` | Check if current user is local |
| `getListOfUsers()` | None | `Map<String, Object>` | Get all user list |

### Network Download API

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `webget(url, saveDir)` | `url`: download URL, `saveDir`: save directory | `String[]` | Download file to specified directory, filename auto-extracted from URL, returns `["SUCCESS", filename]` or `["ERROR", code]` |
| `webget(url, saveDir, timeout)` | `url`: download URL, `saveDir`: save directory, `timeout`: timeout (ms) | `String[]` | Download with timeout setting |

**Filename extraction rules:**
- `https://example.com/image.png` → `image.png`
- `https://example.com/path/file.zip` → `file.zip`
- `https://example.com/` → `index.html`
- `https://example.com/page.html?foo=bar` → `page.html`

**Usage Example:**
```fcl
# Download image to specified directory
result = webget("https://example.com/photo.jpg", "/user/local/images/")
if result[0] == "SUCCESS" {
    filename = result[1]  # "photo.jpg"
}

# Download file with 30-second timeout
result = webget("https://example.com/archive.zip", "/user/local/downloads/", 30000)
```

### Socket API

Provides TCP and UDP network communication functionality, supporting server/client modes with auto-save of received data to files.

#### TCP Server/Client

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `socket.createServer(host, port, saveDir)` | `host`: bind address, `port`: port, `saveDir`: data save directory (optional, auto-selects based on user) | `String[]` | Create TCP server, returns `["SUCCESS", socketId]` |
| `socket.accept(serverId, saveDir)` | `serverId`: server socket ID, `saveDir`: data save directory (optional) | `String[]` | Accept client connection, returns `["SUCCESS", clientSocketId]` |
| `socket.connect(host, port, saveDir)` | `host`: server address, `port`: port, `saveDir`: data save directory (optional) | `String[]` | Connect to TCP server, returns `["SUCCESS", socketId]` |
| `socket.send(socketId, data)` | `socketId`: socket ID, `data`: data to send | `String[]` | Send data |
| `socket.receive(socketId, saveDir)` | `socketId`: socket ID, `saveDir`: save directory (optional, uses socket's default) | `String[]` | Receive data and save to file, returns `["SUCCESS", filename]` |
| `socket.close(socketId)` | `socketId`: socket ID | `String[]` | Close socket |
| `socket.getInfo(socketId)` | `socketId`: socket ID | `Map` | Get socket information |
| `socket.list()` | None | `Map` | List all sockets owned by current process |

#### UDP Communication

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `socket.createUdp(host, port, saveDir)` | `host`: bind address, `port`: port (0 for auto-assign), `saveDir`: data save directory (optional) | `String[]` | Create UDP socket, returns `["SUCCESS", socketId]` |
| `socket.sendTo(socketId, host, port, data)` | `socketId`: socket ID, `host`: target address, `port`: target port, `data`: data | `String[]` | Send UDP packet |

**Default save directory rules:**
- **Local user**: `/user/local/sockets/`
- **Regular user alice**: `/user/alice/sockets/`
- **Regular user bob**: `/user/bob/sockets/`

> Note: Regular users cannot save to `/system/` or other user directories; permission check returns `INSUFFICIENT_PERMISSION` error.

**Usage Examples:**
```fcl
# TCP Server Example
result = socket.createServer("127.0.0.1", 8080, "/user/local/sockets/")
if result[0] == "SUCCESS" {
    serverId = int(result[1])
    
    # Accept client connection
    clientResult = socket.accept(serverId, "/user/local/sockets/")
    if clientResult[0] == "SUCCESS" {
        clientId = int(clientResult[1])
        
        # Receive data (auto-saved to file)
        recvResult = socket.receive(clientId)
        if recvResult[0] == "SUCCESS" {
            filename = recvResult[1]  # e.g., "socket_2_20260321_201145_123.dat"
        }
        
        # Send response
        socket.send(clientId, "Hello Client!")
        
        # Close connection
        socket.close(clientId)
    }
    
    socket.close(serverId)
}

# TCP Client Example
result = socket.connect("127.0.0.1", 8080, "/user/local/sockets/")
if result[0] == "SUCCESS" {
    socketId = int(result[1])
    
    # Send data
    socket.send(socketId, "Hello Server!")
    
    # Receive response
    recvResult = socket.receive(socketId)
    if recvResult[0] == "SUCCESS" {
        filename = recvResult[1]
    }
    
    socket.close(socketId)
}

# UDP Example
result = socket.createUdp("0.0.0.0", 0, "/user/local/sockets/")
if result[0] == "SUCCESS" {
    udpSocket = int(result[1])
    
    # Send UDP packet
    socket.sendTo(udpSocket, "127.0.0.1", 9090, "Hello UDP!")
    
    # Receive data (auto-saved)
    recvResult = socket.receive(udpSocket)
    
    socket.close(udpSocket)
}
```

### Mathematical Functions API

Comprehensive mathematical operations including arithmetic, trigonometric functions, logarithms, random numbers, statistics, number theory, and more.

#### Basic Arithmetic

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.abs(n)` | `n`: number | `number` | Absolute value |
| `math.max(...numbers)` | `...numbers`: list of numbers | `number` | Maximum value |
| `math.min(...numbers)` | `...numbers`: list of numbers | `number` | Minimum value |
| `math.pow(base, exp)` | `base`: base, `exp`: exponent | `number` | Power operation |
| `math.sqrt(n)` | `n`: number | `number` | Square root |
| `math.cbrt(n)` | `n`: number | `number` | Cube root |
| `math.round(n, decimals)` | `n`: number, `decimals`: decimal places (optional) | `number` | Rounding |
| `math.floor(n)` | `n`: number | `number` | Floor (round down) |
| `math.ceil(n)` | `n`: number | `number` | Ceiling (round up) |
| `math.mod(a, b)` | `a`: dividend, `b`: divisor | `number` | Modulo |
| `math.sign(n)` | `n`: number | `number` | Sign function (-1, 0, 1) |
| `math.clamp(n, min, max)` | `n`: number, `min`: minimum, `max`: maximum | `number` | Clamp to range |
| `math.lerp(start, end, t)` | `start`: start value, `end`: end value, `t`: interpolation factor (0-1) | `number` | Linear interpolation |

#### Trigonometric Functions

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.sin(rad)` | `rad`: radians | `number` | Sine |
| `math.cos(rad)` | `rad`: radians | `number` | Cosine |
| `math.tan(rad)` | `rad`: radians | `number` | Tangent |
| `math.asin(n)` | `n`: number | `number` | Arc sine |
| `math.acos(n)` | `n`: number | `number` | Arc cosine |
| `math.atan(n)` | `n`: number | `number` | Arc tangent |
| `math.atan2(y, x)` | `y`: Y coordinate, `x`: X coordinate | `number` | Arc tangent 2 |
| `math.sinh(n)` | `n`: number | `number` | Hyperbolic sine |
| `math.cosh(n)` | `n`: number | `number` | Hyperbolic cosine |
| `math.tanh(n)` | `n`: number | `number` | Hyperbolic tangent |
| `math.deg(rad)` | `rad`: radians | `number` | Radians to degrees |
| `math.rad(deg)` | `deg`: degrees | `number` | Degrees to radians |

#### Logarithms and Exponentials

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.log(base, n)` | `base`: base, `n`: number | `number` | Logarithm with base |
| `math.log10(n)` | `n`: number | `number` | Base-10 logarithm |
| `math.log2(n)` | `n`: number | `number` | Base-2 logarithm |
| `math.ln(n)` | `n`: number | `number` | Natural logarithm (base e) |
| `math.exp(n)` | `n`: number | `number` | e to the power of n |

#### Random Numbers

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.random(min, max)` | `min`: minimum (optional), `max`: maximum (optional) | `number` | Random number (0-1 or specified range) |
| `math.randint(min, max)` | `min`: minimum (optional), `max`: maximum (optional) | `int` | Random integer |
| `math.randfloat(min, max)` | `min`: minimum (optional), `max`: maximum (optional) | `number` | Random float |
| `math.randchoice(list)` | `list`: array | `any` | Random element from list |
| `math.shuffle(list)` | `list`: array | `array` | Randomly shuffle list |

#### Statistical Functions

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.sum(...numbers)` | `...numbers`: list of numbers | `number` | Sum |
| `math.avg(...numbers)` | `...numbers`: list of numbers | `number` | Average (mean) |
| `math.mean(...numbers)` | `...numbers`: list of numbers | `number` | Average (mean) |
| `math.median(...numbers)` | `...numbers`: list of numbers | `number` | Median |
| `math.mode(...numbers)` | `...numbers`: list of numbers | `number` | Mode (most frequent) |
| `math.var(...numbers)` | `...numbers`: list of numbers | `number` | Variance |
| `math.std(...numbers)` | `...numbers`: list of numbers | `number` | Standard deviation |
| `math.minarr(arr)` | `arr`: array | `number` | Minimum in array |
| `math.maxarr(arr)` | `arr`: array | `number` | Maximum in array |
| `math.range(start, end, step)` | `start`: start, `end`: end, `step`: step (optional) | `array` | Generate range array |

#### Number Theory Functions

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.gcd(a, b)` | `a`: integer, `b`: integer | `int` | Greatest common divisor |
| `math.lcm(a, b)` | `a`: integer, `b`: integer | `int` | Least common multiple |
| `math.prime(n)` | `n`: integer | `boolean` | Check if prime |
| `math.factors(n)` | `n`: integer | `array` | Get all factors |
| `math.fib(n)` | `n`: integer | `int` | nth Fibonacci number |
| `math.factorial(n)` | `n`: integer | `int` | Factorial |

#### Mathematical Constants

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.pi` | None | `number` | π (3.14159...) |
| `math.e` | None | `number` | Euler's number e (2.71828...) |
| `math.tau` | None | `number` | 2π (6.28318...) |
| `math.inf` | None | `number` | Positive infinity |
| `math.nan` | None | `number` | Not a number |

#### Geometric Functions

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.hypot(a, b)` | `a`: leg, `b`: leg | `number` | Hypotenuse length |
| `math.dist(x1, y1, x2, y2)` | two points coordinates | `number` | Distance between points |
| `math.area.circle(radius)` | `radius`: radius | `number` | Circle area |
| `math.area.rect(width, height)` | `width`: width, `height`: height | `number` | Rectangle area |
| `math.vol.sphere(radius)` | `radius`: radius | `number` | Sphere volume |

#### Bitwise Operations

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.bit.and(a, b)` | `a`: integer, `b`: integer | `int` | Bitwise AND |
| `math.bit.or(a, b)` | `a`: integer, `b`: integer | `int` | Bitwise OR |
| `math.bit.xor(a, b)` | `a`: integer, `b`: integer | `int` | Bitwise XOR |
| `math.bit.not(a)` | `a`: integer | `int` | Bitwise NOT |
| `math.bit.shiftl(a, bits)` | `a`: integer, `bits`: number of bits | `int` | Left shift |
| `math.bit.shiftr(a, bits)` | `a`: integer, `bits`: number of bits | `int` | Right shift |

#### Advanced Functions

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.map(value, inMin, inMax, outMin, outMax)` | input value and ranges | `number` | Map value to new range |
| `math.norm(value, min, max)` | `value`: value, `min`: minimum, `max`: maximum | `number` | Normalize to 0-1 |
| `math.perlin(x, y)` | `x`: X coordinate, `y`: Y coordinate (optional) | `number` | Perlin noise |
| `math.noise(x)` | `x`: coordinate | `number` | Simple noise |

**Usage Examples:**
```fcl
# Basic arithmetic
x = math.abs(-10)           # 10
y = math.pow(2, 8)          # 256
z = math.sqrt(16)           # 4
w = math.clamp(x, 0, 100)   # Clamp to 0-100

# Trigonometry
angle = math.rad(90)        # π/2
s = math.sin(angle)         # 1.0

# Random numbers
r = math.random()           # Random number 0-1
dice = math.randint(1, 6)   # Random integer 1-6

# Statistics
data = [1, 2, 3, 4, 5]
sum = math.sum(data)        # 15
avg = math.avg(data)        # 3.0
std = math.std(data)        # Standard deviation

# Number theory
p = math.prime(17)          # true
f = math.factors(12)        # [1, 2, 3, 4, 6, 12]
fib10 = math.fib(10)        # 55

# Geometry
area = math.area.circle(5)  # 78.54...
dist = math.dist(0, 0, 3, 4) # 5.0

# Bitwise operations
result = math.bit.and(5, 3) # 1
```

### Utility Functions API

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `now()` | None | `int[]` | Get current time `[year, month, day, hour, minute, second, millisecond]` |
| `parseJson(jsonStr)` | `jsonStr`: JSON string | `Object` | Parse JSON to object |
| `toJson(obj)` | `obj`: any object | `String` | Convert object to JSON string |
| `int(value)` | `value`: string or number | `int` | Convert to integer |
| `str(value)` | `value`: any value | `String` | Convert to string |
| `len(collection)` | `collection`: array/map/string | `int` | Get length |

### Script Language Syntax

**Variable Declaration:**
```
int x = 10
string name = "hello"
array arr = [1, 2, 3]
map m = {a: 1, b: 2}
```

**Control Flow:**
```
if x > 5 {
    // do something
}

while running {
    // loop
}
```

**Function Definition:**
```
func add(a, b) {
    return a + b
}
```

**Import Script:**
```
import "script.txt"
```

**Array/Map Operations:**
```
arr[0] = 100          // Array index assignment
m["key"] = "value"    // Map key assignment
x = arr[0]            // Index access
len = #arr            // Get length (# prefix)
```

---

## Plugin Development Guide

CilExec uses a plugin system to extend script functions. Developers can add new script functions by implementing the `FunctionProvider` interface.

### Quick Start

#### Method 1: Add to Existing Provider (Recommended)

If the new function relates to existing functionality, modify the existing Provider directly:

**1. Modify `UtilFunctionProvider.java`**

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

**2. Done!** No other modifications needed; immediately usable in scripts:

```fcl
sum = math.add(10, 20)  # sum = 30
```

#### Method 2: Create New Provider

If the new functionality is relatively independent, consider creating a new Provider:

**1. Create `src/main/java/com/follarce/plugin/RandomFunctionProvider.java`**

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

**2. Register in `Main.java`**

```java
private static void registerFunctionProviders() {
    // ... existing Providers ...
    
    // Register new Provider
    FunctionRegistry.register(new RandomFunctionProvider());
}
```

**3. Done!** Use in scripts:

```fcl
num1 = random()         # 0-99
num2 = random(10)       # 0-9
num3 = random(5, 15)    # 5-14
```

### Development Standards

#### Function Naming Convention

```
file.read           # File operations
process.fork        # Process operations
user.create         # User operations
math.add            # Mathematical functions
str.upper           # String functions
random.randint      # Random number functions
```

#### Return Value Standards

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

#### Parameter Check Template

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

### FunctionContext Description

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

### Debugging Tips

**1. Use Logger**

```java
import com.follarce.basicUtil.Logger;

Logger.debug("Function called: " + name);
Logger.info("Operation success");
Logger.error("Error: " + e.getMessage());
```

**2. View Registered Functions**

```java
// Temporarily add in Main.java
System.out.println(FunctionRegistry.generateDocumentation());
```

**3. Test Scripts**

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

### Summary

| Operation | Effort | Use Case |
|-----------|--------|----------|
| Modify Existing Provider | 2 minutes | Add a few related functions |
| Create New Provider | 5 minutes | Add a set of new functionality |

**Core Principle: Only modify Providers, leave other code untouched!**

## Usage Examples

### Example 1: Basic File Operations
```
// Create file and write content
createFile("/user/local/app/", "test.txt")
write("/user/local/app/test.txt", "Hello World")

// Read file
result = read("/user/local/app/test.txt")
if result[0] == "SUCCESS" {
    content = result[1]
}
```

### Example 2: Process Creation
```
pid = fork()
if pid == 0 {
    // Child process
    exec("/user/local/app/child.txt", [])
} else {
    // Parent process
    waitPID(pid)
}
```

### Example 3: Swap Pool Usage
```
// Create swap pool
swapPool.create("shared")

// Add variable (permanent)
swapPool.add("counter:0", "shared", ["always"])

// Add variable (limit to 3 reads)
swapPool.add("token:abc123", "shared", ["times(3)"])

// Get variable
value = swapPool.get("counter", "shared")
```

### Example 4: Network Download
```
// Download image to specified directory (filename auto-extracted)
result = webget("https://example.com/image.png", "/user/local/downloads/")
if result[0] == "SUCCESS" {
    filename = result[1]  // "image.png"
}

// Download file with 30-second timeout
result = webget("https://example.com/archive.zip", "/user/local/downloads/", 30000)
```

### Example 5: Socket TCP Communication

```fcl
# TCP Server (using default save directory)
# Local user default: /user/local/sockets/
# Regular user alice default: /user/alice/sockets/
result = socket.createServer("127.0.0.1", 8080)
if result[0] == "SUCCESS" {
    serverId = int(result[1])
    
    # Accept client connection
    clientResult = socket.accept(serverId)
    if clientResult[0] == "SUCCESS" {
        clientId = int(clientResult[1])
        
        # Receive data (auto-saved to default directory)
        recvResult = socket.receive(clientId)
        if recvResult[0] == "SUCCESS" {
            filename = recvResult[1]  # e.g., "socket_2_20260321_201145_123.dat"
        }
        
        # Send response
        socket.send(clientId, "Hello Client!")
        socket.close(clientId)
    }
    socket.close(serverId)
}

# TCP Client (using default save directory)
result = socket.connect("127.0.0.1", 8080)
if result[0] == "SUCCESS" {
    socketId = int(result[1])
    socket.send(socketId, "Hello Server!")
    recvResult = socket.receive(socketId)
    socket.close(socketId)
}

# Regular user attempting to save to system directory (will fail)
result = socket.createServer("127.0.0.1", 8081, "/system/data/")
# Returns: ["ERROR", "INSUFFICIENT_PERMISSION"]
```

### Example 6: Socket UDP Communication

```fcl
# Create UDP socket (port 0 for auto-assign, using default save directory)
result = socket.createUdp("0.0.0.0", 0)
if result[0] == "SUCCESS" {
    udpSocket = int(result[1])
    
    # Send UDP packet to specified address
    socket.sendTo(udpSocket, "127.0.0.1", 9090, "Hello UDP!")
    
    # Receive data (auto-saved to default directory)
    recvResult = socket.receive(udpSocket)
    if recvResult[0] == "SUCCESS" {
        filename = recvResult[1]
    }
    
    socket.close(udpSocket)
}
```

## Return Value Standards

All functions returning `String[]` follow a unified format:
- Success: `["SUCCESS", null]` or `["SUCCESS", data]`
- Failure: `["ERROR", "ERROR_CODE"]`

Common error codes:

**File Operation Error Codes:**
- `INVALID_PATH` - Invalid path
- `FILE_DOES_NOT_EXIST` - File does not exist
- `DIRECTORY_DOES_NOT_EXIST` - Directory does not exist
- `FILE_EXIST` - File already exists
- `DIRECTORY_EXIST` - Directory already exists
- `INSUFFICIENT_PERMISSION` - Insufficient permission
- `FILE_IS_LOCKED` - File is locked
- `DIRECTORY_IS_LOCKED` - Directory is locked
- `IS_NOT_FILE` - Path is not a file
- `IS_NOT_DIRECTORY` - Path is not a directory
- `DIRECTORY_IS_NOT_EMPTY` - Directory is not empty

**Process Operation Error Codes:**
- `PROCESS_DOES_NOT_EXIST` - Process does not exist
- `CANNOT_KILL_INIT` - Cannot terminate INIT process
- `INSUFFICIENT_PERMISSION` - Insufficient permission

**User Management Error Codes:**
- `INVALID_USERNAME` - Invalid username
- `INVALID_PASSWORD` - Invalid password
- `USER_EXISTS` - User already exists
- `USER_NOT_EXISTS` - User does not exist
- `CANNOT_REMOVE_LOCAL` - Cannot remove local user
- `SAVE_FAILED` - Save failed
- `READ_FAILED` - Read failed
- `INVALID_USER_DATA` - Invalid user data
- `USERNAME_MUST_BE_STRING` - Username must be string
- `PASSWORD_MUST_BE_STRING` - Password must be string
- `ISLOCAL_MUST_BE_BOOLEAN` - isLocal must be boolean
- `TOO_MANY_ARGUMENTS` - Too many arguments
- `UNKNOWN_FUNCTION` - Unknown function

**Swap Pool Error Codes:**
- `POOL_EXISTS` - Swap pool already exists
- `POOL_DOES_NOT_EXIST` - Swap pool does not exist
- `VARIABLE_EXISTS` - Variable already exists
- `VARIABLE_DOES_NOT_EXIST` - Variable does not exist
- `VARIABLE_IS_LOCKED` - Variable is locked
- `INSUFFICIENT_PERMISSION` - Insufficient permission

**Network Download Error Codes:**
- `INVALID_URL` - URL is empty or malformed
- `INVALID_SAVE_DIR` - Save directory is empty
- `SAVE_DIR_MUST_BE_STRING` - Save directory must be string
- `CANNOT_EXTRACT_FILENAME` - Cannot extract filename from URL
- `TOO_MANY_REDIRECTS` - Too many redirects
- `RESOURCE_NOT_FOUND` - HTTP 404
- `ACCESS_FORBIDDEN` - HTTP 403
- `UNAUTHORIZED` - HTTP 401
- `SERVER_ERROR` - HTTP 5xx error
- `CONNECTION_TIMEOUT` - Connection timeout
- `UNKNOWN_HOST` - Cannot resolve host
- `CONNECTION_REFUSED` - Connection refused
- `IO_ERROR` - I/O error
- `DOWNLOAD_FAILED` - Download failed

**Socket Error Codes:**
- `INVALID_HOST` - Invalid host address
- `INVALID_PORT` - Invalid port number (1-65535)
- `INVALID_SAVE_DIR` - Invalid save directory
- `INVALID_DATA` - Invalid data
- `INVALID_TIMEOUT` - Invalid timeout
- `SOCKET_ID_MUST_BE_NUMBER` - Socket ID must be number
- `HOST_MUST_BE_STRING` - Host must be string
- `PORT_MUST_BE_NUMBER` - Port must be number
- `DATA_MUST_BE_STRING` - Data must be string
- `SAVE_DIR_MUST_BE_STRING` - Save directory must be string
- `SOCKET_DOES_NOT_EXIST` - Socket does not exist
- `SOCKET_CLOSED` - Socket is closed
- `NOT_SERVER_SOCKET` - Not a server socket
- `NOT_UDP_SOCKET` - Not a UDP socket
- `INVALID_SOCKET_TYPE` - Invalid socket type
- `PORT_IN_USE` - Port already in use
- `CREATE_SOCKET_FAILED` - Failed to create socket
- `CONNECT_FAILED` - Failed to connect
- `ACCEPT_FAILED` - Failed to accept connection
- `ACCEPT_TIMEOUT` - Accept timeout
- `SEND_FAILED` - Failed to send
- `RECEIVE_FAILED` - Failed to receive
- `RECEIVE_TIMEOUT` - Receive timeout
- `NO_DATA_RECEIVED` - No data received
- `CONNECTION_REFUSED` - Connection refused

**General Error Codes:**
- `INVALID_ARGUMENTS` - Invalid arguments
- `INVALID_JSON` - Invalid JSON format
- `CREATE_FAILED` - Create failed
- `DELETE_FAILED` - Delete failed
- `WRITE_FAILED` - Write failed
- `READ_FAILED` - Read failed
- `RENAME_FAILED` - Rename failed

## Logging System

### Log File Location

Log file `app.log` is located in the program's run directory (same directory as the JAR file).

### Log Format

```
[2024-01-15 10:30:25] [INFO] Operation success
[2024-01-15 10:30:26] [ERROR] Error: file not found
```

### Startup and Shutdown Markers

Each program run automatically adds separator markers:

```
============================================================
[2024-01-15 10:30:25] [STARTUP] Application started
============================================================
[2024-01-15 10:30:25] [INFO] Registered 5 function providers
...
============================================================
[2024-01-15 10:30:30] [SHUTDOWN] Application ended
============================================================
```

### Log Levels

- `DEBUG` - Debugging information
- `INFO` - General information (default level)
- `WARN` - Warning information
- `ERROR` - Error information

### Usage

```java
import com.follarce.basicUtil.Logger;

Logger.debug("Debug message");
Logger.info("Info message");
Logger.warn("Warning message");
Logger.error("Error message");
Logger.error("Error with exception", throwable);
```

## Use Cases

- Operating system teaching demonstrations
- Virtualization technology research
- Script engine development reference
- Embedded system prototyping

## License

This project is open-sourced under the [MIT License](LICENSE).