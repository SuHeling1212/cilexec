# API 参考

## 使用方式说明

CilExec 提供两种使用方式：

1. **脚本语言调用** - 在 CilExec 脚本中直接调用函数
2. **Java API 调用** - 在 Java 代码中直接调用工具类方法

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



### EnvVarUtil - 环境变量

```java
// 设置环境变量
String[] result = EnvVarUtil.setEnv("VAR_NAME", "value");

// 获取环境变量
String[] result = EnvVarUtil.getEnv("VAR_NAME");
if ("SUCCESS".equals(result[0])) {
    String value = result[1];
}

// 列出所有环境变量
String[] result = EnvVarUtil.listEnv();
if ("SUCCESS".equals(result[0])) {
    Map<String, String> envVars = (Map<String, String>) JsonUtil.readJson(result[1]);
}

// 删除环境变量
String[] result = EnvVarUtil.deleteEnv("VAR_NAME");
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

### NetworkUtil - 网络下载

```java
// 下载文件到指定目录（文件名自动从 URL 提取）
String[] result = NetworkUtil.webget("https://example.com/image.png", "/user/local/downloads/");
if ("SUCCESS".equals(result[0])) {
    String filename = result[1];  // "image.png"
}

// 自定义超时（30秒）
String[] result = NetworkUtil.webget("https://example.com/file.zip", "/user/local/downloads/", 30000);
```

### JsonUtil - JSON 处理

```java
// 解析 JSON
Object obj = JsonUtil.readJson(jsonString);  // 返回 Map/List/String/Number/Boolean

// 转换为 JSON（紧凑格式）
String json = JsonUtil.toJson(object);

// 转换为 JSON（格式化输出，带缩进）
String prettyJson = JsonUtil.toJsonPretty(object);

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

## 脚本语言

---

### 脚本语言语法

#### 变量声明

```
x = 10
name = "hello"
arr = [1, 2, 3]
m = {a: 1, b: 2}
```

#### 控制流

```
if x > 5 {
    // do something
}

while running {
    // loop
}
```

#### 函数定义

```
func add(a, b) {
    return a + b
}
```

#### 导入脚本

```
import "script.txt"
```

#### 数组/Map操作

```
arr[0] = 100          // 数组索引赋值
m["key"] = "value"    // Map键值赋值
x = arr[0]            // 索引访问
len = #arr            // 获取长度（#前缀）
```

#### 嵌套数据结构访问

```
// 定义嵌套结构
nested = {"data": {"data": {"a": 1}}, "users": [{"name": "Alice", "age": 30}]}

// 连续索引访问
nested["data"]["data"]["a"]           // 返回 1
nested["users"][0]["name"]            // 返回 "Alice"

// 连续索引赋值
nested["data"]["data"]["b"] = 2       // 添加新字段
nested["users"][0]["age"] = 31        // 修改嵌套值

// 查看数据结构
println(toJson(nested))               // 紧凑格式
println(toJsonPretty(nested))         // 格式化输出（推荐）
```

---
### 文件操作API

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

**示例：**
```fcl
// 创建文件并写入内容
createFile("/user/local/app/", "test.txt")
write("/user/local/app/test.txt", "Hello World")

// 读取文件
result = read("/user/local/app/test.txt")
if result[0] == "SUCCESS" {
    content = result[1]
    println("文件内容: " + content)
}

// 追加内容
append("/user/local/app/test.txt", "Second line")

// 列出目录
dirList = listdir("/user/local/")
if dirList[0] == "SUCCESS" {
    println("目录内容: " + toJson(dirList))
}
```

### 进程管理 API

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `getPID()` | 无 | `int` | 获取当前进程ID |
| `getPPID()` | 无 | `int` | 获取父进程ID |
| `fork()` | 无 | `int` | 创建子进程，父进程返回子PID，子进程返回0 |
| `exec(path, params)` | `path`: 程序路径, `params`: 参数数组（支持特殊参数系统） | `String[]` | 执行新程序替换当前进程，支持特殊参数系统和进程级用户上下文 |
| `kill(pid)` | `pid`: 进程ID | `String[]` | 终止指定进程 |
| `wait()` | 无 | `String[]` | 等待任意子进程结束 |
| `waitPID(pid)` | `pid`: 子进程ID | `String[]` | 等待指定子进程结束 |
| `Pause(pid)` | `pid`: 进程ID | `String[]` | 暂停进程 |
| `Continue(pid)` | `pid`: 进程ID | `String[]` | 继续暂停的进程 |
| `getListOfChildProcess()` | 无 | `Map<String, Integer>` | 获取子进程列表 |
| `getListOfProcess()` | 无 | `Map<String, Integer>` | 获取所有进程列表（需要local权限） |

