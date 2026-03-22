# API 参考

## 使用方式说明

CilExec 提供两种使用方式：

1. **脚本语言调用** - 在 CilExec 脚本中直接调用函数
2. **Java API 调用** - 在 Java 代码中直接调用工具类方法

---

## Java API

### FileUtil - 虚拟文件系统

```java
// 读取文件
String[] result = FileUtil.read("/path/to/file.txt");
if ("SUCCESS".equals(result[0])) {
    String content = result[1];
}

// 写入文件
String[] result = FileUtil.write("/path/to/file.txt", "content");

// 创建文件
String[] result = FileUtil.createFile("/path/to/", "filename.txt");

// 创建目录
String[] result = FileUtil.createDirectory("/path/to/", "dirname");

// 列出目录
String[] result = FileUtil.getListOfFileAndDirectory("/path/to/");

// 删除文件
String[] result = FileUtil.removeFile("/path/to/file.txt");

// 删除目录
String[] result = FileUtil.removeDirectory("/path/to/dir/");

// 重命名
String[] result = FileUtil.Rename("/path/to/old", "newname");

// 创建链接
String[] result = FileUtil.Link("/path/to/linkdir/", "/path/to/source");

// 锁定/解锁文件
String[] result = FileUtil.lock("/path/to/file.txt");
String[] result = FileUtil.unlock("/path/to/file.txt");

// 读写文件元数据
String[] result = FileUtil.readFileMetaData("/path/to/file.txt");
String[] result = FileUtil.writeFileMetaData("/path/to/file.txt", jsonContent);

// 读写目录元数据
String[] result = FileUtil.readDirectoryMetaData("/path/to/dir/");
String[] result = FileUtil.writeDirectoryMetaData("/path/to/dir/", jsonContent);
String[] result = FileUtil.createDirectoryMetaData("/path/to/dir/");

// 获取 VFS 根目录
String vfsRoot = FileUtil.getVfsRoot();

// 函数分派（脚本引擎内部使用）
Object result = FileUtil.call("read", new Object[]{"/path/to/file.txt"});
```

### ProcessFunc - 进程管理

```java
// 设置当前 PID（重要：调用前需设置）
ProcessFunc.setCurrentPid(pid);

// 获取当前 PID
int pid = ProcessFunc.getPID();

// 获取父进程 PID
int ppid = ProcessFunc.getPPID();

// 创建子进程
int childPid = ProcessFunc.fork();

// 执行程序
String[] result = ProcessFunc.exec("/path/to/script.txt", new String[]{"arg1", "arg2"});

// 终止进程
String[] result = ProcessFunc.kill(pid);

// 等待子进程
String[] result = ProcessFunc.waitProcess();
String[] result = ProcessFunc.waitPID(childPid);

// 暂停/继续进程
String[] result = ProcessFunc.Pause(pid);
String[] result = ProcessFunc.Continue(pid);

// 获取进程列表
Object children = ProcessFunc.getListOfChildProcess();  // Map<String, Integer>
Object all = ProcessFunc.getListOfProcess();            // Map<String, Integer>

// 函数分派（脚本引擎内部使用）
Object result = ProcessFunc.call("fork", new Object[]{});
```

### SwapUtil - 交换池

```java
// 创建/删除交换池
String[] result = SwapUtil.createSwapPool("poolName");
String[] result = SwapUtil.removeSwapPool("poolName");

// 添加变量
String[] result = SwapUtil.swapPoolAdd("varName:value", "poolName", new String[]{"always"});
String[] result = SwapUtil.swapPoolAdd("varName:value", "poolName", new String[]{"times(3)"});

// 获取变量
Object value = SwapUtil.swapPoolGet("varName", "poolName");

// 删除变量
String[] result = SwapUtil.swapPoolRemove("varName", "poolName");

// 锁定/解锁变量
String[] result = SwapUtil.swapPoolLock("varName", "poolName");
String[] result = SwapUtil.swapPoolUnlock("varName", "poolName");

// 更新变量
String[] result = SwapUtil.swapPoolUpdate("varName", "poolName", "newValue");

// 获取所有变量（仅 owner）
Object allVars = SwapUtil.swapPoolGetAll("poolName");  // Map<String, Object>
```

### JsonUtil - JSON 处理

```java
// 解析 JSON
Object obj = JsonUtil.readJson(jsonString);  // 返回 Map/List/String/Number/Boolean

// 转换为 JSON
String json = JsonUtil.toJson(object);

// 验证 JSON
boolean valid = JsonUtil.isValidJson(jsonString);
```

