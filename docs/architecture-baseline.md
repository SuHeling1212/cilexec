# CilExec Database-Driven and Dockerized Rebuild Master Plan (Java Edition)

Status: **current architecture reference with preserved historical decision record**
Core Runtime: **Java 26**; standalone market server: **Java 21**
Implementation language: **Java (existing language and FCL runtime semantics retained)**  
Runtime database: **PostgreSQL**  
Package artifact: **immutable SQLite `.db`**  
Distribution: **Docker Compose, Linux AMD64 and ARM64**

---

## 0. Document Purpose and Constraints

This document records the architecture that resulted from the 86-question questionnaire and
the current implementation. Sections that describe the shipped Runtime use present tense.
The decision register and implementation phases are retained as historical design history;
they are not an assertion that their work remains open. Explicit future work is identified as
such and must be reconciled with the current schema before implementation.

The completed rebuild accomplished three things at once:

1. It moves CilExec from file-driven persistence to a transaction-oriented runtime backed by PostgreSQL;
2. It keeps the Java implementation and the existing FCL semantics while restructuring the runtime layers, database access, and recovery boundaries;
3. It makes Docker Compose the standard development, test, and release environment from phase one onward.

Throughout this document, the term "Kernel" is consistently called the **CilExec Runtime** to avoid confusion with the Linux kernel. It refers to the Java/JVM core program that interprets FCL, schedules CilExec processes, accesses PostgreSQL, enforces permissions, and implements IPC, VFS, packages, and recovery.

### 0.1 Decision Priority

When conflicts arise, they are resolved in this order:

```text
Committed database state is the only truth
> immutable artifacts and exact content identity
> recoverable, auditable transaction semantics
> explicit user choice
> implementation convenience
```

### 0.2 Question 9 Consistency

The questionnaire chose "PostgreSQL data lives in the container writable layer." That choice applies only to an explicitly **disposable development profile** — not because the user decision is being changed, but because two runtime profiles must be distinguished:

```text
ephemeral profile
= data lives in the container writable layer
= deleting or replacing the PostgreSQL container loses the instance
= intended for tests, demos, and one-off runs

persistent profile
= data lives on a named volume, bind mount, or external PostgreSQL
= intended for any instance that must survive
```

CilExec's persistence promise applies only to the persistent profile. Programs must never describe the ephemeral profile as reliable durable storage.

---

## 1. Product Positioning

CilExec is positioned as:

> **A database-driven user-space operating system.**

It runs on Linux/Docker but manages, inside its own logical boundary:

```text
CilExec users and groups
CilExec processes
FCL programs and execution continuations
scheduling queues
IPC, channels, topics, and broadcasts
timers
VFS
package releases and process bindings
external side effects
terminal sessions
audit and recovery
```

Docker/Linux provides host capabilities:

```text
CPU and memory
processes and threads
TCP/IP
block storage
container isolation
host file mounts
clock
signals
```

PostgreSQL provides:

```text
authoritative state
transactions
locks
indexes
constraints
WAL and database crash recovery
user roles and RLS
```

The Java CilExec Runtime provides:

```text
FCL language semantics
process state machine
statement-level transaction advancement
scheduling
authorization decisions
IPC
VFS
package resolution
effect journal
semantic recovery
```

---

## 2. Final System Topology

### 2.1 Standard Compose Topology

```text
Docker Compose Project
├── postgres
│   ├── PostgreSQL server
│   ├── healthcheck
│   └── data directory
│
├── migrate
│   ├── built from the same source as CilExec
│   ├── uses the migrator Role
│   ├── runs Flyway migrations
│   └── exits once successful
│
└── cilexec
    ├── Java CilExec Runtime
    ├── Scheduler workers
    ├── Effect workers
    ├── Terminal/API
    └── disposable package cache
```

Startup ordering:

```text
postgres healthy
→ migrate completed successfully
→ cilexec starts
```

PostgreSQL and CilExec live in separate containers. CilExec must also support connecting to an external PostgreSQL; the standard local development environment uses the PostgreSQL instance in Compose.

### 2.2 Networking

The standard Compose configuration exposes PostgreSQL only on the internal Compose network;
it has no host `ports` mapping. Development tools that require direct database access may add
an explicit loopback-only override:

```yaml
ports:
  - "127.0.0.1:5432:5432"
```

That optional override is exposed only to the host loopback and is not directly reachable on
the LAN.

Remote access requires explicit changes to the listen address, TLS, authentication, and firewall rules.

### 2.3 Multi-Architecture

Release images must be published for:

```text
linux/amd64
linux/arm64
```

Developers can run the ARM64 image locally on Apple Silicon Macs and produce AMD64 images via Docker Buildx or CI.

### 2.4 Container User

Both `cilexec` and `migrate` run as fixed non-root Linux users. The final image must not contain:

```text
the full JDK compiler toolchain
a Maven local repository
source code
test code and debug build tools
unneeded shell tools
database passwords
```

### 2.5 Configuration Profiles

```text
docker/compose/ephemeral.yml
- PostgreSQL uses the container writable layer
- explicitly marked disposable
- intended for tests and demos

docker/compose/persistent.yml
- PostgreSQL uses a named volume or bind mount
- intended for long-lived local development and production runs

external-postgres
- starts only migrate and cilexec
- connects to an external PostgreSQL
```

---

## 3. Java Rebuild Boundaries

Java is retained; there is no language rewrite. The goal is to move the existing FCL interpreter, process system, and built-in functions onto clean database transaction boundaries rather than reinventing language behavior.

The following principles are mandatory:

```text
FCL's external semantics are preserved
Java classes may be reorganized and rewritten
the legacy file persistence implementation must be deleted
database repositories must not leak into the expression and syntax layers
JVM in-memory objects must never become the truth for recovery
```

The frozen FCL semantic snapshot (compiled-format version 2) includes: `//` is the only comment syntax; `#` is only the unary length operator; statement keywords are reserved words; arithmetic overflow raises a compile/runtime error instead of silently wrapping; and a top-level `return` ends the program, with its value becoming the program result.

### 3.1 Maven Project and Java Package Structure

Maven continues to be used. The first release keeps a single repository with multiple packages rather than forcing several independent Maven artifacts; a multi-module build may be introduced later once module boundaries stabilize.

Actual layout:

```text
src/main/java/com/follarce/
├── Main.java                      stable executable entry point
├── app/                           startup/shutdown/commands (CilExecApplication,
│                                  RuntimeBootstrap, RuntimeLifecycle, ApplicationCommand)
├── application/                   database-aware FCL functions (FclRuntimeFunctions),
│                                  statement dispatch (ProcessStatementExecutor),
│                                  ProgramService, ProcessService, TerminalReplService
├── auth/                          AuthService, Authorization, PasswordPolicy, UsernamePolicy
├── audit/                         AuditRetentionService
├── config/                        CilExecConfig, database pool settings
├── domain/                        persistence-independent model and ports
│   └── (process, program, vfs, scheduler, ipc, effect, timer, auth, audit)
├── effect/                        journaled effects (EffectWorkerService, BuiltinEffectHandlers)
├── exporter/                      verified logical PostgreSQL → SQLite export
├── extension/                     immutable compile-time extension catalog
├── fcl/                           compiler, continuation runtime, evaluator, codecs, builtins
├── health/                        HealthServer
├── host/                          host-move tooling
├── ipc/                           durable messaging services
├── market/                        built-in market client functions
├── package_manager/               SQLite package builds and environments
├── persistence/postgres/          connection, transaction, repository, error, mapper
├── scheduler/                     SchedulerService and claim handling
├── terminal/                      TerminalServer, DatabaseTerminalControl, TerminalBootstrap
├── timer/                         TimerService, TimerWorkerService
└── vfs/                           VfsService, AdminVfsService

src/main/resources/
├── db/baseline/                   Flyway SQL baseline modules (foundation.sql,
│                                  execution.sql, vfs_package.sql, effect_terminal_audit.sql,
│                                  contracts.sql, administrator_storage.sql,
│                                  atomic_administration.sql, environment_permissions.sql,
│                                  password_vfs_runtime.sql, terminal_runtime.sql,
│                                  production_hardening.sql, package_lifecycle.sql)
├── logback.xml
└── cilexec-defaults.properties
```

