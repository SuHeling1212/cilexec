# CilExec 数据库驱动重构总方案

状态：设计草案 v0.2
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

软件包数据库中的 `package_file` 可以通过只读视图出现在 VFS 中。

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

# 7. 软件包系统的核心定义

## 7.1 一个软件包就是一个不可变数据库文件

CilExec 软件包的发布实体不是目录、ZIP 归档或若干松散文件，而是一个独立的数据库文件：

```text
<package-name>-<version>.db
```

首版采用 SQLite 作为软件包数据库文件格式，原因只有一个：它能够将完整的关系数据和二进制内容封装为单个跨平台文件。

运行实例仍然使用 PostgreSQL。

二者职责不同：

```text
PostgreSQL
    保存正在运行的 CilExec 实例状态

软件包 .db
    携带一个已经发布、不可修改的软件包
```

软件包数据库不是 CilExec 运行实例的子数据库，也不会长期作为可写数据库使用。

它是一个可验证、可复制、可签名、可发布的只读发布物。

---

## 7.2 软件包必须满足的设计原则

### 原则一：不可变

软件包完成发布后，其数据库文件中的任何内容都不得修改。

需要改变代码、资源、依赖、权限声明或入口点时，必须产生新的软件包数据库，并获得新的 `package_hash`。

不得对已发布软件包执行：

```text
UPDATE
INSERT
DELETE
ALTER TABLE
VACUUM 后覆盖原发布物
```

软件包可以被读取、验证和复制，但不能被原地升级。

### 原则二：内容身份优先

软件包的最终身份是：

```text
package_hash
```

而不是：

```text
文件路径
安装位置
数据库行号
namespace/name/version
```

`namespace`、`name` 和 `version` 是供人理解和选择的坐标。

`package_hash` 才是运行时、依赖、进程和安装记录最终引用的身份。

### 原则三：允许同一软件包存在多个版本

下面这些发布物可以同时存在：

```text
std/network 1.0.0 hash-A
std/network 1.1.0 hash-B
std/network 2.0.0 hash-C
```

它们互不覆盖。

同一个 CilExec 实例、同一个用户，甚至同一时间运行的不同进程，都可以分别引用不同版本。

### 原则四：安装结果原子可见

安装一个软件包及其依赖时，其他进程只能看到两种状态：

```text
安装前
或者
完整安装后
```

不能看到：

```text
主包已经存在但依赖尚未存在
文件已经可见但安装绑定尚未建立
新版本已经覆盖旧版本但进程引用尚未更新
```

### 原则五：发布物自包含

软件包数据库必须包含运行该软件包所需要的全部发布信息：

* 软件包元数据；
* FCL 模块；
* 资源；
* 入口点；
* 导出符号；
* 精确依赖；
* 能力申请；
* 完整性信息；
* 发布签名。

构建机器上的目录、绝对路径和临时文件不能成为运行依赖。

### 原则六：代码与运行数据分离

软件包数据库只保存不可变发布内容。

用户配置、缓存、运行记录和包私有数据不写回软件包数据库。

它们属于 CilExec 实例中的可变数据空间。

---

## 7.3 软件包原子性的三个层次

### 构建原子性

构建过程中产生的数据库文件不是有效软件包。

只有完成全部表写入、约束检查、哈希计算和封存后，最终 `.db` 文件才成为有效发布物。

### 导入原子性

软件包数据库进入 PostgreSQL 时，发布记录、依赖、入口点、导出和原始数据库文件必须在同一个事务中建立。

### 安装原子性

用户或环境对一个精确 `package_hash` 的绑定，必须和依赖可用性、权限检查及数据空间建立在同一个事务中完成。

不再为包安装额外设计文件式事务日志、根清单或者中间可见状态。

PostgreSQL 事务就是安装事务。

---

# 8. 软件包 `.db` 的内部结构

## 8.1 数据库文件约束

