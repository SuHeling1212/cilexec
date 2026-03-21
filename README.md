# CilExec

>**声明：本项目由 AI（TREA,DeepSeek）开发完成，甚至连这个文档也是 AI 生成的。我不对代码质量和问题负任何责任**


一个用 Java 实现的虚拟操作系统内核。

## 项目简介

CilExec 是一个概念验证项目，展示了如何用 Java 实现操作系统的核心机制。它提供了一个完整的虚拟化环境，包括进程管理、文件系统、脚本引擎和权限框架。

## 技术栈

- Java 25
- Maven
- Gson (JSON 处理)

## 脚本格式

**`.fcl`** (Follarce CilExec Language) 是标准的系统脚本格式。

示例：
```fcl
# 这是注释
while true {
    # INIT 进程主循环
}
```

## 项目结构

```
src/main/java/com/follarce/
├── Main.java                 # 程序入口
├── init/                     # 系统初始化
│   ├── FileInit.java         # 文件系统初始化
│   ├── ProcessInit.java      # 进程系统初始化
│   └── UserInit.java         # 用户管理
├── process/                  # 进程管理
│   ├── ProcessRunner.java    # 脚本执行引擎
│   ├── ProcessFunc.java      # 进程操作函数
│   └── SwapUtil.java         # 进程间数据交换
├── network/                  # 网络功能
│   ├── NetworkUtil.java      # 网络下载工具
│   ├── NetworkFunctionProvider.java # 网络函数提供者
│   ├── SocketUtil.java       # Socket工具
│   └── SocketFunctionProvider.java  # Socket函数提供者
├── plugin/                   # 插件系统
│   ├── FunctionProvider.java # 函数提供者接口
│   ├── FunctionContext.java  # 函数调用上下文
│   ├── FunctionInfo.java     # 函数信息描述
│   ├── FunctionRegistry.java # 函数注册中心
│   ├── FileFunctionProvider.java    # 文件操作函数
│   ├── ProcessFunctionProvider.java # 进程管理函数
│   ├── UserFunctionProvider.java    # 用户管理函数
│   ├── UtilFunctionProvider.java    # 工具函数
│   └── RandomFunctionProvider.java  # 随机数函数示例
└── basicUtil/                # 基础工具类
    ├── FileUtil.java         # 虚拟文件系统
    ├── JsonUtil.java         # JSON 工具
    ├── TimeUtil.java         # 时间工具
    ├── UserUtil.java         # 权限管理
    ├── Logger.java           # 日志工具
    └── Constants.java        # 常量定义
```

## 核心功能

### 1. 虚拟文件系统 (VFS)

- 文件和目录的创建、读取、写入、删除
- 元数据管理（时间、所有者、权限、锁状态）
- 软链接支持
- 文件/目录锁定机制

### 2. 进程管理

- 类 Unix 进程模型
- fork/exec/wait/kill 操作
- 进程树结构（父子关系）
- 孤儿进程自动收养（由 INIT 进程接管）
- 进程状态持久化

### 3. 脚本引擎

内置解释器支持：
- 变量类型：int, string, array, map
- 控制流：if, while
- 函数定义和调用
- 脚本导入
- 内置系统调用

### 4. 交换池系统

进程间数据共享机制：
- 创建/删除交换池
- 变量的添加、获取、更新、删除
- 访问控制（白名单/黑名单）
- 变量类型：always, times(n), sync

### 5. 权限框架

- 基于所有者的权限检查
- Local 用户（超级用户）模式
- 文件操作权限验证
- 进程操作权限验证

## 架构设计说明

### "零内存状态"设计原则

CilExec 的核心设计原则是**所有系统状态都持久化到文件系统，不在内存中保存业务状态**。这带来以下优势：

- **极端容错性**：随时断电、kill -9，状态不丢失
- **恒定内存占用**：与进程数量、数据量无关
- **状态完全透明**：直接查看文件即可了解系统状态
- **可恢复性**：重启后从文件恢复，继续执行

### 架构限制说明

#### 完全无内存状态的模块 ✅

| 模块 | 实现方式 | 状态存储位置 |
|------|----------|--------------|
| 虚拟文件系统 (FileUtil) | 所有文件操作直接读写磁盘 | `/system/files/` |
| 进程管理 (ProcessFunc) | 进程状态保存为 JSON | `/system/process/*.json` |
| 交换池 (SwapUtil) | 变量数据持久化到文件 | `/system/swap/*.json` |
| 用户系统 (UserUtil) | 用户信息存储在配置文件 | `/system/config/users.json` |

