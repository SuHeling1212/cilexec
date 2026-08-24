# Terminal + Administrator Global Management: Current State and Historical Plan

Status: administrator VFS, cross-user access, FCL system APIs, and the durable interactive
terminal are **implemented**. The earlier planning assumptions are retained below as historical
context, not pending work.

> 2026-07-26 implementation note: `SYSTEM_ADMIN`, `AdminVfsService`, cross-user audit,
> VFS list/write/create/rename/constrained-delete, and the administrator FCL file
> functions are complete. Ordinary `NOLOGIN`, `NOINHERIT` tenant roles remain under forced RLS. This document
> reflects the shipped design rather than the earlier planning sketch: the interactive
> terminal is delivered by the TCP `TerminalServer` plus `DatabaseTerminalControl`,
> process and package management are FCL functions rather than shell subcommands, and
> administrator VFS traversal is routed through `/Users/<username>/...` paths. FCL
> namespaces and security limits follow the repository root `README.md`.

---

## 0. Scope

1. Provide a durable interactive terminal (`cilexec>` REPL) that executes FCL with every namespace available.
2. Allow scripts, process control, and VFS file operations from the terminal.
3. Let an administrator cross the user isolation boundary to view and manage every user's processes and VFS, under full audit.

---

## 1. Current State

### 1.1 Implemented Components

| Component | File | Status |
|---|---|---|
| `TerminalServer` (TCP session server, port 8022 default) | `terminal/TerminalServer.java` | Implemented |
| `TerminalAccessService` (login, registration, rate limiting) | `terminal/TerminalAccessService.java` | Implemented |
| `TerminalBootstrap` (admin account provisioning) | `terminal/TerminalBootstrap.java` | Implemented |
| `DatabaseTerminalControl` (durable control plane + FCL REPL) | `terminal/DatabaseTerminalControl.java` | Implemented |
| `TerminalReplService` (durable FCL process submissions) | `application/TerminalReplService.java` | Implemented |
| `TerminalConsole` (host console adapter) | `terminal/TerminalConsole.java` | Implemented |
| `ShellCommand` / `ShellCommandParser` (colon commands) | `terminal/ShellCommand*.java` | Implemented |
| `TerminalService` (sessions, history, input submission) | `terminal/TerminalService.java` | Implemented |
| `AdminVfsService` (cross-user VFS) | `vfs/AdminVfsService.java` | Implemented |
| `VfsService` (owner-scoped file CRUD) | `vfs/VfsService.java` | Implemented |
| `ProgramService` (FCL compilation) | `application/ProgramService.java` | Implemented |
| `ProcessService` (process lifecycle, ownership-checked control) | `application/ProcessService.java` | Implemented |
| `AuthService` (user creation) | `auth/AuthService.java` | Implemented |
| `Authorization` (capability gates) | `auth/Authorization.java` | Implemented |
| `RuntimeBootstrap.startTerminalServer()` (assembly) | `app/RuntimeBootstrap.java` | Implemented |

### 1.2 Historical Plan vs. Shipped Behavior

The original plan sketched an in-process `TerminalConsole` with `TerminalControl`
commands such as `run`, `ps`, `inspect`, `admin-ps`, and `admin-kill`. The shipped
design keeps the durable terminal but moves almost all operations into FCL:

| Plan assumption | Shipped behavior |
|---|---|
| `CilExecShell` implements `TerminalControl` | `DatabaseTerminalControl` implements `TerminalControl`; process, file, package, user, effect, and system operations are FCL functions with every namespace available |
| Shell subcommands `run`, `ps`, `kill`, `inspect`, `pause`, `resume` | Express these as FCL (e.g. `process.fork`, `process.getList`, `process.kill`) or via the REPL; the terminal command set is deliberately small |
| Shell subcommands `admin-ps`, `admin-kill`, `admin-ls`, `admin-cat`, `admin-tree` | Administrator process control goes through FCL with `PROCESS_CONTROL_OWN`/`PROCESS_CONTROL_ANY`/`SYSTEM_ADMIN` rules; administrator VFS traversal uses `/Users/<username>/...` path routing |
| `TerminalControl` interface, no implementation | Implemented by `DatabaseTerminalControl` (interactive and headless variants) |
| One history per user, in memory | Per-user persistent history (most recent 200 entries) across reconnects and restarts |

