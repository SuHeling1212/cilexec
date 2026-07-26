# 命令行扩展 + 管理员全局管理 实施计划

状态：管理员 VFS 与 FCL 系统 API 已实现；交互式 Shell 扩展仍为后续项
日期：2026-07-26

> 2026-07-26 实现说明：`SYSTEM_ADMIN`、`AdminVfsService`、跨用户审计、VFS 列表/写入/
> 创建/改名/受约束删除，以及 FCL 的管理员文件函数已经完成。普通 LOGIN 角色继续受强制
> RLS 约束。本文后续未勾选的 Shell/进程管理内容仍是计划，不应再把“管理员穿透缺失”视为
> 当前状态。FCL 命名空间和安全限制以根目录 `README.md` 为准。

---

## 0. 目标

1. 启动交互式命令行终端（`cilexec>` REPL）
2. 在终端中可以创建脚本、管理进程、操作 VFS 文件
3. 管理员可以穿透隔离，查看/管理所有用户的进程和 VFS

---

## 1. 当前状态

### 1.1 已有能力

| 组件 | 文件 | 状态 |
|---|---|---|
| `TerminalConsole`（REPL） | `terminal/TerminalConsole.java` | 已编写，未启动 |
| `ShellCommand`（命令枚举） | `terminal/ShellCommand.java` | 12 种命令 |
| `ShellCommandParser`（解析器） | `terminal/ShellCommandParser.java` | 已编写 |
| `TerminalControl`（执行接口） | `terminal/TerminalControl.java` | 已定义，无实现 |
| `VfsService`（文件 CRUD） | `vfs/VfsService.java` | 已编写 |
| `ProgramService`（编译 FCL） | `application/ProgramService.java` | 已编写 |
| `ProcessService`（进程生命周期） | `application/ProcessService.java` | 已编写 |
| `AuthService`（用户创建） | `auth/AuthService.java` | 已编写 |
| `TerminalService`（终端控制） | `terminal/TerminalService.java` | 已编写 |

### 1.2 缺失能力

| 缺失项 | 说明 |
|---|---|
| `TerminalControl` 实现 | 没有任何类实现该接口 |
| 终端启动入口 | `RuntimeBootstrap` 未启动 `TerminalConsole` |
| VFS 列出子节点 | `VfsRepository` 只有 `findChild(name)`，没有 `findChildren()` |
| VFS 删除节点 | `VfsService` / `VfsRepository` 都没有删除方法 |
| VFS 路径解析 | 只有 `findChild(parentId, name)`，不支持 `"/a/b/c"` 字符串路径 |
| 用户列表 | `AuthRepository` 没有 `listAll()` |
| 跨进程列表 | `ProcessRepository` 没有 `findAll()` |
| 管理员穿透 | 无任何 admin 能力 |

---

## 2. 设计决策

### 2.1 VFS 不做全局树

保持每个用户拥有独立的根 `/`。管理员通过运行时角色穿透 RLS 来查看所有用户的 VFS，而不是重新设计 VFS 为全局树。

**原因：**
- 改动量小，不影响现有 schema
- 类比 Docker namespace —— 每个容器有自己的 `/`，host（管理员）可见全部
- RLS 隔离默认安全

### 2.2 管理员穿透机制

不修改 RLS 策略。管理员操作**不通过 `SET LOCAL ROLE`**，直接以 `cilexec_runtime` 数据库角色执行事务。

`cilexec_runtime` 在 RLS 策略中拥有 `USING (true)` 的完全通透权限，天然可以查看所有用户的 VFS 和进程。

**两条执行路径：**

```
普通用户操作：
  JdbcTransactionExecutor.inUserTransaction(userId, ...)
    → SET LOCAL ROLE cilexec_user_xxx
    → RLS: owner_id = auth.current_cilexec_user_id()  → 只能看自己的

管理员操作：
  JdbcTransactionExecutor.inTransaction(...)          // 已有方法，不走 SET ROLE
    → 保持 cilexec_runtime 角色
    → RLS: TO cilexec_runtime USING (true)            → 看全部
```