### TimeUtil - 时间工具

```java
// 获取当前时间 [年, 月, 日, 时, 分, 秒, 毫秒]
int[] time = TimeUtil.getTime();
// time[0] = 年, time[1] = 月, ..., time[6] = 毫秒
```

### UserUtil - 用户权限

```java
// 设置/获取当前用户
UserUtil.setCurrentUser("username");
String user = UserUtil.getCurrentUser();

// 检查是否是 local 用户
boolean isLocal = UserUtil.isLocal();

// 权限检查
boolean canAccess = UserUtil.checkFilePermission("/path/to/file", "read");
boolean canManage = UserUtil.checkProcessPermission(pid);
```

### UserInit - 用户管理

```java
// 获取用户列表
Map<String, Object> users = UserInit.getListOfUsers();

// 创建用户
String[] result = UserInit.createUser("username", "password", false);

// 删除用户（需要密码验证）
String[] result = UserInit.removeUser("username", "password");

// 检查用户是否存在
boolean exists = UserInit.userExists("username");

// 验证用户密码
boolean valid = UserInit.validateUser("username", "password");

// 获取当前登录用户
String currentUser = UserInit.getCurrentUser();

// 切换用户（需要密码验证）
String[] result = UserInit.switchUser("username", "password");

// 检查当前用户是否是 local
boolean isLocal = UserInit.isLocal();

// 获取用户信息
Map<String, Object> userInfo = UserInit.getUserInfo("username");
```

### ProcessRunner - 脚本执行器

```java
// 创建进程运行器
ProcessRunner runner = new ProcessRunner(pid);

// 在独立线程中运行
new Thread(runner).start();

// 检查状态
boolean running = runner.isRunning();
int currentPid = runner.getPid();

// 停止进程
runner.stop();

// 单步执行（如需手动控制）
runner.executeLine();
```

### ProcessInit - 系统初始化

```java
// 初始化整个进程系统（通常在 Main 中调用）
ProcessInit.init();

// 获取指定 PID 的 runner
ProcessRunner runner = ProcessInit.getRunner(pid);

// 关闭系统
ProcessInit.shutdown();
```

### FileInit - 文件系统初始化

```java
// 初始化文件系统（创建目录结构、配置文件等）
FileInit.init();
```

### SwapUtil - 进程退出清理

```java
// 进程退出时清理交换池中的同步变量（自动调用）
SwapUtil.onProcessExit(pid);
```

---

## 脚本语言 API

### 文件操作

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `read(path)` | `path`: 文件路径 | `String[]` | 读取文件内容，返回 `["SUCCESS", content]` 或 `["ERROR", code]` |
| `write(path, content)` | `path`: 文件路径, `content`: 内容 | `String[]` | 写入文件（覆盖原有内容） |
| `append(path, content)` | `path`: 文件路径, `content`: 内容 | `String[]` | 追加内容到文件（在末尾添加新行） |
| `createFile(path, name)` | `path`: 目录路径, `name`: 文件名 | `String[]` | 创建新文件 |
| `removeFile(path)` | `path`: 文件路径 | `String[]` | 删除文件 |
| `createDir(path, name)` | `path`: 父目录路径, `name`: 目录名 | `String[]` | 创建目录 |
| `removeDir(path)` | `path`: 目录路径 | `String[]` | 删除空目录 |
| `listdir(path)` | `path`: 目录路径 | `String[]` | 列出目录内容，返回 `["SUCCESS", "item1/", "item2", ...]` |
| `rename(path, newName)` | `path`: 原路径, `newName`: 新名称 | `String[]` | 重命名文件或目录 |
| `link(path, sourcePath)` | `path`: 链接存放目录, `sourcePath`: 源文件路径 | `String[]` | 创建软链接 |
| `lock(path)` | `path`: 文件路径 | `String[]` | 锁定文件 |
| `unlock(path)` | `path`: 文件路径 | `String[]` | 解锁文件 |
| `readMeta(path)` | `path`: 文件路径 | `String[]` | 读取文件元数据（JSON格式） |
| `writeMeta(path, content)` | `path`: 文件路径, `content`: JSON元数据 | `String[]` | 写入文件元数据 |

