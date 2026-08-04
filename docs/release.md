# Releasing CilExec

## Local One-Command Release

Run from the project root:

```bash
./build/release.sh
```

Windows runs:

```bat
build\release.bat
```

The default flow runs the full Maven test suite and produces:

- `dist/cilexec-app.jar`
- `dist/cilexec-market-server.jar`
- `dist/repository/packages/<namespace>/<name>/<version>/<name>.db`
- `dist/catalog.json`
- `dist/SHA256SUMS`

The flow cross-validates JARs, SQLite packages, the market catalog, and SHA-256 sums in a
temporary directory. Existing artifacts are replaced only after all validations pass. The
Git commit id is baked into the Runtime; when building outside a Git working tree, provide
it via `CILEXEC_BUILD_REVISION`. A local working tree with uncommitted changes is recorded
as `<commit>-dirty` so an unreproducible local build is never mistaken for a formal release
of that commit.

Optional flags:

```bash
# Use when CI has already run the full test suite
./build/release.sh --skip-tests

# Do not build; only recheck all existing artifacts in dist
./build/release.sh --verify-only
```

`--skip-tests` only applies when a trusted CI job in the same run has already executed the
tests; it should not be the default for manual formal releases.

## Runtime Configuration Defaults

The release Runtime reads its defaults from `cilexec-defaults.properties`, overridable via
`CILEXEC_*` environment variables and Docker secrets. Notable defaults:

- `runtime.pool.max=20` for the HikariCP runtime pool. The invariant at config load is
  `runtime.pool.max >= scheduler.workers + effect.workers + 2`; with the defaults
  (10 scheduler workers, 6 effect workers) the pool must be at least 18, and it is.
- `database.migrate-on-start` (`CILEXEC_MIGRATE_ON_START`, default `false`) is honored at
  startup: when enabled, the Runtime applies pending Flyway migrations itself during boot
  instead of requiring the one-shot `migrate` command.

## GitHub Actions

Ordinary pushes and pull requests run validation of Java, the market server, the host
scripts, the Docker image, and the full release directory. Pushing a `v*` tag or manually
running the `release-artifacts` workflow runs the full release flow and uploads
`cilexec-release.tar.gz`. The archive contains the two JARs, the market repository, the
catalog, the readme, and the checksum files; it does not contain source directories or
build caches.

After downloading the archive, verify it in the extraction directory:

```bash
sha256sum -c SHA256SUMS
```

On macOS:

```bash
shasum -a 256 -c SHA256SUMS
```
