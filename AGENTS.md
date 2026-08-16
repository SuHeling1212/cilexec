# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Core Runtime: requires Java 26 and Maven 3.9+
mvn compile

# Unit tests (`*Test`; no Testcontainers integration suites)
mvn test

# Full verification (`*Test` plus `*IT`; Docker and PostgreSQL/Testcontainers required)
mvn clean verify

# Create the shaded Runtime JAR
mvn package

# Build a Linux Docker-image installer
bash ./build/package.sh <version>

# Clean Maven outputs
bash ./build/clean.sh

# Build the verified distribution
bash ./tools/release.sh

```

The standalone market server remains a separate Java 21 Maven project:
`mvn -f market-server/pom.xml clean verify`.

## Change Discipline

After every file change, inspect the relevant user and developer documentation and update it
when the behavior, configuration, interface, or workflow has changed. Include documentation
updates in the same change and state the documentation check in the final report.

## High-Level Architecture

Cilexec is a **process management & scripting engine** that runs FCL (Follarce CilExec Language) scripts on a **PostgreSQL-backed runtime**. Programs, full continuations, process identities, FIFO scheduler leases, IPC, timers, VFS nodes, package bindings, external-effect journals, terminal input, and audit events are all committed to PostgreSQL. There is no `.proc` snapshot format and no `cilexec_root/` host-directory state store.

### Core Design Principle: "Zero Memory State"

The system has **no in-memory semantic state required for crash recovery**. Every execution slice commits in one explicit database transaction; disposable JVM objects (threads, caches, task queues) may accelerate execution, but a restart reconstructs work from committed PostgreSQL rows. An in-flight slice rolls back and may replay. A PostgreSQL advisory lock guarantees one active runtime per database, and `state_version` + `execution_epoch` fencing prevents stale workers from committing.

### Key Packages

| Package | Purpose |
|---|---|
| `com.follarce.Main` | Stable executable entry point |
| `com.follarce.app` | Startup, shutdown, application commands (`terminal`, `runtime`, `migrate`, `export`, `package build`, `host move`), runtime assembly (`CilExecApplication`, `RuntimeBootstrap`, `RuntimeLifecycle`) |
| `com.follarce.domain` | Persistence-independent domain model and ports (process, program, vfs, scheduler, ipc, timer, effect, terminal, auth, audit) |
| `com.follarce.fcl` | FCL compiler, full continuation runtime, expression evaluator, continuation/program codecs, built-ins, and function registry |
| `com.follarce.application` | Database-aware built-in FCL functions (`FclRuntimeFunctions`), statement dispatch (`ProcessStatementExecutor`), process/program services, REPL service |
| `com.follarce.scheduler` | **SchedulerService** — bounded virtual-thread workers (default 10) claiming durable FIFO leases |
| `com.follarce.effect` | Journaled external-effect subsystem: **EffectWorkerService** (default 6 workers), heartbeat and stuck-effect reclamation, `BuiltinEffectHandlers` |
| `com.follarce.timer` | Durable timers (`TimerService`, `TimerWorkerService`) |
| `com.follarce.ipc` | Durable direct/channel/topic/broadcast messaging |
| `com.follarce.vfs` | Content-addressed VFS use cases (`VfsService`, `AdminVfsService`) |
| `com.follarce.package_manager` | Immutable SQLite `.db` artifacts, per-user installation lifecycle, private package data, and exact-hash bindings (`PackageManager`, `PackageBuilder`) |
| `com.follarce.market.client` | Built-in market client (`MarketRuntimeFunctions`) for the standalone `cilexec-market-server.jar` |
| `com.follarce.exporter` | Verified logical PostgreSQL → SQLite export (`LogicalExportService`) |
| `com.follarce.persistence.postgres` | HikariCP data sources, Flyway migrations, JDBC repositories and transaction executor |
| `com.follarce.auth` | PostgreSQL principal lifecycle (`AuthService`), password/username policy |
| `com.follarce.terminal` | Durable host control plane: `TerminalServer`, console, command parser, output routing |
| `com.follarce.health` | `HealthServer` — liveness/readiness bound to `127.0.0.1` |
| `com.follarce.audit` | Structured audit event retention (`AuditRetentionService`) |
| `com.follarce.config` | `CilExecConfig` — environment/defaults-driven configuration with pool invariants |
| `com.follarce.extension` | Immutable compile-time extension catalog (`JavaExtensionCatalog`, `SourceExtensionIndex`); no runtime/classpath discovery |
| `com.follarce.extension.api` | Stable source-extension contracts: `CilExecExtension`, `ExtensionRegistrar`, functions, effect handlers, state, transactions |

### FCL Script Engine Pipeline

```
FCL source → FclCompiler (lexer, parser, continuation program)
           → ProcessStatementExecutor (pattern-match: if/while/func/import/return/etc.,
              one execution slice — terminal processes batch up to 4096 steps
              or 20 ms, all others one interpreter step — committed per slice)
           → FclExpressionEvaluator (expression evaluation)
           → JdbcTransactionExecutor (commit state to PostgreSQL after each slice)