软件包数据库必须满足：

```text
单文件
只读发布
固定 schema 版本
启用外键约束
不包含 WAL 或 journal 伴随文件
不依赖外部数据库
不允许 ATTACH 其他数据库
不允许触发器执行安装逻辑
不允许自定义扩展或虚拟表
```

软件包加载器必须检查数据库 schema，而不能仅根据扩展名判断它是合法软件包。

加载器还必须执行资源限制检查：

```text
最大数据库文件体积
最大文件数量
单个文件最大体积
全部 package_file 内容总量
最大依赖数量
最大入口点和导出数量
最大字符串长度
```

具体上限由内核配置和软件包格式版本共同决定，验证必须在读取全部内容进入 JVM 之前尽可能提前失败。

数据库中允许存在的表、列、约束和索引由软件包格式版本明确规定。

未知的关键表或关键字段必须导致验证失败，避免旧内核错误解释新格式。

---

## 8.2 `package_metadata`

该表只能存在一行：

```text
schema_version
namespace
name
version
package_hash
minimum_kernel_version
fcl_language_version
description
license
publisher
created_at
sealed
```

其中：

```text
package_hash = 软件包规范化内容的 SHA-256
sealed       = 是否已经完成封存
```

合法发布物必须满足：

```text
sealed = true
```

`created_at` 只用于展示，不参与 `package_hash` 计算。

---

## 8.3 数据库中的文件抽象

软件包数据库中的源码和资源统一抽象为文件，但并不是所有软件包数据都是文件。

以下内容是文件：

```text
FCL 源代码
模板
静态资源
二进制资源
配置默认值
许可证文本
```

以下内容不是文件，而是关系数据：

```text
依赖
入口点
导出
能力申请
签名
版本信息
```

文件由 `package_file` 表表示。

### `package_file`

```text
file_id
path
file_kind
media_type
encoding
content
content_hash
byte_size
executable
```

`file_kind`：

```text
MODULE
RESOURCE
TEMPLATE
DOCUMENT
BINARY
```

规则：

* `path` 是软件包内部的规范化相对路径；
* 路径统一使用 `/`；
* 不允许 `..`；
* 不允许绝对路径；
* 不允许 Windows 盘符；
* 不保存显式目录条目；
* 同一路径只能存在一行；
* `content_hash` 必须等于 `content` 的 SHA-256；
* `byte_size` 必须等于内容字节数；
* 文件内容一旦封存不得修改。

目录由文件路径自然推导，不作为独立软件包对象保存。

---

## 8.4 `package_module`

FCL 模块对 `package_file` 进行语义标注：

```text
module_id
module_name
file_id
language_version
load_order
```

约束：

```text
file_id 必须引用 file_kind = MODULE 的文件
module_name 在软件包内唯一
```

运行时按模块读取 FCL 源代码，而不是把所有 `.fcl` 文件直接拼接成一个没有身份的字符串。

---

## 8.5 `package_dependency`

```text
dependency_id
binding
namespace
name
version
required_hash
optional
scope
```

`required_hash` 是最终依赖身份。

`namespace/name/version` 用于诊断和展示，但加载时必须验证它们与 `required_hash` 指向的软件包一致。

`scope`：

```text
RUNTIME
BUILD
DEVELOPMENT
```

发布后的运行时依赖必须拥有精确 `required_hash`。

不允许在进程启动时临时选择“最新版本”。

---

## 8.6 `package_entrypoint`

```text
entrypoint_id
name
entrypoint_kind
module_id
symbol
```

`entrypoint_kind`：

```text
COMMAND
LIBRARY
SERVICE
PLUGIN
BOOTSTRAP
```

入口点必须引用数据库中真实存在的模块和符号。

---

## 8.7 `package_export`

```text
export_id
export_name
export_kind
module_id
symbol
```

它定义软件包允许其他包或用户代码访问的公开接口。