---

## 2. Design Decisions

### 2.1 VFS Has No Global Tree

Each user keeps an independent root `/`. Administrators see every user's VFS by
traversing under `/Users/<username>/...` rather than by redesigning the VFS as one
global tree.

**Reasons:**
- Small change; the schema is untouched.
- Analogy to Docker namespaces — each container has its own `/`; the host
  (administrator) sees everything.
- RLS isolation is safe by default.

### 2.2 Administrator Traversal Mechanism

RLS policies are unchanged. Administrator operations do **not** go through
`SET LOCAL ROLE`; they run directly as the `cilexec_runtime` database role inside
explicit transactions and use the `*ByAdministrator` repository/service methods,
which are backed by forced-RLS policies covering administrator access. The Runtime
still re-checks the actor's `SYSTEM_ADMIN` capability, and every cross-user
operation appends an audit event.

**Two execution paths:**

```text
normal user operations:
  JdbcTransactionExecutor.inUserTransaction(userId, ...)
    → SET LOCAL ROLE cilexec_user_xxx
    → RLS: owner_id = auth.current_cilexec_user_id()  → only own rows

administrator operations:
  explicit transactions as cilexec_runtime (no SET ROLE)
    → capability check (SYSTEM_ADMIN)
    → AdminVfsService / *ByAdministrator methods
    → RLS permits administrator policies; audit appended
```

The `local` administrator account additionally gets a stable `/Users` virtual
directory as the entry point for per-user home mounts.

### 2.3 The system_admin Capability

```sql
INSERT INTO auth.capability (capability_id, capability_key, description, system_capability)
VALUES ('00000000-0000-4000-8000-00000000000c', 'system_admin',
        'Manage all users, processes, VFS nodes, IPC, timers, and effects', true)
ON CONFLICT (capability_key) DO NOTHING;
```

Capability enforcement:

```java
// same-owner control → PROCESS_CONTROL_OWN; cross-owner → PROCESS_CONTROL_ANY;
// SYSTEM_ADMIN always allowed (mirrored by ProcessService and the FCL runtime)
Authorization.require(transaction, userId, capability);   // general gate
Authorization.requireAdministrator(transaction, userId);  // SYSTEM_ADMIN gate
```

`SYSTEM_ADMIN` satisfies every capability check. It never implies PostgreSQL
`BYPASSRLS` or cluster-superuser privileges.

---

## 3. Terminal Protocol and Session Behavior

### 3.1 Transport

`TerminalServer` is a TCP server (default port 8022, configurable via
`CILEXEC_TERMINAL_PORT`). Each interactive client supplies a stable terminal context ID;
after authentication that ID selects one durable host session and its attached REPL PID. The
distributed `tools/Install.sh` derives the ID from the project path and host TTY, so reconnecting
after a Runtime/Docker restart resumes the same terminal while other host terminals remain
separate. Older clients without an ID use the legacy most-recent-session fallback. A headless
variant (`DatabaseTerminalControl.headless`) binds a named durable context for host tooling.

### 3.2 Session Lifecycle

- Sessions are durable: working directory, FCL process context, and command history
  are persisted and resume after reconnect or Runtime restart.
- **Interactive disconnect detection.** The interactive input pump treats end-of-stream on the socket as the
  authoritative disconnect signal and wakes the blocked session loop, so a
  disconnected client never keeps a session slot (or a polling loop) alive forever.
