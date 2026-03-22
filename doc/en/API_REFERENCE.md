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

### Utility Functions API

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `now()` | None | `int[]` | Get current time `[year, month, day, hour, minute, second, millisecond]` |
| `parseJson(jsonStr)` | `jsonStr`: JSON string | `Object` | Parse JSON to object |
| `toJson(obj)` | `obj`: any object | `String` | Convert object to JSON string |
| `int(value)` | `value`: string or number | `int` | Convert to integer |
| `str(value)` | `value`: any value | `String` | Convert to string |
| `len(collection)` | `collection`: array/map/string | `int` | Get length |
| `sleep(millis)` | `millis`: milliseconds | `String[]` | Sleep for specified milliseconds |

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
