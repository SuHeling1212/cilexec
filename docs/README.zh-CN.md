# CilExec Documentation

> Project overview in Chinese: see [../README.zh-CN.md](../README.zh-CN.md) at the repository root.

This directory holds the current documentation for the CilExec runtime, written as part of
the PostgreSQL-backed rewrite. The project coordinates are still `com.follarce:cilexec` and
the entry point is still `com.follarce.Main`.

PostgreSQL is the only authoritative runtime state. Programs, complete continuations,
process identities, FIFO queues and leases, IPC, timers, VFS nodes, package environments,
external effects, terminal input, and audit events are all saved in database transactions.
JVM threads, caches, and task queues may be lost at any time; after a restart everything is
reconstructed from the database. There is no `.proc` snapshot format and no
`cilexec_root/` host-directory state store.

## Documents

- [architecture-baseline.md](architecture-baseline.md) — normative design and acceptance
  criteria for the PostgreSQL and Docker-based rewrite (Java, PostgreSQL, immutable SQLite
  packages).
- [fcl-function-reference.md](fcl-function-reference.md) — complete reference for the FCL
  function registry, namespaces, aliases, permission scope, and terminal commands.
- [java-extension-development.md](java-extension-development.md) — how to add source-only
  Java extensions: functions, effect handlers, persistence and effect rules, recovery
  policies, and the release checklist.
- [package-market.md](package-market.md) — the built-in market client and the standalone
  `cilexec-market-server.jar`.
- [headless-mode.md](headless-mode.md) — running one FCL submission from the host without
  entering the interactive Shell (`./tools/Headless.sh`).
- [host-vfs-import.md](host-vfs-import.md) — importing one named host file into the VFS
  (`tools/HostMove.sh` / `host move`), including the required capabilities.
- [release.md](release.md) — one-command local release process (`./tools/release.sh`).
- [terminal-and-admin-plan.md](terminal-and-admin-plan.md) — implementation plan and status
  for the terminal command surface and `SYSTEM_ADMIN` global administration.

## Core Constraints

- Each FCL semantic statement corresponds to exactly one explicit database commit.
- `state_version + execution_epoch` prevents stale workers from committing.
- A PostgreSQL advisory lock guarantees only one active Runtime per database.
- PIDs are monotonic and never reused.
- IPC supports direct, channel, topic, and broadcast; timers do not rely on in-memory sleeps.
- VFS content uses SHA-256 content-addressed objects; packages are read-only, immutable
  SQLite `.db` files.
- All external operations (HTTP, sockets, host writes) must enter the effect journal first.
- CilExec users map to stable PostgreSQL LOGIN roles; user tables enforce forced RLS.
- `//` is the only comment syntax; `#` is only the length operator.

## Build

```bash
mvn clean test
mvn clean verify
```

Outputs:

```text
target/cilexec-app.jar
target/dependency-lock.txt
```

## Running with Docker Compose

Create the service secret files under `docker/secrets/` first (passwords must be at least
16 characters):

```text
postgres-admin-password
cilexec-migrator-password
cilexec-runtime-password
cilexec-effect-worker-password
cilexec-readonly-password
cilexec-exporter-password
```

Ephemeral database:

```bash
docker compose -f compose.yml -f docker/compose/ephemeral.yml up --build
```

Persistent volume (the volume is not a backup; use `pg_dump` for production backups):

```bash
docker compose -f compose.yml -f docker/compose/persistent.yml up --build
```

Application commands:

```bash
java -jar target/cilexec-app.jar migrate
java -jar target/cilexec-app.jar runtime
```

Health checks: `GET /health/live` and `GET /health/ready`, bound to `127.0.0.1`.

## License

[MIT](../LICENSE)