The `com.follarce` package name is retained. During migration an adapter layer may be introduced first and legacy classes moved gradually onto the boundaries above; existing tests must not be broken all at once for the sake of directory neatness.

### 3.2 Dependency Direction

```text
domain
↑
process / ipc / vfs / package / effect
↑
fcl-runtime
↑
application/bootstrap

persistence-postgres
implements the repository and transaction ports defined by domain
```

Domain objects and the FCL runtime semantics layer must not directly depend on:

```text
JDBC ResultSet
Connection or PreparedStatement
PostgreSQL-specific Java types
SQL strings
Docker
HTTP frameworks
connection pool implementations
specific logging backends
```

Database row models and domain objects are converted through mappers. Mutable `ResultSet` objects, connection objects, or database proxy objects must never be stored in a process continuation.

### 3.3 Java Data Access Stack

Standard stack:

```text
PostgreSQL JDBC Driver
HikariCP
Flyway
explicit SQL
explicit TransactionContext
```

SQLite package reading uses a separate read-only SQLite JDBC connection. PostgreSQL and SQLite connections are never mixed, and no cross-database atomic transaction is attempted.

All dependency versions must be pinned exactly in `pom.xml`; floating version ranges are forbidden.

### 3.4 TransactionContext

Repository methods must not hide transaction open/commit/rollback themselves. Cross-module atomic operations are created by an application service as a `TransactionContext`, which passes the same JDBC `Connection` to every repository participating in the transaction.

Sketch:

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

The transaction executor must guarantee:

```java
public <T> T inTransaction(
        Isolation isolation,
        TransactionWork<T> work
) throws CilExecException;
```

Rules:

1. `autoCommit=false`;
2. default isolation is `READ_COMMITTED`;
3. `commit()` is called only by the outermost application service;
4. any exception rolls back;
5. `close()` restores connection state and returns the connection to the pool;
6. repositories never expose `Connection` to FCL function implementations;
7. external side effects are never executed inside the transaction.

### 3.5 SQL and Mapping Rules

Named SQL files or Java text blocks are both acceptable, but every statement must have a stable name that tests can target, for example:

```text
process.claimNext
process.loadContinuation
process.commitStatement
ipc.createDeliveries
vfs.replaceContent
package.bindRelease
```

Every update statement must check its affected-row count. Optimistic updates whose affected count is not 1 are treated as conflicts and must not silently continue.

Database exceptions map to CilExec exception categories through one centralized classifier:

```text
SQLSTATE 23505 → UniqueConflict
SQLSTATE 23503 → ReferenceConflict
SQLSTATE 40001 → SerializationRetryable
SQLSTATE 40P01 → DeadlockRetryable
SQLTimeoutException / 57014 / 57P01 → RetryableTransient
connection interruption (08*, transient/non-transient/recoverable
    connection exceptions) → DatabaseUnavailable
other exceptions → PersistenceFailure
```

The SQLSTATE mapping is maintained in one place, not scattered across repositories. Connection-pool timeouts (`SQLTimeoutException`) are classified as transient and retryable; they must never fence the Runtime by themselves.

### 3.6 JVM Concurrency Model

Java virtual threads remain in use, but database concurrency must be bounded.

```text
one runnable CilExec worker
→ one short-lived virtual thread task
→ at most one database transaction held
```

`Executors.newVirtualThreadPerTaskExecutor()` must not be treated as unlimited database concurrency. Concurrency is bounded by at least one of:

```text
fixed scheduler worker count
Semaphore
bounded task queue
maximum connection pool size
```

The enforced relationship:

```text
runtime.pool.max ≥ scheduler.workers + effect.workers + 2
```

Defaults: `scheduler.workers=10`, `effect.workers=6`, `runtime.pool.max=20` (10 + 6 + 2 = 18 ≤ 20). The invariant is validated at configuration load, and effect workers draw from their own pool (`effect.pool.max=6`); the migrator uses a small separate pool (`migrator.pool.max=2`). The scheduler and effect worker counts plus admin connections must stay within the PostgreSQL `max_connections` allowance.

The control connection is a separate physical connection and never enters the ordinary HikariCP pool.

All of the following in-JVM objects are disposable:

```text
Thread / VirtualThread
Future / CompletableFuture
Executor queues
Java Lock / Semaphore
parse caches
function lookup caches
timed wake-up tasks
```

They may accelerate execution but never determine recovery outcomes.

### 3.7 Build Artifacts

The Maven build produces at least:

```text
cilexec-app.jar
dependency lock manifest
build version information
Git commit identifier
supported database schema range
FCL runtime format version
```

The first release may use an executable fat JAR to simplify container assembly. If `jlink` is adopted later, full integration tests must prove that the PostgreSQL JDBC, SQLite JDBC, TLS, logging, and character-set modules were not trimmed away.

### 3.8 Docker Multi-Stage Build

```text
build stage
- Linux Java 26 JDK image pinned by digest
- Maven Wrapper or pinned Maven version
- mvn --batch-mode verify -DskipITs
- produces the final JAR

runtime stage
- Linux Java 26 JRE pinned by digest or a verified jlink runtime
- non-root user
- copies the runtime JAR, healthcheck script, and native terminal client
- no Maven cache, sources, or test reports
```

The Dockerfile deliberately skips `*IT` suites while assembling an image; CI runs those
PostgreSQL/Testcontainers suites independently with `mvn clean verify` before accepting image
or release artifacts. The formal `release` target replaces the self-built JAR with the exact
verified `dist/cilexec-app.jar` recorded in the release manifest.

Entry point form:

```text
java <JVM_OPTIONS> -jar /opt/cilexec/cilexec-app.jar
```

JVM options are injected through configuration; machine-specific memory sizes are not hardcoded in the image. The container must receive SIGTERM correctly, and the Java main process must be PID 1, not wrapped in a shell that swallows signals.

---

## 4. PostgreSQL Instance Model

### 4.1 Instance Boundary

```text
one CilExec instance
=
one PostgreSQL database
```

A single PostgreSQL cluster may host several CilExec databases, but cross-instance operations are outside the atomic transaction scope of the first release.

### 4.2 Schema Overview

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

### 4.3 The Database Is the Source of Truth

The following state must be persisted in PostgreSQL:

```text
users, groups, role mappings, and capabilities
programs and program hashes
process continuations
current process variable values
process relationships
scheduling queue and leases
IPC messages, channels, topics, subscriptions
timers
VFS nodes and content
package artifacts, environments, and bindings
terminal committed input and attachment relationships
effect requests, attempts, and results
audit records
startup, shutdown, and recovery information
```