#### 受技术限制无法完全无内存状态的模块 ⚠️

**Socket 网络功能 (SocketUtil)**

**原因**：
1. Java Socket 对象无法序列化（`java.net.Socket` 未实现 `Serializable`）
2. Socket 连接是操作系统内核资源，不是纯 Java 对象
3. 操作系统内核不持久化 TCP 连接状态，进程结束后强制关闭所有 Socket
4. TCP 协议本身是有状态连接，断线后必须重新三次握手

**影响**：
- 系统重启后所有 Socket 连接丢失
- Socket ID 生成器在内存中，重启后可能产生重复 ID

**对策**：
- Socket 元数据（ID、配置）仍保存到 `/system/sockets/*.json`
- 实际连接对象必须在内存中维护
- 进程退出时自动清理所有 Socket

**教学价值**：
这是理解"可持久化状态"与"临时运行时资源"区别的绝佳案例。有些资源（如网络连接、文件句柄、线程）本质上是临时的，无法持久化。

## 构建和运行

```bash
# 使用 Maven 打包
mvn clean package

# 或使用提供的脚本
./package.sh

# 运行
java -jar target/cilexec-1.0.2-SNAPSHOT.jar
```

## 项目性质

**这是一个操作系统内核的概念验证项目。**

它实现了操作系统的核心机制，但本身不是一个完整的可用系统：

- ✅ 内核功能完整（进程、文件系统、脚本引擎）
- ❌ 没有 Shell/命令行界面
- ❌ 没有用户登录系统
- ❌ 没有系统工具（ls, cat, echo 等）

## API 参考

### 使用方式说明

CilExec 提供两种使用方式：

1. **脚本语言调用** - 在 CilExec 脚本中直接调用函数
2. **Java API 调用** - 在 Java 代码中直接调用工具类方法

---

### Java API

如果你想在 Java 代码中使用 CilExec 的功能，可以直接调用以下类：

#### FileUtil - 虚拟文件系统

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

#### ProcessFunc - 进程管理

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

#### SwapUtil - 交换池

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

#### NetworkUtil - 网络下载

```java
// 下载文件到指定目录（文件名自动从 URL 提取）
String[] result = NetworkUtil.webget("https://example.com/image.png", "/user/local/downloads/");
if ("SUCCESS".equals(result[0])) {
    String filename = result[1];  // "image.png"
}

// 自定义超时（30秒）
String[] result = NetworkUtil.webget("https://example.com/file.zip", "/user/local/downloads/", 30000);
```

#### JsonUtil - JSON 处理

```java
// 解析 JSON
Object obj = JsonUtil.readJson(jsonString);  // 返回 Map/List/String/Number/Boolean

// 转换为 JSON
String json = JsonUtil.toJson(object);

// 验证 JSON
boolean valid = JsonUtil.isValidJson(jsonString);
```

#### TimeUtil - 时间工具

```java
// 获取当前时间 [年, 月, 日, 时, 分, 秒, 毫秒]
int[] time = TimeUtil.getTime();
// time[0] = 年, time[1] = 月, ..., time[6] = 毫秒
```

#### UserUtil - 用户权限

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

#### UserInit - 用户管理

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

#### ProcessRunner - 脚本执行器

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

#### ProcessInit - 系统初始化

```java
// 初始化整个进程系统（通常在 Main 中调用）
ProcessInit.init();

// 获取指定 PID 的 runner
ProcessRunner runner = ProcessInit.getRunner(pid);

// 关闭系统
ProcessInit.shutdown();
```

#### FileInit - 文件系统初始化

```java
// 初始化文件系统（创建目录结构、配置文件等）
FileInit.init();
```

#### UserInit - 用户系统初始化

```java
// 用户系统不需要显式初始化
// users.json 在 FileInit.init() 中自动创建
```

#### SwapUtil - 进程退出清理

```java
// 进程退出时清理交换池中的同步变量（自动调用）
SwapUtil.onProcessExit(pid);
```

---

### 脚本语言 API

在脚本中可直接调用的文件操作函数：

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `read(path)` | `path`: 文件路径 | `String[]` | 读取文件内容，返回 `["SUCCESS", content]` 或 `["ERROR", code]` |
| `write(path, content)` | `path`: 文件路径, `content`: 内容 | `String[]` | 写入文件 |
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