```

Language notes: `//` is the only comment syntax; `#` is only the length operator. Statement
keywords are reserved. Each process's full continuation (variables, call stack, program
counter) is serialized to PostgreSQL rows after every committed slice.

### Threading Model

Java 26 virtual threads are used throughout the core Runtime. The scheduler runs bounded worker pools
(defaults: 10 scheduler workers, 6 effect workers, for the whole server, not per user) that
claim durable leases in PostgreSQL; idle workers block in memory instead of polling the
database, and transaction-commit notifications (`PostgresWorkListener`) wake them on queue,
effect, timer, lease, or retention changes. Runnable processes beyond the worker count remain
in the durable FIFO queue.

### Permission System

- Coarse capabilities defined in `Capability` (`PROCESS_CREATE`, `PROCESS_CONTROL_OWN`/`ANY`,
  `VFS_READ`/`WRITE`/`MOUNT_HOST`, `PACKAGE_IMPORT`/`BIND`, `EFFECT_REQUEST`,
  `TERMINAL_ATTACH`, `AUDIT_READ`, `SYSTEM_ADMIN`) and checked via `Authorization.require()`.
- CilExec users map to stable PostgreSQL `NOLOGIN`, `NOINHERIT` tenant roles; user tables use **forced RLS**, so row
  ownership isolation is enforced by the database itself.
- `SYSTEM_ADMIN` is the CilExec application superuser: it satisfies every capability and has an
  explicit forced-RLS policy over all user runtime data, but never receives PostgreSQL
  `BYPASSRLS`, cluster-superuser, or host OS privileges.
- Ordinary terminal registrations receive only `PROCESS_CREATE`, `PROCESS_CONTROL_OWN`,
  `VFS_READ`, `VFS_WRITE`, `TERMINAL_ATTACH`, and `AUDIT_READ`. Package, effect, host-mount,
  cross-owner, and administrator capabilities require an explicit grant.
- The frozen V001 baseline modules under `src/main/resources/db/baseline/` define roles, RLS
  policies, and SECURITY DEFINER functions. The active V002 migration adds the active-effect
  quota index; do not modify either applied migration.

### Adding New Functionality

To add a source-only Java extension:
1. Implement `CilExecExtension` under the developer's own Java package.
2. Register functions/effect handlers through `ExtensionRegistrar`.
3. Add exactly one constructor entry to `SourceExtensionIndex.sourceExtensions()`.
4. Follow `docs/java-extension-development.md`, especially the persistence/effect rules.
5. Run `mvn clean test` and rebuild the JAR/image. There is intentionally no runtime
   Java-plugin install path.

Host-to-VFS transfer is deliberately a host tool rather than an FCL function. `tools/HostMove.sh`
mounts one explicitly named regular file read-only into a disposable tool container; the
`host move` application command streams it into PostgreSQL and creates the VFS node while
retaining the host source. `host move` requires a target user holding `VFS_MOUNT_HOST` and
`VFS_WRITE` and unconditionally refuses the `local` superuser. Never mount the Docker Socket
or a broad host directory for this feature.

## Important Design Details

- **Stable compatibility policy:** `V001__CilexecBaseline` is the frozen modular baseline and
  `V002__EffectActiveQuotaIndex` is active. Never modify an applied migration; the next schema
  or persisted-format change must be an immutable `V003` forward migration with upgrade,
  backup, and rollback-by-restore tests. Automatic downgrades remain forbidden.
- **Database migrations:** Flyway baselines live in `src/main/resources/db/baseline/`;
  `database.migrate-on-start` (env `CILEXEC_MIGRATE_ON_START`, default `false`) now takes
  effect — when enabled, the Runtime applies pending migrations at startup instead of
  requiring the one-shot `migrate` command.
- **Connection pools:** HikariCP. `runtime.pool.max` defaults to 20 and must be at least
  `scheduler.workers + effect.workers + 2`; the invariant is enforced at config load.
- **SQLite packages:** package artifacts are immutable, read-only SQLite `.db` files (format
  v2). Releases have logical and file-SHA-256 identities; installations, dependency closures,
  private data spaces, and exact process bindings are persisted per user.
- **Zero memory state:** all authoritative runtime state is persisted to PostgreSQL after each committed slice;
  recovery validates continuations, leases, IPC, timers, effects, and security invariants.
- **Configuration and secrets:** `src/main/resources/cilexec-defaults.properties` provides
  non-secret defaults and `CILEXEC_*` overrides. Local Compose secrets and TLS material are
  generated with `bash docker/create-secrets.sh` under `docker/secrets/`; production may use an
  external secret manager. Never commit secret material.
- **Health endpoints:** `GET /health/live` and `GET /health/ready` on an HTTP server bound to
  `127.0.0.1` only (internal to the container).
- **Import/include:** `import` resolves an exact installed `.db` SHA-256;
  `include` expands VFS source files before compilation.
