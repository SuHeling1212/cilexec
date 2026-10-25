# Release Version Source

The single editable CilExec release version is in [`.mvn/maven.config`](../.mvn/maven.config):

```text
-Drevision=0.0.5
```

Do not edit release numbers in Maven files, Docker files, Compose files, or runtime code. They either read this Maven property directly or obtain it through `tools/Version.sh`.

For the current `0.0.N` release line, `N` is derived as the runtime-format, package format
fence, and database schema number. Thus `0.0.5`:

- reads and writes FCL program/continuation format `5` and source language `fcl-0.0.5`;
- reads and writes SQLite package database format `3`;
- requires Flyway migration `V005` (database schema version 5).

0.0.5 is **not** an in-place upgrade of earlier releases. The startup format fence rejects an
existing `cilexec-data/` directory or external PostgreSQL database that was not created by
0.0.5, before any migration or instance write runs. Old data are never converted, deleted, or
migrated; the operator must use a fresh data directory/database and keep or archive the old
data with the older release.

Before a new release, add its immutable `V00N` migration first (a no-DDL version fence when the
relational schema itself is unchanged), then change only `-Drevision=0.0.N`, and run the normal
verification and release workflow. Once a version is released, its migration and persisted
format identities are frozen. The tests reject an invalid version mapping.
