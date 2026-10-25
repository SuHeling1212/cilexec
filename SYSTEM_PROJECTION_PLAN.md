# CilExec SystemProjection 安全边界（0.0.5）

状态：当前实现；不改变 FCL 格式、版本号或数据库结构。

## 已核对的旧问题

此前 `/.cilexec/system/db/<schema>/<table>/<key>.json` 对 67 张表默认返回完整
`to_jsonb` 行，并允许通用插入、更新和删除。数据库约束无法覆盖进程续体、调度、
IPC、外部效果、VFS、权限与审计等跨表不变量。密码校验值、对象内容和终端输入
亦可被管理员经通用文件接口读出。真实 VFS `/.cilexec` 节点可能被合成树遮蔽。

## 当前策略

`/.cilexec/system/db` 仍可列出 schema 和表；`_schema.json` 提供列、主键、
生成/identity 列，以及 `readable`、`creatable`、`writable`、`updatableColumns`、
`deletable`。行读取采用显式白名单：
`readableColumns` 是每表固定列白名单；未来新增的列默认不会自动出现在行 JSON 中。

| schema | 可读数据表 |
| --- | --- |
| meta | `table_security_classification`, `security_definer_public_allowlist` |
| auth | `user_account`, `group_account`, `group_member`, `capability`, `user_capability`, `group_capability` |
| vfs | `node`（仅 ID、所有者、父节点、名称、类型及时间；不含对象哈希或字节） |
| audit | `retention_policy` |

其余当前表和新表默认不允许通过投影读取数据行，包括 `auth.user_credential`、
`auth.login_throttle`、`auth.environment_variable`、`auth.shared_environment_variable`、
`object_store.*`、`program.*`、`process.*`、`scheduler.*`、`ipc.*`、`effect.*`、
`terminal.*`、`audit.event`、包私有数据及 VFS 文件修订。敏感数据请用有权限和
语义约束的类型化 API。白名单表目前不存在需脱敏的值列；今后新增列/表必须重审策略。

投影创建和删除在所有表均禁止。唯一允许的通用更新如下：

| 表 | 可更新列 | 依据 |
| --- | --- | --- |
| `auth.group_account` | `group_name` | 仅展示名称；组 ID、所有者、状态和成员关系不变 |
| `auth.capability` | `description` | 仅说明文本；能力 ID、键和系统标记不变 |

其余列只读。路径中必须给完整主键；JSON 中主键只能与路径一致，不能改值；
未知字段、生成列、identity 列和只读列一律拒绝；仅给主键而不改值也拒绝。
重复 JSON 字段也拒绝，不采用最后一个同名字段覆盖前值的语义。
真正的类型、唯一性、长度与数据库约束继续由 PostgreSQL 执行，但这不是
通用写入的授权依据。行不存在时不执行插入。

## 鉴权、审计和事务

每次操作先检查 `SYSTEM_ADMIN` 和真实 VFS 冲突。JDBC 仓储在切换到 Runtime
角色前，再通过数据库中的管理员校验函数核对当前角色、用户 claim 和 actor ID。
成功更新通过现有 `audit.event` 记录 `projection.update`、执行者、表、主键和
时间；`details_json` 为空，不包含行值。审计与更新共用数据库事务。审计失败
时回滚事务，即使 FCL 捕获异常也不能提交该更新。当前没有允许的投影创建或
删除，因此不存在相应成功事件。拒绝操作和事务失败不声称已持久审计。
投影 SQL 失败仅向调用者保留错误类别及 SQLSTATE，不附带可能包含行值的
JDBC 异常详情。
审计表本身不可经投影修改、删除或读取；其正规清理由 `audit.purge` 控制。

## 路径冲突

`/.cilexec` 是保留根。创建文件、目录、链接或改名进入该根会被拒绝。
若管理员 VFS 根下已有真实 `.cilexec` 节点，则投影访问及终端目录导航
抛出 `ProjectionConflict`，不会静默优先选择合成树。普通用户仍收到
`PathNotFound`，不通过冲突错误探测该树。

## 验证与限制

单元测试覆盖权限白名单、非管理员隐藏、冲突和改名拦截。临时真实 PostgreSQL
集成测试覆盖角色切换、RLS、受保护读取、非法字段、主键、审计、约束失败回滚
与并发独立行更新。该集成夹具只创建所需的测试表与角色，不代表对完整 67 表
实际实例逐表验证。保留的能力是浏览结构和白名单元数据；需要变更其他资源时，
必须使用领域服务或类型化资源 API。旧 FCL 程序若依赖原有通用写入会被拒绝，
这是安全收紧，未改变 0.0.5 的序列化格式。