### 进程管理 API

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `getPID()` | 无 | `int` | 获取当前进程ID |
| `getPPID()` | 无 | `int` | 获取父进程ID |
| `fork()` | 无 | `int` | 创建子进程，父进程返回子PID，子进程返回0 |
| `exec(path, params)` | `path`: 程序路径, `params`: 参数数组 | `String[]` | 执行新程序替换当前进程 |
| `kill(pid)` | `pid`: 进程ID | `String[]` | 终止指定进程 |
| `wait()` | 无 | `String[]` | 等待任意子进程结束 |
| `waitPID(pid)` | `pid`: 子进程ID | `String[]` | 等待指定子进程结束 |
| `Pause(pid)` | `pid`: 进程ID | `String[]` | 暂停进程 |
| `Continue(pid)` | `pid`: 进程ID | `String[]` | 继续暂停的进程 |
| `getListOfChildProcess()` | 无 | `Map<String, Integer>` | 获取子进程列表 |
| `getListOfProcess()` | 无 | `Map<String, Integer>` | 获取所有进程列表（需要local权限） |

### 交换池 API

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `swapPool.create(name)` | `name`: 池名称 | `String[]` | 创建交换池 |
| `swapPool.remove(name)` | `name`: 池名称 | `String[]` | 删除交换池 |
| `swapPool.add(varSpec, poolName, params)` | `varSpec`: "name:value", `poolName`: 池名, `params`: 参数数组 | `String[]` | 添加变量到交换池 |
| `swapPool.get(varName, poolName)` | `varName`: 变量名, `poolName`: 池名 | `Object` | 从交换池获取变量 |
| `swapPool.removeVar(varName, poolName)` | `varName`: 变量名, `poolName`: 池名 | `String[]` | 从交换池删除变量 |
| `swapPool.lock(varName, poolName)` | `varName`: 变量名, `poolName`: 池名 | `String[]` | 锁定变量 |
| `swapPool.unlock(varName, poolName)` | `varName`: 变量名, `poolName`: 池名 | `String[]` | 解锁变量 |
| `swapPool.update(varName, poolName, newValue)` | `varName`: 变量名, `poolName`: 池名, `newValue`: 新值 | `String[]` | 更新变量值 |
| `swapPool.getAll(poolName)` | `poolName`: 池名 | `Map<String, Object>` | 获取池内所有变量（仅owner） |

**swapPool.add 的参数选项：**
- `"always"` - 变量永久有效（默认）
- `"times(n)"` - 变量可被读取n次后自动删除
- `"sync"` - 同步变量，变更时通知读取者
- `"whitelist{pid1,pid2,...}"` - 白名单访问控制
- `"blacklist{pid1,pid2,...}"` - 黑名单访问控制

### 用户管理 API

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `createUser(username, password, isLocal)` | `username`: 用户名, `password`: 密码, `isLocal`: 是否为local用户 | `String[]` | 创建新用户 |
| `removeUser(username, password)` | `username`: 用户名, `password`: 密码 | `String[]` | 删除用户（需密码验证） |
| `userExists(username)` | `username`: 用户名 | `boolean` | 检查用户是否存在 |
| `validateUser(username, password)` | `username`: 用户名, `password`: 密码 | `boolean` | 验证用户密码 |
| `switchUser(username, password)` | `username`: 用户名, `password`: 密码 | `String[]` | 切换当前用户 |
| `getCurrentUser()` | 无 | `String` | 获取当前登录用户 |
| `isLocal()` | 无 | `boolean` | 检查当前用户是否为local |
| `getListOfUsers()` | 无 | `Map<String, Object>` | 获取所有用户列表 |

### 工具函数 API

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `now()` | 无 | `int[]` | 获取当前时间 `[年,月,日,时,分,秒,毫秒]` |
| `parseJson(jsonStr)` | `jsonStr`: JSON字符串 | `Object` | 解析JSON为对象 |
| `toJson(obj)` | `obj`: 任意对象 | `String` | 将对象转为JSON字符串 |
| `int(value)` | `value`: 字符串或数字 | `int` | 转换为整数 |
| `str(value)` | `value`: 任意值 | `String` | 转换为字符串 |
| `len(collection)` | `collection`: 数组/Map/字符串 | `int` | 获取长度 |
| `sleep(millis)` | `millis`: 毫秒数 | `String[]` | 休眠指定毫秒 |

### 脚本语言语法

**变量声明：**
```
int x = 10
string name = "hello"
array arr = [1, 2, 3]
map m = {a: 1, b: 2}
```

**控制流：**
```
if x > 5 {
    // do something
}

while running {
    // loop
}
```

**函数定义：**
```
func add(a, b) {
    return a + b
}
```

**导入脚本：**
```
import "script.txt"
```

**数组/Map操作：**
```
arr[0] = 100          // 数组索引赋值
m["key"] = "value"    // Map键值赋值
x = arr[0]            // 索引访问
len = #arr            // 获取长度（#前缀）
```
