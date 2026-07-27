# CilExec

CilExec is a Java 26 runtime for FCL (Follarce CilExec Language). PostgreSQL is the
only authoritative runtime state: programs, complete continuations, process identities,
FIFO scheduling leases, IPC, timers, VFS nodes, package bindings, external-effect journals,
terminal input, and audit events are all committed to the database.

This generation contains no `.proc` snapshots or host-directory state database. Disposable
JVM objects may accelerate execution, but a restart always reconstructs work from committed
PostgreSQL rows.

## Runtime guarantees

- One semantic FCL statement per explicit database transaction.
- Complete continuation persistence with `state_version` and `execution_epoch` fencing.
- One active runtime per database, protected by a PostgreSQL advisory lock.
- Monotonic, non-reusable PIDs and recoverable FIFO scheduler leases.
- Durable direct/channel/topic/broadcast IPC and database-authoritative timers.
- Content-addressed VFS objects and immutable SQLite `package.db` releases.
- External operations pass through the effect journal before execution.
- CilExec users map to stable PostgreSQL LOGIN roles; user tables use forced RLS.
- `SYSTEM_ADMIN` is the CilExec application superuser: it satisfies every capability and has an
  explicit forced-RLS policy over all user runtime data. It never receives PostgreSQL
  `BYPASSRLS`, cluster-superuser, or host operating-system privileges.
- Separate structured audit events, runtime logs, liveness, and readiness endpoints.
- Verifiable application-level SQLite exports built from one committed read-only snapshot.

## Build

Requirements: JDK 26, Maven 3.9+, and PostgreSQL 17.1 or newer. The minimum
database version is enforced at Runtime startup because user-role/RLS identity
resolution depends on the security-correct `SET ROLE` behavior in patched PostgreSQL.

```bash
mvn clean test
mvn clean verify
```

The packaged executable is `target/cilexec-app.jar`. The build also creates
`target/dependency-lock.txt`; build/schema/FCL format metadata are embedded in the JAR.

## Run with Compose

For the normal local setup, the one-command launcher creates all missing internal secrets,
uses `12345678` as the initial `local` administrator password, starts PostgreSQL, applies
migrations, and opens the interactive terminal:

```bash
./start.sh
```

At the access prompt choose `login`, then enter username `local` and password `12345678`.
The launcher uses the persistent Compose profile and stops the containers when the terminal
exits; the database volume is retained for the next run.

The terminal prints `[CILEXEC-TIMING]` lines by default so command latency can be diagnosed.
Set `CILEXEC_COMMAND_TIMING=false` before the launcher to turn those diagnostic lines off.

The manual deployment procedure is below.

Create the six secret files under `docker/secrets/`. The five PostgreSQL service passwords must
contain at least 16 characters; the human `cilexec-terminal-password` must contain at least 8:

```text
postgres-admin-password
cilexec-migrator-password
cilexec-runtime-password
cilexec-effect-worker-password
cilexec-readonly-password
cilexec-terminal-password
```

Disposable database:

```bash
docker compose -f compose.yml -f compose.ephemeral.yml up --build
```

Persistent named volume (the volume is not a backup):

```bash
docker compose -f compose.yml -f compose.persistent.yml up --build
```

For an externally managed database, bootstrap the service roles once using
`docker/postgres/init/00-cilexec-bootstrap.sh` or equivalent DBA SQL, then use
`compose.external.yml` and set `CILEXEC_DATABASE_URL`. External servers older than
PostgreSQL 17.1 are rejected.

The migration container receives only the migrator secret. The terminal Runtime receives its
runtime/effect secrets plus the one-time password used to provision the deployment-bound
`local` CilExec administrator. CilExec runs as UID/GID 10001 with a read-only root filesystem.

## Commands

```bash
java -jar target/cilexec-app.jar terminal
java -jar target/cilexec-app.jar migrate
java -jar target/cilexec-app.jar runtime
java -jar target/cilexec-app.jar export /explicit/path/cilexec-export.db
java -jar target/cilexec-app.jar package build ./examples/hello-package ./hello.db
```