**示例：**
```fcl
// 创建子进程
pid = fork()
if pid == 0 {
    // 子进程
    println("我是子进程")
    exec("/user/local/app/child.txt", [])
} else {
    // 父进程
    println("父进程等待子进程: " + str(pid))
    waitPID(pid)
    println("子进程结束")
}
```

#### exec 参数系统

`exec` 函数的参数系统支持特殊参数类型，用于修改执行行为：

**参数解析规则：**
- 以 `-` 开头的参数表示特殊参数类型（如 `-user`）
- 后续连续的非 `-` 开头的参数作为该类型的值
- 特殊参数会从 `argv` 中过滤，存储在新进程的 `data` 对象中
- 不关联任何特殊参数的参数作为常规命令行参数传入 `argv`

**特殊参数示例：**
- `-user`：指定新进程的运行用户
- `-test`：自定义参数，值会存储在 `data` 对象中

**-user 参数功能：**
1. 将 `"user": ["用户名"]` 存储到新进程的 `data` 对象中
2. 触发进程用户上下文切换到指定用户名
3. 新进程的文件操作将以该用户身份进行

**示例：**
```fcl
// 以 testuser 用户身份执行程序
exec("/user/local/program.fcl", ["-user", "testuser", "arg1", "arg2"])

// 多个特殊参数
exec("/user/local/program.fcl", ["-user", "testuser", "-config", "value1", "value2", "regular_arg"])
```

**进程级用户上下文：**
- 每个进程拥有独立的用户身份（ThreadLocal 存储）
- `switchUser(username, password)` 只切换当前进程的用户
- `getCurrentUser()` 返回当前进程的用户名
- 文件操作（创建、读写）使用当前进程的用户作为所有者

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

**示例：**
```fcl
// 创建交换池
swapPool.create("shared")

// 添加变量（永久有效）
swapPool.add("counter:0", "shared", ["always"])

// 添加变量（限制读取3次）
swapPool.add("token:abc123", "shared", ["times(3)"])

// 获取变量
value = swapPool.get("counter", "shared")
println("计数器: " + str(value))

// 更新变量
swapPool.update("counter", "shared", "10")

// 锁定变量
swapPool.lock("counter", "shared")
// ... 执行操作
swapPool.unlock("counter", "shared")
```

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

### 网络下载 API

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `webget(url, saveDir)` | `url`: 下载地址, `saveDir`: 保存目录 | `String[]` | 下载文件到指定目录，文件名自动从 URL 提取，返回 `["SUCCESS", filename]` 或 `["ERROR", code]` |
| `webget(url, saveDir, timeout)` | `url`: 下载地址, `saveDir`: 保存目录, `timeout`: 超时时间(毫秒) | `String[]` | 带超时设置的下载 |

**文件名提取规则：**
- `https://example.com/image.png` → `image.png`
- `https://example.com/path/file.zip` → `file.zip`
- `https://example.com/` → `index.html`
- `https://example.com/page.html?foo=bar` → `page.html`

**示例：**
```fcl
// 下载图片到指定目录（文件名自动提取）
result = webget("https://example.com/image.png", "/user/local/downloads/")
if result[0] == "SUCCESS" {
    filename = result[1]  // "image.png"
    println("下载成功: " + filename)
}

// 下载文件并设置30秒超时
result = webget("https://example.com/archive.zip", "/user/local/downloads/", 30000)
if result[0] == "SUCCESS" {
    println("文件已下载")
} else {
    println("下载失败: " + result[1])
}
```

### Socket API

提供 TCP 和 UDP 网络通信功能，支持服务器/客户端模式，接收的数据自动保存为文件。

