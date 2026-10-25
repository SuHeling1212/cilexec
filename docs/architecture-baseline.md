# CilExec Runtime architecture

The supported 0.0.5 distribution is a Java 26 executable JAR. On macOS and Linux it
starts its bundled PostgreSQL 17.6 database and stores all instance data in
`cilexec-data/` beside the JAR. An externally managed PostgreSQL database is optional.
The separate market server uses Java 21.

## State and recovery

PostgreSQL is the authoritative store for programs, complete FCL continuations,
process identities, scheduler queues and leases, IPC, timers, VFS nodes, package
installations and bindings, private package data, external effects, terminal input,
and audit events. There is no host-directory process snapshot.

Each execution slice commits in one transaction. An interrupted slice rolls back and
may replay. Disposable JVM workers, caches, and notifications can be rebuilt from
committed rows. A database advisory lock permits one active Runtime per database;
`state_version` and `execution_epoch` fence stale workers. Scheduler and effect
workers have bounded concurrency. Runnable work remains in a durable FIFO queue.

## Execution pipeline

```text
FCL source → compiler → continuation program
           → ProcessStatementExecutor → expression evaluator
           → JDBC transaction → committed continuation
```

The compiler and interpreter live in `com.follarce.fcl`. The application layer
registers database-aware built-ins and runs process slices. A normal process slice
advances one interpreter step; a terminal slice may batch up to 4096 steps or 20 ms
and stops sooner on suspension, directive, completion, or failure. External effects
use a journal and separate workers. IPC, timers, terminal input, and package bindings
are durable.

FCL 0.0.5 uses system entity handles for files, processes, IPC, pools, packages, and
administrative entities. A handle serializes a type, stable identity, and any required
lease information. Methods recheck existence, caller identity, capabilities, and
database permissions. User class instances retain value-copy semantics. See the
[function reference](fcl-function-reference.md) and
[object guide](fcl-object-oriented-guide.md).

## Storage and permissions

VFS content uses SHA-256 addressed objects in PostgreSQL. Packages are immutable
SQLite `.db` artifacts with exact-hash dependencies and per-user installation
ledgers. Host files enter through the explicit `host move` application command.

Capabilities are checked in the application. Users map to stable PostgreSQL
`NOLOGIN`, `NOINHERIT` roles; forced row-level security protects user tables.
`SYSTEM_ADMIN` has an explicit policy over application data but no PostgreSQL
cluster-superuser or `BYPASSRLS` privilege.

## Version boundary

0.0.5 uses FCL program and continuation format 5, package database format 3,
and schema V005. Startup checks the format before any upgrade write and refuses
older local data directories or external databases. It does not delete, convert, or
migrate them. The applied V001 baseline and V002–V005 migrations are immutable;
future schema changes require a new forward migration.

## Source map

| Package | Responsibility |
| --- | --- |
| `com.follarce.app` | Startup, shutdown, application commands, runtime assembly |
| `com.follarce.fcl` | Compiler, evaluator, continuation codecs, built-ins |
| `com.follarce.application` | Transactional statement execution and runtime functions |
| `com.follarce.persistence.postgres` | Data sources, migrations, repositories, transactions |
| `com.follarce.scheduler` | Durable FIFO scheduler and lease workers |
| `com.follarce.effect` | Journaled external effects and workers |
| `com.follarce.ipc`, `com.follarce.timer` | Messaging and timers |
| `com.follarce.vfs` | Virtual file system |
| `com.follarce.package_manager` | Immutable packages and private data |
| `com.follarce.auth`, `com.follarce.audit` | Authentication and audit |
| `com.follarce.terminal`, `com.follarce.health` | Console and loopback health endpoints |

Build and run instructions are in the [root README](../README.md).
Operational backup and restore instructions are in the
[runbook](production-backup-restore.md).