**使用示例：**
```fcl
# 下载图片到指定目录
result = webget("https://example.com/photo.jpg", "/user/local/images/")
if result[0] == "SUCCESS" {
    filename = result[1]  # "photo.jpg"
}

# 下载文件并设置30秒超时
result = webget("https://example.com/archive.zip", "/user/local/downloads/", 30000)
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

> 注意：普通用户无法保存到 `/system/` 或其他用户的目录，权限检查会返回 `INSUFFICIENT_PERMISSION` 错误。

**使用示例：**
```fcl
# TCP 服务器示例
result = socket.createServer("127.0.0.1", 8080, "/user/local/sockets/")
if result[0] == "SUCCESS" {
    serverId = int(result[1])
    
    # 接受客户端连接
    clientResult = socket.accept(serverId, "/user/local/sockets/")
    if clientResult[0] == "SUCCESS" {
        clientId = int(clientResult[1])
        
        # 接收数据（自动保存到文件）
        recvResult = socket.receive(clientId)
        if recvResult[0] == "SUCCESS" {
            filename = recvResult[1]  # 例如: "socket_2_20260321_201145_123.dat"
        }
        
        # 发送响应
        socket.send(clientId, "Hello Client!")
        
        # 关闭连接
        socket.close(clientId)
    }
    
    socket.close(serverId)
}

# TCP 客户端示例
result = socket.connect("127.0.0.1", 8080, "/user/local/sockets/")
if result[0] == "SUCCESS" {
    socketId = int(result[1])
    
    # 发送数据
    socket.send(socketId, "Hello Server!")
    
    # 接收响应
    recvResult = socket.receive(socketId)
    if recvResult[0] == "SUCCESS" {
        filename = recvResult[1]
    }
    
    socket.close(socketId)
}

# UDP 示例
result = socket.createUdp("0.0.0.0", 0, "/user/local/sockets/")
if result[0] == "SUCCESS" {
    udpSocket = int(result[1])
    
    # 发送 UDP 数据包
    socket.sendTo(udpSocket, "127.0.0.1", 9090, "Hello UDP!")
    
    # 接收数据（自动保存）
    recvResult = socket.receive(udpSocket)
    
    socket.close(udpSocket)
}
```

### 工具函数 API

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `now()` | 无 | `int[]` | 获取当前时间 `[年,月,日,时,分,秒,毫秒]` |
| `parseJson(jsonStr)` | `jsonStr`: JSON字符串 | `Object` | 解析JSON为对象 |
| `toJson(obj)` | `obj`: 任意对象 | `String` | 将对象转为JSON字符串 |
| `int(value)` | `value`: 字符串或数字 | `int` | 转换为整数 |
| `str(value)` | `value`: 任意值 | `String` | 转换为字符串 |
| `len(collection)` | `collection`: 数组/Map/字符串 | `int` | 获取长度 |

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

---

## 插件开发指南

CilExec 使用插件系统来扩展脚本函数。开发者可以通过实现 `FunctionProvider` 接口来添加新的脚本函数。

### 快速开始

#### 方式一：添加到现有 Provider（推荐）

如果新函数与现有功能相关（如添加数学函数到工具函数），直接修改现有 Provider：

**1. 修改 `UtilFunctionProvider.java`**

```java
public class UtilFunctionProvider implements FunctionProvider {
    
    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            // ... 现有函数 ...
            
            // 添加新函数
            case "math.add":
                if (args.length < 2) return error("INVALID_ARGUMENTS");
                return ((Number) args[0]).intValue() + ((Number) args[1]).intValue();
        }
        return null;
    }
    
    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[]{
            // ... 现有函数信息 ...
            
            // 添加新函数信息
            new FunctionInfo("math.add", "Add two numbers", 
                new String[]{"a: int", "b: int"}, "int", "Math")
        };
    }
}
```

**2. 完成！** 无需其他修改，脚本中立即可以使用：

```fcl
sum = math.add(10, 20)  # sum = 30
```

#### 方式二：创建新 Provider

如果新功能较为独立，建议创建新的 Provider：

**1. 创建新文件 `src/main/java/com/follarce/plugin/RandomFunctionProvider.java`**

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

**2. 在 `Main.java` 中注册**

```java
private static void registerFunctionProviders() {
    // ... 现有 Provider ...
    
    // 注册新的 Provider
    FunctionRegistry.register(new RandomFunctionProvider());
}
```

**3. 完成！** 脚本中使用：

```fcl
num1 = random()         # 0-99
num2 = random(10)       # 0-9
num3 = random(5, 15)    # 5-14
```

### 开发规范

#### 函数命名规范

```
file.read           # 文件操作
process.fork        # 进程操作
user.create         # 用户操作
math.add            # 数学函数
str.upper           # 字符串函数
random.randint      # 随机数函数
```

#### 返回值规范

```java
// 成功 - 直接返回数据
return result;

