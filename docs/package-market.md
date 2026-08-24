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

## Starting the Server

With no options, the server starts its interactive management console and serves HTTP in
the background. The console can list, publish, and unpublish packages; closing it stops
the HTTP service:

```bash
java --enable-native-access=ALL-UNNAMED -jar dist/cilexec-market-server.jar
```

Use `--headless` for the foreground HTTP-only mode intended for systemd and containers:

```bash
java --enable-native-access=ALL-UNNAMED \
  -jar dist/cilexec-market-server.jar \
  --repository dist/repository \
  --catalog dist/catalog.json \
  --headless
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
| `POST /market/v1/publish` | Publishes a `.db` uploaded with a valid Bearer publish token. |

Package downloads support a single explicit `Range: bytes=start-end`, `ETag`, `If-Range`,
`Content-Length`, and `416` end-of-file probing, matching the Runtime's 4 MiB persisted
chunked download. `v1` is the market HTTP protocol major version, not a package version.

`dist/catalog.json` is the publication manifest. Files present in the repository but not
listed in the manifest are never listed for sale. On startup the server rejects
symlinks, path escapes, packages larger than 64 MiB, SQLite databases that are not format 2,
coordinates that disagree with their path, invalid dependency hashes, and duplicate
hashes. Size and SHA-256 are rechecked before every download.

The server does not need a restart for new packages. Every index request re-reads and fully
validates the repository, then atomically replaces the in-memory snapshot. For manual
catalog management, first place the new `.db` in its final version directory and only then
update `catalog.json` with an atomic rename; published content-hash files must never be
overwritten. The console and authenticated publish endpoint perform this publication flow.
If validation fails, index requests return `503`, an existing valid snapshot is never
replaced by a half-built one, and in-flight SHA-256 downloads do not switch files.

The `sha256` in the index is the 64-digit lowercase SHA-256 of the complete `.db` file and
doubles as package ID, download path, and dependency ID. It is a content identity, not a
signature or publisher identity. `POST /market/v1/publish` accepts one raw `.db` body up to
64 MiB with `Authorization: Bearer <token>` and a `Content-Length`; it returns `201` with
the coordinate, SHA-256, byte count, and stored path. Optional `summary`, `description`,
and `tags` query parameters override package metadata.

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
| `market.search(text)` | Uses whitespace-separated AND terms. Each term prefix-searches package name, namespace, kind, `namespace/name`, tags, and summary/description words. A term of at least eight characters can prefix-match the package SHA-256; versions do not participate in search. |
| `market.info(sha256)` | Returns one full package record, or `null` if absent. |
| `market.download(sha256)` | Downloads in chunks and recomputes the full-file SHA-256. |
| `market.install(sha256)` | Recursively installs exact-hash dependencies; identity is the SHA-256, so a different hash is a different package. |
| `market.list()` | Lists installed package SHA-256s and their coordinates. |
| `market.help()` | Returns function help. |
| `market.run()` | Returns the client version and help without requiring a configured origin. |

Installing an editor end to end:

```fcl
market.configure("http://host.docker.internal:8787")
market.update()
market.search("editor")
market.install("c8b8a024847aa873de9655443280104f4cc185b1770b6308ca073999b1503bff")
import "c8b8a024847aa873de9655443280104f4cc185b1770b6308ca073999b1503bff" as "editor"
editor.open("notes.txt")
```

The index cache lives at `/market/index.json` in the current user's VFS, downloads at
`/market/packages/{sha256}.db`, and market installation receipts at
`/market/installed.json`. Ordinary users cannot see these three kinds of data of other
users.

Download chunks are first written to the object store as immutable objects; the target VFS
node is published only after the last chunk completes. The client then re-reads the whole
logical file in 4 MiB units and rechecks the declared size and SHA-256. On install the
Runtime additionally revalidates the SQLite structure, package-internal hashes, capability
declaration, and the exact dependency graph. The market package limit is 64 MiB; the
ordinary single-VFS-file limit remains 1 GiB.

Downloaded `.db` files under `/market/packages/` are registered as managed artifacts in
the database. After a successful uninstall, the client deletes them and reports the count
as `cacheFilesRemoved`; ordinary user files are never registered or touched.

## Private Package Data

Every user and exact installed package release owns an isolated private data space
(`package-data://<database-file-sha256>/`) with a default quota of 256 MiB. Package
code writes to it with the `packageData.*` functions, which require the declared
`package.data` capability and resolve the space from the linked package identity;
other packages and ordinary VFS paths cannot reach it. Users inspect, export,
import, and clear the space with `package.dataInfo`, `package.dataList`,
`package.dataRead`, `package.dataExport`, `package.dataImport`, and
`package.clearData`. Uninstallation removes the private space, the market download
cache, the installation ledger, and process bindings, and garbage-collects
globally unreferenced release payloads; ordinary user documents are never touched.