The following are never authoritative state:

```text
Java Threads, virtual threads, or Futures
database connection objects
connection-pool internal queues
Mutex/RwLock
parse caches
prepared-query caches
SQLite package local caches
log files
container IDs
```

---

## 5. PostgreSQL Roles, Users, and RLS

### 5.1 System Roles

```text
cilexec_owner
cilexec_migrator
cilexec_runtime
cilexec_effect_worker
cilexec_readonly
```

Responsibilities:

| Role | LOGIN | Primary privileges |
|---|---:|---|
| `cilexec_owner` | optional | owns schemas and objects; not used for daily operation |
| `cilexec_migrator` | yes | creates and modifies schema, tables, indexes, functions, and policies |
| `cilexec_runtime` | yes | executes processes, scheduling, IPC, VFS, and package management |
| `cilexec_effect_worker` | yes | claims effects, writes attempts/results, reads required requests |
| `cilexec_readonly` | yes | diagnostics and read-only administration |

In production the Runtime has no `CREATE/ALTER/DROP` privileges. Development environments may allow the Runtime to run migrations behind an explicit switch, but the standard Compose setup still uses a separate migrate service.

### 5.2 CilExec Users Map to PostgreSQL Tenant Roles

Every CilExec user corresponds to a stable PostgreSQL `NOLOGIN`, `NOINHERIT` tenant role.
The application password verifier is not a PostgreSQL login credential.

Stable mapping:

```text
auth.user_account.user_id
↔
PostgreSQL Role: cilexec_user_<encoded_user_id>
```

Usernames are mutable, but the database role name is derived from the stable `user_id` so renames never invalidate objects or policies.

`auth.user_account` stores at least:

```text
user_id
username
postgres_role_name
status
created_at
disabled_at
credential_version
```

### 5.3 Connection Model

Instead of opening a physical connection per request, the Runtime maintains a bounded pool and executes inside transactions:

```sql
SET LOCAL ROLE cilexec_user_<id>;
SET LOCAL app.cilexec_user_id = '<uuid>';
```

`SET LOCAL` expires automatically when the transaction ends.

The runtime Role must have controlled membership that allows switching to user roles, but it must not be a PostgreSQL superuser and must not hold `BYPASSRLS`.

Tenant roles cannot log in directly. Normal CilExec usage goes through the Runtime, which
assumes the tenant role transaction-locally. Changing a tenant role to `LOGIN` is outside the
supported security baseline.

### 5.4 Comprehensive RLS

Every business table that carries a user, owner, or instance-resource boundary must enable RLS:

```sql
ALTER TABLE process.process ENABLE ROW LEVEL SECURITY;
ALTER TABLE process.process FORCE ROW LEVEL SECURITY;
```

Policies identify the user through the transaction setting:

```sql
current_setting('app.cilexec_user_id', true)
```

RLS is the final row-level isolation backstop; the Runtime still performs the richer capability, relationship, and state-machine checks.

System-level tables fall into:

```text
invisible to all users
readable only by the readonly admin role
accessible by the Runtime on behalf of users
```

Any migration that adds a business table must either declare its RLS policy or explicitly mark the table as a system table; otherwise the migration tests must fail.

### 5.5 Secrets

Database passwords and keys are injected via:

```text
Docker secrets
or
an external secret manager
```

They must never be written into:

```text
the Dockerfile
Git
Maven settings.xml or pom.xml
image layers
default compose files
logs
database business tables
```

### 5.6 Capability Enforcement Details

- **Process control ownership rule.** Controlling a process requires `SYSTEM_ADMIN`, or — for same-owner control — `PROCESS_CONTROL_OWN`, or — for cross-owner control — `PROCESS_CONTROL_ANY`. Both `ProcessService` and the FCL runtime apply the same rule.
- **Package capability policy.** A package manifest may declare a fixed set of required capability keys (`vfs.read`, `vfs.write`, `terminal.raw_input`, `network.http`, `network.socket`, `process.create`, `process.control`, `package.manage`, `package.data`, `system.admin`). At import time the package source is compiled and checked so it cannot silently use more authority than the manifest declares. `packageData.*` maps to `package.data`; its package provenance and per-user installation are checked before access. A user must hold the mapped capabilities before the package can run.
- **Extension state keys.** Durable extension state is namespaced as `cilexec.extension.<id.length>.<id>.<key>`; the length prefix keeps dotted extension IDs prefix-independent.

---

## 6. Migration and Schema Versioning

Flyway is the schema versioning tool.

```text
V001__CilexecBaseline.java       # frozen CilExec 0.0.1 modular baseline
V002__later_forward_change.java   # next schema or persisted-format change
...
```

Rules:

1. an applied migration is never modified;
2. migrations carry checksums;
3. only forward migrations are allowed;
4. automatic downgrades are forbidden;
5. the Runtime verifies the database schema version at startup;
6. on a version mismatch the Runtime refuses to start in production;
7. downgrades are performed by restoring a backup;
8. development environments may explicitly allow the Runtime to run migrations, but this must never become the production default.

The V001 SQL modules live in `src/main/resources/db/baseline/` and are applied in this order:
`foundation`, `execution`, `vfs_package`, `effect_terminal_audit`, `contracts`,
`administrator_storage`, `atomic_administration`, `environment_permissions`,
`password_vfs_runtime`, `terminal_runtime`, `production_hardening`, and `package_lifecycle`.
They define roles, RLS policies, package lifecycle storage, quotas, retention, and SECURITY
DEFINER functions. V001 and all of its modules are frozen together; the active-effect quota
index is part of its `effect_terminal_audit` module. The next change must be a new versioned
Java migration and must not edit V001.

**Migration on start.** `database.migrate-on-start` (env `CILEXEC_MIGRATE_ON_START`, default `false`) takes effect: when enabled, the Runtime applies pending migrations at startup through the migrator role and validates them before continuing, instead of requiring the one-shot `migrate` command.

**Schema verification.** At startup `SchemaVerifier` checks the actual schema version against the build's supported `[minimumSchema, maximumSchema]` range, currently `[1, 1]`. A failed or out-of-range migration — a version below the minimum or above the maximum — prevents the Runtime from entering the ready state.

---

## 7. Single Active Runtime and Control Lock

Only one active CilExec Runtime is allowed per database at any time.

At startup the Runtime opens a dedicated control connection and attempts to take a PostgreSQL session advisory lock:

```sql
SELECT pg_try_advisory_lock(:instance_lock_key);
```

Outcome:

```text
true  → control acquired; startup continues
false → an active Runtime already exists; this container refuses to enter the running state
```

The control connection is not part of the ordinary pool and never executes FCL transactions.

When the connection is lost:

```text
stop claiming new processes
stop committing any execution results
enter FENCED state
stop effect workers
close terminal output
exit the container
```

Pure computation may not continue after the control lock is lost, to be committed later. While the database is disconnected, all CilExec processes freeze.

---

## 8. Transactions and the FCL Execution Model

### 8.1 Default Isolation Level

Ordinary transactions use:

```text
READ COMMITTED
```

Rationale:

```text
transactions are bounded execution slices
conflicts are validated with state_version and row locks
failed transactions allow bounded retries
```

A few multi-row strong-invariant operations may use `SERIALIZABLE`, but must be explicitly marked and retried only a bounded number of times with jitter, and only when replayable.

