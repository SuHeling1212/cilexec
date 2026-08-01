# CilExec 数据库驱动与 Docker 化重构总方案（Java 版）

状态：**决策修订冻结版 v1.1**  
日期：2026-07-22  
实现语言：**Java（保留现有语言与 FCL 运行语义）**  
运行数据库：**PostgreSQL**  
软件包发布物：**不可变 SQLite `.db`**  
发行形态：**Docker Compose，支持 Linux AMD64 与 ARM64**

---

## 0. 文档目的与约束

本文件是 CilExec 下一版本的正式重构规格。它不再是讨论草案，而是依据已完成的 86 项架构问卷冻结后的实现边界。

本轮重构同时完成三件事：

1. 将 CilExec 从旧的文件驱动持久化，重构为 PostgreSQL 驱动的事务化运行系统；
2. 保留 Java 实现与现有 FCL 语义，重构运行时分层、数据库访问和恢复边界；
3. 将 Docker Compose 作为第一阶段即启用的标准开发、测试和发行环境。

本文件中的“Kernel”统一称为 **CilExec Runtime**，避免与 Linux kernel 混淆。它指负责解释 FCL、调度 CilExec 进程、访问 PostgreSQL、执行权限、IPC、VFS、软件包和恢复逻辑的 Java/JVM 核心程序。

### 0.1 决策优先级

发生冲突时，按以下顺序解释：

```text
数据库已提交状态是唯一真相
> 不可变发布物和精确内容身份
> 可恢复、可审计的事务语义
> 用户明确选择
> 实现便利
```

### 0.2 第 9 题的一致性处理

问卷选择了“PostgreSQL 数据存在容器可写层”。这个选择只能用于**明确可丢弃的开发模式**。

原因不是改变用户决定，而是区分两个运行档案：

```text
ephemeral profile
= 数据存在容器可写层
= 删除或替换 PostgreSQL 容器会丢失实例
= 只用于测试、演示和一次性运行

persistent profile
= 数据挂载 named volume、bind mount 或位于外部 PostgreSQL
= 用于任何需要长期保存的实例
```

CilExec 的持久化承诺只适用于 persistent profile。程序不得把 ephemeral profile 描述为可靠持久存储。

---

# 1. 产品定位

CilExec 定位为：

> **数据库驱动的用户态操作系统。**

它运行在 Linux/Docker 上，但在自己的逻辑边界内管理：

```text
CilExec 用户和组
CilExec 进程
FCL 程序与执行 continuation
调度队列
IPC、channel、topic 和广播
Timer
VFS
软件包和软件包环境
外部副作用
终端会话
审计和恢复
```

Docker/Linux 提供宿主能力：

```text
CPU 与内存
进程和线程
TCP/IP
块存储
容器隔离
宿主文件挂载
时钟
信号
```

PostgreSQL 提供：

```text
权威状态
事务
锁
索引
约束
WAL 与数据库崩溃恢复
用户 Role 与 RLS
```

Java CilExec Runtime 提供：

```text
FCL 语言语义
进程状态机
语句级事务推进
调度
权限决策
IPC
VFS
软件包解析
effect journal
语义恢复
```

---

# 2. 最终系统拓扑

## 2.1 标准 Compose 拓扑

```text
Docker Compose Project
├── postgres
│   ├── PostgreSQL server
│   ├── healthcheck
│   └── 数据目录
│
├── migrate
│   ├── 与 CilExec 同源构建
│   ├── 使用 migrator Role
│   ├── 执行 Flyway migration
│   └── 成功后退出
│
└── cilexec
    ├── Java CilExec Runtime
    ├── Scheduler workers
    ├── Effect workers
    ├── Terminal/API
    └── 可丢弃 package cache
```

启动依赖：

```text
postgres healthy
→ migrate completed successfully
→ cilexec starts
```

PostgreSQL 和 CilExec 分属不同容器。CilExec 也必须支持连接外部 PostgreSQL，但标准本地开发环境以 Compose 中的 PostgreSQL 为准。

## 2.2 网络

用户决定 PostgreSQL 5432 默认映射到宿主。标准开发配置采用：

```yaml
ports:
  - "127.0.0.1:5432:5432"
```

这仍然属于“暴露到宿主”，但默认只绑定本机回环地址，不直接暴露到局域网。

需要远程访问时，必须显式修改监听地址、TLS、认证和防火墙规则。

## 2.3 多架构

发行镜像必须同时发布：

```text
linux/amd64
linux/arm64
```

开发者可在 Apple Silicon Mac 上本地运行 ARM64 镜像，并通过 Docker Buildx 或 CI 生成 AMD64 镜像。

## 2.4 容器用户

`cilexec` 和 `migrate` 均以固定非 root Linux 用户运行。最终镜像不得包含：

```text
完整 JDK 编译工具链
Maven 本地仓库
源代码
测试代码与调试构建工具
不需要的 shell 工具
数据库密码
```

## 2.5 配置档案

```text
docker/compose/ephemeral.yml
- PostgreSQL 使用容器可写层
- 明确标记 disposable
- 用于测试和演示

docker/compose/persistent.yml
- PostgreSQL 使用 named volume 或 bind mount
- 用于本地长期开发和正式运行

external-postgres
- 只启动 migrate 与 cilexec
- 连接外部 PostgreSQL
```

---

# 3. Java 重构边界

本轮保留 Java，不进行语言重写。目标是把已经存在的 FCL 解释器、进程系统和内置函数迁移到清晰的数据库事务边界中，而不是重新发明语言行为。

必须遵守以下原则：

```text
FCL 对外语义保持不变
Java 类可以重组和重写
旧文件持久化实现必须删除
数据库 repository 不得渗透进表达式和语法层
JVM 内存对象不得成为恢复真相
```

## 3.1 Maven 工程与 Java package 结构

继续使用 Maven。首版优先采用单仓库、多 package 的结构，不强制拆成多个独立 Maven artifact；当模块边界稳定后再决定是否转为 multi-module build。

建议目录：