未列入 `package_export` 的内部函数和模块不能通过包导入机制直接访问。

---

## 8.8 `package_capability`

```text
capability_id
capability_name
resource_pattern
required
reason
```

软件包只能声明它需要什么能力。

声明本身不会授予权限。

安装时由 CilExec 权限系统决定是否允许该安装绑定获得这些能力。

---

## 8.9 `package_signature`

```text
signature_id
key_id
algorithm
signed_package_hash
signature
created_at
```

签名覆盖 `package_hash`，而不是数据库文件路径或安装位置。

首版建议支持：

```text
SHA-256
Ed25519
```

签名不参与 `package_hash` 计算，否则给同一内容增加签名会改变被签名对象本身。

---

## 8.10 软件包数据库中明确不保存的内容

```text
安装状态
用户绑定
进程引用
用户私有数据
缓存
运行日志
宿主路径
数据库连接信息
安装事务状态
```

这些内容属于运行实例，不属于发布物。

---

# 9. 哈希、版本和多版本规则

## 9.1 `package_hash` 是唯一持久身份

`package_hash` 根据数据库中的规范化逻辑内容计算，至少覆盖：

```text
package_metadata 中参与身份的字段
按 path 排序后的 package_file 及其 content_hash
package_module
按 binding 排序后的精确依赖
entrypoint
export
capability
```

不参与计算：

```text
created_at
签名行
SQLite 页号
空闲页
数据库内部索引布局
宿主文件名
```

因此，同样的逻辑软件包应得到相同 `package_hash`，即使数据库文件的物理页面布局不同。

---

## 9.2 版本不是身份替代品

版本号是人类可读的发布标签。

数据库必须允许：

```text
同一 namespace/name
存在多个 version
每个 version 对应一个或多个 package_hash 记录
```

最终选择结果必须是精确哈希。

只按版本查询时：

* 若只匹配一个哈希，可以返回该发布；
* 若匹配多个不同哈希，必须报告歧义；
* 不得静默选择最后导入、最新时间或任意一项。

正式仓库可以进一步规定：

> 同一发布者已经发布的 `namespace/name/version` 不允许被另一份不同内容覆盖。

但底层存储仍以哈希为身份，不依赖这条仓库策略维持正确性。

---

## 9.3 升级不是修改旧包

升级行为是：

```text
旧安装绑定 -> hash-A

原子切换为

新安装绑定 -> hash-B
```

`hash-A` 对应的软件包不会被修改。

仍在运行并引用 `hash-A` 的进程可以继续使用旧版本。

新进程或重新解析后的环境可以使用 `hash-B`。

---

# 10. 软件包在 PostgreSQL 中的表示

## 10.1 设计原则

PostgreSQL 不把软件包拆成一套模拟旧文件系统的对象目录。

它只保存运行实例真正需要管理的关系：

```text
发布物
发布元数据
依赖关系
安装绑定
进程绑定
包数据空间
```

PostgreSQL 的事务、索引、唯一约束和外键直接承担一致性、查找和引用完整性。

包管理层不再额外维护一套平行的持久化协议。

---

## 10.2 `package.release`

一行代表一个不可变发布物：

```text
package_hash
namespace
name
version
schema_version
minimum_kernel_version
fcl_language_version
database_bytes
database_byte_size
database_file_hash
publisher_id
signature_status
imported_at
revoked_at
```

主键：

```text
package_hash
```

`database_bytes` 保存完整的 `.db` 发布文件，使 CilExec 能够重新导出同一个软件包数据库。

`database_file_hash` 只验证传输和存储字节是否损坏。

它不是软件包语义身份。

核心索引：

```text
(namespace, name, version)
(namespace, name)
```

这些索引用于查询，不承担最终身份。

---

## 10.3 PostgreSQL 中的发布索引表

为了避免每次解析依赖都重新打开软件包数据库，导入时只抽取运行时需要频繁查询的关系：

