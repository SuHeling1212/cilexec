# CilExec 系统资源投影与挂载计划（未实施）

状态：**方案文档，尚未实现**。本文描述两级虚拟投影树、67 张数据表的完整挂载清单、
分阶段实施步骤、测试与文档清单。所有阶段均遵守五词资源纪律
（remove / clear / purge / uninstall / disable）与"零内存状态"架构。

## 1. 目标

1. **所有权原则**：用户完全管理自己名下的资源；管理员额外管理系统级资源。
2. **两级投影树**：
   - **用户树**：每个用户自己的命名空间内挂载只读投影（自己的进程、程序、定时器、
     会话、包安装、审计事件等），由 RLS 天然限定为本人行。
   - **管理员树** `/local/.cilexec/`：仅管理员可见的系统全景——全部 67 张表的兜底
     浏览器、内核表、全局注册层。普通用户访问该前缀一律表现为路径不存在。
3. **单一事实源不变**：全部投影实时生成，不建新持久化结构（除阶段三的
   SECURITY DEFINER 读函数）；`/local/.cilexec/` 与用户树内一切写操作硬拒绝，
   变更仍走既有资源函数。

## 2. 67 张表完整挂载清单

图例：🔒 = 仅管理员树　👤 = 双挂载：用户树只见自己名下的行，管理员树见全部行

### meta（5）— 全部 🔒
| 表 | 备注 |
| --- | --- |
| meta.instance | 内核身份 |
| meta.boot | 启动史 |
| meta.kernel_instance | Runtime 世代 |
| meta.table_security_classification | 兼作投影白名单 |
| meta.security_definer_public_allowlist | DEFINER 函数清单 |

### object_store（2）— 全部 🔒，字节永不出，只投影 hash/size/media_type
object、chunk_manifest

### package 全局注册层（7）— 全部 🔒（用户侧走 package.info/list 函数）
release_identity、release、release_module、release_export、release_entrypoint、
release_dependency、release_capability

### auth（11）
| 表 | 挂载 | 备注 |
| --- | --- | --- |
| user_account | 👤 | 自己的账号行 / 管理员全部 |
| user_capability | 👤 | 自己的能力授予 |
| environment_variable | 👤 | 自己的环境变量 |
| shared_environment_variable | 👤（只读投影） | 设计即全员共享读；写走函数 |
| shared_environment_policy | 👤（只读投影） | 同上 |
| group_account | 👤 | 组属主见本组全量 |
| group_member | 👤 | 成员见自己的成员关系行 |
| group_capability | 👤 | 组属主见本组授予 |
| capability | 🔒（可选开放全员只读字典） | 全局字典，无敏感列 |
| user_credential | 🔒 + 脱敏 | 口令验证材料永不出 |
| login_throttle | 🔒 | 防爆破计数细节 |

### vfs（4）— 全部 👤
node、file_revision、node_lock、mount

### program（3）— 全部 👤
program、statement、module_binding

### process（10）— 全部 👤
process、variable、scope、call_frame、exception_frame、wait_state、event、
relationship、package_binding、timer

### scheduler（3）
queue 👤、lease 👤、runner 🔒

### ipc（7）— 全部 👤
channel、topic、message、delivery、subscription、swap_pool、swap_value

### package 账户层（7）— 全部 👤
installation_root、installation_member、managed_node、data_space、data_entry、
data_policy、data_quota_override

### effect（2）— 全部 👤
effect、attempt

### terminal（4）— 全部 👤
session、input、attachment、command_history

### audit（2）
event 👤（RLS 已允许 SELECT 自己的事件；删除仅 audit.purge）、retention_policy 🔒

合计：🔒 19 张 + 👤 48 张 = 67 张全覆盖。
实现机制：🔒 走 SECURITY DEFINER 读函数；👤 直接跑在调用者角色下由 RLS 限定。

### 投影路径布局
```
用户树（每个用户自己的 / 下）：
/.cilexec/process/<pid>/status.json
/.cilexec/program/<id>/{meta.json, source.fcl}
/.cilexec/timer/{fired,cancelled}/…
/.cilexec/session/<id>/
/.cilexec/package/<sha256>/install.json
/.cilexec/log/audit?after=<event_id>

管理员树（仅管理员可见）：
/local/.cilexec/db/<schema>/<table>[?<after>=<键>]   ← 67 表兜底浏览器
/local/.cilexec/users/…                              ← auth 账号与能力全景
/local/.cilexec/kernel/…                             ← meta.instance/boot/kernel_instance
```

## 3. 实施步骤

### 阶段一：自控权放宽（零迁移）

