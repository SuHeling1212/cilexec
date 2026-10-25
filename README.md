# CilExec

**CilExec** is a durable scripting and process-management engine. It runs **FCL** (Follarce
CilExec Language) programs on a PostgreSQL-backed runtime where **PostgreSQL is the only
authoritative state**. After a crash, the Runtime reconstructs work from committed database
rows. An in-flight transaction is rolled back and its execution slice may run again.
> **Development note:** CilExec was developed with the use of AI, while the project's architecture, design, review, and decision-making have always been human-led.


> Why it exists: ordinary scripting runtimes keep process state in memory and lose it on
> crash. CilExec commits each execution slice in one explicit database transaction, so
> committed scripts, timers, message queues, and external-effect records survive restarts
> and multi-tenant isolation is enforced by the database itself (forced Row-Level Security).

## Highlights

- **Zero memory state** — programs, continuations, scheduler leases, IPC, timers, VFS,
  package bindings, effect journals, terminal input, and audit events all live in PostgreSQL;
  one advisory lock guarantees a single active runtime per database.
- **Crash-safe continuations** — the full interpreter state (variables, call stack, program
  counter, system entity handles) is serialized after every committed execution slice;
  `state_version` + `execution_epoch` fencing prevents stale workers from committing.
- **Responsive durable terminal** — post-commit process notifications replace the former
  25 ms terminal polling loop. Full-screen FCL applications may use `term.render` to publish
  disposable repaint frames after state commit, while ordinary `io.print` retains its
  recoverable effect-journal path.
- **Immutable executable artifacts** — new programs retain readable FCL source and a separate
  versioned FCLB instruction artifact; recovery validates both and runs the original compiled
  instruction table without recompiling source. Nothing is deleted on a schedule: programs,
  timers, terminal sessions, and audit history are removed only by explicit administrator
  operations.
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
- **Explicit, forward-only 0.0.5 format** — programs, continuations, packages, and the database
  schema have one current format each. Old persistent data are **refused with an explanation**
  at startup; they are never converted, deleted, or migrated in place.

## Quick start

### Standalone JAR (macOS or Linux) — the supported distribution

Install Java 26, then build or place the shaded `cilexec-app.jar` in the directory where the
instance should live:

```bash
mvn package
java -jar target/cilexec-app.jar
```

No Docker, separately installed PostgreSQL, Compose files, or database password files are
needed. The JAR contains PostgreSQL 17.6 binaries for macOS and Linux on ARM64 and AMD64.
First startup creates `cilexec-data/` beside the JAR, initializes the database and the current
schema, and connects the launching TTY to the terminal. Routine startup logs are hidden by
default; set `CILEXEC_LOG_LEVEL=INFO` to see them. Later starts reuse the same data. Keep the
JAR and `cilexec-data/` together when moving an instance; back up `cilexec-data/` with the
program stopped. Startup prints a progress line immediately; first initialization may take
several tens of seconds. Maven clean preserves `target/cilexec-data` when running the JAR from
`target/`. The default terminal username is `local`; it asks for an administrator password on
first use.

A JAR must own its data directory: run it from a dedicated directory, not from the repository
root. To start a second instance, copy the JAR to another directory. 0.0.5 **refuses to start**
on a `cilexec-data/` created by another CilExec release and explains that the old data must be
archived or removed by the operator; the Runtime never modifies it.

Setting `CILEXEC_DATABASE_URL` selects the externally managed PostgreSQL mode, which still
requires configured credentials and an explicit `migrate` command. Old external databases are
refused by the same format fence before any migration runs.

### Source checkout

Running from a source checkout requires JDK 26 and Maven 3.9+; Docker is not used.

```bash
mvn package
mkdir -p /tmp/cilexec-instance
cp target/cilexec-app.jar /tmp/cilexec-instance/
cd /tmp/cilexec-instance
java -jar cilexec-app.jar
```

On first use the terminal asks for the administrator password (username `local`). Then try
FCL directly:

