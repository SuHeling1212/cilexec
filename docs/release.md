# Releasing CilExec

> **0.0.5 status.** The supported distribution is the standalone `cilexec-app.jar` with its
> bundled PostgreSQL (`cilexec-data/` beside the JAR).

## Local One-Command Release

Run from the project root:

```bash
./tools/release.sh
```

Windows runs:

```bat
tools\release.bat
```

The default flow runs the Maven tests and produces:

- `dist/cilexec-app.jar`
- `dist/cilexec-market-server.jar`
- `dist/repository/packages/<namespace>/<name>/<version>/<name>.db`
- `dist/catalog.json`
- `dist/MARKET.md` and `dist/RELEASE.txt`
- `dist/LICENSE` and `dist/THIRD_PARTY_NOTICES.txt`
- `dist/SBOM.cdx.json` (CycloneDX 1.5)
- `dist/runtime-dependencies.txt` and `dist/market-dependencies.txt`
- `dist/release-manifest.json`
- `dist/SHA256SUMS`

The flow cross-validates JARs, SQLite packages, the market catalog, release documents,
license, resolved Maven runtime inventories, SBOM, release manifest, and SHA-256 sums in a
temporary directory. The manifest records the version, revision, formal/candidate status,
size, and digest of every payload file. Existing artifacts are replaced only after all
validations pass. The Git commit id is baked into the Runtime; when building outside a Git
working tree, provide it via `CILEXEC_BUILD_REVISION`. A local working tree with uncommitted
changes is recorded as `<commit>-dirty`.

Optional flags:

```bash
# Use when the local test suite has already been run in this working tree
./tools/release.sh --skip-tests

# Do not build; only recheck all existing artifacts in dist
./tools/release.sh --verify-only
```

`--skip-tests` only applies to candidate assembly when the local test suite has already been
run and its reports are available locally. Formal mode rejects it. A normal build also runs
`tools/check_test_reports.py` before staging artifacts.

Formal release mode can be used for a tagged local release:

```bash
version="$(./tools/Version.sh)"
python3 tools/release.py --formal --tag "v${version}"
```

It rejects a dirty tree, a `SNAPSHOT` or unknown version/revision, a tag other than
`v<project.version>`, a revision different from `HEAD`, or a tag that does not point at
`HEAD`, or an attempt to skip tests. Candidate builds intentionally do not use `--formal`.

## Runtime Configuration Defaults

The release Runtime reads its defaults from `cilexec-defaults.properties`, overridable via
`CILEXEC_*` environment variables. The standalone JAR creates local database credentials in
memory; optional external PostgreSQL may use protected secret files. Notable defaults:

- `runtime.pool.max=20` for the HikariCP runtime pool. The invariant at config load is
  `runtime.pool.max >= scheduler.workers + effect.workers + 2`; with the defaults
  (10 scheduler workers, 6 effect workers) the pool must be at least 18, and it is.
- `database.migrate-on-start` (`CILEXEC_MIGRATE_ON_START`, default `false`) is honored at
  startup: when enabled, the Runtime applies pending Flyway migrations itself during boot
  instead of requiring the one-shot `migrate` command.

## Publishing

This repository no longer uses CI workflows. Build and verify the release locally, then publish
the files in `dist/` through the operator's chosen channel:

```bash
./tools/release.sh
```

No GitHub Actions, GHCR image, or cosign signature is produced by this repository.

After copying or downloading the artifacts, verify the checksums.

```bash
sha256sum -c SHA256SUMS
```

On macOS:

```bash
shasum -a 256 -c SHA256SUMS
```
