# CilExec 市场

## 组成

完整市场由两个独立部分组成：

```text
宿主机包仓库                         CilExec 内的 market FCL 包
──────────────────                   ──────────────────────────
保存不可变 .db 包                     下载完整索引并缓存到用户 VFS
提供 /market/v1/index.json  ─────►    本地自然语言搜索和信息查询
提供 /market/v1/<sha256>    ◄─────    按 SHA-256 下载、安装和管理包
```

`market/server.py` 只是仓库。真正供用户操作的市场客户端是
`cilexec/market/1.0.2` FCL 应用包。市场复用现有网络、VFS 和包管理函数，
不引入第二套包格式，也不改变 FCL 语法。

## 市场地址

CilExec 只通过一个宿主机 HTTP origin 与包仓库通信：

```text
http://host.docker.internal:8787
```

该 origin 下的市场接口为：

| HTTP 路径 | 功能 |
| --- | --- |
| `GET /market/v1/index.json` | 返回仓库中全部软件包的 JSON 列表。 |
| `GET /market/v1/{sha256}` | 按 64 位 SHA-256 包 ID 下载对应 `.db`。 |

`v1` 是市场索引协议的主版本，不是软件包版本。将来出现不兼容协议时可以新增
`/market/v2/...`，而不破坏旧客户端。

旧的 `/v1/index.json` 和坐标文件路径暂时保留兼容，新市场客户端只使用
`/market/v1/...`。

## 包 ID

所有市场操作统一使用 SHA-256，不使用 SHA-512。

市场包 ID 是完整 `.db` 包文件的 SHA-256：64 个小写十六进制字符。例如编辑器
1.0.4 的当前 ID 是：

```text
28bb03a8fd62788100447513a1a7de56123713fcf74811e1aa6fec41bb4b9008
```

该值同时用于：

- 索引记录中的 `sha256`；
- `/market/v1/{sha256}` 下载路径；
- `mkt.info`、`mkt.download`、`mkt.install` 和 `mkt.uninstall` 参数；
- `util.which` 对外部包函数的返回值；
- Runtime 安装时保存和复核的 `databaseFileHash`。

SHA-256 是内容标识，不是签名，也不代表发布者身份。

## 完整索引

市场客户端调用 `mkt.update()` 时一次性下载全部软件包列表。列表体积较小，下载后
保存在当前用户 VFS 的 `/market/index.json`。之后 `mkt.search()` 和 `mkt.info()`
只读取本地文件，不会为每个关键词重新请求服务器。

索引结构示例：

```json
{
  "apiVersion": "cilexec.market/v1",
  "packages": [
    {
      "coordinate": "cilexec/editor/1.0.4",
      "namespace": "cilexec",
      "name": "editor",
      "version": "1.0.4",
      "kind": "application",
      "summary": "自适应终端文本编辑器",
      "description": "具有 nano 级基础编辑能力的 FCL 文本编辑器。",
      "tags": ["editor", "text", "tui", "编辑器", "文本"],
      "download": "/market/v1/28bb03a8fd62788100447513a1a7de56123713fcf74811e1aa6fec41bb4b9008",
      "sha256": "28bb03a8fd62788100447513a1a7de56123713fcf74811e1aa6fec41bb4b9008",
      "bytes": 45056,
      "mediaType": "application/vnd.sqlite3",
      "dependencies": [],
      "latest": true
    }
  ]
}
```

`market/catalog.json` 保存简介、描述和标签；服务器将这些展示信息与 `.db` 中的坐标、
类型、版本和依赖合并为索引。搜索会把输入按空格分为多个词，并要求每个非空词都能
在该包的本地索引记录中找到，因此可以搜索名称、坐标、简介、标签或中文描述。

## 安装市场包

市场自身也以普通 `.db` 包分发。当前 `cilexec/market/1.0.2` 的包 ID 是：

```text
e65c7741c15f9cda04581d6883556092408800f5f34dd970aba85caad0a59229
```

在 CilExec 终端中执行：

```fcl
network.download("http://host.docker.internal:8787/market/v1/e65c7741c15f9cda04581d6883556092408800f5f34dd970aba85caad0a59229", "/market.db")
package.install("/market.db", "market")
import "e65c7741c15f9cda04581d6883556092408800f5f34dd970aba85caad0a59229" as "mkt"
mkt.run()
```

市场也具有应用统一入口：

```fcl
package.run("market")
```

## 市场函数

| 函数 | 功能 | 状态变化 |
| --- | --- | --- |
| `mkt.search(text)` | 在本地完整索引中进行多关键词搜索，返回包记录数组。 | 无 |
| `mkt.info(sha256)` | 按包 ID 返回完整索引记录；不存在时返回 `null`。 | 无 |
| `mkt.list()` | 返回所有已登记包的包名和分发文件 SHA-256；同名版本分别列出。 | 无 |
| `mkt.download(sha256)` | 按哈希地址下载到 `/market/packages/{sha256}.db`。 | 写入当前用户 VFS |
| `mkt.install(sha256)` | 下载、安装并记录绑定与环境信息。 | 安装并绑定包 |
| `mkt.update()` | 重新下载完整索引到 `/market/index.json`。 | 更新当前用户索引 |
| `mkt.upgrade()` | 更新索引并升级所有由市场管理且存在新版本的包。 | 可能更新包绑定 |
| `mkt.uninstall(sha256)` | 删除该市场安装记录对应的包绑定。 | 解除绑定 |
| `mkt.help()` | 在终端显示所有市场函数。 | 无 |
| `mkt.run()` | 无需配置镜像，显示 Market 版本和帮助界面。 | 无 |