```text
cilexec/
├── pom.xml
├── Dockerfile
├── compose.yml
├── docker/
│   ├── compose/
│   │   ├── ephemeral.yml
│   │   ├── persistent.yml
│   │   └── external.yml
│   ├── entrypoint.sh
│   ├── healthcheck.sh
│   └── secrets/
├── src/main/java/com/follarce/
│   ├── app/
│   │   ├── CilExecApplication.java
│   │   ├── RuntimeBootstrap.java
│   │   └── ShutdownCoordinator.java
│   ├── config/
│   │   ├── CilExecConfig.java
│   │   ├── DatabaseConfig.java
│   │   └── DockerSecretLoader.java
│   ├── domain/
│   │   ├── process/
│   │   ├── program/
│   │   ├── ipc/
│   │   ├── vfs/
│   │   ├── packageinfo/
│   │   ├── effect/
│   │   ├── auth/
│   │   └── audit/
│   ├── fcl/
│   │   ├── parser/
│   │   ├── runtime/
│   │   ├── function/
│   │   └── controlflow/
│   ├── persistence/postgres/
│   │   ├── connection/
│   │   ├── transaction/
│   │   ├── repository/
│   │   ├── mapper/
│   │   └── retry/
│   ├── scheduler/
│   ├── ipc/
│   ├── vfs/
│   ├── package_manager/
│   ├── effect/
│   ├── terminal/
│   ├── auth/
│   ├── audit/
│   └── health/
├── src/main/resources/
│   ├── db/migration/
│   ├── logback.xml
│   └── cilexec-defaults.properties
├── src/test/java/
└── src/test/resources/
```

现有 `com.follarce` 包名可以保留。迁移过程中允许先建立适配层，再逐步把旧类移动到上述边界；不能为了目录整洁一次性破坏所有现有测试。

## 3.2 依赖方向

```text
domain
↑
process / ipc / vfs / package / effect
↑
fcl-runtime
↑
application/bootstrap

persistence-postgres
实现 domain 定义的 repository 与 transaction port
```

领域对象和 FCL 运行语义层不得直接依赖：

```text
JDBC ResultSet
Connection 或 PreparedStatement
PostgreSQL 专用 Java 类型
SQL 字符串
Docker
HTTP 框架
连接池实现
具体日志后端
```

数据库行模型与领域对象必须通过 mapper 转换。禁止把可变 `ResultSet`、连接对象或数据库代理对象保存进进程 continuation。

## 3.3 Java 数据访问栈

标准数据访问栈：

```text
PostgreSQL JDBC Driver
HikariCP
Flyway
显式 SQL
显式 TransactionContext
```

SQLite 软件包读取使用独立的只读 SQLite JDBC 连接。PostgreSQL 连接和 SQLite 连接不得混用，也不得尝试建立跨数据库原子事务。

所有依赖版本必须在 `pom.xml` 中精确固定；禁止使用浮动版本范围。

## 3.4 TransactionContext

Repository 方法不得自行隐藏开启、提交或回滚事务。跨模块原子操作必须由 application service 创建一个 `TransactionContext`，并把同一 JDBC `Connection` 传给参与该事务的 repository。

示意：

```java
public interface TransactionContext extends AutoCloseable {
    ProcessRepository processes();
    ProgramRepository programs();
    SchedulerRepository scheduler();
    IpcRepository ipc();
    VfsRepository vfs();
    PackageRepository packages();
    EffectRepository effects();
    AuditRepository audit();

    void commit() throws SQLException;
    void rollback() throws SQLException;

    @Override
    void close();
}
```

事务执行器必须保证：

```java
public <T> T inTransaction(
        Isolation isolation,
        TransactionWork<T> work
) throws CilExecException;
```

规则：

1. `autoCommit=false`；
2. 默认隔离级别为 `READ_COMMITTED`；
3. `commit()` 只能由最外层 application service 调用；
4. 任意异常必须回滚；
5. `close()` 必须恢复连接状态后归还连接池；
6. Repository 不得把 `Connection` 暴露给 FCL 函数实现；
7. 外部副作用不能在该事务内部执行。

## 3.5 SQL 与映射规则

采用具名 SQL 文件或 Java text block 均可，但每条 SQL 必须有稳定名称并能被测试定位，例如：

```text
process.claimNext
process.loadContinuation
process.commitStatement
ipc.createDeliveries
vfs.replaceContent
package.bindRelease
```

所有更新语句必须检查影响行数。涉及乐观并发的更新，影响行数不为 1 即视为冲突，不能静默继续。

数据库异常统一映射为 CilExec 异常类别：

```text
SQLSTATE 23505 → UniqueConflict
SQLSTATE 23503 → ReferenceConflict
SQLSTATE 40001 → SerializationRetryable
SQLSTATE 40P01 → DeadlockRetryable
连接中断       → DatabaseUnavailable / RuntimeFenced
其他异常       → PersistenceFailure
```

具体 SQLSTATE 映射集中维护，不能散落在各 Repository 中。

## 3.6 JVM 并发模型

继续使用 Java 虚拟线程，但数据库并发必须有界。

```text
一个可运行 CilExec worker
→ 一个短生命周期虚拟线程任务
→ 最多持有一个数据库事务
```

禁止把 `Executors.newVirtualThreadPerTaskExecutor()` 当成无限数据库并发。必须通过以下至少一种方式限制：

```text
固定 scheduler worker 数
Semaphore
受限任务队列
连接池最大连接数
```

建议关系：

```text
scheduler worker 数
≤ cilexec_runtime 连接池可用连接数

scheduler worker + effect worker + 管理连接
< PostgreSQL max_connections 预留额度
```

control connection 是独立物理连接，不进入 HikariCP 普通池。

JVM 内的以下对象全部可丢弃：

```text
Thread / VirtualThread
Future / CompletableFuture
Executor 队列
Java Lock / Semaphore
解析缓存
函数查找缓存
定时唤醒任务
```

它们只能加速执行，不能决定恢复结果。

## 3.7 构建产物

Maven 构建至少产生：

```text
cilexec-app.jar
依赖锁定清单
构建版本信息
Git commit 标识
数据库 schema 兼容范围
FCL runtime 格式版本
```

首版可使用可执行 fat JAR，减少容器装配复杂度。若后续使用 `jlink`，必须通过完整集成测试证明 PostgreSQL JDBC、SQLite JDBC、TLS、日志和字符集模块没有被裁掉。

## 3.8 Docker 多阶段构建

```text
build stage
- 固定摘要的 Linux JDK 镜像
- Maven Wrapper 或固定 Maven 版本
- mvn --batch-mode verify
- 生成最终 JAR

runtime stage
- 固定摘要的 Linux JRE 或经过验证的 jlink runtime
- 非 root 用户
- 只复制运行 JAR、必要配置和健康检查脚本
- 不复制 Maven cache、源码和测试报告
```

入口形式：

```text
java <JVM_OPTIONS> -jar /opt/cilexec/cilexec-app.jar
```

