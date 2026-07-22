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
- Separate structured audit events, runtime logs, liveness, and readiness endpoints.

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

Create the five secret files under `docker/secrets/` (each password must be at least 16
characters):

```text
postgres-admin-password
cilexec-migrator-password
cilexec-runtime-password
cilexec-effect-worker-password
cilexec-readonly-password
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

The migration container receives only the migrator secret. The runtime container receives
only runtime/effect secrets. CilExec runs as UID/GID 10001 with a read-only root filesystem.

## Commands

```bash
java -jar target/cilexec-app.jar migrate
java -jar target/cilexec-app.jar runtime
```

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
