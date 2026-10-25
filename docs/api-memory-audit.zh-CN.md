# API 与内存控制核查（CilExec 0.0.4，历史资料）

> **0.0.5 说明。** 本文记录的是 0.0.4 的 260 个限定名称，其中大量旧式实体函数已删除。
> 当前接口请以 [0.0.5 函数与对象参考](fcl-function-reference.zh-CN.md) 和运行实例的
> `system.list()` 为准。

核查对象：当前源码与独立临时实例的 `system.list()` 输出。内置 FCL 接口共 **260 个限定名称**，含别名；`SourceExtensionIndex` 当前没有额外扩展。以下名称按运行时实际注册顺序列出。

## FCL 函数

| 命名空间 | 函数名（省略重复的命名空间前缀） |
| --- | --- |
| `math` (15) | `sin`、`cos`、`tan`、`sqrt`、`log`、`abs`、`round`、`floor`、`ceil`、`pow`、`max`、`min`、`pi`、`e`、`random` |
| `util` (18) | `toJson`、`fromJson`、`typeOf`、`isArray`、`isMap`、`isNumber`、`isString`、`isBool`、`toString`、`string`、`length`、`getTime`、`which`、`print`、`println`、`input`、`sleep`、`exit` |
| `array` (2) | `insert`、`removeAt` |
| `text` (9) | `slice`、`split`、`join`、`indexOf`、`lastIndexOf`、`commonPrefixLength`、`commonSuffixLength`、`repeat`、`replace` |
| `path` (11) | `normalize`、`resolve`、`getFileName`、`getParentPath`、`getParent`、`isAbsolute`、`join`、`setAlias`、`removeAlias`、`getAlias`、`listAliases` |
| `term` (39) | `color`、`paint`、`bold`、`dim`、`reset`、`clear`、`eraseLine`、`inverse`、`underline`、`strikethrough`、`alternate`、`mouse`、`paste`、`focus`、`bg`、`color256`、`bg256`、`trueColor`、`bgTrueColor`、`hideCursor`、`showCursor`、`displayWidth`、`truncate`、`cursorTo`、`cursorUp`、`cursorDown`、`cursorForward`、`cursorBack`、`red`、`green`、`yellow`、`blue`、`magenta`、`cyan`、`white`、`getSize`、`size`、`sanitize`、`render` |
| `env` (10) | `get`、`set`、`remove`、`list`、`getShared`、`listShared`、`setShared`、`removeShared`、`getSharedPolicy`、`setSharedPolicy` |
| `io` (7) | `print`、`println`、`input`、`readChar`、`readKey`、`readFile`、`writeFile` |
| `storage` (1) | `purgeUnreferenced` |
| `memory` (2) | `list`、`destroy` |
| `file` (17) | `read`、`readChunk`、`size`、`exists`、`list`、`readMetaData`、`write`、`append`、`createFile`、`createDir`、`remove`、`clear`、`rename`、`link`、`lock`、`renewLock`、`unlock` |
| `process` (15) | `args`、`arg`、`getPID`、`getPPID`、`listChildren`、`list`、`kill`、`pause`、`continue`、`fork`、`run`、`exec`、`wait`、`waitPID`、`removeFinished` |
| `swapPool` (13) | `create`、`remove`、`exists`、`list`、`add`、`get`、`update`、`clear`、`lock`、`renewLock`、`unlock`、`signal`、`waitFor` |
| `ipc` (14) | `createChannel`、`createTopic`、`removeChannel`、`removeTopic`、`subscribeChannel`、`subscribeTopic`、`sendDirect`、`sendChannel`、`publishTopic`、`broadcast`、`purge`、`receive`、`poll`、`consume` |
| `system` (7) | `list`、`kill`、`resolveEffect`、`exec`、`invoke`、`extensions`、`reset` |
| `user` (8) | `getCurrentUser`、`isLocal`、`validateUser`、`list`、`create`、`disable`、`remove`、`switchUser` |
| `program` (1) | `remove` |
| `terminal` (1) | `remove` |
| `timer` (1) | `purge` |
| `audit` (1) | `purge` |
| `network` (5) | `httpGet`、`webget`、`httpPost`、`webpost`、`download` |
| `socket` (6) | `connect`、`send`、`receive`、`close`、`bind`、`accept` |
| `package` (19) | `info`、`list`、`install`、`uninstall`、`build`、`run`、`verify`、`resource`、`pin`、`dataInfo`、`dataList`、`dataRead`、`dataExport`、`dataImport`、`clearData`、`dataQuota`、`setDataQuota`、`removeDataQuota`、`recover` |
| `packageData` (28) | `root`、`packageRoot`、`exists`、`packageExists`、`read`、`packageRead`、`readChunk`、`packageReadChunk`、`write`、`packageWrite`、`seedResource`、`packageSeedResource`、`append`、`packageAppend`、`mkdir`、`packageMkdir`、`list`、`packageList`、`remove`、`packageRemove`、`clear`、`packageClear`、`rename`、`packageRename`、`size`、`packageSize`、`usage`、`packageUsage` |
| `market` (10) | `configure`、`origin`、`update`、`search`、`info`、`download`、`install`、`list`、`help`、`run` |