With no arguments, the JAR starts the authenticated composite FCL REPL and terminal. Choose
`login` to authenticate with a CilExec username and password, or `create` to register a regular
user with all owner-scoped process, file, package, effect, terminal, and audit capabilities. The
deployment-bound `local` account remains the system administrator. Real TTY password entry has
echo disabled. `:logout` returns to the access prompt and preserves the durable REPL context for
the next login.

Compose exposes this terminal as the user interface (`stdin_open` plus TTY); the health endpoint
remains internal to the container. The deliberately small colon-command surface is `:help`,
`:cd`, `:pwd`, `:ls`, `:logout`, and `:exit`; process, file, package, user, effect, and system
operations use FCL functions. Every other complete input is compiled and run as FCL with the full
function registry. A terminal owns one permanent process and PID: each completed input leaves that
process `PAUSED`, and the next input is installed atomically only while it remains suspended. Its
working directory, variables, imports, and function declarations live in that process and survive
logout/login and Runtime restarts.
Relative VFS paths in REPL submissions resolve against the terminal's durable working directory.
Use `:help` for the command list. The explicit
`runtime` command remains a headless operations mode for deployments that deliberately provide
another terminal transport.

`package build` is an offline command and does not read database configuration or secrets. It
reads `package.json` plus the explicitly declared module/resource files, validates every FCL
module and exported symbol, builds an immutable SQLite `package.db`, validates the completed
database through the production package reader, and refuses to replace an existing output.

The export command refuses to replace an existing path. It reads PostgreSQL in a
`SERIALIZABLE READ ONLY DEFERRABLE` transaction, verifies SQLite `integrity_check`, every
canonical-JSON row hash, every table count/hash, and the aggregate manifest before publishing
the completed file atomically. The delivery is marked read-only and also contains mutation
guard triggers. It contains committed semantic tables, schema/build/FCL format metadata, and
content-addressed object bytes; it excludes the Runtime incarnation, scheduler runners and
leases, control backend identity/proof fields, connections, database locks, transactions, WAL,
and JVM state.

The dedicated Compose export container mounts only the migrator secret and enforces the
read-only transaction in PostgreSQL. The destination directory must already be writable by
UID 10001. It is opt-in through the `tools` profile:

```bash
CILEXEC_EXPORT_FILE=cilexec-2026-07-26.db \
  docker compose --profile tools run --rm export
```

The default host destination is `exports/`; set `CILEXEC_EXPORT_DIRECTORY` to bind another
directory. Export files are a portable inspection/delivery format, not a replacement for the
separate `pg_dump` disaster-recovery backup.

## FCL system APIs

Complete user-facing signatures, aliases, permission scope, terminal commands, and examples are
in [the FCL function reference](docs/fcl-function-reference.md).

The runtime registry exposes `math`, `util`, `path`, `term`, `file`, `io`, `process`, `user`,
`swapPool`, `network`, `socket`, `package`, and `system` namespaces. Local database operations
commit with the FCL statement. Input, timers, HTTP, terminal output, one-shot sockets, and
allowlisted host commands suspend the continuation and resume through durable inbox/effect rows;
already completed calls in the same statement are not repeated after recovery.

File functions accept an optional final target-user argument for cross-user operations. The same
function checks the current process identity internally: ordinary users remain owner-scoped, while
`SYSTEM_ADMIN` users may target any user and administrator actions are audited. There is no parallel
`file.admin*` namespace. `process.getList()` (also available as
`process.getListOfProcess()`) lists the caller's processes for ordinary users and every process for
administrators; administrators may also pause, continue, terminate, or wait for foreign processes.
All calls still execute under the caller's stable PostgreSQL LOGIN role and forced RLS.
`system.exec` additionally requires its
executable in the comma-separated `CILEXEC_FCL_EXEC_ALLOWLIST`; the default allowlist is empty.
Arbitrary JVM reflection, live socket handles, in-place identity switching, and destructive host
filesystem reset are intentionally not exposed because they violate the durable/RLS boundary.