- **Idle timeout.** A 60-second socket timeout periodically wakes interactive reads so the
  server can check the durable session policy. An authenticated session is closed only when
  its attached process has remained `PAUSED` for the configured idle threshold (60 minutes
  by default). Pre-authentication connections do not currently have a separate 60-second
  lifetime limit.
- `:exit` closes only the calling transport; `:logout` returns to the login prompt
  without losing REPL state; both leave the shared Runtime and background processes
  running.

### 3.3 Input Model

A control frame (`\0`-prefixed) carries interrupt and dimension events, so Ctrl+C
works while FCL is executing. The session supports:

- free-form **FCL input** (default mode): expressions and statements reuse one
  durable FCL process for the terminal context; `func`/`if`/`while` blocks continue on a `...>` multiline
  prompt, `Shift+Enter` inserts a line break without submitting (so continued lines
  work even with balanced delimiters), and a trailing `\` before Enter (C-style line
  continuation) keeps the submission open, joining the lines before compilation. The
  editor negotiates the kitty keyboard protocol (`CSI > 1 u`) so terminals with
  modifier-key support send `Shift+Enter` natively;
- **attached input**: when a process waits in `io.input()`, the prompt changes to
  `pid:?` and the next line is delivered verbatim; terminal commands still start
  with `:`, and `::text` sends raw input beginning with `:`;
- key-mode input (`io.readKey`) forwards structured raw key, mouse, paste, focus, timeout,
  and unknown-sequence events to FCL. REPL history and cursor editing apply only to normal
  editable line mode. With text coalescing enabled, printable input waits at most 8 ms for a
  batch; an already-buffered held Backspace key is sent as one ordered repeat event.
- the terminal control plane waits for post-commit process-state notifications rather than
  querying PostgreSQL every 25 ms. PostgreSQL remains authoritative and is rechecked after a
  one-second fallback deadline if no notification arrives.
- full-screen packages can use `term.render(frame)` for disposable redraws. It publishes only
  after the editor's durable state commit and bypasses the external-effect worker; ordinary
  `io.print` and `io.println` retain their journaled external-effect behavior.

### 3.4 Colon Commands

The terminal command set (prefix with `:`):

| Command | Effect |
| --- | --- |
| `:help` | Show terminal help. |
| `:cd <vfs-path>` | Change the durable working directory; the target must be a directory. `:cd` with no argument reports an error instead of crashing. |
| `:pwd` | Print the working directory. |
| `:ls [vfs-path]` | List the current or given directory; directory names end with `/`. |
| `:clear` (alias `:cls`) | Clear the screen. |
| `:logout` | Return to login, keeping the user's terminal state and working directory. |
| `:exit` (alias `:quit`) | Disconnect only this terminal connection; the shared Runtime and background processes continue. |
| `:shutdown` | Ask for the administrator password and stop the shared Runtime; only users with `SYSTEM_ADMIN` may use it. |

Plain input without `:` is FCL. Consequently `ls` and `cd` remain legal FCL identifiers;
directory commands must use `:ls` and `:cd`.

### 3.5 REPL Execution Model

Each terminal context reuses one durable process identity. `TerminalReplService` compiles
each submission as a new immutable program, attaches it to that process, enqueues it in the
FIFO scheduler, and waits for the process to return to `PAUSED` or start waiting for input.
A top-level `return` completes the submission; its value is rendered at the prompt and the
same PID remains available for the next submission. Terminal processes share the bounded worker
pools (default 10 scheduler workers, 6 effect workers) and the shared connection
pool; processes beyond the worker count wait in the durable queue. Every process
executes at most 4096 interpreter steps or 20 ms per scheduler slice and stops early
on suspension, directive, completion, or failure, then persists and re-enqueues when ready.

REPL rendering uses indented JSON for maps and arrays. A string whose trimmed content is a
valid JSON object or array is displayed as that parsed JSON for readability; this affects only
display, not the persisted string value. Other strings are rendered as JSON strings.

---

## 4. Authentication and Administrator Bootstrap

### 4.1 Login and First Use

`TerminalAccessService` authenticates against application-owned credential
verifiers (never the host OS). On first use, `isFirstUse()` triggers bootstrap: the
`local` administrator account is created with `SYSTEM_ADMIN`. Ordinary registrations
receive only `USER_CAPABILITIES`: `PROCESS_CREATE`, `PROCESS_CONTROL_OWN`,
`VFS_READ`, `VFS_WRITE`, `TERMINAL_ATTACH`, and `AUDIT_READ`. `PACKAGE_IMPORT`,
`PACKAGE_BIND`, `EFFECT_REQUEST`, `VFS_MOUNT_HOST`, `PROCESS_CONTROL_ANY`, and
administrator authority require explicit grants.

### 4.2 Rate-Limited Password Verification

- Failed logins apply an exponential backoff (250 ms doubling), capped per
  principal; a shared `<unknown>` bucket with a lower ceiling absorbs unknown-username
  lookups so one attacker cannot slow unrelated logins.
- Unknown usernames and inactive accounts verify a **dummy credential** so response
  timing does not reveal account existence.
- Concurrent credential checks are bounded by a semaphore.
- Administrator password verification (used by `:shutdown` and by
  admin-account registration) goes through the same rate limiter.
- Admin-account registration also requires the operator account to currently
  hold the effective `SYSTEM_ADMIN` capability (direct or group-derived,
  expiry-aware). A correct administrator password alone is never sufficient:
  a revoked or expired `SYSTEM_ADMIN` cannot be used to mint a fresh
  administrator account.
- Passwords are read through `PasswordPrompt`, whose `Secret` buffer is
  deterministically zeroed when its lexical scope ends; usernames, passwords, and
  raw `io.input()` content never enter command history.

### 4.3 TerminalBootstrap Merges Capabilities

`TerminalBootstrap.ensure()` provisions the administrator account and its VFS
directory. It merges `SYSTEM_ADMIN` into the account's **existing** capability set
and never deletes capabilities assigned through other channels.

---

## 5. Administrator Capabilities and Services

### 5.1 AdminVfsService

Cross-user VFS operations (`AdminVfsService`) are constrained by PostgreSQL RLS even
for accounts holding the administrator capability, and every call appends an audit
event:

```text
adminListNodes / adminListDirectory   list a target user's tree
adminReadFile / adminReadFileByPath   read target user files
adminWriteFile / adminCreate*         create and write target user files
adminRename* / adminDeleteNode        rename and constrained-delete (no recursive
                                      deletion of non-empty directories)