```text
package.release_dependency
package.release_entrypoint
package.release_export
package.release_capability
```

它们全部以 `package_hash` 为外键。

软件包文件内容仍以原始数据库文件为权威，不要求在 PostgreSQL 中复制第二份完整内容。

Java 可以按 `package_hash` 建立可丢弃的只读本地缓存，以便通过 SQLite 读取 `package_file` 和 `package_module`。

缓存不是持久真相，删除后可以从 `database_bytes` 重新生成。

---

## 10.4 `package.installation`

安装不是复制软件包，而是建立一个可见绑定：

```text
installation_id
owner_id
environment_id
binding
package_hash
data_scope_id
installed_at
installed_by
```

关键约束：

```text
UNIQUE(owner_id, environment_id, binding)
```

同一用户可以通过不同 binding 同时安装不同版本：

```text
network_v1 -> hash-A
network_v2 -> hash-B
```

同一个哈希可以被多个用户和环境共享，不需要复制发布数据库。

`package.installation` 行完成提交，就是安装对该用户或环境的可见性边界。

不需要额外的“根清单”。

---

## 10.5 `process.package_binding`

```text
process_uid
binding
package_hash
installation_id
resolved_at
```

进程第一次解析包时，将最终选择写成精确哈希。

之后：

```text
用户升级安装绑定
不会偷偷改变已运行进程的包版本
```

恢复进程时，直接根据 `package_hash` 重新加载同一个发布物。

这保证了进程恢复的确定性。

---

## 10.6 `package.data_scope`

软件包数据库不可写，因此可变数据必须单独保存：

```text
data_scope_id
owner_id
environment_id
binding
created_at
```

包私有数据可以进入 VFS 或专用关系表，但必须通过 `data_scope_id` 隔离。

规则：

* 升级同一 binding 时默认保留原 data scope；
* 以新 binding 并行安装时创建独立 data scope；
* 卸载是否删除数据由用户明确决定；
* 不允许把运行数据写回发布数据库。

---

## 10.7 发布物保留与清理

发布物是否仍被使用，可以通过数据库外键直接判断：

```text
package.installation -> package.release
process.package_binding -> package.release
```

删除发布物时使用 `ON DELETE RESTRICT`。

只有不存在安装绑定和进程绑定的发布物才允许清理。

首版只需要一个普通的“删除无引用发布物”维护操作。

长期保留某个发布物时，可以建立普通的保留策略表，但它不是包身份模型的核心组成部分。

---

# 11. 软件包构建、导入和安装流程

## 11.1 构建与封存

```text
1. 读取包项目输入
2. 验证元数据、模块、入口点和导出
3. 解析并固定所有运行时依赖 hash
4. 创建临时软件包数据库
5. 在一个数据库事务中写入全部表
6. 计算每个 package_file.content_hash
7. 根据规范化逻辑内容计算 package_hash
8. 写入 package_metadata.package_hash
9. 写入签名
10. 设置 sealed = true
11. 提交并关闭数据库
12. 完成完整性检查
13. 将临时数据库原子发布为最终 .db 文件
```

构建失败时，不得留下一个看似有效的半成品软件包。

---

## 11.2 导入验证

导入 `.db` 前必须验证：

```text
数据库文件可正常打开
数据库 schema 版本受支持
只存在允许的 schema 对象
外键和约束完整
package_metadata 只有一行
sealed = true
所有文件 hash 正确
所有 module 引用有效
所有 entrypoint 和 export 引用有效
所有依赖具有精确 required_hash
重新计算的 package_hash 与声明一致
签名有效或符合当前信任策略
```

导入验证在 PostgreSQL 长事务之外进行。

未通过验证的软件包不能进入 `package.release`。

---

## 11.3 发布物导入事务

验证完成后开启 PostgreSQL 事务：

```text
插入 package.release
插入 dependency 索引
插入 entrypoint 索引
插入 export 索引
插入 capability 索引
记录审计事件
COMMIT
```