// 错误 - 返回标准错误格式
return new String[]{"ERROR", "ERROR_CODE"};

// 常见错误码
"INVALID_ARGUMENTS"         // 参数错误
"INSUFFICIENT_PERMISSION"   // 权限不足
"FILE_DOES_NOT_EXIST"       // 文件不存在
"INVALID_RANGE"             // 范围无效
```

#### 参数检查模板

```java
case "myFunc":
    // 检查参数数量
    if (args.length < 2) {
        return new String[]{"ERROR", "INVALID_ARGUMENTS"};
    }
    
    // 检查参数类型
    if (!(args[0] instanceof String)) {
        return new String[]{"ERROR", "ARGUMENT_MUST_BE_STRING"};
    }
    if (!(args[1] instanceof Number)) {
        return new String[]{"ERROR", "ARGUMENT_MUST_BE_NUMBER"};
    }
    
    // 执行逻辑
    String str = (String) args[0];
    int num = ((Number) args[1]).intValue();
    return doSomething(str, num);
```

### FunctionContext 说明

`FunctionContext` 提供调用时的环境信息：

```java
public class FunctionContext {
    public int getPid();           // 获取当前进程ID
    public int getPpid();          // 获取父进程ID
    public String getCurrentUser(); // 获取当前用户
    public boolean isLocal();      // 检查是否是local用户
}
```

使用示例：

```java
@Override
public Object call(String name, Object[] args, FunctionContext context) {
    switch (name) {
        case "getMyPid":
            return context.getPid();  // 返回调用者的PID
    }
    return null;
}
```

### 调试技巧

**1. 使用 Logger**

```java
import com.follarce.util.Logger;

Logger.debug("Function called: " + name);
Logger.info("Operation success");
Logger.error("Error: " + e.getMessage());
```

**2. 查看已注册函数**

```java
// 在 Main.java 中临时添加
System.out.println(FunctionRegistry.generateDocumentation());
```

**3. 测试脚本**

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

### 完整示例：MathFunctionProvider

```java
package com.follarce.plugin;

public class MathFunctionProvider implements FunctionProvider {

    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            case "math.abs":
                if (args.length < 1 || !(args[0] instanceof Number)) {
                    return new String[]{"ERROR", "INVALID_ARGUMENTS"};
                }
                return Math.abs(((Number) args[0]).intValue());
                
            case "math.max":
                if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
                    return new String[]{"ERROR", "INVALID_ARGUMENTS"};
                }
                return Math.max(((Number) args[0]).intValue(), ((Number) args[1]).intValue());
                
            case "math.min":
                if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
                    return new String[]{"ERROR", "INVALID_ARGUMENTS"};
                }
                return Math.min(((Number) args[0]).intValue(), ((Number) args[1]).intValue());
                
            default:
                return null;
        }
    }

    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[]{
            new FunctionInfo("math.abs", "Absolute value", 
                new String[]{"n: int"}, "int", "Math"),
            new FunctionInfo("math.max", "Maximum of two numbers", 
                new String[]{"a: int", "b: int"}, "int", "Math"),
            new FunctionInfo("math.min", "Minimum of two numbers", 
                new String[]{"a: int", "b: int"}, "int", "Math")
        };
    }

    @Override
    public String getProviderName() {
        return "MathFunctionProvider";
    }
}
```

脚本使用：

```fcl
x = math.abs(-10)       # 10
y = math.max(5, 8)      # 8
z = math.min(3, 7)      # 3
```

### 总结

| 操作 | 工作量 | 适用场景 |
|------|--------|----------|
| 修改现有 Provider | 2分钟 | 添加少量相关函数 |
| 创建新 Provider | 5分钟 | 添加一组新功能 |

**核心原则：只修改 Provider，不动其他代码！**

## 使用示例

### 示例1：基本文件操作
```
// 创建文件并写入内容
createFile("/user/local/app/", "test.txt")
write("/user/local/app/test.txt", "Hello World")

