# CilExec 数据库驱动重构总方案

状态：设计草案 v0.1
目标：将 CilExec 从文件驱动运行时，重构为 PostgreSQL 持久化、Java 驱动的事务化进程系统。

---

## 1. 本次重构的最终目标

重构完成后，CilExec 应满足以下定义：

> 一个 PostgreSQL database 表示一个完整的 CilExec 实例；Java 程序负责解释和执行 FCL，PostgreSQL 负责保存该实例的全部持久状态。

PostgreSQL 中，一个 database 可以包含多个 schema；schema 用于组织表、函数和其他数据库对象。角色属于整个 PostgreSQL cluster。一个连接在同一时刻访问一个指定 database。

目标结构：

```text
PostgreSQL cluster
├── PostgreSQL roles
│   ├── cilexec_owner
│   ├── cilexec_migrator
│   ├── cilexec_kernel
│   ├── cilexec_effect_worker
│   └── cilexec_readonly
│
└── cilexec database
    ├── meta
    ├── auth
    ├── object_store
    ├── vfs
    ├── package
    ├── process
    ├── scheduler
    ├── ipc
    ├── effect
    ├── terminal
    └── audit
```

重构完成后：

```text
数据库已提交状态 = 实例的真实状态
Java 内存对象       = 实例的一次运行投影
COMMIT             = 状态变化正式生效的时刻
```

---

# 2. 已确定的架构决策

## 2.1 Java 保留

CilExec 继续使用 Java。

理由不是保守，而是明确的工程选择：

* CilExec 的主要复杂度是状态模型和运行语义，不是底层语言。
* Java 提供跨平台运行环境。
* JDBC 与 PostgreSQL 集成成熟。
* 虚拟线程适合大量等待型任务。
* 当前开发能力和现有代码都集中在 Java。
* 不增加 Rust、C、构建工具链和跨平台编译负担。

本次重构只更换持久化内核，不更换执行语言。

---

## 2.2 PostgreSQL 是运行实例的唯一真相来源

以下状态必须进入 PostgreSQL：

```text
用户
用户组
权限
进程
变量
代码
函数调用状态
进程关系
调度队列
IPC 消息
虚拟文件系统
软件包
终端会话
外部副作用日志
审计记录
恢复信息
```

以下内容不属于实例状态：

```text
Java Thread 对象
JDBC Connection
PreparedStatement
Java 锁对象
解析器临时缓存
ClassLoader 实例
可重新构建的 AST
日志输出文件
PostgreSQL 连接密码
```

数据库外不再保存任何权威进程状态。

---

## 2.3 不保留旧 `.proc` 运行格式

项目尚未发布，因此不做：

* 旧格式兼容；
* 双写；
* 自动迁移旧测试数据；
* `FileProcessStore`；
* 文件存储和数据库存储并行维护。

旧实现只保留在 Git 历史中。

重构分支建立前，应创建一个标签：

```bash
git tag -a pre-database-runtime \
  -m "Last file-backed CilExec runtime"
```

---

## 2.4 一个实例一个 PostgreSQL database

不采用：

```text
一个进程一个数据库
一个用户一个数据库
一个模块一个数据库
```

这些方案会重新引入跨数据库事务问题。

采用：

```text
一个 CilExec 实例
=
一个 PostgreSQL database
```

模块通过 schema 划分，而不是通过数据库文件划分。

---

## 2.5 首版只支持一个主动 CilExec 内核实例

第一版不实现分布式 CilExec 集群。

规则：

```text
一个 cilexec database
同一时间
只能由一个主动 CilExec Kernel 控制
```

PostgreSQL 可以支持多个连接和多个 Java worker，但它们属于同一个 JVM 内核实例。

未来要支持多节点时，再扩展 leader election、分布式 lease 和故障转移。

---

# 3. 数据库 schema 总览

## 3.1 `meta`

保存实例自身的信息。

主要表：

```text
meta.instance
meta.boot
meta.schema_migration
meta.kernel_instance
```

### `meta.instance`

一个实例只有一行：

```text
instance_id
instance_name
database_format_version
minimum_kernel_version
created_at
last_started_at
last_clean_shutdown_at
instance_status
```

