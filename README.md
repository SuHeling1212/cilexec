# CilExec

**CilExec** is a durable scripting and process-management engine. It runs **FCL** (Follarce
CilExec Language) programs on a PostgreSQL-backed runtime where **PostgreSQL is the only
authoritative state**. After a crash, the Runtime reconstructs work from committed database
rows. An in-flight transaction is rolled back and its execution slice may run again.

> Why it exists: ordinary scripting runtimes keep process state in memory and lose it on
> crash. CilExec commits each execution slice in one explicit database transaction, so
> committed scripts, timers, message queues, and external-effect records survive restarts
> and multi-tenant isolation is enforced by the database itself (forced Row-Level Security).

## Highlights

- **Zero memory state** — programs, continuations, scheduler leases, IPC, timers, VFS,
  package bindings, effect journals, terminal input, and audit events all live in PostgreSQL;
  one advisory lock guarantees a single active runtime per database.
- **Crash-safe continuations** — the full interpreter state (variables, call stack, program
  counter) is serialized after every committed execution slice; `state_version` +
  `execution_epoch` fencing prevents stale workers from committing.
- **Multi-tenant by design** — every user maps to a stable PostgreSQL `NOLOGIN`, `NOINHERIT`
  tenant role with forced
  RLS; `SYSTEM_ADMIN` is an application-level superuser that never receives cluster or host
  privileges.
- **Content-addressed VFS** — mutable namespace nodes point to immutable SHA-256-addressed
  file or symlink content; directories and mounts are relational metadata. Cross-user
  administration is audited through
  `SECURITY DEFINER` APIs.
- **Immutable packages** — offline-built SQLite `package.db` releases bound by exact SHA-256;
  imports use the exact database-file hash.
- **Journaled external effects** — FCL HTTP, socket, and allow-listed command requests suspend
  the continuation and resume through durable effect rows. An interrupted external outcome
  can become `UNKNOWN` and is resolved by query, idempotent retry, or manual action. Timers
  use their own durable timer rows.
- **Durable IPC & timers** — direct/channel/topic/broadcast messaging and timers that wake
  paused processes across restarts.
- **Verified exports** — logical PostgreSQL → SQLite export from one read-only snapshot,
  hash-verified end to end.
- **Human-friendly terminal** — an interactive REPL whose process (variables, imports,
  functions, working directory) persists across logout/login and Runtime restarts, plus a
  headless protocol for host scripting.
- **Forward-only schema upgrades** — the 1.0 V001 baseline is frozen; every later persisted
  format change ships as an immutable Flyway V002+ migration and downgrades restore a backup.

## Quick start

Requirements: JDK 26, Maven 3.9+, PostgreSQL 17.1+.

```bash
./tools/Install.sh            # one command: secrets + PostgreSQL + migrations + terminal
```

On first use the terminal asks for the administrator password (username `local` by default).
Then try FCL directly:

```fcl
io.println("hello cilexec")
sum = 0; i = 1
while i <= 10 { sum = sum + i; i = i + 1 }
return sum
```

Run one-off submissions from the host without the interactive shell:

```bash
./tools/Headless.sh 'value = 41'
./tools/Headless.sh 'io.println(value + 1)'     # 42; same durable REPL process per host terminal
```

Production operators should use the signed release image/artifacts and follow
[release verification](docs/release.md) and the
[backup, restore, and credential rotation runbook](docs/production-backup-restore.md).

When using Docker Desktop, give its Linux VM at least 10 GB of memory (Settings →
Resources → Advanced) on a 16 GB host. The Runtime JVM, PostgreSQL, and the
`mvn verify` integration suite each need a few GB; with the default 7.8 GB VM the
host can run out of memory and the Java tooling or Runtime can be killed.

Without Docker, run the JAR directly (a database must exist first):

```bash
java -jar target/cilexec-app.jar migrate
java -jar target/cilexec-app.jar terminal
```

## Core concepts

| Concept | What it is |
|---|---|
| **FCL** | The scripting language: `//` comments, `#` length operator, reserved keywords, functions, loops, maps/lists, package imports. Full reference: [docs/fcl-function-reference.md](docs/fcl-function-reference.md) |
| **Process & continuation** | A process is durable; its full interpreter state is persisted after each committed slice. An uncommitted slice rolls back and may replay after recovery |
| **VFS** | Per-user content-addressed file tree at `/`, with `/Users/<name>` visible to administrators; host files enter via `host move` |
| **Packages** | Immutable SQLite databases with declared capabilities, entrypoints, and exact-hash dependencies |
| **Effects** | Journaled external operations (HTTP, sockets, host commands) that survive crashes and recover by policy |

## Architecture at a glance

```
FCL source → compiler → ProcessStatementExecutor (one transaction per execution slice)
          → scheduler workers (bounded, lease-based FIFO) → PostgreSQL (only state store)
          → effect workers (journaled side effects) · timers · IPC · terminal · VFS
```

Non-terminal slices execute one interpreter step. Terminal slices may batch up to 4,096 steps
or 20 ms and stop on suspension, directive, completion, or failure. Everything else — pools,
threads, caches — is disposable and rebuilt from committed rows on
startup. Source layout and design decisions: [docs/architecture-baseline.md](docs/architecture-baseline.md).

## Repository map

```text
src/main/java/com/follarce
  app/            startup, shutdown, application commands
  fcl/            FCL compiler, continuation runtime, built-ins
  application/    database-aware built-ins, statement executor, REPL
  persistence/    JDBC repositories, transactions, SQLite package reader
  scheduler/      bounded FIFO/lease workers
  effect/         journaled external-effect workers
  ipc/ timer/     durable messaging and timers
  vfs/            content-addressed VFS use cases
  package_manager/ market/   immutable packages and the market client
  exporter/       verified PostgreSQL → SQLite export
  terminal/       interactive and headless control plane
  auth/ audit/ health/ config/ extension/   security, ops, and extension surfaces
src/main/resources/db/baseline/   frozen V001 modules (roles, RLS, SQL functions)
```

## Build & test

```bash
mvn clean test        # 293+ unit and lifecycle tests
mvn clean verify      # mandatory PostgreSQL/crash-recovery ITs + quality gates + JAR
```

## Documentation

- [FCL function & terminal reference](docs/fcl-function-reference.md)
- [Architecture baseline](docs/architecture-baseline.md)
- [Java source extensions](docs/java-extension-development.md)
- [Package & market](docs/package-market.md)
- [Headless mode](docs/headless-mode.md)
- [Host-to-VFS import](docs/host-vfs-import.md)
- [Terminal & administration](docs/terminal-and-admin-plan.md)
- [Release process](docs/release.md)

## License

[MIT](LICENSE)
