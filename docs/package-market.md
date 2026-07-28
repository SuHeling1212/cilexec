# CilExec 本地包市场方案

## 目标

包的分发单位始终是由 `package.json` 和声明文件构建出的不可变 SQLite `.db` 文件。
Runtime 镜像不携带市场包源码或包数据库。宿主机负责构建、上架和提供 HTTP 下载，
CilExec 用户负责把包下载到自己的 VFS、安装到包环境，再通过 `import` 或
`package.run` 使用。

## 当前闭环

```text
market/sources/editor/
        │ package build
        ▼
market/repository/packages/cilexec/editor/1.0.0/editor.db
        │ host HTTP :8787
        ▼
network.download(..., "/editor.db")
        │ VFS binary file
        ▼
package.install("/editor.db", "editor")
        │ exact package-hash binding
        ▼
import "editor" → editor.open(path)
```

市场服务提供：

- `GET /v1/index.json`：包索引，包含坐标、下载路径、文件大小和 SHA-256。
- `GET /packages/{namespace}/{name}/{version}/{name}.db`：不可变包数据库。
- `.db` 响应媒体类型为 `application/vnd.sqlite3`。
- Docker Desktop 和 Linux Compose 均通过 `host.docker.internal` 访问宿主机；Compose
  为 Linux 配置了 `host-gateway` 映射。

## 启动市场并安装编辑器

在宿主机项目目录运行：

```bash
./market/start.sh
```

脚本会先使用 CilExec 自己的离线包构建器制作 `editor.db`，发布到市场仓库，然后在
`0.0.0.0:8787` 启动 HTTP 服务。宿主机索引地址：

```text
http://127.0.0.1:8787/v1/index.json
```

如果市场已经在 `8787` 端口运行，重复执行脚本会直接提示并正常退出，不会再启动第二个
服务。若该端口被其他程序占用，可通过 `./market/start.sh 8788` 选择其他端口；容器内的
下载地址也要使用相同端口。

进入 CilExec 终端后执行：

```fcl
downloaded = network.download("http://host.docker.internal:8787/packages/cilexec/editor/1.0.0/editor.db", "/editor.db")
installed = package.install("/editor.db", "editor")
import "editor"
editor.open("notes.txt")
```

重新登录后，包环境绑定和终端进程的导入上下文都会持久化，不需要重复安装。

## 后续市场能力

当前实现是可运行的最小市场。正式远程市场还应按以下顺序扩展：

1. 给索引和包发行版增加发布者签名、密钥轮换、撤销状态与信任策略。
2. 增加坐标/版本解析、依赖求解、锁文件和批量事务安装。
3. 在现有 4 MiB 持久化分块和 1 GiB 单文件支持上增加跨命令断点续传与下载进度查询。
4. 增加多个仓库源、镜像、缓存、离线导出和内容哈希去重。
5. 增加发布鉴权、审核、恶意包扫描、配额、统计和搜索接口。
6. 商店 UI 最后接入；底层仍只调用同一套索引、下载和包安装能力。