`instance_status`：

```text
INITIALIZING
READY
RUNNING
RECOVERING
STOPPING
BROKEN
```

### `meta.boot`

每次 CilExec 启动产生一行：

```text
boot_id
kernel_instance_id
cilexec_version
java_version
host_name
started_at
ended_at
shutdown_type
```

`shutdown_type`：

```text
CLEAN
CRASHED
FORCED
UNKNOWN
```

新启动时，所有没有 `ended_at` 的旧启动记录都被视为异常终止。

### `meta.schema_migration`

```text
migration_version
description
checksum
applied_at
applied_by
```

数据库结构只能通过 migration 修改。

禁止 CilExec 在普通启动过程中临时检查字段并自行修改表。

---

## 3.2 `auth`

保存 CilExec 用户和权限。

主要表：

```text
auth.user_account
auth.user_group
auth.group_member
auth.capability
auth.capability_grant
auth.session
```

普通 CilExec 用户默认不对应可直接登录 PostgreSQL 的 Role。

PostgreSQL Role 用于区分系统组件：

```text
cilexec_owner
cilexec_migrator
cilexec_kernel
cilexec_effect_worker
cilexec_readonly
```

CilExec 用户用于表达：

```text
能否创建进程
能否终止其他进程
能否使用 Host Shell
能否访问某个 VFS 路径
能否加载某个软件包
能否发送 IPC
能否执行网络请求
```

### `auth.user_account`

```text
user_id
username
password_hash
status
primary_group_id
home_node_id
created_at
last_login_at
```

### `auth.capability_grant`

```text
grant_id
subject_type
subject_id
capability_name
resource_type
resource_pattern
effect
granted_by
created_at
expires_at
```

示例：

```text
USER 12  VFS_READ      PATH     /home/12/**    ALLOW
USER 12  HOST_EXEC     GLOBAL   *              DENY
GROUP 4  PACKAGE_LOAD  PACKAGE  compiler/**    ALLOW
```

---

# 4. 数据库中的“文件”模型

数据库中的文件不能只是：

```text
path + bytea
```

一个完整文件至少需要区分四种身份：

```text
文件节点身份
文件当前内容身份
文件历史版本身份
目录中的名称和位置
```

最终模型：

```text
文件 = VFS 节点 + 不可变内容对象 + 当前版本引用
```

---

## 4.1 `vfs.node`

每个目录、文件和符号链接都是一个节点：

```text
node_id
parent_node_id
name
node_type

owner_id
group_id
permission_mode

current_content_hash
size
version

created_at
modified_at
deleted_at
```

`node_type`：

```text
DIRECTORY
FILE
SYMLINK
MOUNT
DEVICE
```

关键约束：

```text
UNIQUE(parent_node_id, name)
```

目录：

```text
current_content_hash = NULL
```

普通文件：

```text
current_content_hash = 对应 object_store.object
```

文件身份由 `node_id` 决定。

文件内容身份由 `current_content_hash` 决定。

同一个文件可以多次修改，但 `node_id` 不变，内容 hash 改变。

---

## 4.2 `vfs.file_revision`

用于保存文件修改历史：

```text
revision_id
node_id
content_hash
size
previous_revision_id
created_by_user
created_by_process
created_at
reason
```

首版可以只为以下文件启用历史：

* 系统配置；
* FCL 源代码；
* 软件包描述；
* 用户主动要求版本化的文件。

不要求所有临时文件永久保存历史。

---

## 4.3 `vfs.symlink`

```text
node_id
target_path
```

---

## 4.4 `vfs.mount`

```text
mount_id
node_id
mount_type
source_id
read_only
created_at
```

`mount_type`：

```text
PACKAGE
HOST
TEMPORARY
SYSTEM
```

软件包安装后可以通过只读挂载出现在 VFS 中。

---

# 5. 不可变内容对象存储

所有文件内容、软件包内容和大型二进制数据统一进入：

```text
object_store
```

## 5.1 `object_store.object`

```text
object_hash
size
chunk_count
media_type
compression
created_at
```

建议：

```text
hash algorithm = SHA-256
object_hash     = 32 字节 bytea
```

