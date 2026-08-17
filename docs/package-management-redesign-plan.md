# CilExec Package Management: Historical Redesign Plan and Current State

**Current status.** The core per-user installation ledger, exact dependency closures, private
data spaces, quotas, provenance-gated `packageData.*`, atomic uninstall, and controlled global
release cleanup described here shipped in the frozen V001 modular baseline, including the effect
active-quota index. Market receipt authority and all-or-nothing Market bundle publication remain
follow-up work. This document preserves the design rationale and implementation sequence.
Sections labelled historical describe completed design, not pending changes. To preserve useful
detail, unqualified imperative or future wording in sections 5-34 is also historical unless a
section explicitly says future; a future package schema change must start at V002.

## 1. Implemented Decisions

This redesign follows these confirmed requirements:

- Package installation and uninstallation are scoped per user.
- Global release payloads are garbage-collected only after the last user and process reference disappears.
- Uninstallation rejects active process references by default.
- `force=true` terminates and removes affected processes.
- `market.uninstall()` delegates directly to the same core uninstaller as `package.uninstall()`.
- Uninstallation removes package-managed data, caches, receipts, bindings, orphan dependencies, and unreferenced payloads.
- Ordinary user documents are never guessed or deleted.
- Every user and exact immutable package release has an isolated private data space.
- Other packages cannot access that private data.
- Users can inspect, export, import, and clear their own package data.
- Different package versions use separate data spaces.
- The default quota is 256 MiB per user and package, with administrator overrides.
- Audit records and permanent package identity tombstones are retained.
- V001 is frozen, including the effect quota index. Future package schema changes must use V002
  or later immutable forward migrations.

## 2. Historical Problems and Remaining Market Gaps

Most pre-lifecycle limitations below are resolved by the shipped core lifecycle. The remaining
Market receipt and bundle-publication items are explicitly future work:

- Resolved: the global immutable release is paired with per-user installation roots and members;
  local `package.uninstall()` removes the caller's installation transactionally.
- Resolved: package listing, import/run access, bindings, private data, managed artifacts, and
  release cleanup are scoped through the current user's installation ledger.
- Future: `/market/installed.json` remains a receipt cache and a pre-existing receipt can still
  short-circuit `market.install()` before the cache and runtime installation are revalidated.
- Future: Market installation recursively stages and installs packages one at a time; it is not
  yet one all-or-nothing publication of the complete dependency bundle.

## 3. Shipped Architecture

The package system has five distinct layers:

| Layer | Authority |
| --- | --- |
| Immutable release | Validated SQLite payload and derived indexes |
| User installation | Which exact packages the user currently owns |
| Dependency closure | Why a package remains installed |
| Process binding | Which exact package a process currently uses |
| Private data space | Mutable per-user, per-package runtime data |

The implementation exposes this lifecycle through `PackageManager`, package repository
operations and bounded PostgreSQL functions rather than separate Java classes named
`PackageInstallationService`, `PackageUninstallService`, or `PackageGarbageCollector`:

```text
`package.install` / `package.uninstall`
`PackageManager` and `JdbcPackageRepository`
`PackageDataService`
controlled package SQL functions
```

Local operations use this contract. Market installation ultimately invokes the local installation
contract for each downloaded artifact, while the receipt shortcut and bundle atomicity remain
future work; Market uninstall invokes the core uninstall contract before updating its receipt.

## 4. Historical Baseline Integration

The original design proposed a separate `V002__AtomicPackageLifecycleAndPrivateData` migration.
Before the baseline was frozen, the lifecycle schema was merged into the final V001 module,
`db/baseline/package_lifecycle.sql`. The active-effect quota index was likewise merged into
V001's `db/baseline/effect_terminal_audit.sql`; the next schema change is an immutable V002 or
later forward migration.

Do not modify `V001__CilexecBaseline` or its baseline SQL files after a public release.

Files:

```text
src/main/java/db/migration/V001__CilexecBaseline.java
src/main/resources/db/baseline/package_lifecycle.sql
```

The Java migration executes the ordered baseline modules in one Flyway transaction.

## 5. Historical Design, Implemented in V001: Permanent Release Identity

`package.release_identity` contains:

- `package_hash`, `namespace`, `package_name`, `package_version`,
  `database_file_hash`, `first_registered_at`
- `UNIQUE(namespace, package_name, package_version)`
- `UNIQUE(database_file_hash)`

This table is a permanent identity tombstone. When a release payload is
garbage-collected, its coordinate remains permanently associated with the
original package and file hashes. A different payload can never reuse the same
coordinate. Direct update and delete must be forbidden.

## 6. Implemented Per-User Installation Roots

`package.installation_root` contains `installation_id`, `owner_id`,
`root_package_hash`, `source` (`LOCAL`/`MARKET`/`LEGACY`), `installed_at`,
and `UNIQUE(owner_id, root_package_hash, source)`.

## 7. Implemented Installation Dependency Closure

`package.installation_member` contains `installation_id`, `owner_id`,
`package_hash`, `dependency_depth`, `optional`, `created_at`, and
`PRIMARY KEY(installation_id, package_hash)`.