#### TCP 服务器/客户端

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `socket.createServer(host, port, saveDir)` | `host`: 绑定地址, `port`: 端口, `saveDir`: 数据保存目录(可选，默认根据用户自动选择) | `String[]` | 创建 TCP 服务器，返回 `["SUCCESS", socketId]` |
| `socket.accept(serverId, saveDir)` | `serverId`: 服务器 socket ID, `saveDir`: 数据保存目录(可选，默认根据用户自动选择) | `String[]` | 接受客户端连接，返回 `["SUCCESS", clientSocketId]` |
| `socket.connect(host, port, saveDir)` | `host`: 服务器地址, `port`: 端口, `saveDir`: 数据保存目录(可选，默认根据用户自动选择) | `String[]` | 连接 TCP 服务器，返回 `["SUCCESS", socketId]` |
| `socket.send(socketId, data)` | `socketId`: socket ID, `data`: 要发送的数据 | `String[]` | 发送数据 |
| `socket.receive(socketId, saveDir)` | `socketId`: socket ID, `saveDir`: 保存目录(可选，默认使用 socket 创建时的目录) | `String[]` | 接收数据并保存到文件，返回 `["SUCCESS", filename]` |
| `socket.close(socketId)` | `socketId`: socket ID | `String[]` | 关闭 socket |
| `socket.getInfo(socketId)` | `socketId`: socket ID | `Map` | 获取 socket 信息 |
| `socket.list()` | 无 | `Map` | 列出当前进程的所有 socket |

#### UDP 通信

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `socket.createUdp(host, port, saveDir)` | `host`: 绑定地址, `port`: 端口(0表示自动分配), `saveDir`: 数据保存目录(可选，默认根据用户自动选择) | `String[]` | 创建 UDP socket，返回 `["SUCCESS", socketId]` |
| `socket.sendTo(socketId, host, port, data)` | `socketId`: socket ID, `host`: 目标地址, `port`: 目标端口, `data`: 数据 | `String[]` | 发送 UDP 数据包 |

**默认保存目录规则：**
- **Local 用户**: `/user/local/sockets/`
- **普通用户 alice**: `/user/alice/sockets/`
- **普通用户 bob**: `/user/bob/sockets/`

**TCP 示例：**
```fcl
# TCP 服务器（使用默认保存目录）
result = socket.createServer("127.0.0.1", 8080)
if result[0] == "SUCCESS" {
    serverId = int(result[1])
    
    # 接受客户端连接
    clientResult = socket.accept(serverId)
    if clientResult[0] == "SUCCESS" {
        clientId = int(clientResult[1])
        
        # 接收数据（自动保存到默认目录）
        recvResult = socket.receive(clientId)
        if recvResult[0] == "SUCCESS" {
            filename = recvResult[1]
            println("收到数据: " + filename)
        }
        
        # 发送响应
        socket.send(clientId, "Hello Client!")
        socket.close(clientId)
    }
    socket.close(serverId)
}

# TCP 客户端
result = socket.connect("127.0.0.1", 8080)
if result[0] == "SUCCESS" {
    socketId = int(result[1])
    socket.send(socketId, "Hello Server!")
    recvResult = socket.receive(socketId)
    socket.close(socketId)
}
```

**UDP 示例：**
```fcl
# 创建 UDP socket（端口 0 表示自动分配）
result = socket.createUdp("0.0.0.0", 0)
if result[0] == "SUCCESS" {
    udpSocket = int(result[1])
    
    # 发送 UDP 数据包到指定地址
    socket.sendTo(udpSocket, "127.0.0.1", 9090, "Hello UDP!")
    
    # 接收数据
    recvResult = socket.receive(udpSocket)
    if recvResult[0] == "SUCCESS" {
        filename = recvResult[1]
    }
    
    socket.close(udpSocket)
}
```

### 工具函数 API

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `now()` | 无 | `int[]` | 获取当前时间 `[年,月,日,时,分,秒,毫秒]` |
| `parseJson(jsonStr)` | `jsonStr`: JSON字符串 | `Object` | 解析JSON为对象 |
| `toJson(obj)` | `obj`: 任意对象 | `String` | 将对象转为JSON字符串（紧凑格式） |
| `toJsonPretty(obj)` | `obj`: 任意对象 | `String` | 将对象转为格式化的JSON字符串（带缩进换行） |
| `int(value)` | `value`: 字符串或数字 | `int` | 转换为整数 |
| `str(value)` | `value`: 任意值 | `String` | 转换为字符串 |
| `len(collection)` | `collection`: 数组/Map/字符串 | `int` | 获取长度 |

**示例：**
```fcl
// 获取当前时间
time = now()
println("当前时间: " + str(time[0]) + "年" + str(time[1]) + "月" + str(time[2]) + "日")

// JSON 转换
data = {"name": "Alice", "age": 30}
jsonStr = toJson(data)           // 紧凑格式
jsonPretty = toJsonPretty(data)  // 格式化输出

// 解析 JSON
parsed = parseJson("{\"key\": \"value\"}")
println(parsed["key"])

// 类型转换
num = int("42")        // 字符串转整数
text = str(123)        // 整数转字符串

// 获取长度
arr = [1, 2, 3, 4, 5]
println("数组长度: " + str(len(arr)))
```