约束：

```sql
CHECK (octet_length(object_hash) = 32)
```

内容对象一旦创建，不允许修改。

修改文件时不是修改旧对象，而是：

```text
生成新内容
计算新 hash
插入新 object
更新 vfs.node.current_content_hash
```

---

## 5.2 `object_store.chunk`

```text
object_hash
chunk_index
chunk_data
```

主键：

```text
(object_hash, chunk_index)
```

首版建议固定：

```text
chunk size = 1 MiB
```

最后一块可以小于 1 MiB。

采用 chunk 表而不是把整个大文件放进一行，原因是：

* 可以流式读写；
* 不必一次把整个文件读入 JVM；
* 可以验证每个对象是否完整；
* 避免超大单行；
* 更容易做限额和进度报告；
* 删除对象时可以通过外键级联删除所有 chunk。

---

## 5.3 首版不使用 PostgreSQL Large Object

PostgreSQL 提供 Large Object 接口，但它拥有独立的对象生命周期和访问方式。PostgreSQL JDBC 文档特别提醒，删除引用 Large Object 的普通表行并不会自动删除该对象，并且其权限处理也需要额外注意。

因此首版采用：

```text
普通表
+
分块 bytea
+
外键
```

而不采用 `pg_largeobject`。

以后只有在需要真正的超大型随机访问对象时，再重新评估。

---

# 6. 文件原子写入流程

一次 CilExec VFS 写入：

```fcl
write("/home/user/a.txt", "hello")
```

执行流程：

```text
1. Java 计算内容 hash
2. 开启事务
3. 如果 object 不存在，插入 object 和 chunks
4. 锁定对应 vfs.node
5. 插入 file_revision
6. 更新 node.current_content_hash
7. 更新 node.size 和 version
8. 更新进程指令位置
9. 写入审计事件
10. COMMIT
```

最终只允许两种结果：

```text
文件和进程状态都更新
或者
任何内容都没有更新
```

不允许出现：

```text
文件已经修改
但进程还停留在原语句
```

---

# 7. 软件包发布格式

## 7.1 明确区分两种数据库

运行实例：

```text
PostgreSQL
```

发布包文件：

```text
SQLite application file
```

这不是让 SQLite 重新成为运行时数据库。

SQLite 在这里仅作为一种**单文件软件包格式**。

SQLite 数据库是单个、跨平台文件，并且官方明确将“用 SQLite 作为应用文件格式”作为推荐用途之一。SQLite 还提供 `application_id` 和 `user_version` 字段，用于识别应用文件类型及其格式版本。

软件包扩展名：

```text
.cilpkg
```

例如：

```text
compiler-2.1.0.cilpkg
```

物理上它是 SQLite 数据库。

逻辑上它是一个不可变 CilExec 软件包。

---

# 8. `.cilpkg` 文件必须包含的部分

## 8.1 SQLite 文件头标识

创建包时设置：

```sql
PRAGMA application_id = <CilExec 分配的固定整数>;
PRAGMA user_version = 1;
```

`application_id` 表示这是 CilExec 软件包。

`user_version` 表示 `.cilpkg` 格式版本。

---

## 8.2 `package_manifest`

只有一行：

```text
package_id
namespace
package_name
version

package_format_version
fcl_language_version
minimum_kernel_version

description
license
publisher
homepage

release_hash
created_at
```

完整包名：

```text
namespace/package_name
```

例如：

```text
std/network
suheling/compiler
```

---

## 8.3 `package_file`

```text
path
file_type
content_hash
size
media_type
permission_mode
executable
```

`file_type`：

```text
FILE
DIRECTORY
SYMLINK
```

路径必须：

* 使用 `/`；
* 是包内相对路径；
* 不允许 `..`；
* 不允许宿主系统绝对路径；
* 不允许 Windows 盘符；
* 不允许空路径段。

---

## 8.4 `package_object`

```text
content_hash
size
compression
content
```

较小的包可以每个对象一行。

为了统一，也可以使用：

```text
package_object
package_object_chunk
```

其结构与 PostgreSQL 的 object store 一致。

---

## 8.5 `package_dependency`