```fcl
io.println("hello cilexec")
sum = 0; i = 1
while i <= 10 { sum = sum + i; i = i + 1 }
return sum

// 0.0.5 entity objects: get the object once, then call methods on it.
note = file.create("/notes/hello.txt", "first")
note.append(" second")
io.println(note.read())   // first second
```

Type `:exit` to disconnect the terminal. The shared Runtime and background processes keep
running; use `:shutdown` (administrator password required) to stop the Runtime. Stop the JAR
with `:shutdown` or SIGTERM rather than a hard kill: a surviving embedded PostgreSQL is
reported with its PID on the next start, which asks you to stop it first instead of waiting
for a timeout.

Production operators should keep the JAR and its `cilexec-data/` directory together, stop the
Runtime before backups, and follow the
[backup, restore, and credential rotation runbook](docs/production-backup-restore.md).
The Docker-image distribution and its installer scripts are deprecated as of 0.0.5 and are no
longer required or tested for normal operation.

## Core concepts

| Concept | What it is |
|---|---|
| **FCL** | The scripting language: `//` comments, `#` length operator, reserved keywords, functions, loops, maps/lists, classes, package imports, and entity-object method calls. Full reference: [docs/fcl-function-reference.md](docs/fcl-function-reference.md) |
| **Process & continuation** | A process is durable; its full interpreter state (including system entity handles) is persisted after each committed slice. An uncommitted slice rolls back and may replay after recovery |
| **VFS** | Per-user content-addressed file tree at `/`, with `/Users/<name>` visible to administrators; host files enter via `host move` |
| **Packages** | Immutable SQLite databases with declared capabilities, entrypoints, and exact-hash dependencies |
| **Effects** | Journaled external operations (HTTP, sockets, host commands) that survive crashes and recover by policy |

## Architecture at a glance

```
FCL source → compiler → ProcessStatementExecutor (one transaction per execution slice)
          → scheduler workers (bounded, lease-based FIFO) → PostgreSQL (only state store)
          → effect workers (journaled side effects) · timers · IPC · terminal · VFS
```

Every process slice may batch up to 4,096 interpreter steps or 20 ms and stops on suspension,
directive, completion, or failure. Everything else — pools, threads, caches — is disposable and rebuilt from committed rows on
startup. Source layout and design decisions: [docs/architecture-baseline.md](docs/architecture-baseline.md).

## Repository map

```text
src/main/java/com/follarce
  app/            startup, shutdown, application commands
  fcl/            FCL compiler, continuation runtime, built-ins, entity handles
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
src/main/resources/db/contracts/  immutable V002+ contract/fence SQL
```

## Build & test

```bash
mvn test              # unit and lifecycle tests (no Docker required)
mvn package -DskipTests   # build target/cilexec-app.jar
mvn verify            # optional: PostgreSQL/crash-recovery integration tests (Docker)
```

The integration suite requires Docker and is not part of the standalone-JAR acceptance path;
`mvn test` plus the standalone JAR smoke tests are sufficient for normal development.

## Documentation

- [FCL function & object reference](docs/fcl-function-reference.md) · [中文](docs/fcl-function-reference.zh-CN.md)
- [FCL 0.0.5 object guide](docs/fcl-object-oriented-guide.md) · [中文](docs/fcl-object-oriented-guide.zh-CN.md)
- [FCL 0.0.4 reference (historical)](docs/fcl-function-reference-004.md)
- [FCLB executable artifacts](docs/fcl-executable-artifact.md)
- [Release version source](docs/versioning.md)
- [Architecture baseline](docs/architecture-baseline.md)
- [Java source extensions](docs/java-extension-development.md)
- [Package & market](docs/package-market.md)
- [Durable FCL Snake game](docs/snake-game.md)
- [Headless mode](docs/headless-mode.md)
- [Host-to-VFS import](docs/host-vfs-import.md)
- [Terminal & administration](docs/terminal-and-admin-plan.md)
- [Release process](docs/release.md)

## License

[MIT](LICENSE)