除 `mkt.run()` 外，首次调用任何 Market 操作时，如果当前用户尚未设置
`MARKET_ORIGIN`，客户端会进入单镜像配置流程并通过 `io.input()` 要求输入一个
`http://` 或 `https://` origin。当前只保存一个镜像；再次配置可直接用
`env.set("MARKET_ORIGIN", "...")` 覆盖。
终端会先显示英文提示 `Please enter the mirror source address> `，不会再在
空白画面中等待输入。

`mkt.uninstall()` 删除的是安装绑定和市场凭据。不可变包内容仍按 Runtime 的内容存储
策略保留，避免破坏仍被其他环境或进程引用的数据。

## 函数来源查询

Runtime 新增：

```fcl
util.which("函数名")
```

返回规则：

| 函数来源 | 返回值 |
| --- | --- |
| Runtime 自带函数 | `0` |
| 编译期 Java 扩展函数 | `0` |
| 当前进程已导入的外部 FCL 包函数 | 包 `.db` 文件的 SHA-256 市场 ID |
| 未知函数或当前源码自己定义的函数 | `null` |

示例：

```fcl
util.which("file.read")
# 0

import "28bb03a8fd62788100447513a1a7de56123713fcf74811e1aa6fec41bb4b9008" as "e"
util.which("e.open")
# 28bb03a8fd62788100447513a1a7de56123713fcf74811e1aa6fec41bb4b9008
```

## 下载与持久化

`mkt.download()` 最终调用现有 `network.download()`。下载以 4 MiB 持久化分块执行，
目标 VFS 文件只在完整下载结束后出现。因此未完成包不会进入可安装文件列表，
`mkt.install()` 也不会看到半个 `.db`。

Runtime 安装包时会重新读取包数据库、检查其内部结构并记录文件 SHA-256。
`package.verify()` 可在安装后重新检查内容是否仍与安装记录一致。

## 宿主机发布

在项目目录执行：

```bash
./market/start.sh
```

脚本只构建并发布 `editor` 和 `market`，然后启动仓库服务。`files` 包因为当前性能
不达标，保留开发源码但不在市场索引中、不通过市场接口提供下载。
当前已发布版本为：

| 坐标 | SHA-256 市场 ID |
| --- | --- |
| `cilexec/editor/1.0.4` | `28bb03a8fd62788100447513a1a7de56123713fcf74811e1aa6fec41bb4b9008` |
| `cilexec/market/1.0.2` | `e65c7741c15f9cda04581d6883556092408800f5f34dd970aba85caad0a59229` |

仓库只接受回环地址、当前 CilExec Docker 网络和明确放行的客户端；CilExec Runtime
只放行 `http://host.docker.internal:8787` 这一私有 HTTP origin。

## FCL 环境变量

市场客户端从当前用户的持久 FCL 环境变量 `MARKET_ORIGIN` 获取唯一仓库 origin。
未设置时不会偷偷采用默认服务器，而是在除 `mkt.run()` 之外的首次操作中进入配置。
本机开发时可输入：

```text
http://host.docker.internal:8787
```

管理员可设置共享值，用户也可用自己的值覆盖：

```fcl
env.setShared("MARKET_ORIGIN", "https://market.example.com")
env.set("MARKET_ORIGIN", "https://my-mirror.example.com")
```

市场会自动请求 `${MARKET_ORIGIN}/market/v1/index.json`。私有 origin 还必须
同时在 `CILEXEC_NETWORK_ALLOW_PRIVATE_HTTP_ORIGINS` 中精确放行；公网 HTTPS origin
不需要加入私有网络白名单。

## 精确导入

市场 ID 是分发 `.db` 文件的 SHA-256。`import` 完全不接受包名或坐标，只接受这个
64 位哈希。别名不再强制：省略时完整 SHA-256 本身就是函数命名空间，不会产生任何
包名映射。只有显式写出 `as` 才会得到短名称：

```fcl
import "28bb03a8fd62788100447513a1a7de56123713fcf74811e1aa6fec41bb4b9008"
28bb03a8fd62788100447513a1a7de56123713fcf74811e1aa6fec41bb4b9008.open("a.txt")

import "28bb03a8fd62788100447513a1a7de56123713fcf74811e1aa6fec41bb4b9008" as "editor"
editor.open("a.txt")
```

`include "path.fcl"` 只接受当前用户 VFS 中的普通 UTF-8 FCL 文件，并在编译前把
文件源码插入到 `include` 所在位置。相对路径按当前终端工作目录解析；嵌套 include
改按被包含文件所在目录解析；循环引用和 `.db` 包文件会被拒绝。插入后的完整源码与
编译结果一起持久化，因此文件以后发生变化不会改写已经创建的程序。包 `.db` 必须先
用 `package.install()` 注册，再用 `import "<sha256>"` 导入，不能 include。