如果相同 `package_hash` 已经存在：

* 数据库字节一致时视为幂等成功；
* 数据库字节不同但逻辑 hash 相同时，保留已有发布物并记录传输差异；
* 不得覆盖已有发布行。

---

## 11.4 安装依赖图

安装请求必须先解析出完整的精确哈希集合：

```text
root hash
依赖 hash A
依赖 hash B
依赖的依赖 hash C
```

解析和验证可以在事务外进行。

最终安装事务只做确定操作：

```text
确认全部 release hash 已存在
确认能力申请可以授权
确认 binding 没有并发冲突
创建或复用 data scope
插入或更新 package.installation
记录审计事件
COMMIT
```

整个依赖图在提交前都不可见。

安装事务不暴露任何持久中间状态，也不依赖后续恢复步骤补齐安装。

PostgreSQL 已经提供了原子提交，不需要再在应用层模拟一次安装事务协议。

---

## 11.5 升级

升级同一 binding：

```text
锁定 installation 行
验证新 hash 及全部依赖
检查能力变化
将 package_hash 从旧值更新为新值
保留 data_scope_id
COMMIT
```

旧发布物不会被删除。

已经绑定旧哈希的运行进程不受影响。

---

## 11.6 卸载

卸载：

```text
锁定 installation 行
删除 installation
按用户选择保留或删除 data scope
记录审计事件
COMMIT
```

卸载不删除仍被其他安装或进程引用的发布物。

---

## 11.7 进程导入

进程执行包导入时：

```text
读取当前 installation binding
解析到精确 package_hash
验证依赖图
写入 process.package_binding
加载精确版本模块
推进进程 statement
COMMIT
```

进程之后不再根据“当前安装的最新版本”重新解析。

---

## 11.8 首版不提供安装和卸载生命周期钩子

任意生命周期钩子可能执行网络请求、宿主操作或长时间 FCL 代码，无法与 PostgreSQL 安装事务保持真正原子。

因此首版明确规定：

```text
安装和卸载只改变数据库状态
不执行任意 pre/post hook
```

未来若增加生命周期逻辑，只能采用：

* 纯声明式数据库迁移；或
* 明确属于 effect 系统的提交后任务。

它们不能成为软件包“是否已经安装”的可见性边界。

---

# 12. 软件包文件在运行时的表现

## 12.1 只读文件视图

`package_file` 中的内容可以通过 VFS 暴露为只读视图，例如：

```text
/package/<package_hash>/...
```

该路径只是软件包数据库内容的视图，不是复制出来的可写目录。

用户不能通过 VFS 修改包内源码或资源。

---

## 12.2 安装不是复制

安装一个软件包只建立：

```text
binding -> package_hash
```

不会为每个用户复制一份 `.db`，也不会把所有包文件复制到用户目录。

多个用户、多个进程和多个安装可以共享同一 `package.release`。

---

## 12.3 多版本并存

VFS 视图、进程绑定和安装绑定全部以哈希为最终定位：

```text
/package/hash-A/...
/package/hash-B/...
```

因此同一包的多个版本不会发生路径覆盖。

人类可读的别名只是一层解析结果：

```text
network_v1 -> hash-A
network_v2 -> hash-B
```

---

## 12.4 可丢弃缓存

SQLite JDBC 需要文件形式读取软件包数据库时，可以把 `database_bytes` 写入按哈希命名的本地只读缓存：

```text
cache/packages/<package_hash>.db
```

缓存必须满足：

* 只读；
* 可随时删除；
* 启动时无需恢复；
* 不包含任何唯一状态；
* 每次使用前可验证文件 hash；
* 不能成为安装完成的判断依据。

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

## 16.5 软件包安装或升级