### 2.3 新增 system_admin capability

```sql
INSERT INTO auth.capability (capability_id, capability_key, description, system_capability)
VALUES ('00000000-0000-4000-8000-00000000000c', 'system_admin',
        'Manage all users, processes, VFS, and system resources', true)
```

管理员操作前检查此 capability：
```java
Authorization.requireAdmin(transaction, userId);
```

---

## 3. 实施步骤

### Step 1：扩展 VfsRepository（数据层）

**文件：`domain/port/VfsRepository.java`**

```java
// 新增方法
List<VfsNode> findChildren(UUID nodeId);             // 列出子节点
Optional<VfsNode> findByPath(UUID ownerId, String path); // 路径 → 节点
void deleteNode(UUID nodeId);                        // 删除节点（CASCADE 自动级联）
List<VfsNode> findAllNodes(UUID ownerId);            // 列出某用户全部节点
```

**文件：`persistence/postgres/repository/JdbcVfsRepository.java`**

实现上述方法：
- `findChildren`: `SELECT * FROM vfs.node WHERE parent_node_id = ? ORDER BY node_name`
- `findByPath`: 逐级 `findChild(ownerId, parentId, component)` 循环
- `deleteNode`: `DELETE FROM vfs.node WHERE node_id = ?`
- `findAllNodes`: `SELECT * FROM vfs.node WHERE owner_id = ?`

**补充辅助方法 `findByPath`：**

路径格式 `"/home/scripts.fcl"`：
```
parts = ["home", "scripts.fcl"]
current = root(ownerId)
for each part:
    current = findChild(ownerId, current.nodeId, part)
return current
```

---

### Step 2：扩展 ProcessRepository（数据层）

**文件：`domain/port/ProcessRepository.java`**

```java
List<CilProcess> findAll();           // 所有进程
List<CilProcess> findByOwner(UUID ownerId);  // 某用户的进程
```

**文件：`persistence/postgres/repository/JdbcProcessRepository.java`**

实现：
- `findAll`: `SELECT * FROM process.process ORDER BY created_at`
- `findByOwner`: `SELECT * FROM process.process WHERE owner_id = ? ORDER BY created_at`

---

### Step 3：扩展 AuthRepository（数据层）

**文件：`domain/port/AuthRepository.java`**

```java
List<UserAccount> listUsers();  // 所有用户
```

**文件：`persistence/postgres/repository/JdbcAuthRepository.java`**

```java
"SELECT * FROM auth.user_account ORDER BY created_at"
```

---

### Step 4：扩展 VfsService（业务层）

**文件：`vfs/VfsService.java`**

新增方法：
```java
// 普通用户方法
List<VfsNode> listDirectory(UUID ownerId, UUID nodeId);
VfsNode resolvePath(UUID ownerId, String path);
StoredObject readFileByPath(UUID ownerId, String path);
VfsNode createFileText(UUID ownerId, UUID parentId, String name, String content);
void deleteNode(UUID ownerId, UUID nodeId);

// 管理员方法（不走 SET LOCAL ROLE）
List<VfsNode> adminListDirectory(UUID adminUserId, UUID targetOwnerId, UUID parentNodeId);
StoredObject adminReadFile(UUID adminUserId, UUID targetOwnerId, String path);
void adminDeleteNode(UUID adminUserId, UUID targetOwnerId, UUID nodeId);
List<VfsNode> adminListAllNodes(UUID adminUserId);
```

---

### Step 5：扩展 ProcessService（业务层）

**文件：`application/ProcessService.java`**

```java
// 管理员方法
List<CilProcess> adminListAll(UUID adminUserId);
CilProcess adminTerminate(UUID adminUserId, long pid);
Optional<CilProcess> adminInspect(UUID adminUserId, long pid, Continuation continuation);
```

---

### Step 6：扩展 AuthService（业务层）