```text
dependency_namespace
dependency_name
version_constraint
dependency_scope
optional
```

`dependency_scope`：

```text
RUNTIME
BUILD
DEVELOPMENT
```

发布包中保存声明依赖。

---

## 8.6 `package_lock`

```text
dependency_namespace
dependency_name
resolved_version
resolved_release_hash
```

`package_dependency` 表示：

```text
允许使用什么版本
```

`package_lock` 表示：

```text
构建这个包时实际使用了什么版本
```

---

## 8.7 `package_entrypoint`

```text
entrypoint_name
entrypoint_type
target_path
target_symbol
```

`entrypoint_type`：

```text
COMMAND
LIBRARY
SERVICE
PLUGIN
BOOTSTRAP
```

例如：

```text
compile
COMMAND
/bin/compiler.fcl
main
```

---

## 8.8 `package_export`

```text
export_name
export_type
source_path
source_symbol
```

用于声明包对外暴露：

* FCL 函数；
* 命令；
* 插件；
* 资源；
* 服务；
* 类型定义。

---

## 8.9 `package_capability`

```text
capability_name
resource_pattern
required
reason
```

例如：

```text
NETWORK_HTTP    https://example.com/**   true
VFS_READ        /usr/include/**          true
HOST_EXEC       *                        false
```

安装时必须向用户或管理员展示这些能力要求。

软件包不能因为声明了能力就自动获得能力。

声明只是申请。

授权仍由 CilExec 权限系统决定。

---

## 8.10 `package_signature`

```text
key_id
algorithm
signed_release_hash
signature
created_at
```

首版建议：

```text
hash      = SHA-256
signature = Ed25519
```

---

## 8.11 `package_build_info`

```text
builder_version
source_revision
build_environment
reproducible
build_timestamp
```

构建时间不参与语义版本 hash，否则每次构建都会产生不同发布身份。

---

# 9. 软件包的两个 hash

必须区分：

## 9.1 `artifact_hash`

```text
SHA-256(.cilpkg 原始字节)
```

用途：

* 下载完整性；
* 上传完整性；
* 缓存；
* 判断两个文件字节是否相同。

## 9.2 `release_hash`

由规范化内容计算：

```text
manifest 核心字段
+
按路径排序的文件清单
+
每个文件的 content_hash
+
依赖锁
+
能力声明
```

用途：

* 软件包发布身份；
* 签名；
* 依赖锁定；
* 内容寻址；
* 可复现构建。

不能只使用 SQLite 文件本身的字节 hash 作为发布身份。

SQLite 内部页面布局、索引重建或整理可能改变物理文件字节，即使包的逻辑内容相同。

因此：

```text
artifact_hash = 运输文件身份
release_hash  = 软件包语义身份
```

---

# 10. 软件包在 PostgreSQL 中的表示

`.cilpkg` 进入 CilExec 实例后，不能只作为一个无法查询的 BLOB 存放。

它必须同时拥有：

```text
原始包文件
+
规范化软件包记录
```

主要表：

```text
package.package
package.release
package.release_file
package.release_dependency
package.release_entrypoint
package.release_export
package.release_capability
package.release_signature
package.installation
package.mount
```

---

## 10.1 `package.package`

表示软件包名称：

```text
package_id
namespace
name
owner_id
description
created_at
```

唯一约束：

```text
UNIQUE(namespace, name)
```

---

## 10.2 `package.release`

表示一个不可变版本：

```text
release_id
package_id
version
release_hash
artifact_hash
artifact_node_id
publisher_id
minimum_kernel_version
published_at
status
```

`artifact_node_id` 指向 VFS 中的 `.cilpkg` 文件。

状态：

```text
STAGED
VERIFIED
PUBLISHED
REVOKED
BROKEN
```

发布后不得修改。

有变化必须发布新版本。

---

## 10.3 `package.release_file`

```text
release_id
path
file_type
content_hash
size
permission_mode
executable
```

这些内容从 `.cilpkg` 中读取并规范化进入 PostgreSQL。

运行时不需要反复打开 SQLite 软件包文件。

---

## 10.4 `package.installation`

```text
installation_id
environment_id
release_id
installed_by
installed_at
status
```

