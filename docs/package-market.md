# CilExec Java Market

The market consists of two Java programs. There is no longer a distributed `market.db` and
no `mkt` import:

```text
CilExec Runtime (built-in market.*)        cilexec-market-server.jar
──────────────────────────────             ─────────────────────────
Caches the full index in user VFS          Serves the explicit catalog.json
Local natural-language search              Validates read-only SQLite package.db
SHA-256 download, install  ◄────►  Serves index and HEAD/Range downloads
```

The client ships with `cilexec-app.jar`. The server is a standalone fat JAR that runs on
macOS, Linux, and Windows with `java -jar` and depends on neither Python, Bash, nor Docker.

## Building and Starting the Server

```bash
java --enable-native-access=ALL-UNNAMED \
  -jar dist/cilexec-market-server.jar \
  --repository dist/repository \
  --catalog dist/catalog.json
```

Windows PowerShell uses the same JAR:

```powershell
java --enable-native-access=ALL-UNNAMED `
  -jar dist\cilexec-market-server.jar `
  --repository dist\repository `
  --catalog dist\catalog.json
```

By default the server listens only on `127.0.0.1:8787`. To make it reachable from Docker
containers, bind an explicit host interface and allow only the actual container network:

```bash
java --enable-native-access=ALL-UNNAMED \
  -jar dist/cilexec-market-server.jar \
  --repository dist/repository --catalog dist/catalog.json \
  --bind 0.0.0.0 --port 8787 --allow-cidr 172.20.0.0/16
