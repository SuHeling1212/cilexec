# FCL 函数与终端命令参考

本文件对应当前 CilExec Runtime 的实际函数注册表。终端中没有前缀 `:` 的完整输入会作为 FCL 执行；建议始终写完整名称，例如 `file.read("/note.txt")`，避免同名短名称产生歧义。

用 `[]` 表示可选参数，`<...>` 表示必填参数。路径默认相对当前终端工作目录解析；使用 `:cd` 改变工作目录，不存在 `path.cd()`、`path.ls()` 等 FCL 函数。

## 先看这几个例子

```fcl
file.createDir("/demo")
file.write("/demo/hello.txt", "Hello")
file.append("/demo/hello.txt", " world")
file.read("/demo/hello.txt")

pkg = package.build("/demo/package.json", "/packages/demo.db")
package.install("/packages/demo.db", "demo")
package.run("demo")
```

管理员不需要使用另一套 `file.admin*` 函数。文件函数的末尾可传目标用户，例如 `file.read("/home/a.txt", "alice")`；普通用户只能访问自身文件，拥有 `SYSTEM_ADMIN` 的管理员可访问任意用户的文件，且会留下审计记录。

## 终端冒号指令（不是 FCL 函数）

| 指令 | 作用 |
| --- | --- |
| `:help` | 显示终端帮助。 |
| `:cd <路径>` | 切换并持久化当前 VFS 工作目录。目标必须是目录。 |
| `:pwd` | 显示当前工作目录。 |
| `:ls [路径]` | 列出当前目录或指定目录的子项；目录名带 `/`。 |
| `:exp-start [路径]` | 开始录制后续终端操作；默认目标是当前目录的 `terminal-export.fcl`。录制状态可跨断线和 Runtime 重启。 |
| `:exp-end` | 停止录制，将区间内的操作写成 FCL 脚本，然后删除临时录制数据。已有目标文件会原子覆盖并保留修订历史。 |
| `:logout` | 回到登录界面，保留该用户的终端状态和工作目录。 |
| `:exit` | 断开当前终端连接；共享 Runtime 和后台进程继续运行。 |
| `:shutdown` | 输入当前管理员密码并关闭共享 Runtime；仅拥有 `SYSTEM_ADMIN` 权限的用户可用。 |

在真实交互终端中，`↑` / `↓` 选取当前用户先前输入的 FCL 或冒号指令，`←` / `→` 在当前输入行内移动光标。历史记录按用户持久化，重新登录、重启 Runtime 或重启容器后仍可用。用户名、密码和 `io.input()` 的原始输入不会进入历史记录。

只有 `:exp-start` 与 `:exp-end` 之间的操作会进入临时录制。FCL 输入按原样写入；`:cd`、`:ls` 等终端指令以注释保存，因为它们不是 FCL 语法。两个边界指令、用户名、密码及 `io.input()` 输入都不会写入脚本。成功导出后，数据库会级联删除该录制及其中的操作，因此系统不会为了导出而永久保存全部历史；方向键历史仍独立保留最近 200 条。

所有在线用户共享一个 Runtime JVM、一个数据库连接池以及有上限的 worker 池。默认有 4
个 scheduler worker 和 2 个 effect worker。超过 worker 数量的进程留在持久化 FIFO
队列中；终端进程每个时间片最多执行 4096 个纯步骤或 20 ms，随后持久化并重新排队。
另有 1 个事件驱动的 Ctrl+C 中断 worker；它只在 PostgreSQL 中断通知到达时唤醒，
不轮询。已设置持久中断标志的进程会被普通 scheduler worker 排除，只能由该
中断 worker 领取并在安全点取消。

编辑器不是冒号指令，也不内置在 Runtime 中。它是宿主机本地市场发布的 SQLite FCL
包。第一次使用时先下载和安装，之后可以在持久终端上下文中直接调用：

```fcl
network.download("http://host.docker.internal:8787/market/v1/28bb03a8fd62788100447513a1a7de56123713fcf74811e1aa6fec41bb4b9008", "/editor.db")
package.install("/editor.db", "editor")
import "28bb03a8fd62788100447513a1a7de56123713fcf74811e1aa6fec41bb4b9008" as "editor"
editor.open("notes.txt")
```

其包坐标为 `cilexec/editor/1.0.4`，绑定名为 `editor`，公开函数为
`editor.open(path)`。包导入只接受已安装 `.db` 文件的 64 位 SHA-256，不接受包名或
`namespace/name/version` 坐标；不写别名时，完整 SHA-256 本身就是函数命名空间，调用
形式为 `<完整SHA256>.函数(...)`。