JVM 参数通过配置注入，镜像中不写死机器相关内存大小。容器必须正确接收 SIGTERM，Java 主进程必须是 PID 1，不能被一个不转发信号的 shell 包裹。

---

# 4. PostgreSQL 实例模型

## 4.1 实例边界

```text
一个 CilExec 实例
=
一个 PostgreSQL database
```

一个 PostgreSQL cluster 可以容纳多个 CilExec database，但任何跨实例操作都不属于首版原子事务范围。

## 4.2 Schema 总览

```text
meta
auth
object_store
vfs
program
process
scheduler
ipc
effect
package
terminal
audit
```

## 4.3 数据库是真相来源

以下状态必须进入 PostgreSQL：

```text
用户、组、Role 映射和 capability
程序和程序哈希
进程 continuation
进程变量当前值
进程关系
调度队列和 lease
IPC、channel、topic、subscription
Timer
VFS 节点和内容
软件包发布物、环境和绑定
终端提交输入和附件关系
effect 请求、尝试和结果
审计记录
启动、关闭和恢复信息
```

以下内容不是权威状态：

```text
Java Thread、虚拟线程或 Future
数据库连接对象
连接池内部队列
Mutex/RwLock
解析缓存
预编译查询缓存
SQLite package 本地缓存
日志文件
容器 ID
```

---

# 5. PostgreSQL Role、用户与 RLS

## 5.1 系统 Role

```text
cilexec_owner
cilexec_migrator
cilexec_runtime
cilexec_effect_worker
cilexec_readonly
```

职责：

| Role | LOGIN | 主要权限 |
|---|---:|---|
| `cilexec_owner` | 可选 | 拥有 schema 和对象，不用于日常运行 |
| `cilexec_migrator` | 是 | 创建和修改 schema、表、索引、函数与 policy |
| `cilexec_runtime` | 是 | 执行进程、调度、IPC、VFS 和包管理 |
| `cilexec_effect_worker` | 是 | 领取 effect、写 attempt/result、读取必要请求 |
| `cilexec_readonly` | 是 | 诊断和只读管理 |

生产环境中 Runtime 不拥有 `CREATE/ALTER/DROP` 权限。开发环境允许通过显式开关让 Runtime 执行 migration，但标准 Compose 仍使用独立 migrate 服务。

## 5.2 CilExec 用户映射为 PostgreSQL LOGIN Role

用户决定每个 CilExec 用户都对应一个 PostgreSQL LOGIN Role。

采用稳定映射：

```text
auth.user_account.user_id
↔
PostgreSQL Role: cilexec_user_<encoded_user_id>
```

用户名可修改，但数据库 Role 名以稳定 `user_id` 生成，避免重命名导致对象和 policy 失效。

`auth.user_account` 至少保存：

```text
user_id
username
postgres_role_name
status
created_at
disabled_at
credential_version
```

## 5.3 连接模型

为了避免每个请求创建新物理连接，Runtime 可以维护有限连接池，并在事务内执行：

```sql
SET LOCAL ROLE cilexec_user_<id>;
SET LOCAL app.cilexec_user_id = '<uuid>';
```

事务结束后 `SET LOCAL` 自动失效。

Runtime Role 必须获得切换到用户 Role 的受控成员关系，但不得是 PostgreSQL superuser，也不得拥有 `BYPASSRLS`。

直接数据库登录属于高级能力。即使 PostgreSQL 端口映射到宿主，普通 CilExec 使用流程仍通过 Runtime。管理员若允许用户直接登录，RLS 和数据库对象权限必须仍然成立。

## 5.4 全面 RLS

所有包含用户、所有者或实例资源边界的业务表必须启用 RLS：

```sql
ALTER TABLE process.process ENABLE ROW LEVEL SECURITY;
ALTER TABLE process.process FORCE ROW LEVEL SECURITY;
```

策略通过事务内的用户身份识别：

```sql
current_setting('app.cilexec_user_id', true)
```

RLS 负责最后一道数据库行隔离；Runtime 仍负责更丰富的 capability、关系和状态机校验。

系统级表分为：

```text
全用户不可见
只允许 readonly 管理角色
允许 Runtime 代表用户访问
```

任何 migration 新增业务表时，如果没有声明其 RLS 策略或明确标记为系统表，migration 测试必须失败。

## 5.5 Secrets

数据库密码和密钥通过：

```text
Docker secrets
或
外部秘密管理器
```

注入。不得写入：

```text
Dockerfile
Git
Maven settings.xml 或 pom.xml
镜像层
默认 compose 文件
日志
数据库业务表
```

---

# 6. Migration 与 Schema 版本

Flyway 作为 schema 版本管理工具。

```text
V001__bootstrap_roles.sql
V002__create_meta.sql
V003__create_auth.sql
...
```

规则：

1. 已执行 migration 不得修改；
2. migration 必须有 checksum；
3. 只允许向前迁移；
4. 禁止自动 downgrade；
5. Runtime 启动时验证数据库版本；
6. 生产环境数据库版本不匹配时拒绝启动；
7. 降级通过恢复备份完成；
8. 开发环境可以显式允许 Runtime 调用 migration，但不得成为生产默认行为。

---

# 7. 单主动 Runtime 与控制锁

一个 database 同一时刻只允许一个主动 CilExec Runtime。

启动时创建一条专用 control connection，并尝试获得 PostgreSQL session advisory lock：

```sql
SELECT pg_try_advisory_lock(:instance_lock_key);
```

结果：

```text
true  → 获得控制权，继续启动
false → 已存在主动 Runtime，当前容器拒绝进入运行态
```

control connection 不加入普通池，不执行 FCL 事务。

连接断开时：

```text
停止领取新进程
停止提交任何运行结果
进入 FENCED
停止 effect worker
关闭终端写入
退出容器
```

不能在失去控制锁后继续纯计算并稍后提交。数据库断线期间所有 CilExec 进程冻结。

---

# 8. 事务和 FCL 执行模型

## 8.1 默认隔离级别

普通事务采用：

```text
READ COMMITTED
```

原因：

```text
事务短
每条 FCL 语句提交
冲突通过 state_version 和行锁验证
失败事务允许有限重试
```

少数跨多行强不变量操作可使用 `SERIALIZABLE`，但必须显式标注，并仅对可重放事务进行有限次数、带抖动重试。

## 8.2 每条 FCL 语句一次提交

用户决定：

```text
一条 FCL 语句
=
一个持久化事务边界
```

普通语句流程：