```

### 5.2 Administrator FCL Functions

File functions accept an optional trailing target user, e.g.
`file.read("/home/a.txt", "alice")`; ordinary users may only reach their own files,
while `SYSTEM_ADMIN` holders may reach any user's files (each cross-user access
leaves an audit record). `system.*` functions, `user.*` user administration, and
`system.*` management functions and `user.*` administration require administrator authority.
`user.create(username, password [, administratorCredentials])` creates users from FCL:
without the third argument any logged-in user may self-register a normal account (matching
terminal registration). Creating an administrator requires `[administratorUsername,
administratorPassword]`: the named administrator must be ACTIVE, match the password, and
currently hold effective `SYSTEM_ADMIN` - the database verifies all of this atomically with
the creation (via `auth.create_user_by_credential`), so a revoked or expired capability can
never mint a fresh administrator.
Direct `socket.bind`/`socket.accept` calls use the ordinary external-effect capability and
the built-in handler restricts binds to loopback; they are not administrator-only.

### 5.3 Process Control Ownership Rules

Process lifecycle operations check ownership before capability:

```text
same-owner process control → PROCESS_CONTROL_OWN
cross-owner process control → PROCESS_CONTROL_ANY
SYSTEM_ADMIN               → always allowed
```

`ProcessService.requireControl` mirrors the FCL runtime's `targetProcess` rule, so
the application boundary and the language boundary agree. Process listing locks rows
in `pid` order to keep list reads deadlock-free against statement transactions.

### 5.4 Cross-User Audit

Every administrator operation appends a structured audit event recording the actor
(the administrator), the target user, the action (`vfs.admin.*`, user
administration, process control, etc.), the result, and details JSON. Audit events
are retained per type and purged by the maintenance loop.

---

## 6. Database Baseline

### 6.1 Implemented V001 Module: system_admin Capability

```sql
-- administrator capability section of db/baseline/administrator_storage.sql
SET ROLE cilexec_owner;

