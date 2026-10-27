# CilExec 0.0.5 管理员数据库投影

未发布的 0.0.5 版本允许 `SYSTEM_ADMIN` 账号在运行中通过
`/.cilexec/system/db/<schema>/<table>/<key>.json` 读取、创建、修改及删除记录。
`local` 拥有 `SYSTEM_ADMIN`，因此也能使用此入口。普通用户看不到投影。

## 操作契约

- 可访问 `meta`、`object_store`、`package`、`auth`、`vfs`、`program`、
  `process`、`scheduler`、`ipc`、`effect`、`terminal` 和 `audit` 中有主键的普通表。
  新增到这些 schema 的普通表自动进入投影。
- 行文件名是完整主键 JSON 的无 padding base64url 编码；`_schema.json`
  显示实际列、主键及生成列。列表每页最多 200 行。
- 读取返回全部列，包括凭据校验值、对象内容、续体、终端输入与审计记录。
  文件接口不脱敏；管理员须自行控制输出去向。
- `file.create(path, json)` 插入新行；对已有行调用 `write(json)` 修改非主键列；
  `remove()` 删除行。路径主键必须和 JSON 中的主键一致。未知字段、重复字段、
  生成列和 identity 列拒绝写入。数据库类型、外键、唯一约束和 RLS 仍然生效。
- 成功的创建、修改和删除写入现有 `audit.event`，记录执行者、操作、表、主键和时间，
  不记录行值。审计失败会回滚对应修改。管理员也能经投影修改审计历史，
  因此审计不能作为抵抗管理员篡改的证据。
- 普通操作仍通过原有领域 API。直接改运行中的进程、续体、队列、IPC、
  VFS 或认证记录可能使后续调度、恢复或登录失败；该风险由有权管理员承担。
- `/.cilexec` 保持保留路径；若真实 VFS 节点占用此路径，投影访问明确报
  `ProjectionConflict`。

## 数据库与版本

产品、FCLB/continuation 和 Flyway schema 版本均保持 **0.0.5 / V005 / 格式 5**。
未发布的 V005 内部补丁扩展私有 Runtime 角色的表权限、管理员不可变表写入边界，
并增加 `program.function_cache`。本地 JAR 启动时对现有 V005 数据应用该补丁；
外部 PostgreSQL 应先运行一次 `migrate` 或启用 `database.migrate-on-start`。
V001–V005 的已登记迁移文件不修改。V004 及更早格式仍按原格式栅栏拒绝。