**文件：`auth/AuthService.java`**

```java
List<UserAccount> adminListUsers(UUID adminUserId);
```

---

### Step 7：扩展 Authorization

**文件：`auth/Authorization.java`**

```java
// 管理员验证
public static void requireAdmin(TransactionContext tx, UUID userId) {
    if (!tx.auth().capabilities(userId).contains(Capability.SYSTEM_ADMIN)) {
        throw new SecurityException("Requires system_admin capability");
    }
}
```

---

### Step 8：扩展 ShellCommand（命令枚举）

**文件：`terminal/ShellCommand.java`**

```java
// VFS
record Ls(List<String> components) {}           // ls [/path]
record Mkdir(List<String> components) {}        // mkdir /path
record Cat(List<String> components) {}          // cat /path
record Write(List<String> components, String content) {}  // write /path "content"
record Rm(List<String> components) {}           // rm /path
record Tree(List<String> components) {}         // tree [path] 递归显示

// 用户
record CreateUser(String username) {}
record ListUsers() {}
record Whoami() {}

// 管理员
record AdminPs() {}
record AdminKill(long pid) {}
record AdminLs(String targetUser, List<String> components) {}
record AdminCat(String targetUser, List<String> components) {}
record AdminRm(String targetUser, List<String> components) {}
record AdminTree(String targetUser, List<String> components) {}

// 扩展已有 Run
// Run 保持不变但需要完整实现
```

---

### Step 9：扩展 ShellCommandParser（解析器）

**文件：`terminal/ShellCommandParser.java`**

```java
case "ls"         -> ls(words)
case "tree"       -> tree(words)
case "mkdir"      -> mkdir(words)
case "cat"        -> cat(words)
case "write"      -> writeCmd(words)
case "rm"         -> rm(words)
case "create-user" -> createUser(words)
case "list-users"  -> exact(words, 1, new ShellCommand.ListUsers())
case "whoami"      -> exact(words, 1, new ShellCommand.Whoami())
case "admin-ps"    -> exact(words, 1, new ShellCommand.AdminPs())
case "admin-kill"  -> exact(words, 2, new ShellCommand.AdminKill(pid(words.get(1))))
case "admin-ls"    -> adminLs(words)
case "admin-cat"   -> adminCat(words)
case "admin-rm"    -> adminRm(words)
case "admin-tree"  -> adminTree(words)
```

路径解析辅助方法：
```java
// "/home/scripts/test.fcl" → ["home", "scripts", "test.fcl"]
static List<String> resolvePath(String raw) {
    if (!raw.startsWith("/")) throw ...
    String[] parts = raw.substring(1).split("/");
    return List.of(parts);
}
```

---

### Step 10：实现 CilExecShell（TerminalControl 实现）

**新建文件：`terminal/CilExecShell.java`**

```java
public final class CilExecShell implements TerminalControl {
    private final JdbcTransactionExecutor adminTx;   // 用于管理员操作
    private final UserTransactionExecutor userTx;    // 用于普通用户操作
    private final VfsService vfsService;
    private final ProgramService programService;
    private final ProcessService processService;
    private final AuthService authService;
    private final TerminalService terminalService;
    
    // 当前 session 状态
    private volatile UUID currentUserId;
    private volatile UUID sessionId;

    @Override
    public String execute(ShellCommand command) {
        return switch (command) {
            case ShellCommand.Help()        -> helpText();
            case ShellCommand.Exit()        -> exit();
            case ShellCommand.Processes()   -> ps();
            case ShellCommand.Run run       -> runProgram(run);
            case ShellCommand.Inspect i     -> inspect(i);
            case ShellCommand.Pause p       -> pause(p);
            case ShellCommand.Resume r      -> resume(r);
            case ShellCommand.Kill k        -> kill(k);
            case ShellCommand.Ls ls         -> ls(ls);
            case ShellCommand.Mkdir m       -> mkdir(m);
            case ShellCommand.Cat cat       -> cat(cat);
            case ShellCommand.Write w       -> writeVfs(w);
            case ShellCommand.Rm rm         -> rm(rm);
            case ShellCommand.ListUsers()   -> listUsers();
            case ShellCommand.Whoami()      -> whoami();
            case ShellCommand.AdminPs()     -> adminPs();
            case ShellCommand.AdminKill k   -> adminKill(k);
            case ShellCommand.AdminLs ls    -> adminLs(ls);
            case ShellCommand.AdminCat cat  -> adminCat(cat);
            case ShellCommand.Shutdown()    -> shutdown();
            default -> "Unknown command";
        };
    }
}
```