> **注意**: 数学函数已移至单独文档 [数学函数.md](数学函数.md)

### 路径管理 API

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `resolvePath(path)` | `path`: 路径 | `String` | 解析路径别名（如 `~` → `/user/local`） |
| `getPathAlias(alias)` | `alias`: 别名 | `String` | 获取别名的目标路径 |
| `listPathAliases()` | 无 | `Map` | 列出所有路径别名 |
| `setPathAlias(alias, target)` | `alias`: 别名, `target`: 目标路径 | `String[]` | 设置路径别名 |
| `getSysEnv(name)` | `name`: 变量名 | `String` | 获取系统环境变量 |
| `listSysEnv()` | 无 | `Map` | 列出所有系统环境变量 |
| `setSysEnv(name, value)` | `name`: 变量名, `value`: 变量值 | `String[]` | 设置系统环境变量 |

**默认路径别名：**
- `~` → `/user/local`
- `$HOME` → `/user/local`

**示例：**
```fcl
// 解析路径别名
path = resolvePath("~/app/config.txt")
// 结果: "/user/local/app/config.txt"

// 设置自定义路径别名
result = setPathAlias("@app", "/user/local/myapp")
if result[0] == "SUCCESS" {
    println("别名设置成功")
}

// 使用自定义别名
path = resolvePath("@app/data.txt")
// 结果: "/user/local/myapp/data.txt"

// 列出所有路径别名
aliases = listPathAliases()
println("所有别名: " + toJson(aliases))

// 获取系统环境变量
home = getSysEnv("HOME")
println("HOME: " + home)
```

**注意事项：**
- 路径别名必须以路径分隔符（`/` 或 `\`）结尾或为完整路径
- 支持多个别名组合（如 `@app/@data/file.txt`）
- 配置保存在 `/system/config/env.json`

### 终端 IO API

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `print(...values)` | `...values`: 要输出的值（可变参数） | `String[]` | 输出到控制台（不换行），返回 `["SUCCESS"]` |
| `println(...values)` | `...values`: 要输出的值（可变参数） | `String[]` | 输出到控制台（换行），返回 `["SUCCESS"]` |
| `printf(format, ...args)` | `format`: 格式化字符串, `...args`: 格式化参数 | `String[]` | 格式化输出，返回 `["SUCCESS"]` 或 `["ERROR", code]` |
| `input(prompt)` | `prompt`: 提示符（可选） | `String` | 从控制台读取输入（提示符在同一行） |
| `inputLine(prompt)` | `prompt`: 提示符（可选） | `String` | 从控制台读取输入（提示符在新行） |
| `printErr(...values)` | `...values`: 要输出的值（可变参数） | `String[]` | 输出到错误流，返回 `["SUCCESS"]` |

**printf 支持的格式说明符：**
- `%s` - 字符串
- `%d` - 整数
- `%f` - 浮点数
- `%n` - 换行符
- `%%` - 百分号

**示例：**
```fcl
// 基本输出
print("Hello ")
print("World")
// 输出: Hello World

println("Hello World")
// 输出: Hello World（带换行）

// 格式化输出
name = "Alice"
age = 25
printf("姓名: %s, 年龄: %d%n", name, age)
// 输出: 姓名: Alice, 年龄: 25

// 读取用户输入（提示符在同一行）
name = input("请输入你的名字: ")
println("你好, " + name + "!")

// 读取用户输入（提示符在新行）
name = inputLine("请输入你的名字:")
println("你好, " + name + "!")

// 错误输出
printErr("错误: 文件未找到")

// 综合示例：简单的交互式程序
println("=== 简单计算器 ===")
print("请输入第一个数字: ")
num1Str = input()
num1 = int(num1Str)

print("请输入第二个数字: ")
num2Str = input()
num2 = int(num2Str)

print("请输入操作符 (+, -, *, /): ")
op = input()

result = 0
if op == "+" {
    result = num1 + num2
} elif op == "-" {
    result = num1 - num2
} elif op == "*" {
    result = num1 * num2
} elif op == "/" {
    result = num1 / num2
}

printf("结果: %d %s %d = %d%n", num1, op, num2, result)
println("计算完成！")
```

