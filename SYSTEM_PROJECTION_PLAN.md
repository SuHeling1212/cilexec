# CilExec 系统资源投影与挂载计划 3.0（0.0.5 / V005，管理员写通已实现）

状态：**第一版已实现并通过真实实例冒烟测试。**
版本：**保持 0.0.5；不新增 Flyway V006。**
范围：**仅 local/系统管理员**可通过文件读写整个数据库的 67 张表。
普通用户的用户树（自己的进程/程序/包等）尚未实现，访问保留前缀一律不可见。

> 本文件替代 2.0 设计稿。2.0 中的 V006、SECURITY DEFINER 读函数、只读限制、
> `/local/.cilexec` 路径均已作废；旧内容保留在 Git 历史。

---

## 1. 当前已实现

管理员可以在 FCL 中使用普通文件 API 直接浏览、创建、修改、删除数据库行：

```fcl
# 浏览 schemas / tables
file.open("/.cilexec/system/db").list()
file.open("/.cilexec/system/db/auth").list()
file.open("/.cilexec/system/db/auth/shared_environment_variable").list()

# 读取 schema 与单行
file.open("/.cilexec/system/db/auth/shared_environment_variable/_schema.json").read()
file.open("/.cilexec/system/db/auth/shared_environment_variable/<key>.json").read()

# 新建/覆盖一行（JSON object）
file.create("/.cilexec/system/db/auth/shared_environment_variable/<key>.json",
            "{\"variable_name\":\"PROJ_TEST\",\"variable_value\":\"one\"}")

# 修改已有行（只需提供要改的列；主键会从路径补回）
file.open("/.cilexec/system/db/auth/shared_environment_variable/<key>.json")
    .write("{\"variable_value\":\"two\"}")

# 删除一行
file.open("/.../<key>.json").remove()
```

`<key>.json` 的文件名是主键 JSON 的 base64url（无 padding）。例如：

```text
{"variable_name":"PROJ_TEST"}
-> eyJ2YXJpYWJsZV9uYW1lIjoiUFJPSl9URVNUIn0.json
```

- 复合主键同样支持，键 JSON 必须包含全部主键列。
- 行文件内容是与 `SELECT to_jsonb(t)` 等价的 JSON object。
- 修改时不允许通过文件改主键；需要改主键请删除旧行后新建。
- 生成列只读；identity 列可以显式写入（使用 `OVERRIDING SYSTEM VALUE`）。
- 所有类型转换、约束、触发器、外键、RLS/授权最终都由 PostgreSQL 执行。

## 2. 路径与分页

```text
/.cilexec/system/db                         所有 schema
/.cilexec/system/db/<schema>                该 schema 的所有表
/.cilexec/system/db/<schema>/<table>        表目录
    _schema.json                            列、主键、generated/identity
    <key>.json                              行（首屏最多 200 行）
    _next.json                              下一页游标（仅满 200 行时出现）
/.cilexec/system/db/<schema>/<table>/page/<cursor>
                                            后续页目录
    <key>.json
    _next.json
```

管理员登录后，终端里也能直接导航：`:ls` 根部显示 `.cilexec/`， `:ls .cilexec` 显示
`system/`，`:ls .cilexec/system` 显示 `db/`，`:cd .cilexec/system/db` 进入投影树。

- 单页硬上限 200 行。
- `_next.json` 内容形如：
  ```json
  {"next":"/.cilexec/system/db/.../page/<cursor>","after":"<cursor>"}
  ```
- 游标是主键 JSON 的 base64url；服务端校验后作为 keyset 查询条件，不拼 SQL 片段。
- 读取游标页时，`_next.json` 会基于当前页继续向后，不会回到第一页。

## 3. 权限与安全边界

- 只有拥有 `SYSTEM_ADMIN` 的账号（当前即 `local`）能解析 `/.cilexec`。
- 非管理员访问 `/.cilexec/system` 返回 `PathNotFound`，不暴露表名/路径存在性。
- `/.cilexec` 是保留前缀：
  - 真实 VFS 写操作不能创建/重命名/链接到该前缀；
  - 命中时返回 `ReadOnlyProjection`。
- 真实 VFS 节点与投影路径冲突时，当前版本**投影优先**；尚未实现冲突拒绝。
  这是下一阶段要补的安全项。
- 实现位于同一 PostgreSQL 事务内：
  1. FCL 层检查 `SYSTEM_ADMIN`；
  2. 仓储层执行 `RESET ROLE` 回到会话 role（`cilexec_runtime`），利用现有运行时 RLS 读取全表；
  3. 执行一条参数化/已验证标识符的 SQL；
  4. 恢复 `SET LOCAL ROLE cilexec_user_<id>`。