状态：

```text
INSTALLING
INSTALLED
DISABLED
REMOVED
FAILED
```

---

# 11. 软件包发布流程

发布分为三个阶段。

## 11.1 构建

```text
读取项目源目录
验证 manifest
计算所有文件 content_hash
生成依赖锁
计算 release_hash
生成签名
创建临时 SQLite 数据库
写入所有包表
设置 application_id
设置 user_version
完成 SQLite 事务
关闭数据库
计算 artifact_hash
```

构建完成后才将临时文件重命名为：

```text
<name>-<version>.cilpkg
```

---

## 11.2 导入

上传 `.cilpkg` 后：

```text
1. 检查 SQLite 文件是否合法
2. 检查 application_id
3. 检查 user_version
4. 检查必需表
5. 检查所有路径
6. 检查对象 hash
7. 检查 release_hash
8. 检查签名
9. 检查版本冲突
```

这些检查在数据库事务外完成，避免长事务。

---

## 11.3 发布事务

验证完成后开启 PostgreSQL 事务：

```text
写入原始 .cilpkg 内容对象
创建 VFS 软件包文件
创建 package.release
写入 release_file
写入 dependencies
写入 exports
写入 capabilities
写入 signatures
将 release 设为 PUBLISHED
COMMIT
```

如果任意一步失败，软件包完全没有发布。

---

# 12. 软件包安装后的文件表现

软件包不直接复制到用户可写目录。

采用：

```text
不可变软件包层
+
可写覆盖层
```

例如：

```text
/packages/std/network/1.2.0
```

是只读挂载。

用户配置和运行数据进入：

```text
/home/user/.config/network
/var/lib/network
```

软件包内容来自：

```text
package.release_file
+
object_store.object
```

VFS 中通过 `vfs.mount` 暴露。

好处：

* 安装不会复制大量相同文件；
* 相同对象只保存一次；
* 软件包不可被悄悄修改；
* 卸载只删除安装和挂载关系；
* 运行中的旧进程仍可引用旧 release；
* 不会破坏可复现性。

---

# 13. 进程数据库模型

## 13.1 进程身份

采用双重身份：

```text
process_uid = 数据库内部稳定 UUID
pid         = 用户可见进程号
generation  = PID 复用代数
```

`process_uid` 作为数据库外键。

用户界面显示：

```text
pid:generation
```

例如：

```text
42:3
```

---

## 13.2 `process.process`

```text
process_uid
pid
generation

owner_id
parent_process_uid

status
priority
current_statement_id
working_directory_node_id

state_version
execution_epoch

interrupt_requested
suspend_reason
exit_code

created_at
updated_at
terminated_at
```

状态：

```text
CREATED
READY
RUNNING
WAITING
SUSPENDED
TERMINATING
TERMINATED
FAILED
```

---

## 13.3 其他进程表

```text
process.statement
process.variable
process.call_frame
process.scope
process.import
process.relationship
process.timer
process.event
```

### `process.statement`

```text
statement_id
process_uid
sequence_number
source_text
source_origin
source_path
created_at
```

### `process.variable`

```text
process_uid
scope_id
variable_name
value_type
value_json
value_object_hash
version
```

小型值进入 `value_json`。

大型值进入 object store。

不保存 Java 序列化对象。

---

# 14. 调度器模型

主要表：

```text
scheduler.queue
scheduler.runner
scheduler.lease
```

## `scheduler.queue`

```text
process_uid
priority
virtual_runtime
ready_at
queue_state
enqueued_at
```

## `scheduler.runner`

```text
runner_id
boot_id
thread_name
status
started_at
heartbeat_at
```

## `scheduler.lease`

```text
process_uid
runner_id
execution_epoch
claimed_at
expires_at
```

每次领取进程：

```text
execution_epoch += 1
```

提交执行结果时必须同时验证：

```text
process_uid
state_version
execution_epoch
```

旧 runner 即使恢复，也无法覆盖新 runner 的结果。

---

# 15. JDBC 与虚拟线程规则

虚拟线程数量可以很多。

数据库连接数量不能跟虚拟线程数量一致。

采用：