## 通用数据与权限规则

| 项目 | 说明 |
| --- | --- |
| 字符串 | 使用双引号，例如 `"hello"`。 |
| 数组 | 使用 `[]`，例如 `["VFS_READ", "VFS_WRITE"]`。 |
| 对象 | 使用 `{}`，例如 `{"name":"demo"}`。 |
| 路径 | 相对路径以 `:pwd` 的结果为基准；`/` 开头是绝对 VFS 路径。 |
| 管理员 | `local` 初始账户拥有 `SYSTEM_ADMIN`。函数会以当前登录用户身份授权。 |
| 外部操作 | 输入、打印、HTTP、Socket、系统命令会暂停 FCL 进程，完成后自动恢复。 |
| 释放变量 | `memory.destroy("name")` 立即递归清空数组/对象容器并删除当前作用域的变量绑定；本语句提交后该值不再出现在持久化 continuation 中。返回 `true` 表示实际删除，变量不存在时返回 `false`。FCL 值赋值时会深拷贝，不存在共享对象别名；它不会删除 VFS 文件或包。 |
| 查看实际名称 | `system.ls()` 返回本次 Runtime 可调用的全部限定函数名和别名。 |
| 查看 Java 扩展 | `system.extensions()` 返回构建时封装进系统的固定扩展清单。 |

常见能力名称：`PROCESS_CREATE`、`PROCESS_CONTROL_OWN`、`PROCESS_CONTROL_ANY`、`VFS_READ`、`VFS_WRITE`、`PACKAGE_IMPORT`、`PACKAGE_BIND`、`EFFECT_REQUEST`、`TERMINAL_ATTACH`、`AUDIT_READ`、`SYSTEM_ADMIN`。普通用户在注册时获得其所有者范围内的常用能力；管理员额外拥有全部权限。

## 数学：`math`

| 调用 | 作用 |
| --- | --- |
| `math.sin(x)` / `math.cos(x)` / `math.tan(x)` | 三角函数，参数为数字。 |
| `math.sqrt(x)` | 平方根；`x` 不得小于 0。 |
| `math.log(x)` | 自然对数；`x` 必须大于 0。 |
| `math.abs(x)` | 绝对值。 |
| `math.round(x)` / `math.floor(x)` / `math.ceil(x)` | 四舍五入、向下取整、向上取整。 |
| `math.pow(base, exponent)` | 幂。 |
| `math.max(a, b)` / `math.min(a, b)` | 两数最大值、最小值。 |
| `math.pi()` / `math.e()` | 圆周率和自然常数。 |
| `math.random()` | 返回 0 到 1 之间的随机小数。 |
| `math.random(lower, upper)` | 返回 `[lower, upper)` 内的随机整数，`upper` 必须大于 `lower`。 |

## 通用工具：`util`

| 调用 | 作用 |
| --- | --- |
| `util.toJson(value)` | 将值编码为 JSON 文本。 |
| `util.fromJson(text)` | 解析 JSON 文本。 |
| `util.typeOf(value)` | 返回 FCL 类型名。 |
| `util.isArray(value)` / `util.isMap(value)` | 判断数组或对象。 |
| `util.isNumber(value)` / `util.isString(value)` / `util.isBool(value)` | 判断数字、字符串、布尔值。 |
| `util.toString(value)` | 转为 FCL 显示文本。别名：`util.string(value)`。 |
| `util.length(value)` | 返回字符串、数组、对象等的长度；也可使用一元 `#`。 |
| `util.getTime()` | 当前 Runtime 时间，Unix 毫秒时间戳。 |
| `util.which(functionName)` | 查询函数来源。Runtime 内置和编译期 Java 扩展返回 `0`；已导入的外部 FCL 包函数返回包 `.db` 文件的 64 位 SHA-256；未知或当前源码函数返回 `null`。 |
| `util.print(value)` / `util.println(value)` | 输出到终端，不换行 / 换行。等价于 `io.print` / `io.println`。 |
| `util.input([prompt])` | 可选地显示提示并等待一行用户输入。等价于 `io.input`。 |
| `util.sleep(milliseconds)` | 暂停当前进程指定毫秒后恢复。 |
| `util.exit([result])` | 正常结束当前 FCL 进程，可指定返回值。 |

## 路径与别名：`path`

这些函数只处理路径字符串或当前 FCL 进程中的别名；切换目录请使用 `:cd`。