```text
BEGIN
验证 Runtime boot 和控制权
验证 scheduler lease
锁定进程行
验证 state_version
验证 execution_epoch
加载该语句需要的 continuation 和变量
执行一条 FCL 语句
写入变量当前值
写入 continuation
更新当前 program counter
更新进程状态与调度队列
写必要审计事件
COMMIT
```

这意味着每个已提交语句都是恢复安全点。

## 8.3 强制安全点

下列操作全部形成明确事务边界：

```text
fork
IPC send/receive
VFS 可见修改
Timer 创建或等待
进程挂起
用户输入等待
effect 请求
包导入或绑定修改
进程终止
执行量子结束
```

由于首版每条语句提交，普通执行量子可视为一条 FCL 语句。未来若引入批量纯计算模式，必须作为新的格式版本和语义变更，不能悄悄改变恢复粒度。

## 8.4 冲突控制

采用：

```text
state_version
+
execution_epoch
+
必要的 SELECT ... FOR UPDATE
```

提交条件示意：

```sql
UPDATE process.process
SET state_version = state_version + 1,
    ...
WHERE process_uid = :process_uid
  AND state_version = :expected_state_version
  AND execution_epoch = :expected_execution_epoch;
```

受影响行数不是 1 时，提交失败，不能覆盖新状态。

## 8.5 锁顺序

跨模块事务必须遵守统一顺序：

```text
meta instance/boot
→ auth principal
→ program
→ process
→ scheduler
→ ipc
→ timer
→ vfs node
→ object_store
→ package environment/binding
→ effect
→ terminal
→ audit
```

同一类资源按稳定主键排序后加锁。

---

# 9. Program 与进程 continuation

## 9.1 Program 是共享不可变代码

程序代码不按进程复制。

```text
program.program
program.statement
program.module_binding
```

`program.program`：

```text
program_id
program_hash
language_version
source_object_hash
compiled_object_hash
statement_count
created_at
```

`program_id` 是数据库内部稳定标识；`program_hash` 是代码逻辑内容身份。

## 9.2 进程身份

用户决定 PID 永不复用。

```text
process_uid = 内部 UUID
pid         = 用户可见、实例内单调递增且永不复用
```

不需要 `generation` 参与用户身份，但 `process_uid` 仍作为所有外键和内部引用。

## 9.3 完整 continuation

崩溃恢复不能只保存“当前语句”。至少保存：

```text
program_id
program_hash
program_counter
call stack
return address
scope stack
局部变量当前值
异常处理栈
循环/分支 continuation
等待原因
等待对象 ID
当前 package binding
语言和 runtime 格式版本
```

建议表：

```text
process.process
process.call_frame
process.scope
process.variable
process.exception_frame
process.wait_state
process.relationship
process.event
```

## 9.4 当前变量与变量审计

所有进程变量的**当前值**必须持久化。

不永久记录每次普通变量变化。审计只保存：

```text
安全事件
权限变化
管理操作
外部可见操作
包安装与升级
宿主 mount
effect
异常恢复
```

这不影响状态恢复：恢复读取 `process.variable` 当前值，而不是从审计历史重放所有赋值。

---

# 10. 调度器

首版策略：

```text
FIFO
```

`process.process` 进入 READY 后加入 `scheduler.queue`。

领取示意：

```sql
SELECT process_uid
FROM scheduler.queue
WHERE queue_state = 'READY'
  AND ready_at <= now()
ORDER BY enqueued_at, process_uid
FOR UPDATE SKIP LOCKED
LIMIT 1;
```

## 10.1 Worker

worker 数量为配置项，默认较小。执行 worker 数量和数据库连接池大小分别配置。

## 10.2 Lease

每次领取创建或更新 lease：

```text
process_uid
runner_id
boot_id
execution_epoch
claimed_at
heartbeat_at
expires_at
```

规则：

```text
领取时 execution_epoch += 1
worker 定期 heartbeat
过期后可被其他 worker 重领
旧 worker 提交时 epoch 不匹配，提交失败
```

## 10.3 崩溃恢复

RUNNING 进程按最后提交状态分类：

```text
纯执行中且 continuation 完整 → READY
等待 IPC                 → WAITING_IPC
等待 Timer               → WAITING_TIMER
等待 effect              → WAITING_EFFECT
已终止但清理未完成        → TERMINATING
状态不满足不变量          → FAILED_RECOVERY
```

---

# 11. IPC、Channel、Topic 与广播

首版直接支持完整消息模型：

```text
进程到进程
命名 channel
topic
subscription
广播
```

## 11.1 数据模型

```text
ipc.message
ipc.delivery
ipc.channel
ipc.subscription
ipc.topic
```

`ipc.message`：

```text
message_id
sender_process_uid
message_kind
channel_id
topic_name
payload_type
payload_json
payload_object_hash
created_at
expires_at
```

`ipc.delivery`：

```text
delivery_id
message_id
receiver_process_uid
status
reserved_by
reserved_at
consumed_at
failed_at
failure_reason
```

状态：

```text
PENDING
RESERVED
CONSUMED
FAILED
DEAD
```

## 11.2 交付语义

在单一 PostgreSQL 实例内，以 `ipc.delivery` 为单位实现精确消费一次：

```text
一个 delivery 只能从 PENDING/RESERVED 进入一次 CONSUMED
```

广播不是多人争抢同一行，而是为每个订阅者建立独立 delivery。

## 11.3 模式

### 定点发送

```text
process A → process B
```

### Channel

```text
多个消费者监听 channel
一条消息由其中一个消费者领取
```

### Topic/订阅

```text
发布到 topic
为所有有效订阅生成 delivery
```

### 广播

广播是 topic fan-out 的明确语法或系统 topic，不单独使用不可靠的内存事件总线。

---

# 12. Timer

Timer 的权威状态进入 PostgreSQL：

```text
process.timer
├── timer_id
├── process_uid
├── wake_at
├── status
├── created_at
├── fired_at
└── payload
```

Java Runtime 只负责：

```text
查询即将到期 Timer
使用虚拟线程等待或短周期轮询
原子领取
将等待进程唤醒
```

`ScheduledExecutorService`、`Thread.sleep` 或虚拟线程等待只用于减少轮询成本，不是 Timer 真相。容器重启后必须扫描数据库中未触发的到期 Timer。

---

# 13. VFS 与 Object Store

## 13.1 文件节点

```text
vfs.node
├── node_id
├── parent_node_id
├── owner_id
├── name
├── node_type
├── current_object_hash
├── mode/capability
├── created_at
└── updated_at
```

## 13.2 首版内容存储