### 8.2 One Commit per Execution Slice

The decision:

```text
one execution slice
=
one persisted transaction boundary
```

Ordinary statement flow:

```text
BEGIN
verify Runtime boot and control
verify scheduler lease
lock the process row
verify state_version
verify execution_epoch
load the continuation and variables needed by the statement
execute up to one scheduling slice of FCL statements
write current variable values
write the continuation
advance the program counter
update process status and the scheduling queue
append required audit events
COMMIT
```

A scheduling slice is at most 4096 interpreter steps or 20 ms for terminal processes
(one interpreter step per slice for all other processes). Every committed slice is
therefore a recovery checkpoint.

### 8.3 Slice Boundaries and Replay

Terminal slices stop on suspension, directive, completion, failure, the 4,096-step limit,
or the 20 ms limit. They do not currently force an immediate boundary after every VFS or IPC
operation; those database changes commit or roll back with the rest of the slice. Effect
requests and blocking operations suspend the continuation and therefore end the slice.

A crash rolls the whole uncommitted slice back. Recovery resumes from the preceding committed
continuation, so the interpreter steps in that slice may execute again. FCL-requested external
HTTP, socket, and command operations are journaled before execution; host administration tools
have separate transaction contracts and are not part of an FCL slice.

### 8.4 Conflict Control

The mechanism is:

```text
state_version
+
execution_epoch
+
SELECT ... FOR UPDATE where necessary
```

Commit condition sketch:

```sql
UPDATE process.process
SET state_version = state_version + 1,
    ...
WHERE process_uid = :process_uid
  AND state_version = :expected_state_version
  AND execution_epoch = :expected_execution_epoch;
```

If the affected-row count is not 1, the commit fails and the newer state is never overwritten.

### 8.5 Lock Order

Cross-module transactions follow one global order:

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
→ exact process package binding
→ effect
→ terminal
→ audit
```

Within a resource class, rows are locked in stable primary-key order. Process-list reads lock rows ordered by `pid` (never as an unordered table-wide scan), so a list transaction can never deadlock against a statement transaction that locks rows in `process_uid` order.

---

## 9. Programs and Process Continuations

### 9.1 Programs Are Shared Immutable Code

Program code is not copied per process.

```text
program.program
program.statement
program.module_binding
```

`program.program`:

```text
program_id
program_hash
language_version
source_object_hash
compiled_object_hash
statement_count
created_at
```

`program_id` is the stable internal database identifier; `program_hash` is the content identity of the logical code.

### 9.2 Process Identity

PIDs are never reused.

```text
process_uid = internal UUID
pid         = user-visible, monotonically increasing within the instance, never reused
```

No `generation` is needed for user-facing identity, but `process_uid` remains the foreign key and internal reference everywhere.

### 9.3 Full Continuation

Crash recovery cannot rely on "the current statement" alone. At minimum it must persist:

```text
program_id
program_hash
program_counter
call stack
return address
scope stack
current local variable values
exception handling stack
loop/branch continuations
wait reason
wait object ID
current package binding
language and runtime format versions
```

Suggested tables:

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

### 9.4 Current Variables and Variable Audit

The **current values** of all process variables must be persisted.

Ordinary variable changes are not permanently recorded. Audit stores only:

```text
security events
permission changes
administrative operations
externally visible operations
package installs and upgrades
host mounts
effects
exception recovery
```

This does not affect recovery: recovery reads current values from `process.variable` instead of replaying assignments from the audit history.

---

## 10. Scheduler

First-release policy:

```text
FIFO
```

A process entering READY is added to `scheduler.queue`.

The current claim path selects the oldest eligible FIFO candidate while locking only its process
row, allowing competing workers to skip an already-claimed head without reversing the
process-then-queue lock order:

```sql
SELECT process_uid
FROM scheduler.queue
WHERE queue_state = 'READY'
  AND ready_at <= now()
ORDER BY enqueued_at, process_uid
LIMIT 1
FOR UPDATE OF process SKIP LOCKED;
```

It then wins ownership through a process `READY → RUNNING` compare-and-set and commits the
queue claim and durable lease in the same transaction. Competing workers skip the locked head
and continue with the next eligible FIFO candidate.

### 10.1 Workers

The worker count is configurable and small by default. Execution workers and the database pool size are configured separately.

### 10.2 Lease

Every claim creates or refreshes a lease:

```text
process_uid
runner_id
boot_id
execution_epoch
claimed_at
heartbeat_at
expires_at
```

Rules:

```text
claiming increments execution_epoch
workers heartbeat periodically, and each heartbeat extends expires_at
      by the remaining lease duration