```text
验证完整精确依赖图
确认所有 package_hash 已导入
锁定目标 installation binding
检查能力授权
创建或复用 data scope
插入或更新 package.installation
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
cilexec-package-database
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

## `cilexec-package-database`

包含：

* 软件包 `.db` 构建与封存；
* SQLite 只读访问；
* schema 验证；
* `package_hash` 计算；
* 签名验证；
* 软件包数据库领域模型。

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

## 阶段 7：软件包数据库与安装绑定

完成：

* 软件包 `.db` schema；
* builder 与 seal；
* validator；
* 规范化 `package_hash`；
* signature；
* `package.release`；
* `package.installation`；
* `process.package_binding`；
* 精确哈希依赖；
* 原子安装、升级和卸载；
* VFS 只读文件视图。

退出条件：

```text
一个软件包 .db 可以在空实例中完成验证和导入
同一软件包的多个哈希版本能够并存
安装绑定切换只产生提交前或提交后两种状态
运行进程始终恢复到原先绑定的精确哈希
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
* 同一坐标的多个不同哈希同时导入；
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

* 软件包数据库不是合法 SQLite 文件；
* schema 版本不受支持；
* 存在未允许的触发器、虚拟表或附加数据库依赖；
* `sealed` 未完成；
* 文件内容被篡改；
* `content_hash` 错误；
* `package_hash` 错误；
* 签名错误；
* 重复路径；
* `../` 路径；
* 入口点或导出引用不存在；
* 依赖哈希与坐标不匹配；
* 循环依赖；
* 不兼容内核版本；
* 未授权 capability；
* 相同逻辑内容重复构建得到相同 `package_hash`；
* 同一包多个版本和哈希可以并存；
* 升级不改变运行进程的旧哈希绑定；
* 安装事务失败后不存在任何部分安装状态。

---

# 25. 首版明确不做

以下内容全部推迟：

* PostgreSQL 多节点高可用；
* CilExec 多内核实例同时控制一个实例；
* 每个普通用户一个 PostgreSQL 登录账号；
* 全表 Row-Level Security；
* 跨 PostgreSQL cluster 的 CilExec 实例；
* 分布式事务；
* 自动把正在运行的旧进程切换到新软件包哈希；
* 自动兼容旧 `.proc`；
* PostgreSQL Large Object；
* 把所有日志永久保存；
* 通用 ORM；
* 数据库触发器实现完整业务逻辑；
* 在数据库内编写 FCL 解释器；
* 让软件包直接执行 PostgreSQL SQL；
* 任意安装或卸载生命周期钩子；
* 仅凭版本号进行有歧义的包解析。

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
不得修改已经 sealed 的软件包数据库
不得把用户运行数据写回软件包数据库
不得仅凭 namespace/name/version 替代 package_hash
不得在有多个哈希候选时静默选择任意版本
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
所有软件包发布、安装绑定和进程包绑定来自 PostgreSQL
所有外部副作用经过 effect journal
所有状态改变拥有明确事务边界
JVM 被强制终止后可以恢复
不存在 .proc 运行文件
不存在文件锁作为核心并发机制
不存在双重真相来源
软件包可以构建为单个不可变 .db 数据库文件
软件包 .db 可以被验证、签名、导入和安装
同一软件包的多个版本和哈希可以同时存在
安装、升级和卸载只具有提交前与提交后两种可见状态
进程使用精确 package_hash 恢复
相同逻辑软件包内容得到相同 package_hash
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
├── 软件包发布与安装记录
├── 进程
├── 调度
├── IPC
├── 外部副作用
├── 终端
└── 审计

package.db
└── 单文件不可变软件包数据库
    ├── package_metadata
    ├── package_file
    ├── package_module
    ├── package_dependency
    ├── package_entrypoint
    ├── package_export
    ├── package_capability
    └── package_signature
```

最终原则：

> PostgreSQL 保存实例状态，Java 推动实例运行，不可变的 SQLite `.db` 负责携带软件包发布物。