每个内容对象首版使用一个 `bytea`：

```text
object_store.object
├── object_hash
├── byte_size
├── media_type
├── content bytea
└── created_at
```

不在首版强制 1 MiB 分块。未来只有在真实基准证明必要时增加分块格式。

## 13.3 内容寻址

`object_hash` 由内容字节计算。对象不可修改：

```text
写新文件内容
→ 计算新 hash
→ INSERT object
→ 更新 node.current_object_hash
→ COMMIT
```

## 13.4 文件历史

默认只保留当前版本。指定节点或类型可以启用 revision：

```text
vfs.file_revision
```

历史保留策略可配置。

## 13.5 宿主 mount

允许高权限、显式配置的宿主目录挂载。

必须同时满足：

```text
Docker 层显式 bind mount
+
CilExec capability 授权
+
vfs.mount 数据库记录
```

普通 FCL 代码不能任意访问未声明宿主路径。

---

# 14. 软件包数据库

## 14.1 发布物

一个软件包就是一个不可变 SQLite `.db` 文件。

内部允许的核心表：

```text
package_metadata
package_file
package_module
package_dependency
package_entrypoint
package_export
package_capability
```

软件包数据库：

```text
只读
不可修改
不保存运行数据
不依赖外部 ATTACH 数据库
不允许虚拟表和任意扩展
```

## 14.2 身份

```text
package_hash
=
规范化逻辑内容哈希
```

传输字节另有：

```text
database_file_hash
```

## 14.3 坐标唯一性

最终决定：

> **同一 `namespace/name/version` 绝对禁止对应不同 `package_hash`。**

允许：

```text
std/network/1.0.0 → hash-A
std/network/1.1.0 → hash-B
```

禁止：

```text
std/network/1.0.0 → hash-A
std/network/1.0.0 → hash-B
```

数据库必须建立唯一约束：

```text
UNIQUE(namespace, name, version)
```

若同一坐标再次导入：

```text
hash 相同 → 幂等成功
hash 不同 → 拒绝，报告版本污染
```

## 14.4 PostgreSQL 保存方式

完整 `.db` 字节进入 `object_store.object`。

```text
package.release.database_object_hash
→ object_store.object.object_hash
```

`package.release` 不再保存第二份 `database_bytes`，避免重复。

完整原始 `.db` 是内容权威；PostgreSQL 中的依赖、入口、导出和 capability 索引是可重建派生数据。

依赖不按坐标或版本范围解析，而是固定到被依赖 `.db` 分发文件的完整 SHA-256：

```text
package.release_dependency.dependency_file_hash
```

坐标只用于显示。安装必需依赖时必须找到完全相同的文件哈希；可选依赖允许缺失。

## 14.5 Package Environment

首版实现显式软件包环境：

```text
package.environment
├── environment_id
├── owner_id
├── name
├── parent_environment_id
├── status
└── created_at
```

安装是：

```text
(environment_id, binding)
→ package_hash
```

同一用户可以建立多个环境，各自绑定不同版本。

## 14.6 进程绑定

进程首次解析导入后保存精确：

```text
process.package_binding
├── process_uid
├── import_name
├── environment_id
└── package_hash
```

后续环境升级不会改变已运行进程的精确哈希。

## 14.7 生命周期钩子

首版完全禁止任意：

```text
pre-install
post-install
pre-upgrade
post-upgrade
pre-uninstall
post-uninstall
```

安装、升级和卸载只改变 PostgreSQL 内的声明式绑定和授权，不自动执行任意 FCL 或宿主操作。

## 14.8 可变数据

软件包运行数据写入独立 `data_scope`，不得写回 package `.db`：

```text
package.data_scope
→ VFS 或受控关系数据
```

## 14.9 完整性

包系统不提供发布者签名、信任状态或密钥管理。安装和运行依靠完整 SQLite 文件的
SHA-256 与规范化逻辑内容哈希检测损坏或内容变化。

---

# 15. 外部副作用

所有数据库外操作必须进入 effect journal：

```text
HTTP
Socket
宿主文件写入
外部程序
邮件
硬件
远程 API
```

状态：

```text
PREPARED
CLAIMED
EXECUTING
COMPLETED
FAILED
UNKNOWN
```

流程：

```text
事务一：
创建 effect
进程进入 WAITING_EFFECT
COMMIT

事务外：
Effect Worker 执行

事务二：
写 result
更新 effect
唤醒进程
COMMIT
```

每类 effect 必须声明：

```text
是否幂等
幂等键如何生成
是否可查询远端状态
失败是否可重试
UNKNOWN 如何处理
```

UNKNOWN 由 effect 类型策略处理；无法确定时进入人工介入，不盲目重试。

Effect Worker 使用独立 PostgreSQL Role。

---

# 16. Terminal 与中断

终端只持久化完整提交输入，不记录每个按键。

```text
terminal.session
terminal.input
terminal.attachment
```

Ctrl+C 不直接调用 Thread.stop，也不把取消 Java Future 当作进程终止语义，而是：

```text
设置 process.interrupt_requested
→ 在下一语句事务或安全点检查
→ 按 FCL 中断语义改变进程状态
```

---

# 17. 审计、日志和健康检查

## 17.1 审计

结构化审计进入 PostgreSQL：

```text
audit.event
├── event_id
├── actor_type
├── actor_id
├── action
├── resource_type
├── resource_id
├── result
├── details_json
└── created_at
```

保留期限按事件类型配置。

## 17.2 运行日志

普通运行日志写：

```text
stdout
stderr
```

不写容器内部永久日志文件，也不把所有日志写入 PostgreSQL。

## 17.3 健康端点

区分：

```text
liveness
= JVM 进程、控制连接监视器和核心执行循环是否存活

readiness
= PostgreSQL 可用
+ migration 版本匹配
+ 已获得 advisory lock
+ 恢复完成
+ Runtime 可接受工作
```

---

# 18. 启动、关闭与恢复

## 18.1 启动

```text
1. 加载非秘密配置
2. 从 secret 文件读取凭据
3. 建立数据库连接
4. 验证 PostgreSQL 和 schema 版本
5. 获取 session advisory lock
6. 创建 meta.kernel_instance
7. 创建 meta.boot
8. 标记旧 boot 状态
9. 执行语义恢复
10. 启动 scheduler
11. 启动 effect worker
12. 启动 IPC/Timer
13. 启动 terminal/API
14. readiness = true
```

## 18.2 SIGTERM