an expired lease can be reclaimed by another worker
a stale worker's commit fails because its epoch no longer matches
```

### 10.3 Crash Recovery

RUNNING processes are classified by their last committed state:

```text
pure execution with an intact continuation → READY
waiting for IPC                 → WAITING_IPC
waiting for a timer             → WAITING_TIMER
waiting for an effect           → WAITING_EFFECT
terminated but cleanup unfinished → TERMINATING
state violates invariants       → FAILED_RECOVERY
```

`RecoveryCoordinator` performs the semantic recovery: it locks recoverable processes in `process_uid` order and transitions them with a compare-and-set on `state_version`/`execution_epoch`. A crashed `TERMINATING` process is completed to `TERMINATED` (its unfired timers are deleted as part of the cleanup); a `RUNNING` process with an intact continuation returns to `READY`. Deterministically corrupt continuations become `FAILED_RECOVERY` with a `CONTINUATION_CORRUPT` failure code.

---

## 11. IPC, Channels, Topics, and Broadcasts

The first release supports the full message model:

```text
process-to-process
named channels
topics
subscriptions
broadcasts
```

### 11.1 Data Model

```text
ipc.message
ipc.delivery
ipc.channel
ipc.subscription
ipc.topic
```

`ipc.message`:

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

`ipc.delivery`:

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

Status:

```text
PENDING
RESERVED
CONSUMED
FAILED
DEAD
```

### 11.2 Delivery Semantics

Within a single PostgreSQL instance, the committed state transition is at most once at the
granularity of `ipc.delivery`:

```text
a delivery may commit the transition from RESERVED to CONSUMED at most once
```

This is not exactly-once application processing. A poll-based consumer that performs work and
crashes before `ipc.consume()` leaves a RESERVED row; startup resets it to PENDING and it may
be delivered again.

Broadcast is not multiple processes contending for one row; each subscriber receives an independent delivery.

### 11.3 Patterns

#### Directed send

```text
process A → process B
```

#### Channel

```text
multiple consumers listen to a channel
one message is claimed by one consumer
```

#### Topic/subscription

```text
publish to a topic
generate a delivery for every valid subscription
```

#### Broadcast

Broadcast is explicit topic fan-out (or a system topic); it is never an unreliable in-memory event bus.

---

## 12. Timers

Timer authoritative state lives in PostgreSQL:

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

The Java Runtime only:

```text
queries timers that are about to fire
waits or polls in short cycles with virtual threads
claims atomically
wakes the waiting process
```

`ScheduledExecutorService`, `Thread.sleep`, or virtual-thread waits only reduce polling cost; they are never the timer truth. After a container restart, all unfired due timers are scanned from the database.

**Row lifecycle.** FIRED timer rows are deleted periodically by the maintenance loop (rows older than a short retention window), and the unfired timers of a process are deleted when that process terminates — so a dead process never leaves wake-up debris behind.

---

## 13. VFS and the Object Store

### 13.1 File Nodes

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

### 13.2 Content Storage

Small content is stored as one immutable object. Large logical files use immutable linked
manifest chunks, all addressed through the same object store:

```text
object_store.object
├── object_hash
├── byte_size
├── media_type
├── content bytea
└── created_at
```

The logical file limit is 1 GiB. Range/download operations use at most 4 MiB per call, and
whole-file reads into one FCL string are capped at 16 MiB.

### 13.3 Content Addressing

`object_hash` is computed from the content bytes. Objects are immutable:

```text
write new file content
→ compute the new hash
→ INSERT object
→ update node.current_object_hash
→ COMMIT
```

### 13.4 File History

Only the current version is kept by default. Selected nodes or types may enable revisions:

```text
vfs.file_revision
```

Retention policy is configurable.

### 13.5 Host Imports and Reserved Mount Schema

The schema and `VfsService` contain a constrained host-mount model, but the standard Runtime
configuration does not supply allowed host-source keys or mount a host directory. Live
host-directory mounts are therefore not a supported standard deployment feature today.

All of the following must hold:

```text
an explicit bind mount at the Docker layer
+
a CilExec capability grant
+
a vfs.mount database record
```

The supported host-to-VFS feature is the `host move` tool: it mounts one explicitly named
regular file read-only into a disposable tool container, streams it into PostgreSQL under a
named active user holding `VFS_MOUNT_HOST` and `VFS_WRITE`, and retains the host source. The
`local` superuser is unconditionally rejected as an import target. It never mounts the Docker
Socket or a broad host directory.

---

## 14. Package Database

### 14.1 Artifacts

A package is one immutable SQLite `.db` file.

Allowed core tables inside it:

```text
package_metadata
package_file
package_module
package_dependency
package_entrypoint
package_export
package_capability
```

The package database is:

```text
read-only
immutable
free of runtime data
free of external ATTACH databases
free of virtual tables and arbitrary extensions
```

### 14.2 Identity

```text
package_hash
=
canonicalized logical-content hash
```

The transported bytes carry a second identity:

```text
database_file_hash
```

### 14.3 Coordinate Uniqueness

Final decision:

> **The same `namespace/name/version` must never map to different `package_hash` values.**

Allowed:

```text
std/network/1.0.0 → hash-A
std/network/1.1.0 → hash-B
```

Forbidden:

```text
std/network/1.0.0 → hash-A
std/network/1.0.0 → hash-B
```

The database enforces:

```text
UNIQUE(namespace, name, version)
```

Re-importing the same coordinates:

```text
same hash → idempotent success
different hash → rejected as version pollution
```

### 14.4 PostgreSQL Storage

The full `.db` bytes go into `object_store.object`:

```text
package.release.database_object_hash
→ object_store.object.object_hash
```

`package.release` does not store a second copy of `database_bytes`; that would duplicate storage. The raw `.db` file is the content authority; the dependency, entrypoint, export, and capability indexes in PostgreSQL are rebuildable derived data.

Dependencies are not resolved by coordinates or version ranges; they are pinned to the full SHA-256 of the depended-on `.db` artifact:

```text
package.release_dependency.dependency_file_hash
```

Coordinates are display-only. Installing a required dependency must find the exact file hash; optional dependencies may be missing.

### 14.5 Per-User Installation Records

There is no `package.environment` table or shared default binding. The current schema records
each user's explicit package roots in `package.installation_root` and exact dependency closure
in `package.installation_member`; package release registration, installation publication, and
uninstallation are atomic PostgreSQL operations. `package.list()` resolves the current user's
installed releases, not the global release catalog. Market receipts and managed VFS cache files
are supporting artifacts, not import authority.

### 14.6 Process Bindings

When a process imports or runs a package, its exact private binding is persisted:

```text
process.package_binding
├── process_uid
├── import_name   -- either the 64-character SHA-256 or a private per-process alias
└── package_hash
```

Import lookup uses the SHA-256 of the `.db` file, while the binding row stores the package's
internal logical-content hash. A private alias may be rebound by a later terminal submission;
already linked execution state keeps its committed binding until the next program is linked.

### 14.7 Lifecycle Hooks

The first release forbids arbitrary:

```text
pre-install
post-install
pre-upgrade
post-upgrade
pre-uninstall
post-uninstall
```

Install and uninstall update the user's installation ledger, process pins, and managed artifacts
inside PostgreSQL; they never automatically execute arbitrary FCL or host operations. There is
no package upgrade: a different content hash is a different package.

### 14.8 Mutable Data

Package runtime data is never written back into the package `.db`. It lives in the per-user,
per-package `package.data_space` and `package.data_entry` tables, references immutable object
store content, and is subject to the configured quota (256 MiB by default). `packageData.*`
can access only the invoking installed package's provenance-derived data space; ordinary VFS
paths and other packages cannot address it directly. User export archives are ordinary VFS files
and are not removed by package uninstallation.

### 14.9 Integrity

The package system provides no publisher signatures, trust states, or key management. Install and run integrity rely on the full SQLite file SHA-256 and the canonicalized logical-content hash to detect corruption or content changes.

---

## 15. External Side Effects

Every FCL-requested HTTP, socket, or allow-listed command operation enters the effect journal:

```text
HTTP
socket
external programs
mail
hardware
remote APIs
```

Status:

```text
PREPARED
CLAIMED
EXECUTING
COMPLETED
FAILED
UNKNOWN
```

Flow:

```text
transaction one:
create the effect
process enters WAITING_EFFECT
COMMIT

outside any transaction:
an Effect Worker executes