## Build, install, import, and run packages

A source package is a directory containing `package.json` and its declared content. Package
modules are libraries: their top level may contain function declarations only. Entrypoints must
be zero-argument functions; exported functions may accept ordinary FCL arguments.

```json
{
  "namespace": "demo",
  "name": "hello",
  "version": "1.0.0",
  "languageVersion": "fcl-1",
  "modules": [{"name": "main", "path": "main.fcl"}],
  "resources": ["assets/message.txt"],
  "entrypoints": [{"name": "run", "module": "main", "function": "run"}],
  "exports": [{"name": "greet", "module": "main", "symbol": "greet"}],
  "dependencies": [],
  "capabilities": []
}
```

Build on the host with the command shown above, or build entirely inside the CilExec VFS:

```text
built = package.build("/src/hello/package.json", "/packages/hello.db")
installed = package.install("/packages/hello.db", "hello")

import "hello" as hello
message = hello.greet("CilExec")

child = package.run("hello", "run")
```

`package.install(path)` uses the package name as its binding. The two-argument form selects an
explicit binding, and the existing three-argument form accepts an explicit environment UUID and
binding. Every user gets a stable `default` environment automatically; additional environments
can be created and listed with `package.createEnvironment(name)` and `package.environments()`.

The first successful import writes an immutable `(process, import name) -> package hash` binding.
On every later statement and after a crash, the Runtime reloads the exact SQLite package bytes,
checks module hashes, deterministically links the exported functions, and resumes the same
continuation. `package.run(binding, entrypoint)` creates a normal durable child process and returns
its PID/process identity. Remote stores and dependency solving are intentionally outside this
local package workflow.

## Backup and disaster recovery

Use PostgreSQL custom-format dumps for disaster recovery. Stop CilExec or otherwise ensure the
operator has selected an appropriate consistent backup window, record the CilExec image/build
revision and PostgreSQL major version, then run `pg_dump --format=custom`. Restore into a newly
created empty database owned by `cilexec_owner`, run `pg_restore --exit-on-error`, apply any newer
Flyway migrations, and start CilExec so semantic recovery can validate the restored continuation,
leases, IPC, timers, effects, and security invariants.

The exact automated restore contract is exercised by `PostgresBackupRestoreIT`; it verifies the
restored semantic rows, all 25 migrations, all CilExec schemas, and
`meta.assert_security_invariants()`. Keep role/global definitions and service credentials in a
separate protected operator backup because a per-database `pg_dump` does not contain cluster-wide
roles. Never attach a PostgreSQL data volume from one major release directly to another; use
dump/restore for major upgrades.

Health endpoints:

```text
GET /health/live
GET /health/ready
```

## Source layout

```text
com.follarce.Main                  stable executable entry point
com.follarce.app                   startup, shutdown, and runtime assembly
com.follarce.domain                persistence-independent domain and ports
com.follarce.fcl                   parser, compiler, full continuation, step runtime
com.follarce.persistence.postgres  explicit JDBC transactions and repositories
com.follarce.persistence.sqlite    immutable package.db inspection
com.follarce.exporter              verified PostgreSQL-to-SQLite logical export
com.follarce.scheduler             bounded FIFO/lease workers
com.follarce.ipc                   durable messaging use cases
com.follarce.timer                 durable timers
com.follarce.vfs                   content-addressed VFS use cases
com.follarce.package_manager       package release and exact-hash environments
com.follarce.effect                journaled external-effect workers
com.follarce.auth                  PostgreSQL principal lifecycle
com.follarce.terminal              durable host control plane
com.follarce.audit / health        operational boundaries
```

The normative design and completion criteria are in
[`theLastFileBeforeNewVersion.md`](theLastFileBeforeNewVersion.md).

## License

[MIT](LICENSE)
