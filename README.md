# CilExec

**CilExec** is a durable scripting and process-management engine. It runs **FCL** (Follarce
CilExec Language) programs on a PostgreSQL-backed runtime where **PostgreSQL is the only
authoritative state** — kill the engine at any moment and restart it: work is reconstructed
from committed database rows, with nothing lost.

> Why it exists: ordinary scripting runtimes keep process state in memory and lose it on
> crash. CilExec commits every semantic FCL statement in one explicit database transaction,
> so long-running scripts, timers, message queues, and external side effects survive restarts
> and multi-tenant isolation is enforced by the database itself (forced Row-Level Security).

## Highlights

- **Zero memory state** — programs, continuations, scheduler leases, IPC, timers, VFS,
  package bindings, effect journals, terminal input, and audit events all live in PostgreSQL;
  one advisory lock guarantees a single active runtime per database.
- **Crash-safe continuations** — the full interpreter state (variables, call stack, program
  counter) is serialized after every statement; `state_version` + `execution_epoch` fencing
  prevents stale workers from committing.
- **Multi-tenant by design** — every user maps to a stable PostgreSQL LOGIN role with forced
  RLS; `SYSTEM_ADMIN` is an application-level superuser that never receives cluster or host
  privileges.
- **Content-addressed VFS** — files, directories, symlinks, and mounts stored as immutable
  objects identified by SHA-256, with cross-user administration audited through
  `SECURITY DEFINER` APIs.
- **Immutable packages** — offline-built SQLite `package.db` releases bound by exact SHA-256;
  imports resolve either an environment binding or the exact database hash.
- **Journaled external effects** — HTTP, sockets, timers, and host commands suspend the
  continuation and resume through durable effect rows; outcomes survive crashes
  (idempotency keys, stalled-effect reclamation, heartbeat-protected workers).
- **Durable IPC & timers** — direct/channel/topic/broadcast messaging and timers that wake
  paused processes across restarts.
- **Verified exports** — logical PostgreSQL → SQLite export from one read-only snapshot,
  hash-verified end to end.
- **Human-friendly terminal** — an interactive REPL whose process (variables, imports,
  functions, working directory) persists across logout/login and Runtime restarts, plus a
  headless protocol for host scripting.
- **Zero-migration policy until first release** — schema changes are made in place in the
  single Flyway baseline; old formats are abandoned, not migrated.

## Quick start

Requirements: JDK 26, Maven 3.9+, PostgreSQL 17.1+.

```bash
./Install.sh                    # one command: secrets + PostgreSQL + migrations + terminal
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
./Headless.sh 'value = 41'
./Headless.sh 'io.println(value + 1)'     # 42; same durable REPL process per host terminal
```

Without Docker, run the JAR directly (a database must exist first):

```bash
java -jar target/cilexec-app.jar terminal
java -jar target/cilexec-app.jar migrate
```

## Core concepts

| Concept | What it is |
|---|---|
| **FCL** | The scripting language: `//` comments, `#` length operator, reserved keywords, functions, loops, maps/lists, package imports. Full reference: [docs/fcl-function-reference.md](docs/fcl-function-reference.md) |
| **Process & continuation** | A process is a durable object; its full interpreter state is persisted after every statement and resumed exactly where it stopped |
| **VFS** | Per-user content-addressed file tree at `/`, with `/Users/<name>` visible to administrators; host files enter via `host move` |
| **Packages** | Immutable SQLite databases with declared capabilities, entrypoints, and exact-hash dependencies |
| **Effects** | Journaled external operations (HTTP, sockets, host commands) that survive crashes and recover by policy |

## Architecture at a glance

```
FCL source → compiler → ProcessStatementExecutor (one durable statement per scheduling slice)
          → scheduler workers (bounded, lease-based FIFO) → PostgreSQL (only state store)
          → effect workers (journaled side effects) · timers · IPC · terminal · VFS
```

Everything else — pools, threads, caches — is disposable and rebuilt from committed rows on
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
src/main/resources/db/baseline/   single Flyway baseline (roles, RLS, SQL functions)
```

## Build & test

```bash
mvn clean test        # 270+ unit, lifecycle, and crash-recovery tests
mvn clean verify      # package target/cilexec-app.jar
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