transaction two:
write the result
update the effect
wake the process
COMMIT
```

Every effect type must declare:

```text
whether it is idempotent
how the idempotency key is generated
whether the remote state can be queried
whether failures are retryable
how UNKNOWN is handled
```

UNKNOWN is handled by the effect type's policy; when it cannot be determined, it escalates to manual intervention instead of blind retry.

Durable timers use `process.timer`, not effect rows. Explicit control-plane commands such as
`host move` and logical export have their own bounded host-I/O and transaction contracts and
are outside the FCL effect journal.

Effect workers use a separate PostgreSQL role (`cilexec_effect_worker`) and their own bounded pool (default 6 workers).

**Stall reclamation.** Effects stuck in EXECUTING are reclaimed: `claimStalled` moves an
EXECUTING effect whose claiming runner has no live heartbeat to UNKNOWN after a 5-minute stall
threshold, tagged with `EFFECT_STALLED`. The heartbeat thread refreshes idle workers every 30
seconds; a busy worker is deliberately allowed to become stale so an execution blocked past
the threshold can be reclaimed. Effect handlers must therefore use explicit timeouts below the
threshold. A reclaimed operation may already have produced an external side effect, so it
follows the type's UNKNOWN policy (remote query, idempotent retry, or manual action).

**Result persistence is decoupled from process wake-up.** The effect row and attempt commit first; waking the waiting process is a separate transaction. If the wake fails, the committed result is retained and the effect is never lost; a later scheduler wake or recovery path resumes the process. A wake conflict can never roll back a committed effect result.

**Remote delivery.** Idempotency keys are passed to remote endpoints through the `Idempotency-Key` HTTP header on effect requests. Network targets may be IPv6 literals (bare or bracketed); DNS names are IDN-encoded, while IP literals skip IDN entirely.

**Failure codes.** Effect attempts record stable machine-readable codes, including `EXECUTION_FAILED`, `OUTCOME_UNKNOWN`, `OUTCOME_QUERY_FAILED`, `EFFECT_NOT_AUTHORIZED`, `UNSUPPORTED_EFFECT`, and `EFFECT_STALLED`.

---

## 16. Terminal and Interrupts

The terminal persists only complete committed input, never every keystroke.

```text
terminal.session
terminal.input
terminal.attachment
```

Ctrl+C never calls `Thread.stop` and never treats canceling a Java Future as process termination. Instead:

```text
set process.interrupt_requested
→ checked at the next statement transaction or checkpoint
→ the process changes state according to FCL interrupt semantics
```

Durable working directories, FCL process context, and per-user command history are persisted per session and survive reconnects and Runtime restarts.

**Disconnect awareness.** Interactive terminal sessions use an input pump: end-of-stream wakes
the blocked session loop and interrupts foreground work. A 60-second socket timeout is only a
periodic wake-up for checking the durable idle policy; authenticated sessions close after their
attached process remains PAUSED for the configured threshold. Headless submissions do not
currently run this concurrent disconnect pump, so work may continue after the client leaves.
`:cd` with no argument reports a clean error instead of crashing the terminal.

**Administrator password verification is rate-limited.** Failed logins apply an exponential backoff; unknown usernames verify a dummy credential (to keep timing uniform) and share a separate, lower ceiling so one attacker cannot slow unrelated logins.

**Bootstrap never deletes capabilities.** `TerminalBootstrap` merges the `SYSTEM_ADMIN` capability into the administrator account's existing capability set and never removes capabilities assigned through other channels.

---

## 17. Audit, Logging, and Health Checks

### 17.1 Audit

Structured audit events go into PostgreSQL:

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

Retention is configured per event type; expired events are purged by the maintenance loop.

### 17.2 Runtime Logs

Ordinary runtime logs go to:

```text
stdout
stderr
```

No permanent log files are kept inside the container, and logs are not all written to PostgreSQL.

### 17.3 Health Endpoints

Two endpoints are distinguished:

```text
liveness
= JVM process, control-connection monitor, and core execution loop alive

readiness
= PostgreSQL available
+ schema/migration version matches
+ advisory lock held
+ recovery complete
+ Runtime accepts work
```

The HTTP server binds to `127.0.0.1` only; the endpoints are never reachable from other containers or hosts.

---

## 18. Startup, Shutdown, and Recovery

### 18.1 Startup

```text
1. load non-secret configuration
2. read credentials from secret files
3. establish database connections
4. acquire the session advisory lock
5. optionally run pending migrations with migrator credentials
6. verify PostgreSQL availability and schema version (failed or out-of-range migrations refuse startup)
7. create meta.kernel_instance
8. create meta.boot
9. mark the previous boot CRASHED
10. run semantic recovery (RecoveryCoordinator)
11. start the scheduler
12. start effect workers
13. start IPC/timers
14. start terminal/API
15. readiness = true
```

### 18.2 SIGTERM

```text
1. readiness = false
2. stop claiming new processes
3. wait for in-flight statement transactions to commit
4. stop claiming new effects
5. ask current tasks to finish at their current checkpoint
6. release leases
7. close terminal output
8. mark CLEAN shutdown
9. release the advisory lock
10. exit
```

A bounded grace period is set. If it expires, the process may be force-killed; the next startup treats that as crash recovery.

### 18.3 Crash Recovery

PostgreSQL first completes its database-layer WAL recovery; CilExec then performs semantic recovery:

```text
mark the previous boot CRASHED
discard old runners
invalidate old leases
recover processes from their last committed continuation
scan for due timers
recover PENDING/RESERVED IPC deliveries
recover WAITING_EFFECT processes
inspect UNKNOWN effects
recover terminal attachments
restart FIFO scheduling
```

---

## 19. Backup, Restore, and Export

### 19.1 A Volume Is Not a Backup

A Docker volume or container writable layer only indicates where data is stored; it does not protect against:

```text
accidental deletion
logical corruption
host disk failure
container data-directory damage
PostgreSQL major-version incompatibility
```

### 19.2 Production Disaster Recovery

Production recovery should use physical backups plus continuous WAL archiving when the chosen
PostgreSQL platform supports them. An encrypted `pg_dumpall --roles-only` export and custom
format `pg_dump` remain portability and fallback artifacts. The bundled Compose files do not
configure a backup repository, WAL archive command, or restore automation; operators must
provide and test those facilities. Repository automation currently verifies logical
`pg_dump`/`pg_restore`, not physical restore or PITR. See `production-backup-restore.md`.

### 19.3 Major-Version Upgrades

The first release uses:

```text
dump
→ new-major-version PostgreSQL
→ restore
→ migration
→ CilExec recovery verification
```

Mounting an old-major-version volume onto a new-major-version image is forbidden, as is a floating `postgres:latest` tag.

### 19.4 CilExec Application-Level Export

A standalone CilExec logical export produces one verified `.db` file containing only durable semantic state — never:

```text
active connections
locks
caches
in-flight database transactions
WAL history
container IDs
Java Threads, virtual threads, Futures, or Executor tasks
```

The export is written to a temporary file, verified against the snapshot manifest, atomically published by hard link (refusing to overwrite an existing file), and only then marked read-only — so the read-only attribute can never block publication. Export filenames may contain `?`; they are treated as literal path characters rather than URI query syntax.

Application-level export and PostgreSQL disaster backups are two different product capabilities.

---

## 20. Test Strategy

Database tests must use a real PostgreSQL container; H2 or SQLite must never simulate PostgreSQL.

### 20.1 Required Test Categories

```text
migration (including failed/out-of-range migration detection)
RLS
role switching
per-slice transactions
state_version conflicts
execution_epoch fencing
FIFO claiming
lease expiry and heartbeat extension
IPC committed-consumption transition and reserve/ack redelivery
broadcast fan-out
timer recovery and FIRED cleanup
VFS atomic replacement
package coordinate-pollution rejection
package hash determinism
package capability policy
effect UNKNOWN and stall reclamation
effect result committed without wake
SIGTERM
force kill
logical pg_dump/restore
dual-architecture images
```

### 20.2 Forced Crash Points

Required fault-injection targets (not all currently covered by automated tests):

```text
right after BEGIN
after variable writes
before continuation advancement
after IPC message insertion
during delivery generation
before COMMIT
after COMMIT
before effect execution
after external success but before the result is written back
after package object insertion
before the install binding commits
```

### 20.3 Performance

The first release does not invent numbers up front.

Process:

```text
finish a minimal runnable version
→ build a repeatable benchmark
→ measure the baseline
→ freeze next-phase targets
```

The baseline at least includes:

```text
idle memory
startup-to-readiness time
per-slice transaction throughput
process recovery time
1k/10k WAITING process resources
IPC direct/channel/broadcast throughput
VFS bytea read/write
package import
database growth rate
ARM64 vs AMD64 differences
```

---

## 21. Historical Implementation Phases

The following phases document the completed rebuild sequence. They are retained for design
provenance; their imperative language is historical, not a current backlog.

## Phase 0: Freeze Legacy Semantics and the Java Database Rebuild Baseline

```text
keep the pre-refactor tag
continue refactoring on main
confirm and pin the current JDK and Maven versions
organize the existing Maven project and Java package boundaries
pin PostgreSQL JDBC, SQLite JDBC, HikariCP, and Flyway versions
build the FCL behavior regression-test list
inventory all legacy persisted state and external side effects
```

Exit conditions:

```text
the legacy implementation remains recoverable via the tag
the Maven project builds and tests on a macOS ARM64 dev machine
   and Linux AMD64/ARM64 CI
