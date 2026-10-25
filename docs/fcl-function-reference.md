# FCL 0.0.5 Function and Object Reference

This reference documents the **current** CilExec 0.0.5 FCL surface. It is compiled from the
function registry, entity-object method registrations, and terminal command parser in
`src/main/java/com/follarce`. The [0.0.4 reference](fcl-function-reference-004.md) documents an
older, incompatible interface and is kept only as a historical copy.

Use `system.list()` in a running instance to see the exact registered qualified names for
the JAR you are running, and `memory.list({"includeRuntime":true})` to see the current
process's variables plus built-in function metadata.

| Layer | Value in 0.0.5 |
| --- | --- |
| CilExec product version | `0.0.5` |
| FCL source language version | `fcl-0.0.5` |
| FCL program and continuation format | `5` |
| Package database (`.db`) format | `3` |
| Database schema (Flyway) | `V005` (schema version 5) |
| Legacy entity API | **removed** — see [Removed 0.0.4 APIs and replacements](#removed-004-apis-and-replacements) |

## Contents

- [Quick start](#quick-start)
- [How to discover the live API](#how-to-discover-the-live-api)
- [FCL language essentials](#fcl-language-essentials)
- [Two kinds of object: values and entity handles](#two-kinds-of-object-values-and-entity-handles)
- [Entity objects](#entity-objects)
  - [Files, directories, paths, and leases](#files-directories-paths-and-leases-file)
  - [Processes](#processes-process)
  - [Swap pools](#swap-pools-swappool)
  - [Messaging](#messaging-ipc)
  - [Packages, package data, and quotas](#packages-package-data-and-quotas-package)
  - [Users, programs, and terminal sessions](#users-programs-and-terminal-sessions-userprogramterminalsession)
- [Removed 0.0.4 APIs and replacements](#removed-004-apis-and-replacements)
- [Terminal colon commands](#terminal-colon-commands-not-fcl-functions)
- [General data and permission rules](#general-data-and-permission-rules)
- [Pure namespaces](#pure-namespaces)
- [I/O and process memory](#io-and-process-memory)
- [Durable namespaces](#durable-namespaces)
- [System and administration namespaces](#system-and-administration-namespaces)
- [Networking and sockets](#networking-and-sockets)
- [Packages and the market](#packages-and-the-market)
- [0.0.5 compatibility and old-data refusal](#005-compatibility-and-old-data-refusal)
- [Quick lookup and troubleshooting](#quick-lookup-and-troubleshooting)
- [Implementation sources](#implementation-sources)

## Quick start

`[]` marks optional arguments, `<...>` marks required ones. Paths resolve relative to the
current durable working directory (`env.get("PWD")`, changed with `:cd`); a leading `/` is
an absolute VFS path.

```fcl
// Read and write a file through one chained object call chain.
note = file.create("/demo/note.txt", "first")
note.append(" second")
io.println(note.read())          // first second

// Open an existing node and inspect its durable metadata.
node = file.open("/demo/note.txt")
io.println(node.size())
io.println(node.metadata())

// Create a directory object, then list it.
dir = file.mkdir("/demo/sub")
io.println(dir.list())

// Control a process by its durable identity.
io.println(process.find(process.getPID()).status())

// Use a swap-pool variable handle.
pool = swapPool.create("demo")
value = pool.variable("count")
pool.add("count", 1)
io.println(value.get())
```

Entities are always created through a factory (`file.open`, `process.find`, ...). The
factory checks access once, and **every method call checks current database state and
permissions again**. A handle is a durable identity, not a cached copy and not a password.

## How to discover the live API

| Call | Effect |
| --- | --- |
| `system.list()` | Return every visible qualified function name and alias accepted by this Runtime. Removed legacy spellings are absent. |
| `memory.list()` | Return `{variables, functions}` for the current FCL process. |
| `memory.list({"includeRuntime":true})` | Also include immutable built-ins, aliases, and Java-extension functions as function metadata. |
| `memory.list({"includeParents":true})` | Include lexical root variables that are visible from the current scope. |
| `system.extensions()` | Return the sealed compile-time Java extensions (`id`, `version`, `description`). |
| `util.which(name)` | Return `0` for a runtime/extension function, the package database SHA-256 for a bound package export/entrypoint, or `null` for source-defined and unknown names. |

Function lookup rules:

- Fully qualified names (`file.open`, `package.find`) are unambiguous and recommended.
- A bare name (for example `open`) resolves only when exactly one function has it; several
  built-ins share a bare name, so an ambiguous bare call is a runtime error that lists the
  qualified candidates.
- User-defined functions and imported package functions take precedence over built-ins in the
  same program.
- Removed 0.0.4 names are registered internally for object-method forwarding but **cannot be
  called from FCL**. `system.invoke` cannot reach them either.

## FCL language essentials

FCL compiles to an interpreter instruction table. Each execution slice is one database
transaction and one recovery checkpoint. A process may batch up to 4,096 interpreter steps or
20 ms per slice and stops when it suspends, executes a directive, completes, or fails. A crash
rolls back the whole uncommitted slice, which may then replay from the preceding checkpoint.

- **Comments.** Only `//` line comments exist; everything from `//` to the end of the line is
  ignored. `#` has nothing to do with comments.
- **Length operator.** `#` is the only unary length operator. `#value` returns the UTF-16
  code-unit count of a string, the element count of an array or map, and `1` for a single
  non-collection value such as a system handle. `#null` is `0`.
- **Reserved words.** `func`, `if`, `else`, `while`, `break`, `continue`, `return`,
  `import`, `include`, `as`, `and`, `or`, `not`, `public`, `private`, `true`,
  `false`, `null`, `class`, `extends`, `new`, `this`, `super`, `link`, `try`,
  `catch` cannot be used as identifiers, function names, or parameter names; doing so is a
  compile error.
- **String escapes.** Only `\n`, `\t`, `\r`, `\\`, `\"` are valid. Any other escape (for
  example `\q` or `\u0041`) is a compile error.
- **Multiline strings.** String literals may span lines: a literal newline inside the quotes
  becomes part of the value (line-ending `\r\n` or `\r` is normalized to `\n`). The
  interactive terminal keeps reading while a string is open.
- **Statements and blocks.** Statements end at a newline or `;`; `if` / `else if` /
  `else`, `while`, `break`, `continue`, and `return` are supported. There is no `for`
  loop and no `throw`/`finally`.
- **Top-level return.** `return` is legal at the top level, and its value becomes the program
  result. REPL submissions and the `package.run` wrapper rely on this.
- **Exceptions.** `try { ... } catch (e) { ... }` catches deterministic FCL program failures
  (`UndefinedFunction`, `UndefinedMethod`, `StaleHandle`, arithmetic failures, and other
  `FclRuntimeException` cases). `e` is catch-local and immutable, with `e.type`,
  `e.message`, and `e.stack`; each stack frame exposes `function`, `source`, `line`, and
  `column`. Kernel faults, forced termination, and database consistency failures cannot be
  caught. Catch values and active handlers are part of every checkpoint.
- **Numeric rules.** `+`, `-`, `*` and unary negation throw on integer overflow; results
  never silently wrap. Comparing a string with a number using an ordering operator is an
  error. Booleans are not ordered (`<`, `<=`, `>`, `>=` on bools fail); `==`, `!=`,
  `and`, `or`, `not` still work.
- **Truthiness.** `0`, `null`, `false`, `""`, empty arrays, and empty maps are false; every
  other value is true. `not value` is the textual spelling of `!value`; `and` and `or`
  short-circuit.
- **Postfix updates.** Numeric names, public fields, and array/map elements support postfix
  `++` / `--`. There is no prefix form. An update expression evaluates to the updated value.
  Updating a non-number, a private value, or a system handle is rejected.
- **Value display.** All "FCL value → text" paths (string concatenation, `text.join`,
  `util.toString`) share one display rule: top-level strings are verbatim, numbers/booleans
  use plain text, `null` renders as `null`, containers render in FCL literal syntax with
  JSON-style quoting inside, and a system handle renders as `Kind{identity}`. Host Java
  representations never leak.
- **String indexing.** String length, indexing, and text slice/search positions use Java UTF-16
  code units. A supplementary Unicode character occupies two positions.
- **Function scopes.** Every invocation has fresh parameters and locals. Base-program functions
  resolve names from their program's top-level scope; imported-module functions resolve only
  from their module's persisted globals. No function can read a caller's locals. Root bindings
  and call frames persist across suspension and recovery.
- **Module visibility.** `public func` exports a module function (the default for an
  unqualified `func`); `private func` is module-internal. `public class` is importable;
  `private class` is not exported.
- **import/include placement.** `import` and `include` are top-level only. `import`
  accepts the full SHA-256 of an installed package database, optionally with a private alias;
  `include` splices a VFS source file before compilation.
- **Method calls on expressions.** `receiver.method(args)` works when `receiver` is a system
  handle stored in a named variable, and `expression.method(args)` works for any expression
  result, for example `file.open("/note.txt").read()` or `process.find(41).kill()`. A
  user-class method called on a temporary expression executes against that temporary object;
  the result is usable, but there is no named receiver to write changes back to.

## Two kinds of object: values and entity handles

FCL has **two different object models**, and confusing them is the most common 0.0.5 mistake.

| Model | Created by | Assignment, arguments, returns | Identity |
| --- | --- | --- | --- |
| User class instance | `new ClassName(...)` | Deep copy — an independent value | No durable identity; equality compares class and fields |
| System entity handle | An entity factory such as `file.open` | The same handle identity is retained | Durable identity (`kind` plus id); equality compares the handle record |

```fcl
class Counter { value = 0
    func increment() { this.value++ }
}
a = new Counter()
b = a                 // copy: b is an independent Counter
b.increment()
io.println(a.value)   // 0
io.println(b.value)   // 1

note = file.create("/note.txt", "first")
same = note           // handle: same file identity
same.append(" second")
io.println(note.read()) // first second
```

Rules that follow from the two models:

- Copying a handle keeps the same entity target. `same.read()` and `note.read()` address the
  same node, pool, process, or delivery.
- Handles are persisted as part of the continuation after every committed slice, so a handle
  survives terminal logout, reconnect, Runtime restart, and crash recovery.
- A handle stores only `kind`, a stable identity string, and optional private attributes (for
  example a lease fencing token or a topic name). It stores **no Java pointer, no grant, and no
  cached permission**.
- A handle can become **stale**. Removing the entity invalidates every existing handle; a new
  entity created later at the same path or with the same PID does not revive the old handle.
- Each method call re-checks the caller's current capabilities, the current owner, the database
  permission/RLS boundary, and the entity's current state.
- Because `b link a` is a persisted name-following relation, a link to a handle name follows
  the name; it does not clone the entity or create a second permission grant.

The full beginner explanation is in the [object guide](fcl-object-oriented-guide.md)
([中文](fcl-object-oriented-guide.zh-CN.md)).

### How receiver dispatch works

The lexer reads a dotted identifier as **one token**. The parser then produces two shapes:

| Written form | Parsed as | Dispatch |
| --- | --- | --- |
| `h.read()` where `h` is a name | namespace-style call `h.read` | The evaluator first checks whether the name `h` currently holds a system handle. If it does, it dispatches the method on that handle's kind. Otherwise it falls back to the normal qualified function/user-class rules. |
| `file.open(p)` | namespace-style call `file.open` | Built-in namespace factory. |
| `a.child.method()` where `a` names a user object | namespace-style call with member path | Resolves the user-object field path and dispatches the class method on the receiver. |
| `file.open(p).read()`, `pool.variable(n).get()`, `pkg.data().read(x)` | expression method call | The target expression is evaluated first; a system handle dispatches by handle kind, a user object dispatches by class. |
| `expresssion.field` | member read | Reads a user-object field; system handles expose no writable fields. |

Both forms are valid and equivalent for system handles. Prefer the chained form for one-off
work and a named variable when you need the handle again or want to pass it around. A user-class
method called on a temporary expression runs, and its return value is usable, but there is no
named receiver to write changes back to.

## Entity objects

Every factory returns a durable handle. The table lists the complete registered surface.
Plain functions that stayed in a namespace are documented later under
[Durable namespaces](#durable-namespaces).

| Factory | Handle kinds and methods | Notes |
| --- | --- | --- |
| `file.open(path [, targetUser])` | `File`: `read()`, `readChunk(offset, maximumBytes)`, `size()`, `metadata()`, `write(text)`, `append(text)`, `rename(newName)`, `remove()`, `lock(milliseconds)`; `Directory`: `list()`, `clear()`, `metadata()`, `rename(newName)`, `remove()` | The node must exist; the handle kind follows the node type. `lock` returns a `FileLease` or `null`. |
| `file.path(path)` | `Path`: `exists()` | A path value, not an existing node. |
| `file.create(path [, content])` | `File`, with all `File` methods | Creates a new file in the current user's VFS; fails if the path exists. |
| `file.mkdir(path)` | `Directory`, with all `Directory` methods | Creates a new directory in the current user's VFS. |
| `process.find(pid)` | `Process`: `status()`, `wait()`, `pause()`, `continue()`, `kill()`, `send(payload [, expiresAt])`, `removeFinished()` | Bound to the stable process UID behind the public PID. Control capability is re-checked per call. |
| `swapPool.create(path)` / `swapPool.open(path)` | `SwapPool`: `get(name)`, `add(name, value)`, `list()`, `clear()`, `remove()`, `variable(name)` | `create` fails when the pool exists; `open` fails when it does not. Pools belong to the current user. |
| `SwapPool.variable(name)` | `SwapVariable`: `get()`, `update(value [, token])`, `remove([token])`, `lock(milliseconds)`, `signal()`, `waitFor()` | `lock` returns a `SwapLease` or `null`. |
| `SwapVariable.lock(ms)` | `SwapLease`: `renew(milliseconds)`, `release()` | The lease handle carries the fencing token. Renewing an expired lease fails. |
| `ipc.createChannel(name)` / `ipc.channel(id)` | `Channel`: `send(payload [, expiresAt])`, `subscribe()`, `remove()` | Channel identity is a UUID. Methods require an owned ACTIVE channel. |
| `ipc.createTopic(name)` / `ipc.topic(name)` | `Topic`: `publish(payload [, expiresAt])`, `broadcast(payload [, expiresAt])`, `subscribe()`, `remove()` | Topic identity is stable; the handle also stores its name. |
| `ipc.delivery(deliveryId)` | `Delivery`: `consume()` | Only the receiving process can address its delivery. |
| `package.find(sha256OrCoordinate)` / `package.install(vfsPath)` | `Package`: `info()`, `verify()`, `run([entrypoint])`, `resource(path)`, `pin()`, `uninstall()`, `data()` | Identity is the exact installed database-file SHA-256. |
| `Package.data()` | `PackageSpace`: `root()`, `info()`, `usage()`, `list([path])`, `read(path)`, `write(path, text)`, `export(vfsPath)`, `import(vfsPath)`, `clear()`, `quota()`, `exists(path)`, `readChunk(path, offset, maximumBytes)`, `seedResource(resourcePath, destinationPath)`, `append(path, text)`, `mkdir(path)`, `remove(path)`, `rename(source, destination)`, `size(path)` | Owner-level methods work for the owning user; the private package-code methods require execution inside that same package. |
| `packageData.current()` | `PackageSpace` for the calling package | Requires linked installed package code with the `package.data` capability. |
| `package.quotaFor(user, sha256)` | `PackageQuota`: `set(bytes)`, `remove()` | Administrator only; `user` is a username or UUID. |
| `user.find(usernameOrUuid)` / `user.create(username, password [, adminCredentials])` | `User`: `info()`, `disable()`, `remove()` | Ordinary users can only find themselves; cross-user operations require administrator identity. |
| `program.find(programId)` | `Program`: `remove()` | Administrator only; referenced Programs cannot be removed. |
| `terminal.find(sessionId)` | `TerminalSession`: `remove()` | Administrator only; only CLOSED sessions can be removed. |

### Per-call semantics for all handles

1. **Kind and identity are fixed at creation.** `util.typeOf(handle)` returns the kind
   (`File`, `Process`, ...); `util.toString(handle)` returns `Kind{identity}`; `#handle`
   is `1`.
2. **Identity checks happen per call.** The implementation re-reads the entity, checks that it
   still exists, re-checks that its type still matches the handle kind, and re-checks the
   caller's capabilities and database permission before doing any work.
3. **Staleness is explicit.** A removed node/process/topic/package yields a `StaleHandle` (or
   `Unknown ...`) runtime error naming the condition; it never silently targets a different
   entity.
4. **Copies share identity.** Assignment, argument passing, array/map storage, and returns keep
   the same target. `==` on two handles compares kind, identity, and stored attributes.
5. **Leases are fenced.** `FileLease` / `SwapLease` carries a fencing token; a lease renewal
   after expiry fails with `ExpiredLease` for method calls. The non-object lock functions
   return the raw `{fencingToken, leaseUntil}` map when you need it.
6. **Data copies at the boundary.** Method arguments and results are deep-copied by the
   registry; a handle value inside those arguments/results still keeps its identity.


### Files, directories, paths, and leases (`file`)

```fcl
note = file.create("/notes/today.txt", "line 1\n")
note.append("line 2\n")
io.println(note.size())
io.println(note.metadata())

same = note
same.append("line 3\n")            // note.read() sees the new text too

renamed = note.rename("today-renamed.txt")   // the handle still points at the same node
io.println(renamed)                 // true

lease = note.lock(30000)            // FileLease or null
if lease != null {
    lease.renew(30000)
    lease.release()
}

dir = file.mkdir("/notes/archive")
io.println(dir.list())
io.println(file.path("/notes/archive/old.txt").exists())   // false
```

Factory and method semantics:

| Call | Permission and behavior |
| --- | --- |
| `file.open(path [, targetUser])` | Requires `VFS_READ`; a cross-user target additionally requires administrator identity. The node must exist. Returns a `File` or `Directory` handle by node type. |
| `file.create(path [, content])` | Requires `VFS_WRITE`. Creates a file in the current user's VFS and fails if the path exists. The optional second argument is the initial text (default the empty string). |
| `file.mkdir(path)` | Requires `VFS_WRITE`. Creates one directory in the current user's VFS. |
| `file.path(path)` | Creates a `Path` value; no database access happens until `exists()` is called. |
| `File.read()` | Requires `VFS_READ`. Reads the whole UTF-8 file, capped at 16 MiB; use `readChunk` for larger files. |
| `File.readChunk(offset, maximumBytes)` | Requires `VFS_READ`. `offset` must be non-negative, `maximumBytes` 0 to 4 MiB. Returns the decoded UTF-8 range. |
| `File.size()` | Requires `VFS_READ`. Returns the logical byte size. |
| `File.metadata()` / `Directory.metadata()` | Requires `VFS_READ`. Returns the node map: `nodeId`, `ownerId`, `name`, `type`, `revisionEnabled`, `updatedAt`, and `objectHash` when present. |
| `File.write(text)` | Requires `VFS_WRITE`. Replaces the whole file content; returns the node id. |
| `File.append(text)` | Requires `VFS_WRITE`. Appends UTF-8 text. Own-user append is chunked; administrator cross-user append loads the old content and refuses an existing file over 16 MiB. |
| `File.rename(newName)` | Requires `VFS_WRITE`. Renames inside the same directory; the name cannot contain slashes, be `.` or `..`, or contain control characters. Returns `true`. **Node identity and every handle survive the rename.** |
| `Directory.rename(newName)` | Same rules for a directory node. |
| `File.remove()` / `Directory.remove()` | Requires `VFS_WRITE`. Removes the node. Roots, mounts, versioned files, and non-empty directories are rejected and nothing is deleted. Returns `true`; existing handles become stale. |
| `Directory.list()` | Requires `VFS_READ`. Returns child node metadata maps. |
| `Directory.clear()` | Requires `VFS_WRITE`. Recursively deletes everything inside, keeping the directory. The target must be an existing non-root directory; encountering a versioned file or mount anywhere aborts the whole call. Returns the number of entries removed. |
| `File.lock(milliseconds)` | Requires `VFS_WRITE`. Acquires a file lease owned by the logical process (process UID plus execution epoch). Returns a `FileLease` handle with a fencing token, or `null` when the lock is busy. |
| `FileLease.renew(milliseconds)` | Renews the lease for the same process. Throws `ExpiredLease` when the lease has expired, and `StaleHandle` when the node was removed. |
| `FileLease.release()` | Releases the lease; returns the repository transition result. |
| `Path.exists()` | Requires `VFS_READ` or `VFS_WRITE`; returns whether the path currently resolves. It does not create a handle target. |

A single VFS file supports at least 1 GiB. Write large files with repeated `append` calls and
inspect or read them in segments with `size()` and `readChunk(...)`; do not load them into one
FCL string with `read()`. Symbolic links are followed by reads (fewer than 16 links; cycles
error out). `file.link(linkPath, targetPath)` remains a namespace function: it creates a
symbolic-link node whose content is the target path.

### Processes (`process`)

```fcl
handle = process.find(process.getPID())
io.println(handle.status())          // RUNNING / READY / WAITING_* / TERMINATED / FAILED
handle.send({"ping": true})          // durable direct IPC message to this process

child = process.fork()               // parent receives the child PID; the child resumes with 0
if child != 0 {
    io.println(process.find(child).wait())   // {pid, status} once the child ends
}
```

| Call | Behavior |
| --- | --- |
| `process.find(pid)` | Requires the process-control capability (own or any, or administrator). Returns a `Process` bound to the process UID, not to the reusable public PID. |
| `Process.status()` | Returns the current status name. |
| `Process.wait()` | If the target already ended, returns `{pid, status}` immediately; otherwise suspends the caller and returns that map when the process ends. |
| `Process.pause()` / `Process.continue()` | Pauses/resumes a controllable process and returns whether the transition happened. A RUNNING process cannot change its own scheduler state. |
| `Process.kill()` | Terminates the target. Killing yourself is equivalent to `util.exit()` and returns `true`. |
| `Process.send(payload [, expiresAt])` | Sends one durable direct message to the process behind the handle. `expiresAt` is an optional ISO-8601 instant. |
| `Process.removeFinished()` | Administrator only. Explicitly removes a TERMINATED or FAILED process together with its persisted state; running, suspended, and waiting processes are never removed. Returns whether it was removed. |

The corresponding plain namespace operations remain for process arguments, creation, and
waiting: `process.args()`, `process.arg(i)`, `process.getPID()`, `process.getPPID()`,
`process.listChildren()`, `process.list()`, `process.fork()`, `process.run(path, args)`,
`process.exec(path [, args])`, `process.wait()`, and administrator
`process.clearFinished()`. See [Durable namespaces](#durable-namespaces).

### Swap pools (`swapPool`)

```fcl
pool = swapPool.create("job-42")
counter = pool.variable("counter")
pool.add("counter", 1)                // SwapPool.add(name, value)
io.println(pool.list())               // variables in this pool

lease = counter.lock(10000)           // SwapLease or null
if lease != null {
    counter.update(2, lease)          // update(value [, fencingToken])
    lease.renew(10000)
    lease.release()
}

counter.signal()
counter.waitFor()                     // blocks until signalled
pool.remove()                         // remove the whole pool
```

| Call | Behavior |
| --- | --- |
| `swapPool.create(name)` | Creates a pool owned by the current user; fails if it already exists. Returns a `SwapPool`. |
| `swapPool.open(name)` | Opens an existing pool; fails for an unknown name. Returns a `SwapPool`. |
| `SwapPool.get(name)` | Reads one variable. Default `ALWAYS` values stay readable until removed; `SYNC` clears its changed flag; `TIMES(n)` decrements remaining reads and becomes unreadable at zero. Returns `null` while the variable is locked; release the lease first. |
| `SwapPool.add(name, value)` | Adds/updates a variable with ALWAYS retention. Returns the repository result value. |
| `SwapPool.list()` | Lists this pool's variables. The namespace call that listed every pool is removed; keep pool paths yourself or use your own index. |
| `SwapPool.clear()` | Clears the pool's variables, keeping the pool. |
| `SwapPool.remove()` | Removes the whole pool. |
| `SwapPool.variable(name)` | Returns a `SwapVariable` handle. |
| `SwapVariable.get()` | Consumes/reads the variable value; `null` when not currently readable. |
| `SwapVariable.update(value [, leaseOrToken])` | Updates the value; when locked, pass the `SwapLease` handle (or its numeric fencing token). |
| `SwapVariable.remove([leaseOrToken])` | Removes the variable; when locked, pass the `SwapLease` handle (or its numeric fencing token). |
| `SwapVariable.lock(milliseconds)` | Acquires a variable lease owned by the logical process; returns a `SwapLease` or `null`. |
| `SwapVariable.signal()` | Sends a variable signal. |
| `SwapVariable.waitFor()` | Waits for a signal; returns `true` when received. |
| `SwapLease.renew(milliseconds)` | Renews the lock; throws `ExpiredLease` after expiry. |
| `SwapLease.release()` | Releases the lock. |

Pool names are not VFS paths: they must match `[A-Za-z0-9_-]{1,128}` (no slashes). Variable
names must match `[A-Za-z_][A-Za-z0-9_.-]{0,127}`. Invalid names fail with
`InvalidSwapPoolName` / `InvalidSwapVariableName` before touching the database.

Swap-pool locks belong to the logical process (process UID), not to a single scheduler time
slice: the same process can renew or release them when it runs again, as long as the lease has
not expired and the current fencing token is used. No other process can use that token even
when it knows the variable name.


### Messaging (`ipc`)

```fcl
channel = ipc.createChannel("work")
channel.subscribe()
channel.send({"job": 1})

topic = ipc.createTopic("events")
topic.subscribe()
topic.publish({"type": "started"})
topic.broadcast({"type": "tick"})

envelope = ipc.receive()          // or ipc.poll() for a non-blocking reserve
if envelope != null {
    delivery = ipc.delivery(envelope["deliveryId"])
    delivery.consume()
}
ipc.purge("2026-01-01T00:00:00Z", 1000)
```

| Call | Behavior |
| --- | --- |
| `ipc.createChannel(name)` | Creates a durable channel; returns a `Channel` handle whose identity is the generated UUID. |
| `ipc.channel(channelId)` | Opens an existing owned ACTIVE channel; fails for an unknown or removed channel. |
| `ipc.createTopic(name)` | Creates a durable topic; returns a `Topic` handle whose identity is the topic id and whose private attributes store the name. |
| `ipc.topic(name)` | Opens an existing owned ACTIVE topic by name. |
| `ipc.delivery(deliveryId)` | Returns a `Delivery` handle only when the delivery's receiver is the current process. |
| `ipc.receive()` | Blocks until an owned message arrives; returns the envelope map. |
| `ipc.poll()` | Immediately reserves and returns the next available envelope, or `null`. |
| `ipc.purge(olderThan [, limit])` | Deletes owned terminal or expired messages older than the ISO-8601 cutoff. Default limit 1000; maximum 10000. |
| `Channel.send(payload [, expiresAt])` | Sends to one active channel consumer. |
| `Channel.subscribe()` | Subscribes the current process to the channel. |
| `Channel.remove()` | Removes the channel. |
| `Topic.publish(payload [, expiresAt])` | Publishes to active topic subscribers. |
| `Topic.broadcast(payload [, expiresAt])` | Broadcasts to active topic subscribers. |
| `Topic.subscribe()` | Subscribes the current process to the topic. |
| `Topic.remove()` | Removes the topic. |
| `Delivery.consume()` | Marks a received delivery as CONSUMED; only the receiver process can do this. |

An envelope contains `deliveryId`, `messageId`, `kind`, `payloadType`, and either
`payloadJson` or `payloadObjectHash`. Message rows are durable after consumption; use
`ipc.purge` to release retained payload references. Pending unexpired deliveries are never
purged. The database makes the committed transition to CONSUMED happen at most once;
`ipc.poll()` is reserve/ack — if the Runtime crashes after reservation but before
`Delivery.consume()`, startup resets the unconsumed reservation and the message can be
delivered again. Treat duplicate delivery as possible and consume only after the externally
visible work is safe to repeat or complete.

### Packages, package data, and quotas (`package`)

```fcl
pkg = package.find("<64-character-package-db-sha256>")
io.println(pkg.info())
io.println(pkg.verify())
io.println(pkg.run())                // creates a child process; returns pid/processUid/...

space = pkg.data()
io.println(space.usage())
space.write("settings.json", "{}")
io.println(space.read("settings.json"))

// Inside linked package code only (package.data capability):
mine = packageData.current()
io.println(mine.exists("cache/x.txt"))
io.println(mine.root())
```

| Call | Behavior |
| --- | --- |
| `package.find(sha256OrCoordinate)` | Requires `PACKAGE_IMPORT`. Accepts a full installed database-file SHA-256 or a `namespace/name/version` coordinate of an installed release. Returns a `Package` handle whose identity is the database-file SHA-256 (also for coordinate lookups). |
| `package.install(vfsPath)` | Installs from a `.db` file in the VFS; the package identity is the SHA-256 of its bytes. Creates the installation, the exact dependency closure, and a private data space. Returns a `Package` handle. |
| `Package.info()` | Requires `PACKAGE_IMPORT` and the installation. Returns coordinate, hashes, kind, dependencies, entrypoints, exports, and capabilities. |
| `Package.verify()` | Recomputes the database object hash and returns the information map plus `valid`/`hashMatches` booleans. |
| `Package.run([entrypoint])` | Requires `PACKAGE_BIND` and `PROCESS_CREATE`. Creates a child process running the named entrypoint (default `run`) and returns `{pid, processUid, programId, coordinate, entrypoint}`. |
| `Package.resource(path)` | Reads one declared immutable text resource from the package database. |
| `Package.pin()` | Validates that the release is still installed and returns its metadata. It does not create or change a process binding. |
| `Package.uninstall()` | Requires `PACKAGE_IMPORT` and `PACKAGE_BIND`. Removes the installation, private data, process bindings, caches, and now-unreferenced payloads for the current user. Dependent installations are reported by coordinate; uninstall them first. Ordinary user files and exported archives are never deleted. Returns a summary map. |
| `Package.data()` | Returns a `PackageSpace` for the installed package's private data. |
| `PackageSpace.root()` | Returns the `package-data://<database-file-sha256>/` URI of this space. Requires package code (see below). |
| `PackageSpace.info()` / `usage()` | Usage, quota, file count, and timestamps. |
| `PackageSpace.list([path])` | Lists direct children; literal paths, not patterns. |
| `PackageSpace.read(path)` | Reads one private file as UTF-8 text. |
| `PackageSpace.write(path, text)` | Creates or CAS-replaces a private file. Available to the owning user. |
| `PackageSpace.export(vfsPath)` | Writes a deterministic SQLite archive of the space to the user's VFS; the destination must not exist. |
| `PackageSpace.import(vfsPath)` | Merges an exported archive into the private space. |
| `PackageSpace.clear()` | Deletes every private entry but keeps the empty space. |
| `PackageSpace.quota()` | Returns the effective quota in bytes. |
| `PackageSpace.exists/readChunk/seedResource/append/mkdir/remove/rename/size` | Private package-code operations. They require the handle identity to match the currently executing package: call them from linked installed package code with the `package.data` capability, not from top-level user FCL. |
| `packageData.current()` | Returns the `PackageSpace` of the calling package; requires linked package provenance. |
| `package.quotaFor(user, sha256)` | Administrator only. Returns a `PackageQuota` handle for that user and installed package. |
| `PackageQuota.set(bytes)` | Sets an administrator quota override; cannot be set below current usage. |
| `PackageQuota.remove()` | Removes the administrator override. |

The default private-data quota is 256 MiB per user and exact package. Exact-version isolation is
deliberate: version migration is an explicit `export` + `import` pair. Package private data is
not an ordinary VFS path; arbitrary `file.*` paths cannot reach it.

### Users, programs, and terminal sessions (`user`/`program`/`terminal`)

```fcl
me = user.find(user.getCurrentUser())
io.println(me.info())

created = user.create("alice", "correct horse battery staple")
admin = user.create("ops", "another secret", ["local", "administrator password"])
alice = user.find("alice")
alice.disable()
alice.remove()

program.find("<program-uuid>").remove()
terminal.find("<terminal-session-uuid>").remove()
```

| Call | Behavior |
| --- | --- |
| `user.find(usernameOrUuid)` | Finds a visible user. Ordinary users only see themselves; administrators see all users. Returns a `User` handle bound to the user UUID. Unknown or inaccessible names raise an error. |
| `user.create(username, password [, adminCredentials])` | Creates a user account and returns its `User` handle. Without the third argument the account gets the ordinary capability profile (self-registration). To create an administrator, pass `[administratorUsername, administratorPassword]`; the database re-verifies that administrator's current effective `SYSTEM_ADMIN` atomically. |
| `User.info()` | Returns `{userId, username, status, credentialVersion}`. |
| `User.disable()` | Disables the account without deleting its data. Requires administrator identity. |
| `User.remove()` | Permanently removes the account, all rows it owns, and its no-login PostgreSQL tenant role in one transaction. Requires administrator identity; an administrator cannot remove itself. |
| `program.find(programId)` | Administrator only. Returns a `Program` handle after validating the Program row. |
| `Program.remove()` | Removes the immutable Program and makes its source/FCLB objects unreachable. If any process of any state still references it, or another Program imports it, nothing is deleted and a reference report is returned. |
| `terminal.find(sessionId)` | Administrator only. Returns a `TerminalSession` handle. |
| `TerminalSession.remove()` | Removes one CLOSED terminal session with its committed input rows and attachments. An OPEN session is never removed. |

## Removed 0.0.4 APIs and replacements

0.0.5 **removed** the old entity functions that took a path, PID, channel id, topic name,
delivery id, or package hash on every call. They are not callable from FCL and
`system.list()`, `util.which()`, and `system.invoke()` never expose them. Calling one of the
names below is an `UndefinedFunction` error.

| Removed 0.0.4 API | 0.0.5 replacement |
| --- | --- |
| `file.read`, `file.readChunk`, `file.size`, `file.exists`, `file.list`, `file.readMetaData` | `file.open(path)` then `read()`, `readChunk(o,n)`, `size()`, `list()`, `metadata()`; `file.path(path).exists()` for a pure existence check |
| `file.write`, `file.append`, `file.createFile`, `file.createDir`, `file.remove`, `file.clear`, `file.rename`, `file.lock`, `file.renewLock`, `file.unlock` | `file.create` / `file.mkdir` factories, then the matching node method; `lock()` returns a `FileLease` with `renew()`/`release()` |
| `io.readFile`, `io.writeFile` (old aliases of `file.read`/`file.write`) | `file.open(path).read()`, `file.create(path, text)` or `file.open(path).write(text)` |
| `process.kill`, `process.pause`, `process.continue`, `process.waitPID`, `process.removeFinished`, `system.kill` | `process.find(pid)` then `kill()`, `pause()`, `continue()`, `wait()`, `removeFinished()` |
| `swapPool.get`, `swapPool.add`, `swapPool.update`, `swapPool.list`, `swapPool.clear`, `swapPool.remove`, `swapPool.exists`, `swapPool.lock`, `swapPool.renewLock`, `swapPool.unlock`, `swapPool.signal`, `swapPool.waitFor` | `swapPool.open/create(path)` pool handle, `pool.get/add/list/clear/remove`, `pool.variable(name)`, then variable methods and leases |
| `ipc.sendDirect`, `ipc.sendChannel`, `ipc.publishTopic`, `ipc.broadcast`, `ipc.subscribeChannel`, `ipc.subscribeTopic`, `ipc.removeChannel`, `ipc.removeTopic`, `ipc.consume` | `ipc.channel(id)` / `ipc.createChannel(name)`, `ipc.topic(name)` / `ipc.createTopic(name)`, then `send/publish/broadcast/subscribe/remove`; `ipc.delivery(id).consume()` |
| `package.info`, `package.verify`, `package.run`, `package.resource`, `package.pin`, `package.uninstall` | `package.find(hash)` / `package.install(path)`, then the `Package` method |
| `package.dataInfo`, `package.dataList`, `package.dataRead`, `package.dataExport`, `package.dataImport`, `package.clearData`, `package.dataQuota`, `package.setDataQuota`, `package.removeDataQuota` | `package.find(hash).data()` then `info/usage/list/read/export/import/clear/quota`; administrator quotas use `package.quotaFor(user, hash).set/remove` |
| `packageData.root`, `packageData.exists`, `packageData.read`, `packageData.readChunk`, `packageData.write`, `packageData.seedResource`, `packageData.append`, `packageData.mkdir`, `packageData.list`, `packageData.remove`, `packageData.clear`, `packageData.rename`, `packageData.size`, `packageData.usage` | `packageData.current()` then the matching `PackageSpace` method (private methods require package code); owner-level data management uses `package.find(hash).data()` |
| `user.validateUser`, `user.disable`, `user.remove` | `user.find(nameOrId)` then `info()`, `disable()`, `remove()` |
| `program.remove`, `terminal.remove` | `program.find(id).remove()`, `terminal.find(id).remove()` |

Kept namespace operations (they are services without an entity receiver):
`file.link`; `process.args`, `process.arg`, `process.getPID`, `process.getPPID`,
`process.list`, `process.listChildren`, `process.fork`, `process.run`, `process.exec`,
`process.wait`, `process.clearFinished`; `swapPool.create`, `swapPool.open`;
`ipc.createChannel`, `ipc.createTopic`, `ipc.receive`, `ipc.poll`, `ipc.purge`;
`package.build`, `package.install`, `package.list`, `package.recover`, `package.find`,
`package.quotaFor`; `packageData.current`; `user.getCurrentUser`, `user.isLocal`,
`user.list`, `user.create`, `user.switchUser`, `user.find`; `program.find`; `terminal.find`;
and every `math`, `util`, `array`, `text`, `path`, `env`, `term`, `io`, `memory`,
`network`, `socket`, `market`, `audit`, `timer`, `storage`, and `system` service below.

## Terminal colon commands (not FCL functions)

| Command | Effect |
| --- | --- |
| `:help` | Show terminal help. |
| `:cd <vfs-path>` | Change the durable working directory. |
| `:pwd` | Print the durable working directory. |
| `:ls [vfs-path]` | List a VFS directory (use this instead of a shell `ls`). |
| `:clear` (`:cls`) | Clear the terminal screen. |
| `:logout` | Return to the login prompt without losing REPL state. |
| `:exit` (`:quit`) | Close only this terminal connection. |
| `:shutdown` | Stop the shared Runtime after interactive administrator password verification. |

Everything else typed at the terminal prompt is FCL. Prefix raw input with `::` when a
process is waiting in `io.input()`.

## General data and permission rules

| Item | Rule |
| --- | --- |
| Strings | Double-quoted UTF-8; may span lines. Escapes: `\n`, `\t`, `\r`, `\\`, `\"`. |
| Arrays / maps | `[...]`, `{key: value}`; `#` returns their length. |
| Paths | Relative paths resolve against the process's durable `PWD`; `/` is the VFS root. Host paths never appear in `file.*`. |
| Capabilities | `PROCESS_CREATE`, `PROCESS_CONTROL_OWN`, `PROCESS_CONTROL_ANY`, `VFS_READ`, `VFS_WRITE`, `VFS_MOUNT_HOST`, `PACKAGE_IMPORT`, `PACKAGE_BIND`, `EFFECT_REQUEST`, `TERMINAL_ATTACH`, `AUDIT_READ`, `SYSTEM_ADMIN`. Ordinary terminal users get a narrow default set; administrators hold `SYSTEM_ADMIN`, which satisfies every check. |
| Checking per call | A handle never grants authority. Every method re-reads the entity, checks the owner/identity, re-checks the required capability, and relies on the database's forced row-level security for the tenant boundary. |
| Deep copies | Ordinary values, arrays, maps, and user-class instances are copied at assignment/argument/return boundaries. System handles keep their identity. |
| Immutable limits | Whole-file `read` is capped at 16 MiB; `readChunk` maximum 4 MiB; a single VFS file supports at least 1 GiB. Use `append` and chunked reads for large data. |
| Deletion | `memory.destroy(target)` is the only FCL deletion of process values. Entity deletion happens through the entity's `remove()` (or explicit namespace purge) and never deletes unrelated user documents. |
| Audit | Administrator and resource-control actions are journaled; `audit.purge` is the only way audit history shrinks and nothing is purged automatically. |

## Pure namespaces

These functions only compute values; they have no durable entity receiver.

### `math`

| Call | Effect |
| --- | --- |
| `math.sin/cos/tan(x)` | Trigonometric functions. |
| `math.sqrt(x)` | Square root; `x` must be non-negative. |
| `math.log(x)` | Natural logarithm; `x` must be positive. |
| `math.abs(x)` | Absolute value. |
| `math.round/floor/ceil(x)` | Round, floor, ceiling. |
| `math.pow(base, exponent)` | Power; non-finite results fail. |
| `math.max(a,b)` / `math.min(a,b)` | Larger / smaller number. |
| `math.pi()` / `math.e()` | Pi / Euler's number. |
| `math.random()` / `math.random(lower, upper)` | Random float, or random integer in `[lower, upper)`. |

### `util`

| Call | Effect |
| --- | --- |
| `util.toJson(value)` / `util.fromJson(text)` | JSON round-trip; integer JSON numbers stay exact (`long`/`BigInteger`). |
| `util.typeOf(value)` | FCL type name; a handle returns its kind. |
| `util.isArray/isMap/isNumber/isString/isBool(value)` | Type predicates. |
| `util.toString(value)` / `util.string(value)` | FCL display text; a handle renders as `Kind{identity}`. |
| `util.length(value)` | Same as `#value`. |
| `util.getTime()` | Unix-millisecond Runtime time. |
| `util.which(functionName)` | `0` for a Runtime/extension function, package database SHA-256 for a bound package function, `null` otherwise. |
| `util.print/println/input` | Aliases of `io.print/println/input`. |
| `util.sleep(milliseconds)` | Suspend the process then resume. |
| `util.exit([result])` | End the process normally with an optional result. |

### `path`

| Call | Effect |
| --- | --- |
| `path.normalize(path)` / `path.resolve(path)` | Normalize `.`, `..`, duplicate slashes. |
| `path.getFileName(path)` | Last segment. |
| `path.getParentPath(path)` (alias `path.getParent`) | Parent path. |
| `path.isAbsolute(path)` | Leading `/` or `\` check. |
| `path.join(parts...)` | Join and normalize; no arguments returns `/`. |
| `path.setAlias/removeAlias/getAlias/listAliases` | Process-local path aliases. |

### `array` and `text`

| Call | Effect |
| --- | --- |
| `array.insert(values, index, value)` | New array with the value inserted. |
| `array.removeAt(values, index)` | New array without that element. |
| `text.slice(value, start [, end])` | UTF-16 slice. |
| `text.split(value, delimiter)` | Split keeping trailing empty items. |
| `text.join(values, delimiter)` | Join using the FCL display rule. |
| `text.indexOf/lastIndexOf(value, search [, start])` | Search; `-1` when absent. |
| `text.commonPrefixLength/commonSuffixLength(a,b)` | Shared prefix/suffix length. |
| `text.repeat(value, count)` | Repeat a string. |
| `text.replace(value, search, replacement)` | Replace all matches. |

### `term`

`term` returns ANSI control text; it does not print by itself.

| Call | Effect |
| --- | --- |
| `term.color/paint(color, value)` | Foreground color (`black red green yellow blue magenta cyan white`). |
| `term.red/green/yellow/blue/magenta/cyan/white(value)` | Color shortcuts. |
| `term.bg(color, value)` / `term.color256(i, value)` / `term.bg256(i, value)` | Background styles. |
| `term.trueColor(r,g,b,value)` / `term.bgTrueColor(r,g,b,value)` | 24-bit color. |
| `term.bold/dim/inverse/underline/strikethrough(value)` | Text styles. |
| `term.reset()`, `term.clear()`, `term.eraseLine()` | Control sequences. |
| `term.cursorUp/Down/Forward/Back(n)`, `term.cursorTo(row, column)` | Cursor movement. |
| `term.hideCursor()` / `term.showCursor()` | Cursor visibility. |
| `term.displayWidth(value)` / `term.truncate(value, width)` | Display columns and safe truncation. |
| `term.getSize()` (alias `term.size`) | `{width, height}`; a resize event arrives through `io.readKey`. |
| `term.sanitize(value)` | Replace control characters and expand tabs. |
| `term.render(frame)` | Queue one disposable ANSI frame for the owning terminal; fails without a terminal route. |
| `term.alternate/mouse/paste/focus(active)` | Toggle the corresponding terminal mode sequences. |

## I/O and process memory

### `io`

| Call | Effect |
| --- | --- |
| `io.print(value)` / `io.println(value)` | Print without / with newline. |
| `io.input([prompt])` | Wait for one line; the prompt becomes `pid:?` while waiting. |
| `io.readChar()` | Wait for and return the first character. |
| `io.readKey([timeoutMs[, coalesceText]])` | Read one structured input event: `key`, `mouse`, `paste`, `paste_rejected`, `focus`, `resize`, `raw`, `repeat`, or `timeout`. Boolean form `io.readKey(true)` enables text coalescing without a timer. |

### `memory`

| Call | Effect |
| --- | --- |
| `memory.list([options])` | Snapshot `{variables, functions}`; `{includeRuntime:true}` adds immutable built-ins/aliases/extensions, `{includeParents:true}` adds lexical root values. |
| `memory.destroy(target)` | Remove a current-scope variable or an array/map element; returns whether something was removed. Never deletes entities, functions, ancestors, or runtime keys. |

### `env`

Variable names are case-insensitive and stored uppercase; values are capped at 64 KiB.
`PWD`, `USER`, `USER_ID`, and `PID` are read-only dynamic values.

| Call | Effect |
| --- | --- |
| `env.get(name [, targetUser])` | Read one variable (user value over shared default). |
| `env.set(name, value [, targetUser])` / `env.remove(name [, targetUser])` | Manage the current user's variable. |
| `env.list([targetUser])` | Full effective environment. |
| `env.getShared/listShared/setShared/removeShared` | Shared variables; writing requires administrator identity. |
| `env.getSharedPolicy/setSharedPolicy(mode, names)` | Shared-name policy; setting is administrator-only. |

## Durable namespaces

These operations stay plain functions because they are services without a single entity
receiver; all still use the current process identity and capabilities.

| Namespace | Visible calls |
| --- | --- |
| `file` | `file.open`, `file.create`, `file.mkdir`, `file.path`, `file.link(linkPath, targetPath)` (creates a symbolic-link node; the old object API replaces the other `file.*` names). |
| `process` | `process.args()`, `process.arg(index)`, `process.getPID()`, `process.getPPID()`, `process.list()`, `process.listChildren()`, `process.fork()` (parent receives the child PID, the child runs from 0), `process.run(sourcePath [, args])` (volatile FCL calculation task, not a resumable process), `process.exec(commandOrPath [, args])`, `process.wait(pid)`, `process.clearFinished()`, `process.find(pid)`. |
| `swapPool` | `swapPool.create(path)`, `swapPool.open(path)`. |
| `ipc` | `ipc.createChannel(name)`, `ipc.channel(id)`, `ipc.createTopic(name)`, `ipc.topic(name)`, `ipc.delivery(id)`, `ipc.receive()`, `ipc.poll()`, `ipc.purge(olderThan [, limit])`. |
| `package` | `package.build(sourcePath, outputPath)`, `package.install(vfsPath)`, `package.list()`, `package.recover()`, `package.find(hashOrCoordinate)`, `package.quotaFor(user, hash)`. |
| `packageData` | `packageData.current()` only. |
| `user` | `user.getCurrentUser()`, `user.isLocal()`, `user.list()`, `user.create(...)`, `user.find(idOrName)`, `user.switchUser(...)` (always unavailable: a durable process identity cannot be changed in place). |
| `program` | `program.find(programId)`. |
| `terminal` | `terminal.find(sessionId)`. |

## Administrator system data files (0.0.5)

The `local` administrator (any account with `SYSTEM_ADMIN`) can browse and edit every
CilExec table as JSON files. The tree is generated on demand and is not part of `vfs.node`:

```text
/.cilexec/system/db/<schema>/<table>/
    _schema.json                  columns, primary key, generated/identity columns
    <key>.json                    one row (first page: at most 200 rows)
    _next.json                    next-page cursor, present only when the page is full
    page/<cursor>/                next page
```

`<key>.json` is the base64url encoding (without padding) of the primary-key JSON,
for example `{"variable_name":"PROJ_TEST"}`. A row file contains the same JSON object as
`SELECT to_jsonb(row)`; write only the columns you want to change. Primary-key columns
cannot be changed through an existing file. Generated columns are read-only.

```fcl
file.open("/.cilexec/system/db").list()
file.open("/.cilexec/system/db/auth/shared_environment_variable/_schema.json").read()
file.create("/.cilexec/system/db/auth/shared_environment_variable/<key>.json",
            "{\"variable_name\":\"PROJ_TEST\",\"variable_value\":\"one\"}")
file.open("/.../<key>.json").write("{\"variable_value\":\"two\"}")
file.open("/.../<key>.json").remove()
```

Only administrators can see the tree; other users receive `PathNotFound`. The
`/.cilexec` prefix is reserved for this projection and is not writable as ordinary VFS.
This is a raw database escape hatch: it bypasses typed resource functions and can corrupt
runtime invariants, so prefer `process.*`, `package.*`, and other object APIs for normal
work. It does not add a schema migration and remains part of 0.0.5.

## System and administration namespaces

| Call | Effect |
| --- | --- |
| `system.list()` | Every visible qualified function name/alias. |
| `system.extensions()` | Sealed compile-time Java extensions: `id`, `version`, `description`. |
| `system.exec(command)` | Administrator: run one host-allowlisted command (string or argument array, no shell); disabled unless `CILEXEC_FCL_EXEC_ALLOWLIST` is configured. |
| `system.invoke(qualifiedFunction [, arguments])` | Administrator: call a registered function by name; cannot target itself, user-defined, package-exported, or source-imported functions. |
| `system.resolveEffect(...)`, `system.reset(...)` | Permanently unavailable from FCL; use the administrator control plane. |
| `timer.purge(before [, limit])` | Administrator: delete FIRED/CANCELLED timer history older than the instant; SCHEDULED/CLAIMED are never touched. |
| `audit.purge(before [, limit])` | Administrator: the only way audit history shrinks; the purge itself is audited. |
| `storage.purgeUnreferenced([limit])` | Administrator: reclaim object-store rows unreachable from every durable root for at least one hour; never deletes VFS files, packages, processes, or Program rows directly. |
| `user.list()` | Administrator: list visible users. |

## Networking and sockets

External operations suspend the FCL process and resume it when the effect completes.

| Call | Effect |
| --- | --- |
| `network.httpGet(url [, headers])`, `network.httpPost(url, body [, headers])`, `network.download(url)`, `network.webget(...)`, `network.webpost(...)` | HTTP requests through the journaled external-effect subsystem, subject to the network target policy. |
| `socket.bind(host, port)` / `socket.accept(handle)` | Administrator-owned listening sockets. |
| `socket.connect(host, port)` | Outbound socket; subject to the network target policy. |
| `socket.send(handle, data)` / `socket.receive(handle [, maximumBytes])` / `socket.close(handle)` | Socket I/O. |

The network target policy blocks loopback, link-local, special-use addresses, and
`.internal` hostnames by default. DNS results are checked and pinned. Operators can
explicitly allow private hosts or HTTP origins through
`CILEXEC_NETWORK_ALLOW_PRIVATE_HOSTS` and
`CILEXEC_NETWORK_ALLOW_PRIVATE_HTTP_ORIGINS`.

## Packages and the market

| Call | Effect |
| --- | --- |
| `package.build(sourcePath, outputPath)` | Build one immutable SQLite package database from a source directory. |
| `package.install(vfsPath)` | Install a `.db` from the VFS for the current user and return a `Package` handle. |
| `package.list()` | List releases known to this Runtime. |
| `package.recover()` | Reconcile installation records and package payloads. |
| `market.*` | Built-in market client: `origin`, `configure`, `update`, `install`, `list`, `info`, `search`, `run`, `help`, `download`. Market installs delegate to the package lifecycle and therefore require the package capabilities. |

## 0.0.5 compatibility and old-data refusal

0.0.5 is intentionally **incompatible** with earlier CilExec releases:

| Persisted state | 0.0.5 behavior |
| --- | --- |
| FCL programs / continuations | Only format `5` (source language `fcl-0.0.5`) is read. Older artifacts are rejected; no converter exists. |
| Package databases | Only package format `3` is accepted; older `.db` files are rejected at build/import/read time. |
| Package source language | Only `fcl-0.0.5` packages can be linked; pre-0.0.5 packages are not import-compatible (the removed entity API). |
| Local `cilexec-data/` beside the JAR | The Runtime checks the data directory **before** starting PostgreSQL or migrating. An existing directory without the `0.0.5` marker (or with another marker) is refused with an explanation; it is never deleted, renamed, rebuilt, or migrated. |
| External PostgreSQL | The migration/startup format fence reads Flyway history before any migration runs. Schema < 5, an unknown non-empty database, a newer schema, or a failed migration history is refused with an explanation. Nothing is upgraded in place. |
| Package private data / VFS / users / processes | Only a fresh 0.0.5 database/data directory is initialized. Old data must be exported by the user's own older tooling or kept as-is. |

To upgrade, start the 0.0.5 JAR in a new directory (or point it at a brand-new database).
The old data are left untouched for the operator to archive or decide about.

## Quick lookup and troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `UndefinedFunction: file.read` | The old entity API is removed. Use `file.open(path).read()`; see the removed-API table. |
| `UndefinedMethod: File.foo` / `InvalidReceiver` | The method does not exist for that handle kind; check the entity table. |
| `StaleHandle` | The entity was removed (or the handle was restored from a different entity). Re-acquire it with the factory. |
| `ExpiredLease` | The fencing lease expired; re-acquire the lock with `lock(milliseconds)`. |
| `Undefined variable: x` after restore | A handle/value existed only in a scope that is not durable; re-create it in code. |
| A package is rejected with a capability message | The source uses an operation whose capability is not declared in `package.json`; declare it and rebuild the package. |
| JAR refuses to start, mentioning 0.0.5 | The data directory or database belongs to another release; use a new data directory/database. |
| `Database schema ... outside supported range` | The external database was not created by 0.0.5; start with a fresh database. |

## Implementation sources

- Function registry and dispatch: `FclFunctionRegistry`, `FclExpressionEvaluator`,
  `FclRuntime`, with entity methods registered from
  `FclFileRuntimeFunctions`, `FclProcessRuntimeFunctions`,
  `FclPackageRuntimeFunctions`, `FclUserRuntimeFunctions`, and
  `FclCoreRuntimeFunctions`.
- Handle value type and persistence: `FclSystemHandle`, `FclValues`,
  `FclContinuationCodec`.
- Program/continuation format: `FclProgramCodec`, `FclContinuation`.
- Startup format fence: `LocalPostgres`, `DatabaseFormatFence`, `SchemaVerifier`,
  `FlywayMigrator`.
- Tests: `FclHandleDispatchTest`, `FclSystemHandleTest`, `FclCodecAndRegistryTest`,
  `PackageCapabilityPolicyTest`, `LocalPostgresDataFormatTest`,
  `DatabaseFormatFenceTest`.