`user.switchUser`、`system.resolveEffect`、`system.reset` 已注册但当前明确不可用。别名包括 `network.webget/webpost`、`util.print/println/input`、`io.readFile/writeFile` 以及 `packageData.package*` 等，已计入 260。交互终端可执行 `system.list()` 取得当前 JAR 的精确清单；`memory.list({"includeRuntime":true})` 可查看函数元数据。

## 其他对外入口

- JAR 命令：默认 `terminal`（别名 `repl`）、`runtime`、`migrate`、`export`、`package build`、`host move`、`compile`。
- 终端命令：`:help`、`:cd`、`:pwd`、`:ls`、`:clear`（`:cls`）、`:logout`、`:exit`（`:quit`）、`:shutdown`。普通输入按 FCL 执行。
- Runtime HTTP：仅 `GET /health/live`、`GET /health/ready`，监听本机 `127.0.0.1`。
- 独立 market-server HTTP：`GET/HEAD /market/v1/index.json`、`GET/HEAD /market/v1/{sha256}`、`POST /market/v1/publish`。它是独立 Java 项目，不随 Runtime JAR 自动启动。
- Runtime 的终端 TCP 协议与 HTTP 分开，默认监听 `127.0.0.1:8022`。

## /Users/local 的存储与内存

`AdministratorUserPath.route` 每次解析时去掉一个指向管理员自身的 `/Users/local` 前缀，最后返回同一个真实根目录或虚拟 `/Users` 列表。算法逐次缩短输入路径，单次解析会终止。重复层级没有对应的 VFS 节点、文件内容或目录树复制。终端 `:cd` 只覆盖 `terminal.session.working_directory` 一列；`:ls` 从用户记录生成列表。

当前 **没有路径字符串总长度限制**：人为持续 `:cd Users/local` 会使同一会话的工作目录字符串越来越长，路径解析也越来越耗时；这不是无限创建目录，但仍是资源边界缺口。

## 内存与并发控制

| 项目 | 当前控制 |
| --- | --- |
| FCL 变量 | 进程 continuation 在每次执行切片事务提交到 PostgreSQL；重启后重建。调用函数时参数和结果深拷贝。 `memory.list` 返回当前变量及函数的快照；`memory.destroy` 删除当前作用域的变量或容器元素，提交后持久化，JVM 内存由 GC 回收。它们不是 JVM 堆配额 API。 |
| 调度与效果 | 默认 10 个调度 worker、6 个效果 worker；单类最多 256；待处理工作和租约在数据库中。每个执行切片最多 4096 步或 20 毫秒。 |
| 数据库连接 | 默认运行池最多 20、效果池最多 6；配置校验运行池至少为调度数 + 效果数 + 2。 |
| 终端 | 最多 128 个连接；每连接输入队列 64 KiB；单次交互源码最多 256 Ki 字符。健康请求并发最多 64。 |
| 文件与外部效果 | `file.read` 等整文件内存读取最多 16 MiB；单 VFS 文件最多 1 GiB，较大文件用分块 API。HTTP 响应体最多 4 MiB；命令/套接字效果输出最多 1 MiB；包数据库读入最多 64 MiB。 |
| 缓存 | `process.run` 编译结果 LRU 缓存最多 64 项；程序与其他缓存应按各自实现核查。 |
| JVM 堆 | 直接 `java -jar` 没有显式 `-Xmx`，堆大小由 JVM/宿主环境决定，系统没有统一的每用户或每进程内存配额。 |

## 需要关注的边界

1. 重复虚拟路径不会扩张 VFS，但 `FclPath.normalizeAbsolute` 和终端工作目录列没有最大路径长度；建议设定统一上限并在 `:cd` 和 FCL 路径入口拒绝超限。
2. `memory.list` 及函数调用的深拷贝可能暂时复制大型变量；当前没有总变量内存配额。
3. `VolatileProcessService` 使用不带并发上限的虚拟线程执行器；大量同时运行的 `process.run` 任务仍可能增加堆占用。
4. `InteractionViewport` 的按用户尺寸映射没有显式移除入口；长期大量不同用户连接会留有一条尺寸记录。

核查方式：源码读取；仅在隔离临时实例运行 `system.list()`，未向用户正在运行的数据库写入数据。