```text
大量虚拟线程
+
有上限的 JDBC 连接池
```

规则：

* 一个虚拟线程只在短事务期间借用连接；
* 不允许持有连接等待 IPC；
* 不允许持有连接执行网络请求；
* 不允许在数据库事务内等待用户输入；
* 不允许一个进程生命周期对应一个连接；
* 所有 JDBC 对象必须及时关闭。

---

# 16. 事务边界

## 16.1 普通 FCL 语句

```text
验证 lease
锁定进程
验证 state_version
更新变量
推进 statement
更新状态
更新调度队列
COMMIT
```

## 16.2 fork

```text
分配 PID 和 generation
创建子进程
复制必要变量和调用上下文
写入父进程返回值
推进父进程 statement
加入子进程调度队列
记录关系
COMMIT
```

## 16.3 IPC send

```text
创建消息
必要时唤醒接收者
推进发送者 statement
更新发送者状态
COMMIT
```

## 16.4 IPC receive

```text
锁定消息
写入接收变量
标记消息已消费
推进接收者 statement
COMMIT
```

## 16.5 软件包安装

```text
验证 release
创建 installation
创建 mount
更新环境包集合
记录审计
COMMIT
```

PostgreSQL 使用事务组织多个步骤，提交时整体生效；WAL 负责先记录恢复所需日志，再允许实际数据页稍后写回。

---

# 17. 外部副作用

以下操作不能直接放进数据库事务：

```text
HTTP 请求
Socket
宿主文件写入
启动外部程序
发送邮件
访问硬件
```

采用：

```text
effect.effect
effect.attempt
effect.result
```

流程：

```text
事务一：
创建 PREPARED effect
将进程设为 WAITING_EFFECT
COMMIT

事务外：
Effect Worker 执行外部操作

事务二：
保存结果
将 effect 设为 COMPLETED
唤醒进程
COMMIT
```

每个 effect 必须有：