```text
1. readiness = false
2. 停止领取新进程
3. 等待正在提交的语句事务完成
4. 停止 effect 新领取
5. 请求当前任务结束当前安全点
6. 释放 lease
7. 关闭 terminal 写入
8. 标记 CLEAN shutdown
9. 释放 advisory lock
10. 退出
```

设置有限宽限期。超时后可被强制终止，下一次启动按崩溃恢复处理。

## 18.3 崩溃恢复

PostgreSQL 先完成数据库层 WAL 恢复；CilExec 再完成语义恢复：

```text
标记旧 boot 为 CRASHED
废弃旧 runner
使旧 lease 失效
按最后提交 continuation 恢复进程
扫描到期 Timer
恢复 PENDING/RESERVED IPC
恢复 WAITING_EFFECT
检查 UNKNOWN effect
恢复 terminal attachment
重新启动 FIFO 调度
```

---

# 19. 备份、恢复与导出

## 19.1 Volume 不是备份

Docker volume 或容器可写层只表示存储位置，不能防止：

```text
误删
逻辑损坏
宿主磁盘损坏
容器数据目录破坏
PostgreSQL 主版本不兼容
```

## 19.2 首版灾难恢复

首版采用 `pg_dump` 逻辑备份。

```text
backup
→ PostgreSQL custom-format dump
→ Role/global 信息按需要单独导出
→ 记录 CilExec 与 PostgreSQL 版本
→ 校验
```

恢复必须自动化测试。

## 19.3 主版本升级

首版使用：

```text
dump
→ 新主版本 PostgreSQL
→ restore
→ migration
→ CilExec 恢复验证
```

禁止直接把旧主版本 volume 挂到新主版本镜像，也禁止使用浮动 `postgres:latest`。

## 19.4 CilExec 应用级导出

独立提供 CilExec `.db` 逻辑导出容器，只导出持久语义状态，不导出：

```text
活跃连接
锁
缓存
运行中数据库事务
WAL 历史
容器 ID
Java Thread、虚拟线程、Future 或 Executor 任务
```

应用级导出和 PostgreSQL 灾难备份是两种不同产品能力。

---

# 20. 测试策略

数据库测试必须使用真实 PostgreSQL 容器，不用 H2 或 SQLite 模拟 PostgreSQL。

## 20.1 必测类别

```text
migration
RLS
Role 切换
每语句事务
state_version 冲突
execution_epoch fencing
FIFO 领取
lease 过期
IPC 精确 delivery
广播 fan-out
Timer 恢复
VFS 原子替换
package 坐标污染拒绝
package 哈希确定性
effect UNKNOWN
SIGTERM
强制 kill
pg_dump/restore
双架构镜像
```

## 20.2 强制崩溃点

至少覆盖：

```text
BEGIN 后
变量写入后
continuation 推进前
IPC message 插入后
delivery 生成中
COMMIT 前
COMMIT 后
effect 执行前
effect 外部成功但结果写回前
package object 插入后
安装 binding 提交前
```

## 20.3 性能

首版不先拍脑袋规定数字。

流程：

```text
完成最小可运行版本
→ 建立可重复 benchmark
→ 测量基线
→ 冻结下一阶段目标
```

基线至少包括：

```text
空闲内存
启动到 readiness 时间
单语句事务吞吐
进程恢复时间
1k/10k WAITING 进程资源
IPC direct/channel/broadcast 吞吐
VFS bytea 读写
package 导入
数据库增长率
ARM64 与 AMD64 差异
```

---

# 21. 实施阶段

## 阶段 0：冻结旧语义与 Java 数据库重构基线

```text
保留现有 pre-refactor tag
继续在 main 上重构
确认当前 JDK 与 Maven 版本并固定
整理现有 Maven 工程和 Java package 边界
锁定 PostgreSQL JDBC、SQLite JDBC、HikariCP 与 Flyway 版本
建立现有 FCL 行为回归测试清单
列出全部旧持久状态和外部副作用
```

退出条件：

```text
旧实现可通过 tag 找回
现有 Maven 工程能在 macOS ARM64 开发机与 Linux AMD64/ARM64 CI 构建和测试
当前 FCL 回归测试形成数据库重构前基线
Docker build 能生成可启动的 Java 镜像骨架
```

## 阶段 1：Docker 与 PostgreSQL 基础设施

```text
postgres/migrate/cilexec 三服务
ephemeral 与 persistent profile
Secrets
healthcheck
Flyway
Role
全面 RLS 测试框架
```

退出条件：

```text
空数据库可自动迁移
Runtime 获得控制锁后进入 readiness
```

## 阶段 2：Meta、Auth 与控制权

```text
meta.instance
meta.boot
meta.kernel_instance
用户 ↔ PostgreSQL LOGIN Role
SET LOCAL ROLE
RLS
advisory lock
```

## 阶段 3：Program、Process 与逐语句事务

```text
共享不可变 program
完整 continuation
变量当前值
PID 永不复用
state_version
execution_epoch
```

退出条件：

```text
任意已提交 FCL 语句后强制 kill 可正确恢复
```

## 阶段 4：FIFO Scheduler 与 Lease

```text
queue
SKIP LOCKED
runner
heartbeat
expires_at
重领和旧 epoch 拒绝
```

## 阶段 5：IPC、Topic、Broadcast 与 Timer

```text
message
delivery
channel
topic
subscription
fan-out
timer
```

## 阶段 6：VFS 与 Object Store

```text
node
bytea object
内容寻址
原子替换
可选 revision
宿主 mount
```

## 阶段 7：SQLite Package 与 Environment

```text
package.db schema
规范化 package_hash
坐标唯一
object_store 存完整 db
环境
binding
进程精确 hash
```

## 阶段 8：Effect、Terminal、Audit

```text
effect journal
独立 worker Role
UNKNOWN
终端提交输入
持久 Ctrl+C
审计保留策略
```

## 阶段 9：备份、导出和强化

```text
pg_dump/restore
应用级 .db export
多架构镜像
崩溃矩阵
性能基线
删除所有旧文件持久化代码
```

---

# 22. 首版明确不做

```text
裸机或自研内核
第二套语言运行时或双实现长期并存
多主动 Runtime
PostgreSQL 高可用集群
跨 database 事务
自动 schema downgrade
任意软件包生命周期钩子
运行中进程自动切换新 package_hash
SQLite 模拟 PostgreSQL 测试
普通变量逐次变化永久审计
PostgreSQL 主版本 volume 直接复用
依靠宿主文件路径保存 package 真相
```

---

# 23. 禁止事项