- 不新增数据库函数、不新增表、不修改 V001–V005；因此**不需要 V006，保持 0.0.5**。
- 没有 audit 记录、没有列脱敏；管理员可读写包括 `auth.user_credential`、`object_store` 在内的敏感数据。这是本版本的明确风险，后续应增加脱敏/审计。

## 4. 67 张表覆盖

投影根目录通过 `pg_class` 动态列出当前 base table，不硬编码清单；当前 baseline 共 67 张：

| schema | 表数 | 表 |
|---|---:|---|
| meta | 5 | instance, boot, kernel_instance, table_security_classification, security_definer_public_allowlist |
| object_store | 2 | object, chunk_manifest |
| package（全局） | 7 | release_identity, release, release_module, release_export, release_entrypoint, release_dependency, release_capability |
| auth | 11 | user_account, user_capability, environment_variable, shared_environment_variable, shared_environment_policy, group_account, group_member, group_capability, capability, user_credential, login_throttle |
| vfs | 4 | node, file_revision, node_lock, mount |
| program | 3 | program, statement, module_binding |
| process | 10 | process, variable, scope, call_frame, exception_frame, wait_state, event, relationship, package_binding, timer |
| scheduler | 3 | queue, lease, runner |
| ipc | 7 | channel, topic, message, delivery, subscription, swap_pool, swap_value |
| package（账户） | 7 | installation_root, installation_member, managed_node, data_space, data_entry, data_policy, data_quota_override |
| effect | 2 | effect, attempt |
| terminal | 4 | session, input, attachment, command_history |
| audit | 2 | event, retention_policy |

约束：

- 表必须有主键；无主键表无法生成行文件。
- 表目录、`_schema.json`、行文件都由当前 catalog/数据实时生成。
- 行文件名超过 200 字符时报 `UnsupportedKey`（当前主键类型通常不会触发）。
- 大字段（bytea、jsonb、长文本）会按 PostgreSQL `to_jsonb` 原样返回；管理员自行承担读取/写入风险。

## 5. 实现文件

```text
src/main/java/com/follarce/domain/port/AdminProjectionRepository.java
src/main/java/com/follarce/persistence/postgres/repository/JdbcAdminProjectionRepository.java
src/main/java/com/follarce/application/SystemProjection.java
src/main/java/com/follarce/application/FclFileRuntimeFunctions.java
src/main/java/com/follarce/persistence/postgres/transaction/JdbcTransactionContext.java
src/main/java/com/follarce/domain/port/TransactionContext.java
```

关键行为：

- `file.open` / `Path.exists` / `file.exists` 先识别投影路径，再走真实 VFS。
- `File.read/readChunk/size/metadata/write/remove` 和 `Directory.list/metadata`
  对 `ProjectionFile`/`ProjectionDirectory` handle 分发到 `SystemProjection`。
- 写路径在真实 VFS capability 检查之前拦截，避免创建真实节点。
- 合成 handle 的 id 是路径；每次方法调用重新鉴权并重新解析表/行。
- 不改变 FCL program/continuation 格式。

## 6. 已验证

真实实例冒烟（新实例，local 管理员）：

```text
ROOT_LIST = 12 schemas
SCHEMA_FILE = auth/shared_environment_variable 的列与主键
file.create(...)  -> 插入 PROJ_TEST / one
File.read         -> 读出 PROJ_TEST / one
File.write        -> 更新为 two
File.read         -> 读出 PROJ_TEST / two
File.remove       -> true
```

结果：`PROJECTION_SMOKE_PASS`。

全量 `mvn test` 在新增端口契约与不可见性测试后通过（见最终交付说明）。

## 7. 当前限制与下一阶段

1. **普通用户树未实现**：普通用户看不到 `/.cilexec`；用户只能改自己资源的文件视图尚未实现。
2. **无脱敏/审计**：管理员可读取密码材料、对象字节、continuation、终端输入等。
3. **直接写数据库绕过领域服务**：修改 `process`/`continuation`、`vfs.node`、`ipc` 等行
   可能破坏运行时不变量。管理员应优先使用类型化资源函数；文件写只作为兜底。
4. **无冲突检测**：已有真实 `/.cilexec` 节点时投影优先，未 fail-closed。
5. **无 DDL**：只能读写 67 张表的数据，不能创建/删除表。
6. **分页**：首版 200 行/页，keyset；大表遍历需按 `_next.json`。
7. **平台/版本**：保持 0.0.5；以后若增加数据库函数，按规则使用 V006 并发布 0.0.6。

## 8. 硬件抽象

本方案不提供硬件抽象层。当前仓库仍然没有 GPIO/串口/传感器/device API；
`hardware` 只是 effect journal 文档中的分类，没有实现。硬件访问应另立
effect-handler / extension 计划，不要把设备字节塞进本投影。
