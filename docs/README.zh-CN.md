# CilExec

CilExec 是使用 Java 26 实现的 FCL（Follarce CilExec Language）运行时。当前版本从零
重构，项目坐标仍是 `com.follarce:cilexec`，入口仍是 `com.follarce.Main`。

PostgreSQL 是唯一权威状态。Program、完整 continuation、进程、FIFO 队列与 lease、IPC、
Timer、VFS、软件包环境、外部副作用、终端输入和审计都在数据库事务中保存。JVM 线程、
缓存和任务队列可以随时丢失，重启后只根据数据库恢复。

## 核心约束

- 每条 FCL 语义语句只对应一次显式数据库提交。
- `state_version + execution_epoch` 阻止旧 worker 提交。
- advisory lock 保证一个数据库只有一个主动 Runtime。
- PID 单调递增且永不复用。
- IPC 支持 direct、channel、topic 和 broadcast；Timer 不依赖内存 sleep。
- VFS 内容使用 SHA-256 内容寻址对象；软件包是只读、不可变 SQLite `.db`。
- 所有 HTTP、Socket、宿主写入等外部操作必须先进入 effect journal。
- CilExec 用户映射为稳定 PostgreSQL LOGIN Role，用户域表强制启用 RLS。
- 不再存在 `.proc`、`cilexec_root` 或宿主文件状态数据库。

## 构建

```bash
mvn clean test
mvn clean verify
```

输出：

```text
target/cilexec-app.jar
target/dependency-lock.txt
```

## 使用 Docker Compose

先在 `docker/secrets/` 创建以下文件，密码至少 16 个字符：

```text
postgres-admin-password
cilexec-migrator-password
cilexec-runtime-password
cilexec-effect-worker-password
cilexec-readonly-password
```

临时数据库：

```bash
docker compose -f compose.yml -f docker/compose/ephemeral.yml up --build
```

持久卷：

```bash
docker compose -f compose.yml -f docker/compose/persistent.yml up --build
```

持久卷不是备份。生产环境应对 PostgreSQL 执行 `pg_dump`、恢复演练和版本升级验证。

应用命令：

```bash
java -jar target/cilexec-app.jar migrate
java -jar target/cilexec-app.jar runtime
```

健康检查：`/health/live` 与 `/health/ready`。

完整架构和验收标准见
[`architecture-baseline.md`](architecture-baseline.md)。

## 许可证

[MIT](../LICENSE)
