# 单 JAR 运行说明

从源码执行 `mvn package`，把 `target/cilexec-app.jar` 复制到希望保存实例的目录，
然后在任意工作目录执行：

```bash
java -jar /绝对路径/cilexec-app.jar
```

启动时会先显示 `Starting CilExec`。首次初始化可能需要几十秒，随后显示终端设置提示。
如果 JAR 位于源码仓库的 `target/`，`mvn clean` 会保留同级的 `cilexec-data/`；
仍建议把正式运行的 JAR 和数据目录放在独立位置并定期备份。

需要 Java 26。通用 JAR 内含 PostgreSQL 17.6 的 Windows x64、Linux x64/ARM64
和 macOS ARM64（Apple 芯片）二进制；无需安装 PostgreSQL，也无需创建数据库密码文件。首次启动会在
JAR 同级创建 `cilexec-data/`，解包本机架构的数据库程序到 `bin/`，把数据库集群
存入 `postgres/`，把嵌入式数据库的工作目录设为 `epg/`，创建所需数据库角色，
并运行 JAR 内已有的 Flyway Schema。
此后启动复用同一个 `postgres/`，并会对 0.0.5 的 V005 数据应用内部补丁；
V004 及更旧目录或外部数据库仍会在任何写入前被拒绝；继续使用旧数据请保留旧版 JAR，0.0.5 需要全新的
`cilexec-data/`（或全新数据库）。
程序不会把运行数据写入当前工作目录。

数据库只监听本机回环地址，使用启动时生成的内存凭据。初始化数据库进程期间
会短暂使用本机信任认证；角色和数据库配置完成后立即改成 SCRAM 认证。正常关闭
（`:shutdown` 或 SIGTERM）会先停止 Runtime，再停止数据库进程。`fsync` 和同步提交
均已开启。不要同时启动同一目录中的两个 JAR 实例；第二个实例会因数据库目录锁而失败。
若 JAR 被强制杀死（如 `kill -9`），残留的 PostgreSQL 可能继续占用数据目录：新 JAR
会在启动前读取 `postmaster.pid` 并明确报告该 PID，要求先用 `pg_ctl ... stop` 停止，
不会自动结束它，也不会再等待 30 秒超时。

`cilexec-data/postgres/` 是权威运行状态。备份时先停止程序，再复制整个
`cilexec-data/`。移动实例时把 JAR 和这个目录一起移动。删除目录会删除该实例的
所有程序、进程、用户、VFS、包状态、定时器、审计记录和终端状态；请先备份。
显式 `export <output.db>`、`package build` 和 `compile` 命令仍按给定路径写输出文件。

默认命令等同 `terminal`，终端用户名默认为 `local`。首次连接时按提示设置应用
管理员密码。从真实 TTY 启动时，当前终端会自动连接 Runtime；输入 `:exit` 只断开
当前终端，后台 Runtime 继续运行。无 TTY 时只启动监听在 `127.0.0.1:8022` 的
终端服务。常规 INFO 启动日志默认隐藏，设置 `CILEXEC_LOG_LEVEL=INFO` 可查看。
本地终端桥接在 macOS/Linux 使用 `stty`。Windows x64 使用控制台行输入，
宿主终端可能回显密码；请在私密终端窗口操作。`runtime`、`migrate`、`export` 和 `host move` 命令也可使用同级数据库，
但同一时刻只能有一个 JAR 实例管理它。

如果显式设置 `CILEXEC_DATABASE_URL`，程序改用外部 PostgreSQL，保留原有
角色、TLS、密码文件和显式迁移配置。这是兼容模式；直接启动单 JAR 时无需设置它。
容器镜像发行文件已从仓库移除；单 JAR 是受支持的发行方式。

同一个 `cilexec-app.jar` 面向上述四种系统架构；首次启动仍只在 JAR 同级创建
`cilexec-data/`。各平台的实际启动需要在对应系统上验证，某一平台的构建或测试
不能代替其他平台的实机测试。构建不会自动替换正在运行的实例或其数据。