// 读取文件
result = read("/user/local/app/test.txt")
if result[0] == "SUCCESS" {
    content = result[1]
}
```

### 示例2：进程创建
```
pid = fork()
if pid == 0 {
    // 子进程
    exec("/user/local/app/child.txt", [])
} else {
    // 父进程
    waitPID(pid)
}
```

### 示例3：交换池使用
```
// 创建交换池
swapPool.create("shared")

// 添加变量（永久有效）
swapPool.add("counter:0", "shared", ["always"])

// 添加变量（限制读取3次）
swapPool.add("token:abc123", "shared", ["times(3)"])

// 获取变量
value = swapPool.get("counter", "shared")
```

### 示例4：网络下载
```
// 下载图片到指定目录（文件名自动提取）
result = webget("https://example.com/image.png", "/user/local/downloads/")
if result[0] == "SUCCESS" {
    filename = result[1]  // "image.png"
}

// 下载文件并设置30秒超时
result = webget("https://example.com/archive.zip", "/user/local/downloads/", 30000)
```

### 示例5：Socket TCP 通信

```fcl
# TCP 服务器（使用默认保存目录）
# Local 用户默认: /user/local/sockets/
# 普通用户 alice 默认: /user/alice/sockets/
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
            filename = recvResult[1]  # 例如: "socket_2_20260321_201145_123.dat"
        }
        
        # 发送响应
        socket.send(clientId, "Hello Client!")
        socket.close(clientId)
    }
    socket.close(serverId)
}

# TCP 客户端（使用默认保存目录）
result = socket.connect("127.0.0.1", 8080)
if result[0] == "SUCCESS" {
    socketId = int(result[1])
    socket.send(socketId, "Hello Server!")
    recvResult = socket.receive(socketId)
    socket.close(socketId)
}