Every installation root records its complete exact-hash dependency closure,
including itself at depth zero. A package is effectively installed when at
least one installation member references it.

## 8. Implemented Per-Package Private Data Storage

`package.data_space` and `package.data_entry` provide package data. Package data is NOT an
ordinary VFS directory, because ordinary VFS isolation is user-based and other
packages with VFS capabilities could access it.

`data_space`: `space_id`, `owner_id`, `package_hash`, `database_file_hash`,
`logical_bytes`, timestamps, `UNIQUE(owner_id, package_hash)`.

`data_entry`: `space_id`, `relative_path`, `entry_type` (`FILE`/`DIRECTORY`),
`object_hash`, `byte_size`, `state_version`, timestamps,
`PRIMARY KEY(space_id, relative_path)`. Files reference
`object_store.object`. No symlinks, mounts, or automatic revision history.

## 9. Virtual Package Data Root

The package-facing logical root is `package-data://<database-file-sha256>/`.
It is not a regular VFS path and cannot be moved with `file.rename()`.
All package-internal paths are relative and traversal-free. Identity is
`(owner_id, package_hash)`, so user path changes cannot make package data
disappear.

## 10. Implemented Package Execution Provenance

Package functions are linked into the caller's program and built-in calls carry package identity
through transient runtime metadata (`FclProgram.Function.packageIdentity`,
`FclFunctionRegistry.Invocation.packageIdentity`) in `FclProgram`,
`FclProgramLinker`, `FclRuntime`, and `FclExpressionEvaluator`. Nested
dependency calls switch identity; returning restores the caller. Top-level
user code has no package identity. The persisted continuation and program
codec formats are not changed; provenance is reconstructed deterministically
every execution slice.

## 11. Package-Internal Data API

`packageData` namespace: `root`, `exists`, `read`, `readChunk`, `write`,
`append`, `mkdir`, `list`, `remove`, `rename`, `size`, `usage`. No function
accepts a package hash; identity comes from invocation provenance. Top-level
user FCL calls are rejected. Writes use immutable content objects and CAS entry
updates, committed with the current execution slice.

## 12. Implemented Package Data Capability

The package manifest capability key `package.data` is enforced by `PackageCapabilityPolicy`,
which
maps every `packageData.*` call to it. A package does not need `VFS_READ` or
`VFS_WRITE` for private data, but still needs `vfs.read`/`vfs.write` for
ordinary user files. No SQLite package format bump is required.

## 13. User Package Data Management

`package.dataInfo`, `package.dataList`, `package.dataRead`,
`package.dataExport`, `package.dataImport`, `package.dataClear`. The target
package must be effectively installed. Exports are deterministic SQLite
archives in the user's VFS and are ordinary user files. Cross-user operations
require `SYSTEM_ADMIN`.

## 14. Exact-Version Isolation

Private data is keyed by immutable logical `packageHash`. Different versions
never share storage automatically. Migration is explicit via
`package.dataExport` + `package.dataImport`. No lifecycle hooks.

## 15. Implemented Package Data Quotas

`package.data_policy` (default 256 MiB) and `package.data_quota_override` enforce quotas.
Writes lock the data-space row, compute old/new usage, resolve the effective
quota, and reject writes exceeding it. Administrators cannot reduce a quota
below current usage. Management functions: `package.dataQuota`,
`package.setDataQuota`, `package.clearDataQuota`.

## 16. Implemented Installation Contract

Release registration, dependency closure, installation roots/members, private data space
creation, managed Market cache registration, and audit are one persisted installation contract.
`package.install()` publishes this contract atomically; reinstallation is idempotent and
preserves private data. The earlier service names were design placeholders, not current class
names.

## 17. Local Installation Flow

`package.install(vfsPath)` reads and validates the SQLite package, registers
or reuses the immutable release, requires mandatory dependencies, creates a
`LOCAL` root plus members and missing data spaces in one SERIALIZABLE
transaction. It never executes package code, creates aliases, deletes the
source `.db`, or runs lifecycle hooks.

## 18. Future: Atomic Market Installation Flow

Market installation should become download staging plus atomic publication: load and
validate the index, resolve the mandatory dependency graph, download and verify
every `.db` into managed staging cache, inspect every package, then invoke
the shared persisted installation contract once for the complete bundle. Downloads are
staging artifacts; only the transactional publication makes a package
installed.

## 19. Future: Remove VFS Receipt Authority

`/market/installed.json` should no longer be authoritative. `market.list()` should query the
installation ledger; the index can remain at `/market/index.json` and downloaded files at
`/market/packages/{sha256}.db` as database-tracked managed artifacts.

## 20. Implemented Access Enforcement

`package.list()`, `market.list()`, `package.info()`,
`package.verify()`, `package.resource()`, `package.run()`, `import`, and
binding creation all require an effective current-user installation. Knowing a
global SHA-256 is no longer enough. Administrators receive separate global
release diagnostics. `package.pin()` remains as a deprecated validation alias.

## 21. Implemented Uninstall Contract

`package.uninstall(sha256)` and `package.uninstall(sha256, {"force": true})` use the shared
persisted uninstall contract. `market.uninstall()` delegates to that contract.