| 调用 | 作用 |
| --- | --- |
| `path.normalize(path)` | 规范化 `.`、`..`、重复斜杠。 |
| `path.resolve(path)` | 规范化路径字符串；不改变终端目录。 |
| `path.getFileName(path)` | 取最后一个路径段。 |
| `path.getParentPath(path)` | 取父路径。别名：`path.getParent(path)`。 |
| `path.isAbsolute(path)` | 判断是否以 `/` 开头。 |
| `path.join(part1, part2, ...)` | 拼接并规范化多个路径段；无参数返回 `/`。 |
| `path.getEnvVar(name)` | 读取进程固有值 `PWD`、`USER`、`USER_ID`、`PID`；其他名称返回 `null`。用户配置使用 `env`。 |
| `path.setAlias(name, path)` | 在当前 FCL 上下文保存路径别名。 |
| `path.removeAlias(name)` | 删除别名，返回是否删除成功。 |
| `path.getAlias(name)` | 读取别名，未找到时返回 `null`。 |
| `path.listAliases()` | 返回全部当前 FCL 别名。 |

## 持久环境变量：`env`

变量名不区分大小写，保存时统一转为大写。用户变量优先于同名共享变量，值最大 64 KiB。
普通用户只能管理自己；管理员可把用户名或用户 UUID 作为最后一个参数，查看、设置或删除
任意用户的变量。

| 调用 | 作用 |
| --- | --- |
| `env.get(name [, targetUser])` | 读取用户变量；没有用户值时读取共享默认值。 |
| `env.set(name, value [, targetUser])` | 持久设置用户变量。 |
| `env.remove(name [, targetUser])` | 删除用户变量。 |
| `env.list([targetUser])` | 返回用户值覆盖共享默认值后的完整环境。 |
| `env.getShared(name)` / `env.listShared()` | 读取共享变量，所有用户可用。 |
| `env.setShared(name, value)` / `env.removeShared(name)` | 管理共享变量，仅管理员可用。 |
| `env.getSharedPolicy()` | 查看共享变量名策略。 |
| `env.setSharedPolicy(mode, names)` | 设置 `ALLOWLIST` 或 `DENYLIST`，仅管理员可用。 |

## 终端样式：`term`

这些函数返回 ANSI 控制文本，通常配合 `io.print()` / `io.println()` 使用。

| 调用 | 作用 |
| --- | --- |
| `term.color(color, value)` | 使用颜色包裹文本。别名：`term.paint(color, value)`。颜色可为 `black`、`red`、`green`、`yellow`、`blue`、`magenta`、`cyan`、`white`。 |
| `term.red(value)`、`term.green(value)`、`term.yellow(value)`、`term.blue(value)`、`term.magenta(value)`、`term.cyan(value)`、`term.white(value)` | 对应颜色的快捷写法。 |
| `term.bold(value)` / `term.dim(value)` | 加粗 / 弱化文本。 |
| `term.reset()` | 返回样式重置控制序列。 |
| `term.clear()` / `term.eraseLine()` | 返回清屏 / 清除当前行控制序列。 |
| `term.cursorUp(n)` / `term.cursorDown(n)` | 返回光标上移 / 下移 `n` 行的控制序列。 |
| `term.cursorForward(n)` / `term.cursorBack(n)` | 返回光标右移 / 左移 `n` 列的控制序列。 |
| `term.cursorTo(row, column)` | 返回绝对光标定位控制序列，行列从 1 开始。 |
| `term.inverse(value)` | 使用反显样式包裹文本。 |
| `term.hideCursor()` / `term.showCursor()` | 隐藏 / 显示终端光标。 |
| `term.getSize()` | 返回当前终端字符尺寸，例如 `{"width":120,"height":40}`。别名：`term.size()`。全屏 TUI 等待按键时每 100ms 刷新一次，因此尺寸变化会在下一次重绘中体现。 |

## 文本处理：`text`

| 调用 | 作用 |
| --- | --- |
| `text.slice(value, start [, end])` | 按字符索引截取字符串。 |
| `text.split(value, delimiter)` | 拆分字符串并保留末尾空项。 |
| `text.join(values, delimiter)` | 使用分隔符连接数组。 |
| `text.indexOf(value, search [, start])` | 从指定位置向后查找，未找到返回 `-1`。 |
| `text.lastIndexOf(value, search [, start])` | 从指定位置向前查找，未找到返回 `-1`。 |
| `text.repeat(value, count)` | 重复字符串。 |
| `text.replace(value, search, replacement)` | 替换全部匹配文本。 |