---

### Step 11：修改 RuntimeBootstrap 接入终端

**文件：`app/RuntimeBootstrap.java`**

在 `start()` 顺序中，在 `markReady()` 之后加入终端启动：

```java
// 在 ProductionHooks 中加入
private volatile Thread terminalThread;

public void startTerminal() {
    CilExecShell shell = new CilExecShell(
        runtimeTransactions,  // adminTx
        runtimeTransactions,  // userTx (同一个实现类)
        new VfsService(runtimeTransactions, Clock.systemUTC()),
        new ProgramService(runtimeTransactions),
        new ProcessService(runtimeTransactions),
        new AuthService(runtimeTransactions, Clock.systemUTC()),
        new TerminalService(runtimeTransactions, Clock.systemUTC())
    );
    
    TerminalConsole console = new TerminalConsole(
        new BufferedReader(new InputStreamReader(System.in)),
        new PrintWriter(System.out, true),
        shell
    );
    terminalThread = Thread.ofVirtual().name("terminal").start(console);
}
```

在 `startup` 中调用：
```java
startupStep(hooks::startTerminal);
```

---

## 4. 命令完整列表

### 4.1 通用命令

| 命令 | 格式 | 说明 |
|---|---|---|
| `help` | `help` | 显示帮助 |
| `exit` / `quit` | `exit` | 退出终端 |
| `whoami` | `whoami` | 显示当前登录用户 |
| `shutdown` | `shutdown` | 关闭系统（管理员） |

### 4.2 进程命令

| 命令 | 格式 | 说明 |
|---|---|---|
| `ps` | `ps` | 列出我的进程 |
| `inspect` | `inspect <pid>` | 检查进程状态和变量 |
| `run` | `run /path/to/script.fcl` | 运行 VFS 中的 FCL 脚本 |
| `pause` | `pause <pid>` | 暂停进程 |
| `resume` | `continue <pid>` 或 `resume <pid>` | 恢复进程 |
| `kill` | `kill <pid>` | 终止我的进程 |

### 4.3 VFS 命令

| 命令 | 格式 | 说明 |
|---|---|---|
| `ls` | `ls /path` 或 `ls` | 列出目录内容 |
| `tree` | `tree /path` 或 `tree` | 递归显示目录树 |
| `mkdir` | `mkdir /path` | 创建目录 |
| `cat` | `cat /path` | 读取文件内容 |
| `write` | `write /path "content..."` | 写入文件（覆盖） |
| `rm` | `rm /path` | 删除文件或目录 |

### 4.4 用户管理（管理员）

| 命令 | 格式 | 说明 |
|---|---|---|
| `create-user` | `create-user <username>` | 创建系统用户 |
| `list-users` | `list-users` | 列出所有用户 |

### 4.5 管理员命令

| 命令 | 格式 | 说明 |
|---|---|---|
| `admin-ps` | `admin-ps` | 列出所有用户的全部进程 |
| `admin-kill` | `admin-kill <pid>` | 终止任意进程 |
| `admin-ls` | `admin-ls <user> /path` | 查看其他用户的目录 |
| `admin-cat` | `admin-cat <user> /path` | 读取其他用户的文件 |
| `admin-tree` | `admin-tree <user> /path` | 查看其他用户的目录树 |

---

## 5. FCL 脚本创建流程