```

Run `java -jar ... --help` for the full parameter list. `--allow-cidr` is repeatable; the
loopback network is always allowed. Before deploying, add `--check` to validate the
repository and catalog and exit immediately without listening on any port.

## HTTP Protocol

| Request | Purpose |
| --- | --- |
| `GET/HEAD /market/v1/index.json` | Returns the index of all published packages. |
| `GET/HEAD /market/v1/{sha256}` | Downloads the immutable `.db` identified by a full SHA-256. |

Package downloads support a single explicit `Range: bytes=start-end`, `ETag`, `If-Range`,
`Content-Length`, and `416` end-of-file probing, matching the Runtime's 4 MiB persisted
chunked download. `v1` is the market HTTP protocol major version, not a package version.

`dist/catalog.json` is the only publication manifest. Files present in the repository but
not listed in the manifest are never listed for sale. On startup the server rejects
symlinks, path escapes, packages larger than 64 MiB, SQLite databases that are not format 2,
coordinates that disagree with their path, invalid dependency hashes, and duplicate
hashes. Size and SHA-256 are rechecked before every download.

The server does not need a restart for new packages. Every index request re-reads and fully
validates the repository, then atomically replaces the in-memory snapshot. A publisher must
first place the new `.db` in its final version directory and only then update
`catalog.json` with an atomic rename; published content-hash files must never be
overwritten. If validation fails, index requests return `503`, an existing valid snapshot
is never replaced by a half-built one, and in-flight SHA-256 downloads do not switch files.

The `sha256` in the index is the 64-digit lowercase SHA-256 of the complete `.db` file and
doubles as package ID, download path, and dependency ID. It is a content identity, not a
signature or publisher identity.

## Built-in Client

Configure a unique mirror origin for the current user first:

```fcl
market.configure("http://host.docker.internal:8787")
market.update()
```

The origin is stored in the current user's persisted FCL environment variable
`MARKET_ORIGIN`. Administrators can provide a shared default via
`env.setShared("MARKET_ORIGIN", "https://market.example.com")`; the user's own value takes
precedence. The client never drops into interactive input mid-script; when unconfigured it
tells you explicitly to call `market.configure(...)`.

| Function | Purpose |
| --- | --- |
| `market.configure(origin)` | Sets the current user's HTTP/HTTPS mirror origin. |
| `market.origin()` | Shows the effective mirror origin. |
| `market.update()` | Downloads, verifies, and persists the full index. |
| `market.search(text)` | Prefix-searches package name, namespace, type, tags, and description words; versions do not participate in search. |
| `market.info(sha256)` | Returns one full package record, or `null` if absent. |
| `market.download(sha256)` | Downloads in chunks and recomputes the full-file SHA-256. |
| `market.install(sha256)` | Recursively installs exact-hash dependencies; identity is the SHA-256, so a different hash is a different package. |
| `market.list()` | Lists installed package SHA-256s and their coordinates. |
| `market.uninstall(sha256)` | Removes the installed package file and its receipt. |
| `market.help()` | Returns function help. |
| `market.run()` | Returns the client version and help without requiring a configured origin. |

Installing an editor end to end:

```fcl
market.configure("http://host.docker.internal:8787")
market.update()
market.search("editor")
market.install("9d3bb9d09774a35aa9b1508b194939a37ae6ef2e6b1698eabb8ce0fe3b7abf9f")
import "9d3bb9d09774a35aa9b1508b194939a37ae6ef2e6b1698eabb8ce0fe3b7abf9f" as "editor"
editor.open("notes.txt")
```

The index cache lives at `/market/index.json` in the current user's VFS, downloads at
`/market/packages/{sha256}.db`, and market install credentials at
`/market/installed.json`. Ordinary users cannot see these three kinds of data of other
users.

Download chunks are first written to the object store as immutable objects; the target VFS
node is published only after the last chunk completes. The client then re-reads the whole
logical file in 4 MiB units and rechecks the declared size and SHA-256. On install the
Runtime additionally revalidates the SQLite structure, package-internal hashes, capability
declaration, and the exact dependency graph. The market package limit is 64 MiB; the
ordinary single-VFS-file limit remains 1 GiB.

## Package Capabilities

`package.run` executes an application package through its declared entrypoints. The
entrypoint name — `package.run(packageHash, entrypoint)` — must be a valid FCL identifier; an
invalid or reserved name is rejected at manifest validation time. Coordinate segments
(namespace, name, and version parts) must be canonical: segments of `"."` or `".."` are
rejected.

Packages declare the capabilities they need in their manifest. The Runtime audits every
call in the package source against the declaration and rejects the package if the source
uses a capability that was not declared. The audit covers the following mapping:

| Function calls | Required package capability |
| --- | --- |
| `io.readFile`, `file.*` read operations | `vfs.read` |
| `io.writeFile`, `file.*` write operations | `vfs.write` |
| `util.input`, `io.input`, `io.readKey`, `io.readChar` | `terminal.raw_input` |
| `market.configure`, `market.update`, `market.download`, `market.install`, `market.uninstall` | `package.manage` |
| `process.exec`, `process.kill`, `process.pause`, `process.continue`, `process.getList` | `process.control` |
| `user.validateUser`, `user.getListOfUsers`, `user.removeUser` | `system.admin` |

Query-only market functions (`market.origin`, `market.search`, `market.info`, `market.list`,
`market.help`, `market.run`) require no capability. `system.ls` and `system.extensions` are
read-only and do **not** require `system.admin`; the remaining `system.*` management calls
do. Bare spellings (`input`, `readFile`, `fork`, `webget`, ...) are mapped by the same
rules. A capability declaration must list every capability key the package source uses;
undeclared usage fails the audit.

## Publishing Packages

A formal release is built from the project root:

```bash
./tools/release.sh
```

Windows runs `tools\release.bat`. The flow runs the Runtime and market-server tests, builds
the two JARs, scans package sources in `dist/*/` that carry a `package.json` and
`market.json`, generates the market repository and `catalog.json` from their coordinates,
then regenerates and rechecks `SHA256SUMS`. Only after all staged artifacts validate are
the release artifacts in `dist` replaced. Use `--skip-tests` when CI has already run the
full test suite; use `--verify-only` to only recheck the existing artifacts.

The editor source lives in `dist/editor/`. To build a single `.db` use the standalone
single-file builder:

```bash
python3 tools/PackageBuild.py dist/editor editor.db
```

The builder depends on neither the CilExec JAR, Docker, nor a database service and produces
an immutable `editor.db`; it does not publish anything. Let the release flow generate the
official repository path and catalog. When the content at the same coordinate changes, the
package version must be incremented.

Every dependency is an exact distribution-file SHA-256:

```json
"dependencies": [
  {"sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "optional": false}
]
```

The client installs required dependencies recursively first, and rejects cycles and
dependency chains deeper than 64 levels. Optional dependencies are never installed
automatically. Package modules call exported functions through the full dependency hash;
coordinates never participate in dependency resolution; dependencies are exact database-file hashes.

## Security and Deployment Notes

- Public deployments should sit behind an HTTPS reverse proxy; the built-in server does not
  terminate TLS.
- Do not treat `--allow-cidr 0.0.0.0/0` as a development shortcut.
- The Runtime's private-network HTTP policy must still permit the configured private
  origin; market configuration does not bypass the network policy.
- The server concurrency defaults to 16 and is tunable with `--workers 1..256`; exceeding
  the limit returns `503` immediately.
- The server repository is read-only: there are no upload, delete, login, or dynamic
  publishing endpoints.
- The package signature system has been fully removed; the security boundary is the
  controlled publication manifest, HTTPS, exact SHA-256, and the Runtime's package
  structure/capability validation.