## 输入与输出：`io`

| 调用 | 作用 |
| --- | --- |
| `io.print(value)` | 输出但不换行。别名：`util.print(value)`。 |
| `io.println(value)` | 输出并换行。别名：`util.println(value)`。 |
| `io.input([prompt])` | 等待一整行输入。别名：`util.input([prompt])`。当进程等待输入时，终端提示符变为 `pid:?`。 |
| `io.readChar()` | 等待输入并返回第一个字符；空输入返回空字符串。 |
| `io.readKey()` | 全屏 FCL 程序专用：立即读取一个按键，并将方向键、Ctrl 键等规范化为名称。 |
| `io.readFile(path [, targetUser])` | `file.read` 的别名。 |
| `io.writeFile(path, content [, targetUser])` | `file.write` 的别名。 |

## 文件与目录：`file`

`targetUser` 可以是用户名或用户 UUID。只有管理员能传入其他用户；不传时默认当前用户。所有读写均在 VFS 中完成，不对应宿主机真实路径。

| 调用 | 作用 |
| --- | --- |
| `file.read(path [, targetUser])` | 读取 UTF-8 文件文本。单次 FCL 字符串超过 JVM 上限时使用 `file.readChunk`。 |
| `file.readChunk(path, offset, maximumBytes [, targetUser])` | 读取 UTF-8 区间；`offset` 非负，单次最多 4 MiB。 |
| `file.size(path [, targetUser])` | 返回逻辑文件字节数。 |
| `file.exists(path [, targetUser])` | 判断路径是否存在。 |
| `file.listdir([path [, targetUser]])` | 返回目录子项的元数据数组；无路径时列当前目录。 |
| `file.readMetaData(path [, targetUser])` | 返回节点元数据，如 `nodeId`、`ownerId`、`type`、`objectHash`。 |
| `file.write(path, content [, targetUser])` | 新建或覆盖文本文件。 |
| `file.append(path, content [, targetUser])` | 追加文本；使用分块存储，不会加载整个旧文件。 |
| `file.createFile(path [, content [, targetUser]])` | 只在文件不存在时创建。若要只指定目标用户，内容位置传空字符串。 |
| `file.createDir(path [, targetUser])` | 创建目录。 |
| `file.removeFile(path [, targetUser])` | 删除文件。 |
| `file.removeDir(path [, targetUser])` | 删除空目录。 |
| `file.rename(path, newName [, targetUser])` | 在原目录中重命名；`newName` 不能含 `/`。 |
| `file.link(linkPath, targetPath)` | 创建内容为目标路径的符号链接节点；仅当前用户范围。 |
| `file.lock(path, leaseMilliseconds)` | 获取文件租约锁，成功返回 `{fencingToken, leaseUntil}`，失败返回 `null`。 |
| `file.renewLock(path, fencingToken, leaseMilliseconds)` | 续期文件锁。 |
| `file.unlock(path, fencingToken)` | 释放当前进程持有的文件锁。 |

单个 VFS 文件至少支持 1 GiB。大文件应通过多次 `file.append` 写入，并用
`file.size` 和 `file.readChunk` 检查或分段读取；不要用 `file.read` 一次性装入 JVM 字符串。

## 进程：`process`

普通用户只能看到自己的进程。管理员可看到所有进程，并可控制其他用户的进程。

| 调用 | 作用 |
| --- | --- |
| `process.getPID()` / `process.getPPID()` | 返回当前 PID / 父 PID；无父进程时 PPID 为 `0`。 |
| `process.getListOfChildProcess()` | 返回当前进程的子 PID 数组。 |
| `process.getList()` | 返回可见进程元数据数组。别名：`process.getListOfProcess()`。 |
| `process.kill(pid)` | 终止指定进程；终止自身等同 `util.exit()`。别名：`system.kill(pid)`。 |
| `process.pause(pid)` | 暂停其他可控制进程。 |
| `process.continue(pid)` | 恢复已暂停的可控制进程。 |
| `process.fork()` | 复制当前 FCL 执行上下文并创建子进程，返回子 PID。 |
| `process.exec(programId)` | 将当前进程切换为已存在的程序并挂起等待执行。 |
| `process.wait()` | 等待一个仍在运行的子进程；若没有活动子进程，返回空数组。 |
| `process.waitPID(pid)` | 等待可访问的指定 PID，结束后返回 `{pid, status}`。 |

## 用户：`user`