# 普通用户尝试保存到 system 目录（会失败）
result = socket.createServer("127.0.0.1", 8081, "/system/data/")
# 返回: ["ERROR", "INSUFFICIENT_PERMISSION"]
```

### 示例6：Socket UDP 通信

```fcl
# 创建 UDP socket（端口 0 表示自动分配，使用默认保存目录）
result = socket.createUdp("0.0.0.0", 0)
if result[0] == "SUCCESS" {
    udpSocket = int(result[1])
    
    # 发送 UDP 数据包到指定地址
    socket.sendTo(udpSocket, "127.0.0.1", 9090, "Hello UDP!")
    
    # 接收数据（自动保存到默认目录）
    recvResult = socket.receive(udpSocket)
    if recvResult[0] == "SUCCESS" {
        filename = recvResult[1]
    }
    
    socket.close(udpSocket)
}
```

## 返回值规范

所有返回 `String[]` 的函数遵循统一格式：
- 成功：`["SUCCESS", null]` 或 `["SUCCESS", data]`
- 失败：`["ERROR", "ERROR_CODE"]`

常见错误码：

**文件操作错误码：**
- `INVALID_PATH` - 无效路径
- `FILE_DOES_NOT_EXIST` - 文件不存在
- `DIRECTORY_DOES_NOT_EXIST` - 目录不存在
- `FILE_EXIST` - 文件已存在
- `DIRECTORY_EXIST` - 目录已存在
- `INSUFFICIENT_PERMISSION` - 权限不足
- `FILE_IS_LOCKED` - 文件被锁定
- `DIRECTORY_IS_LOCKED` - 目录被锁定
- `IS_NOT_FILE` - 路径不是文件
- `IS_NOT_DIRECTORY` - 路径不是目录
- `DIRECTORY_IS_NOT_EMPTY` - 目录不为空

**进程操作错误码：**
- `PROCESS_DOES_NOT_EXIST` - 进程不存在
- `CANNOT_KILL_INIT` - 不能终止 INIT 进程
- `INSUFFICIENT_PERMISSION` - 权限不足

**用户管理错误码：**
- `INVALID_USERNAME` - 无效用户名
- `INVALID_PASSWORD` - 无效密码
- `USER_EXISTS` - 用户已存在
- `USER_NOT_EXISTS` - 用户不存在
- `CANNOT_REMOVE_LOCAL` - 不能删除 local 用户
- `SAVE_FAILED` - 保存失败
- `READ_FAILED` - 读取失败
- `INVALID_USER_DATA` - 用户数据无效
- `USERNAME_MUST_BE_STRING` - 用户名必须是字符串
- `PASSWORD_MUST_BE_STRING` - 密码必须是字符串
- `ISLOCAL_MUST_BE_BOOLEAN` - isLocal 必须是布尔值
- `TOO_MANY_ARGUMENTS` - 参数过多
- `UNKNOWN_FUNCTION` - 未知函数

**交换池错误码：**
- `POOL_EXISTS` - 交换池已存在
- `POOL_DOES_NOT_EXIST` - 交换池不存在
- `VARIABLE_EXISTS` - 变量已存在
- `VARIABLE_DOES_NOT_EXIST` - 变量不存在
- `VARIABLE_IS_LOCKED` - 变量被锁定
- `INSUFFICIENT_PERMISSION` - 权限不足

**网络下载错误码：**
- `INVALID_URL` - URL 为空或格式错误
- `INVALID_SAVE_DIR` - 保存目录为空
- `SAVE_DIR_MUST_BE_STRING` - 保存目录必须是字符串
- `CANNOT_EXTRACT_FILENAME` - 无法从 URL 提取文件名
- `TOO_MANY_REDIRECTS` - 重定向次数过多
- `RESOURCE_NOT_FOUND` - HTTP 404
- `ACCESS_FORBIDDEN` - HTTP 403
- `UNAUTHORIZED` - HTTP 401
- `SERVER_ERROR` - HTTP 5xx 错误
- `CONNECTION_TIMEOUT` - 连接超时
- `UNKNOWN_HOST` - 无法解析主机
- `CONNECTION_REFUSED` - 连接被拒绝
- `IO_ERROR` - I/O 错误
- `DOWNLOAD_FAILED` - 下载失败

**Socket 错误码：**
- `INVALID_HOST` - 无效主机地址
- `INVALID_PORT` - 无效端口号（1-65535）
- `INVALID_SAVE_DIR` - 无效保存目录
- `INVALID_DATA` - 无效数据
- `INVALID_TIMEOUT` - 无效超时时间
- `SOCKET_ID_MUST_BE_NUMBER` - socket ID 必须是数字
- `HOST_MUST_BE_STRING` - 主机地址必须是字符串
- `PORT_MUST_BE_NUMBER` - 端口必须是数字
- `DATA_MUST_BE_STRING` - 数据必须是字符串
- `SAVE_DIR_MUST_BE_STRING` - 保存目录必须是字符串
- `SOCKET_DOES_NOT_EXIST` - socket 不存在
- `SOCKET_CLOSED` - socket 已关闭
- `NOT_SERVER_SOCKET` - 不是服务器 socket
- `NOT_UDP_SOCKET` - 不是 UDP socket
- `INVALID_SOCKET_TYPE` - 无效 socket 类型
- `PORT_IN_USE` - 端口已被占用
- `CREATE_SOCKET_FAILED` - 创建 socket 失败
- `CONNECT_FAILED` - 连接失败
- `ACCEPT_FAILED` - 接受连接失败
- `ACCEPT_TIMEOUT` - 接受连接超时
- `SEND_FAILED` - 发送失败
- `RECEIVE_FAILED` - 接收失败
- `RECEIVE_TIMEOUT` - 接收超时
- `NO_DATA_RECEIVED` - 未接收到数据
- `CONNECTION_REFUSED` - 连接被拒绝

**通用错误码：**
- `INVALID_ARGUMENTS` - 无效参数
- `INVALID_JSON` - 无效 JSON 格式
- `CREATE_FAILED` - 创建失败
- `DELETE_FAILED` - 删除失败
- `WRITE_FAILED` - 写入失败
- `READ_FAILED` - 读取失败
- `RENAME_FAILED` - 重命名失败

## 日志系统

### 日志文件位置

日志文件 `app.log` 位于程序运行目录（JAR 包所在目录）。

### 日志格式

```
[2024-01-15 10:30:25] [INFO] Operation success
[2024-01-15 10:30:26] [ERROR] Error: file not found
```

### 启动和结束标记

每次程序运行会自动添加分隔线标记：

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


### 日志级别

- `DEBUG` - 调试信息
- `INFO` - 一般信息（默认级别）
- `WARN` - 警告信息
- `ERROR` - 错误信息

### 使用方式

```java
import com.follarce.basicUtil.Logger;

Logger.debug("Debug message");
Logger.info("Info message");
Logger.warn("Warning message");
Logger.error("Error message");
Logger.error("Error with exception", throwable);
```

## 用途

- 操作系统教学演示
- 虚拟化技术研究
- 脚本引擎开发参考
- 嵌入式系统原型

## 许可证

本项目采用 [MIT License](LICENSE) 开源许可证。