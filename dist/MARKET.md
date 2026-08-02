# CilExec Java 市场

市场由两个 Java 程序组成，不再分发 `market.db`，也不再需要导入 `mkt`：

```text
CilExec Runtime（内置 market.*）        cilexec-market-server.jar
──────────────────────────────          ─────────────────────────
用户 VFS 缓存完整索引                    读取明确发布的 catalog.json
自然语言本地搜索                         校验只读 SQLite package.db
按 SHA-256 下载、安装和升级      ◄────►  提供索引、HEAD 和 Range 下载
```

客户端随 `cilexec-app.jar` 一起发布。服务端是独立胖 JAR，macOS、Linux 和 Windows
均以 `java -jar` 运行，不依赖 Python、Bash 或 Docker。本文件中的命令均以解压后的
发布目录为当前目录。

## 启动服务端

```bash
java --enable-native-access=ALL-UNNAMED \
  -jar cilexec-market-server.jar \
  --repository repository \
  --catalog catalog.json
```

Windows PowerShell 使用同一 JAR：

```powershell
java --enable-native-access=ALL-UNNAMED `
  -jar cilexec-market-server.jar `
  --repository repository `
  --catalog catalog.json
```

默认只监听 `127.0.0.1:8787`。需要让 Docker 容器访问时，应显式监听宿主接口并只放行
实际的容器网段：

```bash
java --enable-native-access=ALL-UNNAMED \
  -jar cilexec-market-server.jar \
  --repository repository --catalog catalog.json \
  --bind 0.0.0.0 --port 8787 --allow-cidr 172.20.0.0/16
```

完整参数可用 `java -jar ... --help` 查看。`--allow-cidr` 可重复使用；回环网络始终允许。
部署前可添加 `--check` 只校验仓库和清单并立即退出，不会监听任何端口。

## HTTP 协议

| 请求 | 作用 |
| --- | --- |
| `GET/HEAD /market/v1/index.json` | 返回全部已发布包的索引。 |
| `GET/HEAD /market/v1/{sha256}` | 下载完整 SHA-256 指定的不可变 `.db`。 |

包下载支持单个显式 `Range: bytes=start-end`、`ETag`、`If-Range`、`Content-Length` 和
`416` 结束探测，正好对应 Runtime 的 4 MiB 持久化分块下载。`v1` 是市场 HTTP 协议
主版本，不是软件包版本。

`catalog.json` 是唯一发布清单。仓库里存在但未写入清单的文件不会上架。服务端
启动时会拒绝符号链接、路径逃逸、超过 64 MiB 的包、格式不是 2 的 SQLite 数据库、
坐标与路径不一致、非法依赖哈希和重复哈希。下载前还会重新核对大小和 SHA-256。

服务端不需要为新包重启。每次请求索引时都会重新读取并完整验证仓库，然后原子替换
内存快照。发布者必须先把新的 `.db` 放到最终版本目录，最后再以原子重命名更新
`catalog.json`；不能覆盖已经发布的内容哈希文件。验证失败时索引请求返回 `503`，已有
有效快照不会被半成品替换，正在进行的 SHA-256 下载也不会切换文件。

索引中的 `sha256` 是完整 `.db` 文件的 64 位小写 SHA-256，同时也是包 ID、下载路径和
依赖 ID。它是内容标识，不是签名或发布者身份。

## 内置客户端

首次使用先为当前用户配置唯一镜像源：

```fcl
market.configure("http://host.docker.internal:8787")
market.update()
```

镜像源保存在当前用户持久化 FCL 环境变量 `MARKET_ORIGIN` 中。管理员可通过
`env.setShared("MARKET_ORIGIN", "https://market.example.com")` 提供共享默认值，用户
自己的值优先。客户端不会在执行途中突然进入交互输入；未配置时会明确提示调用
`market.configure(...)`。

| 函数 | 功能 |
| --- | --- |
| `market.configure(origin)` | 设置当前用户的 HTTP/HTTPS 镜像 origin。 |
| `market.origin()` | 查看当前生效的镜像源。 |
| `market.update()` | 下载、验证并持久化完整索引。 |
| `market.search(text)` | 按包名、命名空间、类型、标签和说明词前缀搜索；版本号不参与搜索。 |
| `market.info(sha256)` | 查询一个完整包记录，不存在时返回 `null`。 |
| `market.download(sha256)` | 分块下载并重新计算完整文件 SHA-256。 |
| `market.install(sha256)` | 递归安装精确哈希依赖并建立默认绑定。 |
| `market.list()` | 查看由市场管理的包名、绑定和 SHA-256。 |
| `market.upgrade()` | 更新索引并升级所有存在新版本的市场安装。 |
| `market.uninstall(sha256)` | 解除对应绑定并移除市场下载文件。 |
| `market.help()` | 返回函数帮助。 |
| `market.run()` | 返回客户端版本和帮助，不要求配置镜像。 |

安装编辑器的完整流程：

```fcl
market.configure("http://host.docker.internal:8787")
market.update()
market.search("editor")
market.install("1fac4ef3472a90cbc3eb7b2e2042b50bb4197859a89a3129f0e7474089b96557")
import "editor"
editor.open("notes.txt")
```

索引缓存位于当前用户 VFS 的 `/market/index.json`，下载包位于
`/market/packages/{sha256}.db`，市场安装凭据位于 `/market/installed.json`。普通用户
看不到其他用户的这三类数据。

下载分块先作为不可变对象写入对象存储，只有最后一块完成后才把目标 VFS 节点发布。
客户端随后按 4 MiB 重新读取整个逻辑文件，复核索引声明的大小与 SHA-256。安装时
Runtime 还会重新验证 SQLite 结构、包内部哈希、能力声明和精确依赖图。市场包上限为
64 MiB；普通 VFS 单文件上限仍为 1 GiB。

## 生成发布目录

本文件属于已生成的发布目录。开发源码中可从项目根目录执行 `build/release.sh`
（Windows 执行 `build\release.bat`），一次完成测试、两个 JAR、全部 FCL 包、市场清单
和 `SHA256SUMS` 的生成与复核。若只需核对已有发布物，可执行
`python3 build/release.py --verify-only`。发布流水线先在临时目录完成全部检查，成功后才
替换 `dist` 中的生成文件。

每项依赖都必须是精确分发文件 SHA-256：

```json
"dependencies": [
  {"sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "optional": false}
]
```

客户端会先递归安装必需依赖，拒绝循环和超过 64 层的依赖链。可选依赖不会自动安装。
包模块通过完整依赖哈希调用导出函数，坐标和绑定名不参与依赖解析。

## 安全与部署说明

- 公网部署应使用 HTTPS 反向代理；内置服务端本身不终止 TLS。
- 不要把 `--allow-cidr 0.0.0.0/0` 当作开发捷径。
- Runtime 的私网 HTTP 策略仍必须允许配置的私有 origin；市场配置不会绕过网络策略。
- 服务端并发数默认 16，可用 `--workers 1..256` 调整；超过上限立即返回 `503`。
- 服务端只读仓库，不提供上传、删除、登录或动态上架接口。
- 包签名系统已完全移除；安全边界是受控发布清单、HTTPS、精确 SHA-256 和 Runtime
  的包结构/能力校验。