```text
不得双写旧文件和 PostgreSQL
不得在数据库断线后继续推进进程
不得让 Runtime 使用 PostgreSQL superuser
不得让 Runtime 在生产环境修改 schema
不得绕过 RLS
不得在长时间外部操作期间持有事务
不得在数据库事务内执行 HTTP/Socket/宿主命令
不得使用最后写入者胜利覆盖进程状态
不得修改已发布 package.db
不得写回软件包数据库
不得允许同一坐标对应不同 package_hash
不得静默升级运行进程的包绑定
不得把 SQLite package cache 当作真相
不得把容器可写层描述为可靠持久存储
不得把 volume 当作备份
```

---

# 24. 重构完成定义

只有满足全部条件才算完成：

```text
CilExec 核心继续由 Java 实现
现有 FCL 对外语义保持兼容
旧文件持久化已从正式运行路径删除
Compose 从第一阶段即为标准环境
PostgreSQL 是唯一权威状态
每条 FCL 语句拥有明确事务
完整 continuation 可恢复
单主动 Runtime 受 advisory lock 保护
旧 epoch 无法提交
CilExec 用户映射 PostgreSQL LOGIN Role
业务表全面 RLS
FIFO 调度和 lease 可恢复
IPC direct/channel/topic/broadcast 可持久恢复
Timer 不依赖内存 sleep
VFS 内容进入内容寻址 object store
package.db 是不可变 SQLite
同一坐标不能出现不同哈希
package 环境和进程精确哈希有效
所有外部副作用经过 effect journal
审计与普通日志分离
SIGTERM 和强制 kill 均有测试
pg_dump/restore 有自动恢复测试
应用级 .db 导出可验证
AMD64 和 ARM64 CI 均通过
旧 .proc 和旧文件持久化代码被删除
```

---

# 25. 冻结决策登记表