| 调用 | 作用 |
| --- | --- |
| `user.getCurrentUser()` | 返回当前用户 UUID。 |
| `user.isLocal()` | 判断当前用户是否拥有 `SYSTEM_ADMIN`。 |
| `user.validateUser(usernameOrUuid)` | 验证用户是否存在且对当前用户可见；普通用户只会验证自身。 |
| `user.getListOfUsers()` | 返回所有用户的基本信息；需要管理员身份。 |
| `user.createUser(username, password [, capabilities])` | 创建用户；`capabilities` 是能力名称数组；需要管理员身份。 |
| `user.removeUser(userUuid)` | 停用用户；需要管理员身份。 |
| `user.switchUser(...)` | **当前不可用**。持久进程不能在原地更换身份；请使用 `:logout` 后重新登录。 |

## 网络与一次性 Socket：`network`、`socket`

这些函数属于外部效果，会等待结果后恢复。`httpGet/httpPost` 的普通响应最多 4 MiB；`network.download` 使用下表所述的独立分块限制。Socket 是一次性操作，不保存可跨崩溃复用的连接句柄。

| 调用 | 作用 |
| --- | --- |
| `network.httpGet(url)` | 发起 HTTP/HTTPS GET，返回 `{status, body, headers}`。别名：`network.webget(url)`。 |
| `network.httpPost(url, body)` | 发起 HTTP/HTTPS POST，返回 `{status, body, headers}`。别名：`network.webpost(url, body)`。 |
| `network.download(url, vfsPath)` | 以 4 MiB 持久化分块下载二进制文件并原样写入当前用户 VFS，返回路径、状态码、大小和媒体类型。单文件上限为 1 GiB；服务器必须支持 HTTP Range 才能下载超过 4 MiB 的文件。 |
| `socket.connect(host, port)` | 验证能否连接，返回端点信息后即关闭连接。 |
| `socket.send(host, port, data)` | 连接、发送 UTF-8 数据、关闭，返回写入字节数。也可用 `socket.send({"host":"…","port":123}, data)`。 |
| `socket.receive(host, port [, maximumBytes])` | 连接并读取文本后关闭。 |
| `socket.close(...)` | 返回 `true`；Socket 每次调用已自动关闭。 |
| `socket.bind([port])` | 短暂绑定端口并返回 `{host, port, oneShot}`，不会保持监听。 |
| `socket.accept(port [, maximumBytes])` | 短暂监听并接受一次连接，最长等待 30 秒，返回远端与读取数据。 |

## 包：`package`

包是不可变 SQLite `package.db` 文件。推荐流程：`package.build` → `package.install` → `import` 或 `package.run`。
`import` 只导入包，并且目标必须是已安装包数据库文件的 SHA-256；别名可选。普通
FCL 源文件使用 `include "path.fcl"`，会在编译前原样插入到该位置，不能用 `import`。
`package.json` 必须声明 `kind`。`application` 必须提供零参数的通用 `run` 入口；
`library` 用作导入或依赖，可以没有入口。依赖清单完整保存在包内，包含命名空间、名称、
版本约束和是否可选。

宿主机市场的默认索引是 `http://127.0.0.1:8787/market/v1/index.json`；容器内使用
`http://host.docker.internal:8787/market/v1/index.json`。完整方案见 `docs/package-market.md`。

| 调用 | 作用 |
| --- | --- |
| `package.info(coordinateOrHash)` | 查询包及其 `kind`、依赖、入口、导出和能力列表。参数可以是 `namespace/name/version` 或 64 位包哈希；也可传 `(namespace, name, version)` 三个参数。 |
| `package.list()` | 返回已登记的包发行版。 |
| `package.install(vfsPath [, binding])` | 从 VFS 的 `.db` 文件安装到当前用户默认环境。 |
| `package.install(vfsPath, environmentUuid, binding)` | 安装并绑定到指定环境。 |
| `package.build(manifestPath, outputPath)` | 读取 VFS 中的 `package.json` 和声明文件，构建 `.db` 到 VFS。 |
| `package.run(binding [, entrypoint])` | 创建子进程运行已绑定包入口；默认入口为 `run`，返回 PID 等信息。 |
| `package.createEnvironment(name)` | 创建当前用户的包环境。 |
| `package.environments()` | 列出当前用户包环境。 |
| `package.verify(coordinateOrHash)` | 校验包数据库对象是否仍与安装时的 SHA-256 哈希一致。也支持三段坐标参数。 |
| `package.resource(coordinateOrHash, resourcePath)` | 读取包中声明的文本资源。 |
| `package.pin(environmentUuid, binding, coordinateOrHash)` | 将环境绑定固定到发行版。也可将坐标拆为后三个参数。 |
| `package.unpin(environmentUuid, binding)` | 删除一个环境绑定。 |
| `package.remove(environmentUuid, binding)` | `unpin` 的同义操作，删除绑定。 |
| `package.gc()` | 管理员接口；当前不可变包不会被实际删除，返回 `0`。 |
| `package.recover()` | 管理员恢复检查入口，当前返回 `true`。 |