INSERT INTO auth.capability (capability_id, capability_key, description, system_capability)
VALUES ('00000000-0000-4000-8000-00000000000c', 'system_admin',
        'Manage all users, processes, VFS nodes, IPC, timers, and effects', true)
ON CONFLICT (capability_key) DO NOTHING;

RESET ROLE;
```

### 6.2 Implemented Terminal Storage

The terminal uses the `terminal.*` session/input tables and the existing process/scheduler
machinery. Its durable storage, the `system_admin` capability, and administrator RLS policies
are part of the frozen modular V001 baseline. The effect active-quota index is also part of that
baseline; it is not a terminal migration.

---

## 7. File Change Checklist (Shipped)

| Operation | File |
|---|---|
| Implemented | `terminal/TerminalServer.java`, `TerminalAccessService.java`, `TerminalBootstrap.java`, `DatabaseTerminalControl.java`, `TerminalConsole.java`, `ShellCommand.java`, `ShellCommandParser.java`, `TerminalService.java`, `TerminalReplService.java` (application) |
| Implemented | `vfs/AdminVfsService.java`, `vfs/VfsService.java` |
| Implemented | `application/ProcessService.java` (ownership-checked control), `application/ProgramService.java` |
| Implemented | `auth/AuthService.java`, `auth/Authorization.java`, `auth/PasswordPolicy.java`, `auth/UsernamePolicy.java` |
| Implemented | `app/RuntimeBootstrap.java` (terminal assembly), `app/ApplicationCommand.java` |
| Baseline | `src/main/resources/db/baseline/administrator_storage.sql`, `terminal_runtime.sql`, `effect_terminal_audit.sql` |

---

## 8. Verification

- `mvn compile` succeeds.
- `mvn test` passes for `*Test` unit suites.
- `mvn verify` passes for the full `*Test` and `*IT` suite; `*IT` requires Docker and
  PostgreSQL/Testcontainers.
- Starting the Runtime brings up the terminal server; logging in presents the
  `cilexec>` prompt.
- `:help` lists the colon commands; FCL input evaluates as a durable process and a
  top-level `return` renders the program result.
- `:cd`, `:pwd`, `:ls` operate on the durable working directory; `:cd` without an
  argument reports an error without crashing.
- `:exit` disconnects only the caller; the shared Runtime keeps running. A client
  disconnect is detected instead of polling forever. A session is closed for
  idleness only when its attached process has been suspended (PAUSED) for the
  configured threshold (default 60 minutes, `CILEXEC_TERMINAL_IDLE_MINUTES`);
  active processes and full-screen programs waiting on input are never closed.
- First use bootstraps `local` with `SYSTEM_ADMIN`; `TerminalBootstrap` never
  removes pre-existing capabilities.
- Login and administrator-password failures are rate-limited (exponential backoff,
  dummy verification for unknown users).
- An administrator can list/read/write/rename/delete files under `/Users/<user>/...`
  and reach cross-user FCL file operations, with audit records; a non-administrator
  sees only own rows under forced RLS.
- Cross-owner process control requires `PROCESS_CONTROL_ANY` (or `SYSTEM_ADMIN`);
  same-owner requires `PROCESS_CONTROL_OWN`.