| 编号 | 优先级 | 问题 | 最终决定 | 补充 |
|---|---|---|---|---|
| 1 | P0 | 本轮是否更换语言 | A. 保留 Java，只重构持久化和部署 | 最终修订：不进行语言重写 |
| 2 | P0 | CilExec 的正式定位 | B. 数据库驱动的用户态操作系统 |  |
| 3 | P0 | 一个 CilExec 实例对应多少数据库 | B. 一个实例一个 PostgreSQL database |  |
| 4 | P0 | 一个数据库允许多少主动 Kernel | A. 只允许一个主动 Kernel | 不知道啥意思 |
| 5 | P0 | 数据库是不是唯一真相来源 | A. PostgreSQL 是唯一真相来源 |  |
| 6 | P0 | Java 内存状态的地位 | B. 数据库状态为真，内存是可重建投影 |  |
| 7 | P0 | PostgreSQL 与 CilExec 是否放在同一容器 | B. PostgreSQL 与 CilExec 分成两个容器 |  |
| 8 | P0 | 是否设置独立 migration 服务 | B. 独立 migrate 容器先执行迁移 |  |
| 9 | P0 | Docker 中的数据持久化位置 | C. 存在容器可写层 | 仅作为可丢弃开发模式；持久实例必须挂载持久存储 |
| 10 | P1 | Docker 网络暴露 | A. PostgreSQL 5432 默认暴露到宿主 |  |
| 11 | P0 | 启动依赖关系 | B. PostgreSQL 健康后迁移，迁移成功后启动 CilExec |  |
| 12 | P1 | 容器收到 SIGTERM 后的行为 | B. 执行 CilExec 正常关闭流程并设置有限宽限期 |  |
| 13 | P1 | 容器是否以 root 用户运行 | B. 固定非 root 用户 |  |
| 14 | P1 | Docker 镜像支持哪些架构 | C. 同时发布 amd64 和 arm64 |  |
| 15 | P1 | 软件包本地缓存放在哪里 | C. tmpfs 或可丢弃缓存目录 |  |
| 16 | P0 | PostgreSQL 系统角色数量 | B. owner、migrator、kernel、effect-worker、readonly 分离 |  |
| 17 | P0 | CilExec 普通用户是否对应 PostgreSQL Role | A. 每个 CilExec 用户都是数据库 LOGIN Role |  |
| 18 | P0 | Kernel 是否可以修改数据库结构 | C. 开发环境可以，生产环境不可以 |  |
| 19 | P2 | 是否首版启用 Row-Level Security | A. 所有业务表全面启用 |  |
| 20 | P0 | 数据库密码如何提供 | C. Docker secrets 或外部秘密管理器 |  |
| 21 | P0 | 单主动 Kernel 如何保证 | B. PostgreSQL session advisory lock |  |
| 22 | P0 | control connection 断开后怎么办 | B. 停止领取和提交，进入 fenced 状态并退出容器 |  |
| 23 | P1 | PostgreSQL 临时断线期间是否允许执行纯计算 | B. 立即冻结所有进程 |  |
| 24 | P0 | 每条 FCL 语句是否对应一次数据库事务 | A. 每条语句 COMMIT |  |
| 25 | P1 | 执行量子的终止条件（可多选） | C. 遇到外部可见操作；D. 遇到阻塞；E. 用户中断 |  |
| 26 | P0 | 哪些操作是强制安全点（可多选） | A. fork；B. IPC send/receive；C. VFS 可见修改；D. timer 创建或等待；E. 进程挂起；F. 用户输入等待；G. effect 请求；H. 包导入；I. 进程终止；J. 执行量子结束 | 所有 |
| 27 | P0 | 默认事务隔离级别 | A. READ COMMITTED | 由方案按整体一致性决定 |
| 28 | P0 | 冲突处理方式 | B. state_version 乐观并发控制，配合必要行锁 |  |
| 29 | P1 | 数据库死锁或序列化失败是否自动重试 | C. 有限次数、带抖动重试 |  |
| 30 | P0 | 是否规定全局锁顺序 | B. 文档规定统一锁顺序 |  |
| 31 | P0 | 进程代码是否每进程复制 | B. 不可变 program 共享，进程只引用 program_id | 你可能需要解释一下program ID是什么 |
| 32 | P1 | 程序身份 | C. 内容哈希 + 内部 ID |  |
| 33 | P0 | 进程是否保存完整 continuation | B. 保存完整解释器 continuation |  |
| 34 | P1 | PID 的身份规则 | A. PID 永不复用 |  |
| 35 | P0 | RUNNING 状态崩溃后的处理 | C. 根据最后安全点和等待原因分类恢复 |  |
| 36 | P2 | 已终止进程保留多久 | C. 保留元数据，定期归档或清理重状态 | 保留期限和清理策略由配置确定 |
| 37 | P1 | 首版调度策略 | A. FIFO |  |
| 38 | P0 | 调度队列领取方式 | B. PostgreSQL FOR UPDATE SKIP LOCKED |  |
| 39 | P1 | 首版 worker 数量 | C. 配置项，默认较小 |  |
| 40 | P0 | lease 是否必须设置过期时间 | B. 有过期时间和 heartbeat |  |
| 41 | P0 | 首版文件内容如何存储 | A. 每个文件一个 bytea |  |
| 42 | P1 | 文件是否默认保留所有历史版本 | C. 默认只保留当前版本，指定类型可版本化 |  |
| 43 | P0 | 是否使用内容寻址 | B. node 指向不可变内容对象 |  |
| 44 | P0 | Object Store 是否同时保存软件包 .db | C. package.release 只引用 object_store |  |
| 45 | P1 | VFS 是否允许宿主目录挂载 | C. 作为高权限、显式配置的 mount |  |
| 46 | P0 | 软件包 .db 的底层格式 | A. SQLite |  |
| 47 | P0 | 软件包最终身份 | C. 规范化逻辑内容的 package_hash |  |
| 48 | P0 | 同一坐标能否对应多个哈希 | B. 绝对禁止 | 由方案按不可变发布和版本唯一性决定 |
| 49 | P0 | PostgreSQL 是否保存软件包完整 .db | A. 保存完整字节 |  |
| 50 | P0 | 包导入后谁是内容权威 | A. 原始 .db 是权威，索引表是派生数据 |  |
| 51 | P0 | 安装概念是什么 | B. 建立 binding → package_hash |  |
| 52 | P0 | 是否需要 package environment | B. 需要显式环境，类似独立依赖环境 |  |
| 53 | P0 | 进程导入包时是否固定哈希 | B. 首次解析后写入精确 hash |  |
| 54 | P0 | 软件包生命周期钩子 | A. 首版完全禁止 | 你需要解释一下什么意思 |
| 55 | P1 | 包完整性策略 | B. 只使用数据库文件哈希与逻辑内容哈希，不引入信任状态 |  |
| 56 | P0 | 软件包可变数据放在哪里 | B. VFS 中的独立 data scope |  |
| 57 | P0 | IPC 消息交付语义 | C. 数据库内精确消费一次 |  |
| 58 | P1 | 消息消费状态机 | B. PENDING → RESERVED → CONSUMED / FAILED / DEAD |  |
| 59 | P1 | 是否允许广播和 channel | C. 支持广播、topic 和订阅 |  |
| 60 | P1 | Timer 的权威表示 | B. 数据库 timer 行，Java 负责唤醒 |  |
| 61 | P1 | 终端输入保存到什么粒度 | B. 每次完整提交的输入 |  |
| 62 | P0 | Ctrl+C 如何表达 | B. 设置持久 interrupt_requested，在安全点处理 |  |
| 63 | P0 | 外部操作是否统一进入 effect 系统 | A. 是 |  |
| 64 | P0 | effect 的重复执行策略 | B. 每类 effect 声明幂等性和恢复策略 |  |
| 65 | P1 | UNKNOWN effect 如何处理 | C. 按 effect 类型处理；无法判断时人工介入 |  |
| 66 | P1 | Effect Worker 是否使用独立数据库 Role | B. 独立 cilexec_effect_worker |  |
| 67 | P0 | 审计事件与普通日志是否分离 | B. 审计进数据库，运行日志进 stdout/stderr |  |
| 68 | P2 | 审计记录保留多久 | C. 根据事件类型分别设置 |  |
| 69 | P2 | 是否记录所有变量修改 | C. 只记录安全、管理和外部可见事件 |  |
| 70 | P1 | Docker 日志方式 | B. stdout/stderr |  |
| 71 | P1 | 是否提供健康端点 | C. 区分 liveness 与 readiness |  |
| 72 | P0 | PostgreSQL volume 是否视为备份 | B. 不是 |  |
| 73 | P1 | 灾难恢复备份格式 | A. pg_dump 逻辑备份 | 不知道什么意思 |
| 74 | P1 | CilExec 实例是否提供独立逻辑导出 | B. 导出为应用级 .db 容器 |  |
| 75 | P0 | 是否试图导出 PostgreSQL 的全部运行状态 | B. 只导出持久语义状态 |  |
| 76 | P1 | PostgreSQL 主版本升级策略 | B. dump/restore |  |
| 77 | P0 | 数据库测试使用什么 | C. 启动真实 PostgreSQL 测试容器 |  |
| 78 | P0 | 是否进行真实强制崩溃测试 | B. 强制终止 JVM 和容器后验证恢复 |  |
| 79 | P1 | 软件包确定性测试 | C. 两者分别测试 |  |
| 80 | P1 | Docker 测试平台 | C. CI 同时测试 Linux amd64 和 arm64 |  |
| 81 | P1 | 性能基准目标 | A. 先完成可运行版本，测量基线后再设具体目标 |  |
| 82 | P0 | 是否保留旧数据迁移器 | A. 不保留 |  |
| 83 | P0 | 是否在同一分支直接重写 | A. 直接修改主分支 | 已建立重构前 tag，继续在 main 上重构 |
| 84 | P1 | 新数据库 schema 的版本管理工具 | B. Flyway |  |
| 85 | P0 | 是否允许自动降级数据库 schema | B. 禁止，只允许向前 migration |  |
| 86 | P0 | Docker 是否从重构第一阶段就加入 | B. 第一阶段就用 Compose 作为标准开发环境 |  |

---

# 26. 最终架构摘要

```text
Host Linux / Docker
│
├── PostgreSQL
│   └── 一个 database = 一个 CilExec 实例
│
├── Flyway migrate
│
└── Java CilExec Runtime
    ├── FCL parser/runtime
    ├── Program store
    ├── Process continuation
    ├── FIFO scheduler
    ├── Persistent IPC bus
    ├── Timer
    ├── VFS/Object Store
    ├── SQLite package manager
    ├── Package environment
    ├── Effect worker
    ├── Terminal
    ├── Auth/RLS
    └── Audit

PostgreSQL 已提交状态
=
CilExec 实例的真实状态

Java/JVM 内存对象
=
可丢弃、可从数据库重建的运行投影

package.db
=
不可变 SQLite 软件包发布物
```

最终原则：

> **Java 推动执行，PostgreSQL 保存实例真相，Docker 提供可部署宿主边界，不可变 SQLite `.db` 携带软件包。**