## 交换池（进程间数据）：`swapPool`

交换池属于当前用户，适合进程间传值、信号和加锁。

| 调用 | 作用 |
| --- | --- |
| `swapPool.create(path)` / `swapPool.remove(path)` | 创建 / 删除交换池。 |
| `swapPool.exists(path)` | 判断交换池是否存在。 |
| `swapPool.list()` | 列出当前用户全部交换池。 |
| `swapPool.ls(path)` | 列出池中的变量。 |
| `swapPool.add("name:value", pool [, option...])` | 添加变量。可选 `"type:sync"` 或 `"type:times(n)"` 控制保留方式。 |
| `swapPool.get(pool, variable)` | 读取并消费变量值；未找到时返回 `null`。 |
| `swapPool.update(pool, variable, value [, fencingToken])` | 更新变量；有锁时传入围栏令牌。 |
| `swapPool.removeVar(pool, variable [, fencingToken])` | 删除变量。 |
| `swapPool.clear(pool)` | 清空池。 |
| `swapPool.lock(pool, variable, leaseMilliseconds)` | 获取变量锁，成功返回 `{fencingToken, leaseUntil}`。 |
| `swapPool.renewLock(pool, variable, fencingToken, leaseMilliseconds)` | 续期变量锁。 |
| `swapPool.unlock(pool, variable, fencingToken)` | 释放变量锁。 |
| `swapPool.signal(pool, variable)` | 发送变量信号。 |
| `swapPool.waitFor(pool, variable)` | 等待信号；收到后返回 `true`。 |

## 系统：`system`

| 调用 | 作用 |
| --- | --- |
| `system.ls()` | 返回当前 Runtime 注册的全部函数名和别名。 |
| `system.ls(path)` | 列出当前用户该目录下的节点元数据；终端中更易读的目录列表请用 `:ls`。 |
| `system.extensions()` | 返回源码构建期封装的 Java 扩展 `id`、`version`、`description`。运行期不能增删或替换扩展。 |
| `system.kill(pid)` | `process.kill(pid)` 的别名。 |
| `system.exec(command)` | 管理员执行宿主机允许名单内命令。`command` 可为字符串或字符串数组；不经 Shell，且必须设置 `CILEXEC_FCL_EXEC_ALLOWLIST`，默认不允许任何程序。 |
| `system.invoke(qualifiedFunction [, argumentArray])` | 管理员用字符串调用其他 FCL 函数，例如 `system.invoke("file.read", ["/x.txt", "alice"])`。不能调用自身。 |
| `system.forceRemove(path)` | 管理员按路径删除当前用户文件或目录。 |
| `system.forceRemove(targetUserUuid, nodeUuid)` | 管理员强制删除指定用户的节点。 |
| `system.resolveEffect(...)` | **当前不可用**。外部效果由 Runtime 控制平面处理。 |
| `system.reset(...)` | **当前不可用**。Runtime 重置不开放给 FCL。 |

## 快速查询与排错

```fcl
system.ls()                         // 查看实际加载的函数
path.getEnvVar("PWD")              // 当前工作目录
user.getCurrentUser()               // 当前用户 UUID
user.isLocal()                      // 是否管理员
process.getList()                   // 可见进程
file.listdir(".")                  // 当前目录元数据
package.list()                      // 已登记包
```

如果函数提示权限不足，先运行 `user.isLocal()` 和 `user.getCurrentUser()`；普通用户无法通过传入其他用户名绕过权限。若 `io.input()` 正在等待输入，直接在 `pid:?` 提示符输入内容；如果要传入以冒号开头的原始文本，使用 `::文本`。

## 实现来源

本手册由下列当前代码注册点整理：

- `src/main/java/com/follarce/fcl/FclBuiltins.java`：纯数学、工具、路径和终端样式函数。
- `src/main/java/com/follarce/application/FclRuntimeFunctions.java`：文件、进程、用户、包、网络、交换池和系统函数。
- `src/main/java/com/follarce/terminal/DatabaseTerminalControl.java`：终端冒号指令。