## 22. Uninstall Preflight

Compute a complete plan (roots, closures, dependents, bindings, processes,
caches, data spaces, orphan dependencies, other-user and global references)
with deterministic row locking before any change. Any pre-commit failure leaves
state unchanged.

## 23. Default Uninstall

Without `force`: purge already-terminal processes, reject if active processes
use the package, reject if another current-user root depends on it, and report
blocking PIDs, states, roots, and dependency paths.

## 24. Forced Uninstall

With `force=true`: remove affected current-user roots, fence and purge
affected processes (leases, timers, effects, inputs, locks, continuations,
bindings), delete target and orphan data spaces, Market cache nodes, roots and
members, then run global GC with one summary audit event. Self-uninstall is
always rejected; issue it from another terminal process.

## 25. Dependency Autoremove

A dependency is removed only when it has no explicit root, no other retained
root references it, no process binding uses it, and no retained root reaches
it. Explicit installs are retained. Default uninstall rejects reverse
dependents; forced uninstall removes affected current-user roots atomically.

## 26. Private Data Removal

Delete entries, data space, managed cache objects, and recompute usage when
the user no longer effectively owns the package. Shared dependencies keep
their spaces. Reinstalling after complete uninstall creates an empty space.

## 27. User Data That Must Survive

Never delete ordinary VFS documents, files edited by a package, local source
`.db` files, exported data archives, audit events, identity tombstones,
other users' data, or payload objects still referenced by independent VFS
nodes.

## 28. Global Release Garbage Collection

Purge a global release only when no installation member, process binding,
retained dependency, data space, or concurrent publication references it.
Delete derived indexes first, then the release, orphan chunk manifests, and
orphan objects, using a controlled `SECURITY DEFINER` function with
transaction-local `app.cilexec_gc=on`. Keep `package.release_identity`
permanently.

## 29. Atomicity

Installation visibility and the entire uninstall operation are transactional
under SERIALIZABLE isolation with deterministic row locking. Any failure
rolls back every change. Scheduler/effect workers are notified only after
commit. Existing state-version and execution-epoch fencing must hold.

## 30. Database Security

All new user-owned tables require forced RLS. Direct DML by tenant roles is
revoked. Bounded `SECURITY DEFINER` functions cover installation, uninstall,
package data, quotas, export/import, GC, and cross-user administration, with
identity resolution, explicit search paths, validation, and allowlisting.

## 31. Permissions

- Local install: `PACKAGE_IMPORT`
- Import/run: `PACKAGE_BIND`
- Private package data from package code: declared `package.data` and `PACKAGE_BIND`
- User data export: `PACKAGE_BIND` + `VFS_WRITE`
- User data import: `PACKAGE_BIND` + `VFS_READ`
- Normal uninstall: `PACKAGE_IMPORT` + `PACKAGE_BIND`
- Forced process cleanup: `PROCESS_CONTROL_OWN`
- Cross-user operations: `SYSTEM_ADMIN`

## 32. Logical Export and Backup

`LogicalExportService` exports and verifies installation roots/members,
release identities, data policies, quota overrides, data spaces/entries,
managed artifacts, referenced data objects, and bindings. Restore validation
checks objects, references, usage totals, quotas, closures, and user
isolation.

## 33. Future: Recovery Repair Validation

Extend recovery checks for missing releases/members, invalid closures,
bindings for uninstalled packages, missing data objects, usage mismatches,
quota violations, duplicate spaces, invalid paths, orphan managed caches,
incomplete forced uninstalls, and GC-eligible releases. Make
`package.recover()` a real administrator consistency report with explicit,
audited repairs.

## 34. Historical Baseline Backfill

The V001 `package_lifecycle.sql` module copied existing releases into `package.release_identity`,
created `LEGACY` roots from `imported_by` and process bindings, built their dependency closures,
and created empty data spaces. It did not parse historical Market receipts into `MARKET` roots;
receipts are not installation authority. This historical baseline work and the active effect-
quota index are both part of V001.

## 35-43. Historical Implementation Order, Testing, and Completion Criteria

The completed implementation order was: V001 baseline lifecycle schema and tests,
domain/repository plumbing, installation publication, access enforcement, provenance,
`packageData.*`, user-data management, quotas, uninstall, forced cleanup, autoremove, global
cleanup, Market delegation, export/recovery updates, full tests, and documentation. This is not
an instruction to create a package-lifecycle migration after V001.

Completion criteria after successful per-user uninstall: no listing, import,
run, binding, process (with force), Market cache, receipt, data space, or
orphan dependency remains. After the final global reference disappears: no
payload release row, derived index, or unreferenced package object remains,
while identity tombstones, audit history, ordinary user files, exports, and
other users' state survive.

## Final Invariant

One immutable SQLite package payload plus an authoritative per-user
installation ledger, an exact-package private data space, a dependency
reference graph, one shared local/Market installation service, one shared
uninstallation service, and one controlled global garbage collector. All
package lifecycle changes are PostgreSQL transactions, require no lifecycle
scripts, survive crashes, remain user-isolated, and roll back completely on
failure.