| 步骤 | 文件 | 内容 |
| --- | --- | --- |
| 1.1 | src/main/java/com/follarce/application/FclRuntimeFunctions.java:450 | `program.remove` 改为属主或管理员：属主路径直接放行，跨属主保留 requireAdministrator |
| 1.2 | src/main/java/db/migration/V003.java | `program.admin_remove_program_as` 的鉴权改为「调用者是该 program 的 owner_id 或系统管理员」二选一（V003 未发布，随本变更修订） |
| 1.3 | FclProcessRuntimeFunctions.java:188 区域 | `process.removeFinished([pid])`：带 pid 且目标进程属主==调用者 → 无需管理员；无参批量形式保持仅管理员 |
| 1.4 | FclProcessRuntimeFunctions.java（timer 注册处）+ TimerRepository/JdbcTimerRepository | `timer.purge(before[, limit])` 增加可选第三参 owner：普通用户只允许清自己的 FIRED/CANCELLED 行（SQL 加 owner_id=?）；省略 owner 参数保持仅管理员 |
| 1.5 | FclRuntimeFunctions.java registerResourceControl | `terminal.remove`：会话属主或管理员均可删除 CLOSED 会话 |
| 1.6 | PackageCapabilityPolicy.java | 不变——包代码调用这四个函数仍要求 system.admin（包≠属主） |
| 1.7 | docs/fcl-function-reference.md | 四个函数的权限描述更新 |

### 阶段二：投影一期（零迁移）

| 步骤 | 文件 | 内容 |
| --- | --- | --- |
| 2.1 | 新增 application/SystemProjection.java | 前缀分发器 + sealed ProjectionNode{Dir,File}；用户树 `/.cilexec/...` 与管理员树 `/local/.cilexec/...` 两组构造器；可见性判定内置（跨属主先 requireAdministrator，否则 empty=404） |
| 2.2 | domain/port/ProgramRepository.java + JdbcProgramRepository.java | 新增 `findByOwnerWithReferenceCounts(ownerId)`（LEFT JOIN 进程数与被导入数），同时服务投影与未来 program.list |
| 2.3 | application/FclFileRuntimeFunctions.java | file.list / exists / read / readChunk / size / readMetaData 六个读接口先查 SystemProjection，命中即返回合成结果 |
| 2.4 | FclRuntimeFunctions.java | 新增 `requireMutablePath(path)`：前缀命中 `/.cilexec` 或 `/local/.cilexec` 即抛错并指路对应资源函数 |
| 2.5 | 九个变更入口接入守卫 | write、append、createFile、createDir、mkdir(经 createDir)、remove、clear、rename、link、lock |
| 2.6 | 大表分页 | 投影目录列表单页 ≤200 行，`?after=<主键>` keyset 续页 |

### 阶段三：db/ 兜底层（并入 V003 增补）

| 步骤 | 文件 | 内容 |
| --- | --- | --- |
| 3.1 | V003.java | 新增 `meta.admin_read_table_as(schema, table, after_key, limit)`：SECURITY DEFINER；校验管理员；白名单=meta.table_security_classification 全部登记表；脱敏黑名单（user_credential 密钥材料、login_throttle 计数细节）在函数内强制；keyset 分页 LIMIT ≤200；invoker 包装 + allowlist + GRANT |
| 3.2 | JdbcAuditRepository 或新 MetaProjectionReader | Java 端封装调用；db 层每次访问追加一条 audit.event（action=db.read） |
| 3.3 | SystemProjection | 接入 `/local/.cilexec/db/...` 与 kernel/、users/ 视图 |

### 挂起项
- native-image CLI 变体：等 GraalVM 支持 JDK 26（当前 LTS 基于 JDK 25）。
- continuation.json / 包私有数据镜像 / FCLB 字节内容：按需求再议。

## 4. 测试补充清单

| 类别 | 用例 |
| --- | --- |
| 单元（可测部分） | SystemProjection 路径解析与前缀判定；requireMutablePath 九入口覆盖 |
| 外部 IT-权限 | 属主放宽四函数：own 调用成功、cross-user 普通 user 404/拒绝、admin 全通过 |
| 外部 IT-投影 | 用户树只见自己的行；管理员树见全部；removeFinished 后 proc 条目消失；program.remove 后 programs 收缩 |
| 外部 IT-写拒绝 | 对两类树九种变更操作逐一断言报错且原文不变 |
| 外部 IT-db 层 | 白名单外 schema/table 拒绝；脱敏列断言（credential 无密钥材料）；分页正确性；db.read 审计事件存在 |
| 迁移 | V003 修订后在真实 PostgreSQL 上重放 V001→V003 |

## 5. 文档修改清单

| 文档 | 修改 |
| --- | --- |
| docs/fcl-function-reference.md | 新增 "System Paths" 章节（两级树路径表、权限矩阵、只读声明）；四函数权限描述更新 |
| docs/architecture-baseline.md | VFS 章节补"虚拟投影层"小节（单一事实源不变）；表分类说明引用本计划 |
| README.md / README.zh-CN.md | 特性列表提一句管理员全景与用户自投影 |
| AGENTS.md | com.follarce.application 行提及 SystemProjection；audit 行已符合 |
| 本文件 | 每阶段完成后勾选实施状态 |

## 6. 安全红线与不变量

1. 普通用户对 `/local/.cilexec` 前缀的一切请求返回"不存在"（不可探测）。
2. 两棵树整体只读；错误文案指路对应资源函数。
3. 脱敏黑名单在 SQL 层强制，不依赖 Java 层自觉。
4. 分页硬上限 200 行；对象字节内容任何路径不出。
5. db 层读操作必须落审计；curated 读对齐现有 list 类函数（不审计）。
6. 五词纪律与零内存状态架构不受影响；无后台清理任务。