current FCL regression tests form the pre-refactor baseline
Docker builds produce a bootable Java image skeleton
```

## Phase 1: Docker and PostgreSQL Infrastructure

```text
postgres/migrate/cilexec three services
ephemeral and persistent profiles
secrets
healthcheck
Flyway
roles
comprehensive RLS test framework
```

Exit conditions:

```text
an empty database migrates automatically
the Runtime reaches readiness after acquiring the control lock
```

## Phase 2: Meta, Auth, and Control

```text
meta.instance
meta.boot
meta.kernel_instance
user ↔ PostgreSQL NOLOGIN tenant role
SET LOCAL ROLE
RLS
advisory lock
```

## Phase 3: Programs, Processes, and Per-Slice Transactions

```text
shared immutable programs
full continuations
current variable values
PIDs never reused
state_version
execution_epoch
```

Exit conditions:

```text
a force kill after any committed FCL statement recovers correctly
```

## Phase 4: FIFO Scheduler and Leases

```text
queue
FIFO process-row locking with SKIP LOCKED
runner
heartbeat
expires_at
reclaim and stale-epoch rejection
```

## Phase 5: IPC, Topics, Broadcasts, and Timers

```text
message
delivery
channel
topic
subscription
fan-out
timer
```

## Phase 6: VFS and Object Store

```text
node
bytea object
content addressing
atomic replacement
optional revisions
host mounts
```

## Phase 7: SQLite Packages and Environments

```text
SQLite package `.db` schema
canonicalized package_hash
unique coordinates
full db in object_store
environments
bindings
exact per-process hashes
```

## Phase 8: Effects, Terminal, and Audit

```text
effect journal
separate worker role
UNKNOWN handling
committed terminal input
persistent Ctrl+C
audit retention policy
```

## Phase 9: Backup, Export, and Hardening

```text
pg_dump/restore
application-level .db export
multi-architecture images
crash matrix
performance baseline
delete all legacy file persistence code
```

---

## 22. Explicitly Out of Scope for the First Release

```text
bare metal or a custom kernel
a second language runtime or long-term dual implementations
multiple active Runtimes
PostgreSQL HA clusters
cross-database transactions
automatic schema downgrade
arbitrary package lifecycle hooks
switching running processes to a new package_hash automatically
SQLite simulating PostgreSQL in tests
permanent per-change audit of ordinary variables
reusing a PostgreSQL major-version volume directly
relying on host file paths as package truth
```

---

## 23. Prohibitions

```text
no dual-write to legacy files and PostgreSQL
no process advancement while the database is disconnected
no PostgreSQL superuser for the Runtime
no schema modification by the Runtime in production
no bypassing RLS
no holding transactions during long external operations
no HTTP/socket/host commands inside database transactions
no last-writer-wins overwriting of process state
no modifying a published package `.db` artifact
no writing back into the package database
no same-coordinate/different-package_hash mappings
no silently upgrading a running process's package binding
no treating the SQLite package cache as truth
no describing the container writable layer as reliable durable storage
no treating a volume as a backup
```

---

## 24. Definition of Done

The rebuild is complete only when all of the following hold:

```text
the CilExec core remains implemented in Java
existing FCL external semantics stay compatible
legacy file persistence is removed from the production path
Compose is the standard environment from phase one
PostgreSQL is the only authoritative state
every execution slice has an explicit transaction
full continuations are recoverable
a single active Runtime is protected by the advisory lock
stale epochs cannot commit
CilExec users map to PostgreSQL NOLOGIN, NOINHERIT tenant roles
business tables use comprehensive RLS
FIFO scheduling and leases are recoverable
IPC direct/channel/topic/broadcast recover durably
timers do not depend on in-memory sleep
VFS content lives in a content-addressed object store
the package `.db` artifact is immutable SQLite
the same coordinates never map to different hashes
exact per-process package bindings work
FCL-requested HTTP/socket/command effects pass through the effect journal
audit is separated from ordinary logs
SIGTERM and force kill are both tested
logical pg_dump/restore has automated restore tests
application-level .db export is verifiable
CI builds AMD64 and ARM64 images; Runtime verification runs on the GitHub-hosted AMD64 matrix
legacy .proc and legacy file persistence code are deleted
```

---

## 25. Frozen Decision Register

| # | Priority | Question | Final decision | Notes |
|---|---|---|---|---|
| 1 | P0 | Change the language this round? | A. Keep Java; only refactor persistence and deployment | Final revision: no language rewrite |
| 2 | P0 | Official positioning of CilExec | B. Database-driven user-space operating system |  |
| 3 | P0 | How many databases per CilExec instance | B. One PostgreSQL database per instance |  |
| 4 | P0 | How many active Kernels per database | A. Exactly one active Kernel |  |
| 5 | P0 | Is the database the only source of truth | A. PostgreSQL is the only source of truth |  |
| 6 | P0 | Status of Java in-memory state | B. Database state is true; memory is a rebuildable projection |  |
| 7 | P0 | PostgreSQL and CilExec in one container? | B. Two separate containers |  |
| 8 | P0 | Dedicated migration service? | B. A separate migrate container runs migrations first |  |
| 9 | P0 | Where does Docker persistence live | C. Container writable layer | Disposable dev profile only; persistent instances must mount durable storage |
| 10 | P1 | Docker network exposure | PostgreSQL is Compose-network-only by default | Supersedes the earlier host-port choice; add an explicit loopback override for development access |
| 11 | P0 | Startup ordering | B. Migrate after PostgreSQL is healthy; start CilExec after migration succeeds |  |
| 12 | P1 | Behavior on SIGTERM | B. Run normal shutdown with a bounded grace period |  |
| 13 | P1 | Container runs as root? | B. Fixed non-root user |  |
| 14 | P1 | Supported image architectures | C. Publish both amd64 and arm64 |  |
| 15 | P1 | Where the package local cache lives | C. tmpfs or a disposable cache directory |  |
| 16 | P0 | Number of PostgreSQL system roles | B. owner, migrator, kernel, effect-worker, readonly separated |  |
| 17 | P0 | Do normal CilExec users map to PostgreSQL roles | Every CilExec user has a stable NOLOGIN, NOINHERIT tenant role | Runtime assumes it with SET LOCAL ROLE; terminal passwords are not database credentials |
| 18 | P0 | May the Kernel modify database structure | C. Development yes, production no |  |
| 19 | P2 | Enable Row-Level Security in the first release | A. Comprehensively, on all business tables |  |
| 20 | P0 | How database passwords are provided | C. Docker secrets or an external secret manager |  |
| 21 | P0 | How the single active Kernel is guaranteed | B. PostgreSQL session advisory lock |  |
| 22 | P0 | What happens when the control connection drops | B. Stop claiming and committing, enter fenced state, exit the container |  |
| 23 | P1 | Pure computation allowed during a temporary outage | B. Freeze all processes immediately |  |
| 24 | P0 | Does every FCL statement map to a database transaction | One transaction per execution slice | Non-terminal: one interpreter step; terminal: up to 4,096 steps or 20 ms |
| 25 | P1 | Execution-quantum termination conditions | Suspension, directive, completion, failure, step limit, or time limit | Database-visible calls do not all force an immediate boundary |
| 26 | P0 | Forced checkpoint operations | Blocking/effect suspension and the end of the current slice | VFS and IPC writes commit or roll back with the slice |
| 27 | P0 | Default transaction isolation | A. READ COMMITTED | per overall plan consistency |
| 28 | P0 | Conflict handling | B. state_version optimistic concurrency with necessary row locks |  |
| 29 | P1 | Auto-retry deadlocks/serialization failures | C. Bounded retries with jitter |  |
| 30 | P0 | Define a global lock order? | B. The document defines one global lock order |  |
| 31 | P0 | Is process code copied per process | B. Immutable programs are shared; processes reference program_id |  |
| 32 | P1 | Program identity | C. Content hash + internal ID |  |
| 33 | P0 | Does a process store the full continuation | B. Full interpreter continuation |  |
| 34 | P1 | PID identity rule | A. PIDs never reused |  |
| 35 | P0 | Handling of RUNNING processes after a crash | C. Classify by last checkpoint and wait reason |  |
| 36 | P2 | How long terminated processes are retained | C. Keep metadata; archive or clean heavy state periodically | retention and cleanup configured |
| 37 | P1 | First-release scheduling policy | A. FIFO |  |
| 38 | P0 | Queue claiming mechanism | Ordered lockless candidate selection plus process CAS | Queue claim and lease commit in the same transaction |
| 39 | P1 | First-release worker count | C. Configurable, small by default |  |
| 40 | P0 | Must leases expire | B. Expiry plus heartbeat |  |
| 41 | P0 | First-release file content storage | Immutable object or linked manifest chunks | 1 GiB logical limit, 4 MiB ranges, 16 MiB whole FCL read |
| 42 | P1 | Keep all historical file versions by default | C. Current version only by default; selected types versioned |  |
| 43 | P0 | Use content addressing | B. Nodes point at immutable content objects |  |
| 44 | P0 | Does the Object Store also hold package .db files | C. package.release only references object_store |  |
| 45 | P1 | Allow host-directory VFS mounts | Reserved schema/service model; not wired in standard deployment | Supported host feature is one-file `host move` |
| 46 | P0 | Package .db storage format | A. SQLite |  |
| 47 | P0 | Final package identity | C. canonicalized logical-content package_hash |  |
| 48 | P0 | May one coordinate map to multiple hashes | B. Absolutely forbidden | immutable releases and version uniqueness |
| 49 | P0 | Does PostgreSQL store the full package .db | A. Store the full bytes |  |
| 50 | P0 | Content authority after import | A. The original .db is authoritative; indexes are derived data |  |
| 51 | P0 | What "install" means | Register an immutable release plus a per-user installation closure | Receipts and managed VFS cache files are not import authority; process bindings are created by import/run |
| 52 | P0 | Package environments needed | No `package.environment` table in the current schema | Per-user installation ledgers and process bindings provide the required isolation |
| 53 | P0 | Does a process pin the hash when importing | B. Exact hash written after first resolution |  |
| 54 | P0 | Package lifecycle hooks | A. Completely forbidden in the first release |  |
| 55 | P1 | Package integrity policy | B. Only file hash and logical-content hash; no trust state |  |
| 56 | P0 | Where package mutable data lives | B. A separate data scope in VFS |  |
| 57 | P0 | IPC delivery semantics | At-most-once committed CONSUMED transition | Reserve/ack processing can redeliver after a crash before consume |
| 58 | P1 | Message consumption state machine | B. PENDING → RESERVED → CONSUMED / FAILED / DEAD |  |
| 59 | P1 | Broadcast and channel support | C. Broadcasts, topics, and subscriptions |  |
| 60 | P1 | Authoritative timer representation | B. Database timer rows; Java wakes them |  |
| 61 | P1 | Terminal input granularity | B. Complete committed inputs only |  |
| 62 | P0 | How Ctrl+C is expressed | B. Set a persistent interrupt_requested, handled at checkpoints |  |
| 63 | P0 | Do all external operations enter the effect system | FCL HTTP/socket/command operations do | Timers and explicit host tools use separate durable contracts |
| 64 | P0 | Effect re-execution policy | B. Every effect type declares idempotency and recovery policy |  |
| 65 | P1 | How UNKNOWN effects are handled | C. Per effect type; manual intervention when undeterminable |  |
| 66 | P1 | Do effect workers use a separate role | B. Separate cilexec_effect_worker |  |
| 67 | P0 | Separate audit events from ordinary logs | B. Audit to database; runtime logs to stdout/stderr |  |
| 68 | P2 | Audit retention duration | C. Configured per event type |  |
| 69 | P2 | Record every variable modification | C. Only security, admin, and externally visible events |  |
| 70 | P1 | Docker logging mode | B. stdout/stderr |  |
| 71 | P1 | Provide health endpoints | C. Distinguish liveness and readiness |  |
| 72 | P0 | Is a PostgreSQL volume a backup | B. No |  |
| 73 | P1 | Disaster-recovery backup format | Physical backup + continuous WAL for production; logical dumps as fallback | Bundled Compose does not configure either automatically; only logical restore is currently automated in tests |
| 74 | P1 | Does CilExec provide its own logical export | B. Export an application-level .db artifact |  |
| 75 | P0 | Export all of PostgreSQL's runtime state | B. Export only durable semantic state |  |
| 76 | P1 | PostgreSQL major-version upgrade strategy | B. dump/restore |  |
| 77 | P0 | What is used for database tests | C. Real PostgreSQL test containers |  |
| 78 | P0 | Real forced-crash tests | B. Verify recovery after force-killing the JVM and container |  |
| 79 | P1 | Package determinism tests | C. Test both hashes separately |  |
| 80 | P1 | Docker test platforms | C. CI builds Linux amd64 and arm64 images | Runtime verification currently runs on the GitHub-hosted AMD64 matrix |
| 81 | P1 | Performance benchmark targets | A. Finish a runnable version, measure the baseline, then set targets |  |
| 82 | P0 | Keep a legacy data migrator | A. No |  |
| 83 | P0 | Rewrite directly on the same branch | A. Modify main directly | pre-refactor tag established; continue on main |
| 84 | P1 | Schema versioning tool | B. Flyway |  |
| 85 | P0 | Automatic schema downgrade allowed | B. Forbidden; forward migrations only |  |
| 86 | P0 | Docker included from phase one | B. Compose is the standard dev environment from phase one |  |

---

## 26. Final Architecture Summary

```text
Host Linux / Docker
│
├── PostgreSQL
│   └── one database = one CilExec instance
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
    ├── Per-user package installation ledger and private data
    ├── Effect worker
    ├── Terminal
    ├── Auth/RLS
    └── Audit

Committed PostgreSQL state
=
the true state of the CilExec instance

Java/JVM in-memory objects
=
disposable execution projections, rebuildable from the database

package `.db` artifact
=
immutable SQLite package artifacts
```

Final principle:

> **Java drives execution, PostgreSQL stores the instance truth, Docker provides the deployable host boundary, and immutable SQLite `.db` files carry packages.**