```
用户在终端输入：
  cilexec> write /home/hello.fcl "x = 1\ny = x + 2\nprint(y)"

写入流程：
  1. ShellCommandParser 解析 → ShellCommand.Write
  2. CilExecShell.execute() → vfsService.createFileText(ownerId, parentId, "hello.fcl", content)
  3. VfsService → inUserTransaction → VfsRepository → INSERT vfs.node (type=FILE)
  4. 内容计算 hash → INSERT object_store.object

执行流程：
  cilexec> run /home/hello.fcl

  1. ShellCommandParser 解析 → ShellCommand.Run
  2. CilExecShell.execute() →
     a. vfsService.readFileByPath(ownerId, "/home/hello.fcl") → 读取源码
     b. programService.create(ownerId, source) → 编译 + 去重存储
     c. processService.create(ownerId, program, Optional.empty()) → 新建进程
  3. 进程进入 scheduler.queue (READY)
  4. SchedulerService 领取 → ProcessStatementExecutor 逐语句执行
```

---

## 6. 数据库变更

### 6.1 migration V021：新增 system_admin capability

```sql
-- V021__system_admin_capability.sql
SET ROLE cilexec_owner;

INSERT INTO auth.capability (capability_id, capability_key, description, system_capability)
VALUES ('00000000-0000-4000-8000-00000000000c', 'system_admin',
        'Manage all users, processes, VFS nodes, IPC, timers, and effects', true)
ON CONFLICT (capability_key) DO NOTHING;

RESET ROLE;
```

### 6.2 无 schema 变更

不需要修改 VFS 表结构。不需要修改 RLS 策略。不需要修改进程表。

---

## 7. 文件变更清单

| 操作 | 文件 |
|---|---|
| **修改** | `domain/port/VfsRepository.java` |
| **修改** | `domain/port/ProcessRepository.java` |
| **修改** | `domain/port/AuthRepository.java` |
| **修改** | `persistence/postgres/repository/JdbcVfsRepository.java` |
| **修改** | `persistence/postgres/repository/JdbcProcessRepository.java` |
| **修改** | `persistence/postgres/repository/JdbcAuthRepository.java` |
| **修改** | `vfs/VfsService.java` |
| **修改** | `application/ProcessService.java` |
| **修改** | `auth/AuthService.java` |
| **修改** | `auth/Authorization.java` |
| **修改** | `terminal/ShellCommand.java` |
| **修改** | `terminal/ShellCommandParser.java` |
| **修改** | `app/RuntimeBootstrap.java` |
| **新建** | `terminal/CilExecShell.java` |
| **新建** | `src/main/resources/db/migration/V021__system_admin_capability.sql` |

---

## 8. 实施顺序

```
Step 1 (VfsRepo) ─┐
Step 2 (ProcRepo) ─┤ 可并行
Step 3 (AuthRepo) ─┘
        │
Step 4 (VfsService) ─┐
Step 5 (ProcService) ─┤ 可并行
Step 6 (AuthService) ─┤
Step 7 (Authorization)┘
        │
Step 8 (ShellCommand) ─┐ 可并行
Step 9 (Parser)       ─┘
        │
Step 10 (CilExecShell)
        │
Step 11 (Bootstrap)

在任意步骤后运行 mvn test 验证
```

---

## 9. 退出条件

- [ ] `mvn compile` 成功
- [ ] `mvn test` 全部通过
- [ ] 启动 Runtime 后出现 `cilexec>` 提示符
- [ ] `help` 显示完整命令列表
- [ ] `run /path/to/script.fcl` 成功创建进程并执行
- [ ] `ps` 显示进程状态
- [ ] `ls` / `cat` / `write` / `rm` 正常操作 VFS
- [ ] `create-user` 创建用户，`list-users` 列出全部用户
- [ ] `admin-ps` 列出所有进程
- [ ] `admin-ls <user>` 查看其他用户 VFS
- [ ] `admin-kill <pid>` 终止任意进程
