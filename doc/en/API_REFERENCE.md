# API Reference

## Usage Methods

CilExec provides two ways to use its functionality:

1. **Script Language Call** - Call functions directly in CilExec scripts
2. **Java API Call** - Call utility class methods directly in Java code

---

## Java API

### FileUtil - Virtual File System

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

### ProcessFunc - Process Management

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

### SwapUtil - Swap Pool

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

### NetworkUtil - Network Download

```java
// Download file to specified directory (filename auto-extracted from URL)
String[] result = NetworkUtil.webget("https://example.com/image.png", "/user/local/downloads/");
if ("SUCCESS".equals(result[0])) {
    String filename = result[1];  // "image.png"
}

// Custom timeout (30 seconds)
String[] result = NetworkUtil.webget("https://example.com/file.zip", "/user/local/downloads/", 30000);
```

### JsonUtil - JSON Processing

```java
// Parse JSON
Object obj = JsonUtil.readJson(jsonString);  // Returns Map/List/String/Number/Boolean

// Convert to JSON
String json = JsonUtil.toJson(object);

// Validate JSON
boolean valid = JsonUtil.isValidJson(jsonString);
```

### TimeUtil - Time Utilities

```java
// Get current time [year, month, day, hour, minute, second, millisecond]
int[] time = TimeUtil.getTime();
// time[0] = year, time[1] = month, ..., time[6] = millisecond
```

### UserUtil - User Permissions

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

### UserInit - User Management

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

### ProcessRunner - Script Executor

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

### ProcessInit - System Initialization

```java
// Initialize the entire process system (typically called in Main)
ProcessInit.init();

// Get runner for specific PID
ProcessRunner runner = ProcessInit.getRunner(pid);

// Shutdown system
ProcessInit.shutdown();
```

### FileInit - File System Initialization

```java
// Initialize file system (create directory structure, config files, etc.)
FileInit.init();
```

### SwapUtil - Process Exit Cleanup

```java
// Clean up sync variables in swap pool when process exits (automatically called)
SwapUtil.onProcessExit(pid);
```

---

## Script Language API

### File Operations

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
