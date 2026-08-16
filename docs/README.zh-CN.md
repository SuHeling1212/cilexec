# CilExec Documentation

> Project overview in Chinese: see [../README.zh-CN.md](../README.zh-CN.md) at the repository root.

This directory holds the current documentation for the PostgreSQL-backed CilExec runtime. The
project coordinates are `com.follarce:cilexec` and the entry point is `com.follarce.Main`.

PostgreSQL is the only authoritative runtime state. Programs, complete continuations,
process identities, FIFO queues and leases, IPC, timers, VFS nodes, per-user package
installations and bindings, private package data, external effects, terminal input, and audit
events are all saved in database transactions.
JVM threads, caches, and task queues may be lost at any time; after a restart everything is
reconstructed from the database. There is no `.proc` snapshot format and no
`cilexec_root/` host-directory state store.

## Documents

- [architecture-baseline.md](architecture-baseline.md) — current architecture reference plus
  the preserved historical decision record for the PostgreSQL and Docker rewrite.
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
- [package-management-redesign-plan.md](package-management-redesign-plan.md) — historical
  redesign record and current status of the per-user package lifecycle and private data.

## Core Constraints

- Each execution slice corresponds to one explicit database commit. Non-terminal slices run
  one interpreter step; terminal slices may batch up to 4,096 steps or 20 ms.
- `state_version + execution_epoch` prevents stale workers from committing.
- A PostgreSQL advisory lock guarantees only one active Runtime per database.
- PIDs are monotonic and never reused.
- IPC supports direct, channel, topic, and broadcast; timers do not rely on in-memory sleeps.
- VFS content uses SHA-256 content-addressed objects. Packages are immutable SQLite `.db`
  artifacts with per-user installation ledgers, private data spaces, and exact process bindings.
- FCL-requested HTTP, socket, and allow-listed command operations enter the effect journal.
  Durable timers and explicit host administration tools use separate persisted workflows.
- CilExec users map to stable PostgreSQL `NOLOGIN`, `NOINHERIT` tenant roles; user tables
  enforce forced RLS.
- `//` is the only comment syntax; `#` is only the length operator.

## Build

```bash
# Core Runtime: Java 26 and Maven 3.9+
mvn clean test
mvn clean verify

# Standalone market server: Java 21
mvn -f market-server/pom.xml clean verify
```

`mvn test` runs the `*Test` unit suites. `mvn verify` also runs Failsafe `*IT` suites, which
require Docker and PostgreSQL through Testcontainers.

Outputs:

```text
target/cilexec-app.jar
target/dependency-lock.txt
```

## Running with Docker Compose

Generate the six database-password files and local PostgreSQL TLS material before using the
bundled Compose profiles:

```bash
bash docker/create-secrets.sh
```

The password files are 64-character lowercase hexadecimal secrets and must remain untracked.
Production deployments may replace these local Docker-secret files with an external secret
manager, while preserving the configured secret-file contract.

Ephemeral database:

```bash
docker compose -f compose.yml -f docker/compose/ephemeral.yml up --build
```

Persistent volume (the volume is not a backup; follow the production backup runbook):

```bash
docker compose -f compose.yml -f docker/compose/persistent.yml up --build
```

Application commands:

```bash
java -jar target/cilexec-app.jar migrate
java -jar target/cilexec-app.jar runtime
```

Health checks: `GET /health/live` and `GET /health/ready`, bound to `127.0.0.1` inside the
Runtime container.

## License

[MIT](../LICENSE)