## Package Capabilities

`package.run` executes an application package through its declared entrypoints. The first
argument is the installed `.db` file SHA-256. The entrypoint name —
`package.run(databaseFileSha256, entrypoint)` — must be a valid FCL identifier; an
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
| `market.configure`, `market.update`, `market.download`, `market.install` | `package.manage` |
| `packageData.*` private data functions | `package.data` |
| `process.exec`, `process.kill`, `process.pause`, `process.continue`, `process.getList` | `process.control` |
| `user.validateUser`, `user.list`, `user.disable`, `user.remove` | `system.admin` |

Query-only market functions (`market.origin`, `market.search`, `market.info`, `market.list`,
`market.help`, `market.run`) require no capability. `system.list` and `system.extensions` are
read-only and do **not** require `system.admin`; the remaining `system.*` management calls
do. Bare spellings (`input`, `readFile`, `fork`, `webget`, ...) are mapped by the same
rules. A capability declaration must list every capability key the package source uses;
undeclared usage fails the audit.

## Publishing Packages

Local operators can publish through the interactive console, use `publish <file.db>` and
follow its metadata prompts, or use `publish <file.db> --summary TEXT --description TEXT
--tags a,b` to publish without prompts. A one-shot local publish validates the package,
uses its package metadata, publishes it, and exits:

```bash
java --enable-native-access=ALL-UNNAMED -jar dist/cilexec-market-server.jar \
  --publish /path/to/package.db
```

For remote publishing, create a token. Only its SHA-256 digest is stored, and the
plaintext token is printed once. `--token list` lists token names and `--token remove NAME`
revokes one; token changes take effect for a running server immediately.

```bash
java --enable-native-access=ALL-UNNAMED -jar dist/cilexec-market-server.jar \
  --token add developer --tokens /path/to/tokens.json
```

Upload with the bundled helper, passing `--token` or setting `CILEXEC_MARKET_TOKEN`:

```bash
python3 tools/MarketPublish.py --url https://market.example.com \
  --token <token> --summary "Text editor" --tags editor,ui /path/to/package.db
```

Use HTTPS or a trusted private network for tokens. The authenticated HTTP endpoint only
publishes; unpublishing remains a local interactive-console operation.

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

The editor source lives in `dist/editor/`. To build a single `.db`, use the package
builder bundled into the Runtime JAR so package validation uses the authoritative current
FCL compiler:

```bash
java --enable-native-access=ALL-UNNAMED -jar target/cilexec-app.jar \
  package build dist/editor editor.db
```

The command requires neither Docker nor a database service and produces an immutable
`editor.db`; it does not publish anything. Let the release flow generate the official
repository path and catalog. When the content at the same coordinate changes, the package
version must be incremented.

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
- The server provides no unauthenticated upload, delete, or login endpoint. The only
  remote mutation endpoint is authenticated `POST /market/v1/publish`; local console
  commands manage publication and unpublication.
- The package signature system has been fully removed; the security boundary is the
  controlled publication manifest, HTTPS, exact SHA-256, and the Runtime's package
  structure/capability validation.