```text
effect_id
idempotency_key
request_payload
status
attempt_count
result_payload
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

数据库事务不能解决数据库外部操作的 exactly-once，因此现有 effect journal 思想必须保留。

---

# 18. Java 代码结构

建议建立以下模块边界：

```text
cilexec-bootstrap
cilexec-domain
cilexec-interpreter
cilexec-kernel
cilexec-storage-postgres
cilexec-package-format
cilexec-terminal
cilexec-tests
```

## `cilexec-domain`

只包含：

* 领域对象；
* 枚举；
* Identity；
* Mutation；
* Repository 接口；
* 不变量。

不得依赖 JDBC。

## `cilexec-storage-postgres`

包含：

* SQL；
* JDBC；
* RowMapper；
* TransactionManager；
* Repository 实现；
* migration。

## `cilexec-package-format`

包含：

* `.cilpkg` 构建；
* SQLite 读取；
* 包验证；
* hash；
* 签名；
* manifest 模型。

---

# 19. 数据访问技术选择

首版采用：

```text
JDBC
+
明确 SQL
+
轻量映射
```

不建议首版采用 Hibernate/JPA。

原因：

* CilExec 对事务边界要求非常明确；
* 需要精确控制锁和版本条件；
* 大量操作不是普通 CRUD；
* fork、领取任务、IPC 消费都需要专门 SQL；
* 不应引入隐式 flush、延迟加载和对象生命周期。

可以使用：

* JDBC；
* 自己的 RowMapper；
* 可选 jOOQ。

但 Repository 接口不能暴露 SQL。

---

# 20. 启动流程

```text
1. 加载配置
2. 创建 DataSource
3. 验证 PostgreSQL 版本
4. 连接目标 database
5. 执行 migration
6. 获取实例控制锁
7. 创建 meta.kernel_instance
8. 创建 meta.boot
9. 检查旧 boot
10. 执行语义恢复
11. 启动 scheduler
12. 启动 effect worker
13. 启动 terminal
14. 将 instance_status 设为 RUNNING
```

PostgreSQL schema 名称必须在 SQL 中完整限定。

不依赖默认 `search_path`。

PostgreSQL 官方文档提醒，将可被其他用户创建对象的 schema 放入 `search_path` 会形成信任和安全边界。

---

# 21. 崩溃恢复流程

PostgreSQL 首先使用 WAL 恢复到最后一个可靠数据库状态。

随后 CilExec 执行语义恢复：

```text
1. 标记旧 boot 为 CRASHED
2. 使旧 runner 全部失效
3. 清除旧 scheduler lease
4. 找出 RUNNING 进程
5. 检查其最后提交安全点
6. 将可恢复进程改回 READY
7. 检查 WAITING_EFFECT 进程
8. 重新领取未完成 effect
9. 扫描 PENDING IPC
10. 扫描到期 timer
11. 恢复 terminal attachment
12. 启动调度
```

以后 CilExec 不再负责修复半写文件。

CilExec 只负责恢复业务语义。

---

# 22. 正常关闭流程

```text
1. instance_status = STOPPING
2. 停止领取新进程
3. 等待正在提交的短事务完成
4. 请求运行进程到达安全点
5. 释放 scheduler lease
6. 停止 effect worker
7. 停止 terminal
8. 更新 meta.boot.ended_at
9. shutdown_type = CLEAN
10. instance_status = READY
11. 释放实例控制锁
12. 关闭连接池
```

---

# 23. 重构执行阶段

## 阶段 0：冻结当前语义

完成：

* 给当前稳定提交打 tag；
* 列出所有进程状态；
* 列出所有持久字段；
* 列出所有文件写入位置；
* 列出所有锁；
* 列出所有副作用；
* 建立现有行为回归测试。

退出条件：

```text
能够明确回答当前系统究竟持久化了什么
```

---

## 阶段 1：数据库基础设施

完成：

* PostgreSQL 开发环境；
* DataSource；
* migration；
* `meta` schema；
* 测试数据库自动创建；
* 事务管理器；
* 数据库健康检查。

退出条件：

```text
CilExec 可以连接空数据库
执行 migration
记录 boot
正常关闭
```

---

## 阶段 2：object store 与 VFS

完成：

* `object_store.object`；
* `object_store.chunk`；
* `vfs.node`；
* 文件读写；
* 目录操作；
* 权限字段；
* 内容寻址；
* 原子文件替换。

退出条件：

```text
CilExec VFS 不再依赖宿主文件保存实例内部文件
```

---

## 阶段 3：进程状态

完成：

* `process.process`；
* statement；
* variable；
* call frame；
* imports；
* relationships；
* 创建、加载、更新、终止进程。

退出条件：

```text
删除所有 .proc 读写代码
```

---

## 阶段 4：调度与并发

完成：

* queue；
* runner；
* lease；
* state_version；
* execution_epoch；
* 虚拟线程 worker；
* 短事务提交。

退出条件：

```text
多个 worker 无法重复提交同一进程步骤
```

---

## 阶段 5：IPC 与 timer

完成：

* durable inbox；
* send；
* receive；
* 消息消费状态；
* timer；
* 到期唤醒。

退出条件：

```text
JVM 崩溃后消息和 timer 仍然可恢复
```

---

## 阶段 6：effect journal

完成：

* effect 状态机；
* effect worker；
* idempotency key；
* retry；
* UNKNOWN 状态；
* 恢复测试。

退出条件：

```text
任何外部副作用都不能绕过 effect 层
```

---

## 阶段 7：软件包格式与注册表

完成：

* `.cilpkg` SQLite schema；
* builder；
* validator；
* release hash；
* artifact hash；
* signature；
* PostgreSQL package registry；
* 包发布；
* 包安装；
* VFS 只读挂载。

退出条件：

```text
一个 .cilpkg 文件可以在另一套空 CilExec 实例中验证、发布和安装
```

---

## 阶段 8：用户、权限和审计

完成：

* auth；
* capabilities；
* VFS 权限；
* package capabilities；
* Host Shell 权限；
* audit events；
* PostgreSQL Role 分离。

退出条件：

```text
Kernel 不使用 owner 或 superuser 账号运行
```

---

## 阶段 9：清理和强化

删除：

* 旧进程文件代码；
* 文件锁；
* 旧状态迁移器；
* 旧 scheduler 持久化；
* 旧 inbox 文件；
* 无用序列化代码；
* 双重状态枚举；
* 临时兼容层。

退出条件：

```text
停止 PostgreSQL 后，CilExec 无法假装实例仍然存在
```

这意味着数据库已经真正成为唯一真相来源。

---

# 24. 必须建立的测试

## 数据库事务测试

* fork 中途失败；
* IPC 发送中途失败；
* VFS 写入中途失败；
* 包发布中途失败；
* 用户创建中途失败；
* effect 创建中途失败。

验证：

```text
全部提交或全部回滚
```

## 并发测试

* 两个 runner 同时领取同一进程；
* 旧 lease 过期后旧 runner 提交；
* 多进程同时写同一文件；
* 多消费者同时接收消息；
* 同一包版本同时发布；
* 同一对象同时上传。

## 崩溃测试

在以下位置强制终止 JVM：

```text
BEGIN 后
写变量后
推进 statement 前
插入消息后
COMMIT 前
COMMIT 后
effect 执行前
effect 执行后但结果写回前
```

## 软件包测试

* 文件被篡改；
* manifest 被篡改；
* hash 错误；
* 签名错误；
* 重复路径；
* `../` 路径；
* 循环依赖；
* 缺失对象；
* 不兼容内核版本；
* 未授权 capability；
* 相同内容重复构建得到相同 release_hash。

---

# 25. 首版明确不做

以下内容全部推迟：

* PostgreSQL 多节点高可用；
* CilExec 多内核实例同时控制一个实例；
* 每个普通用户一个 PostgreSQL 登录账号；
* 全表 Row-Level Security；
* 跨 PostgreSQL cluster 的 CilExec 实例；
* 分布式事务；
* 软件包热更新正在运行的旧进程；
* 自动兼容旧 `.proc`；
* PostgreSQL Large Object；
* 把所有日志永久保存；
* 通用 ORM；
* 数据库触发器实现完整业务逻辑；
* 在数据库内编写 FCL 解释器；
* 让软件包直接执行 SQL。

---

# 26. 必须遵守的禁止事项

```text
解释器不得直接执行 SQL
内置函数不得直接执行 SQL
ProcessRunner 不得管理 JDBC Connection
普通用户不得获得数据库凭据
不得在长时间计算期间保持事务
不得在事务中执行网络请求
不得双写数据库和旧文件
不得保存 Java 序列化对象
不得直接修改已发布 package.release
不得把 SQLite 软件包文件作为运行时查询数据库
不得用原始 artifact_hash 代替 release_hash
不得使用 PostgreSQL superuser 运行 Kernel
```

---

# 27. 重构完成的定义

只有同时满足以下条件，才能认为迁移完成：

```text
所有进程状态来自 PostgreSQL
所有 IPC 来自 PostgreSQL
所有调度状态来自 PostgreSQL
所有 CilExec VFS 文件来自 PostgreSQL
所有包安装状态来自 PostgreSQL
所有外部副作用经过 effect journal
所有状态改变拥有明确事务边界
JVM 被强制终止后可以恢复
不存在 .proc 运行文件
不存在文件锁作为核心并发机制
不存在双重真相来源
软件包可以构建为单个 .cilpkg
.cilpkg 可以被验证、签名、发布和安装
相同软件包内容得到相同 release_hash
```

---

# 28. 最终架构总结

```text
Java CilExec
├── FCL 解释器
├── Process Runtime
├── Scheduler
├── IPC
├── Effect Worker
├── VFS
├── Package Manager
└── Terminal

PostgreSQL database: cilexec
├── 实例元数据
├── 用户和权限
├── 对象存储
├── VFS
├── 软件包注册表
├── 进程
├── 调度
├── IPC
├── 外部副作用
├── 终端
└── 审计

.cilpkg
└── 单文件 SQLite 软件包
    ├── manifest
    ├── 文件清单
    ├── 内容对象
    ├── 依赖
    ├── 入口
    ├── exports
    ├── capabilities
    ├── 构建信息
    └── 签名
```

最终原则：

> PostgreSQL 保存实例状态，Java 推动实例运行，SQLite `.cilpkg` 负责携带软件包。