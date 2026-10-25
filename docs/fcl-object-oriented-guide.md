# FCL 0.0.5 Object Guide

This guide explains the two object models of FCL 0.0.5 for readers who are new to the
language semantics: **user-class values** and **system entity handles**. It is deliberately
practical — every example runs on a 0.0.5 Runtime and only uses the current API. The complete
method catalog is in the [0.0.5 function and object reference](fcl-function-reference.md).
The [0.0.4 object guide](fcl-object-oriented-guide-004.md) describes the removed API and is
kept only for reading old code.

## Two kinds of object

| | User class instance | System entity handle |
| --- | --- | --- |
| Created by | `new ClassName(...)` | A factory such as `file.open`, `process.find`, `ipc.createChannel` |
| Assignment / argument / return | Deep copy; the copy is independent | Same handle; both names point at the same entity |
| Identity | None; equality compares class name and fields | Durable kind + stable id (+ private attributes) |
| Lifetime | As long as some value holds it in the continuation | As long as the persisted entity exists |
| Permission | Checked by FCL rules for class/private members | Re-checked against the database on **every** method call |

The difference matters most when data crosses a statement, a function call, or a suspension.

```fcl
class Counter {
    value = 0
    func increment() { value++ }
}

left = new Counter()
right = left          // deep copy: right is independent
right.increment()
io.println(left.value)   // 0
io.println(right.value)  // 1
```

```fcl
note = file.create("/note.txt", "first")
same = note                  // handle copy: same durable file
same.append(" second")
io.println(note.read())      // first second
```

A handle is **not** a capability token and not a cached object. It stores only the entity
kind, a stable identity string, and optional private attributes such as a lease fencing token
or a topic name. It never stores a Java pointer, a database connection, or a permission grant.
Every call re-reads the entity and re-checks the caller's identity, capabilities, and the
database permission (RLS) boundary.

## Calling methods

FCL supports both of these forms:

```fcl
h = file.open("/note.txt")
io.println(h.read())                        // named receiver
io.println(file.open("/note.txt").read())   // chained expression receiver
```

Both dispatch to the same `File.read` method. The difference is only syntax:

| Written form | How it dispatches |
| --- | --- |
| `h.read()` | The name `h` currently holds a `File` handle, so the reader dispatches `File.read`. |
| `file.open(p).read()` | `file.open` runs first, then `read` dispatches on the resulting handle. |
| `a.child.method()` | `a` names a user object; the field path is resolved and the class method is dispatched. |
| `new Thing().method()` | A temporary user object runs the method; the returned value is usable, but there is no named receiver to write changes back to. |

Use a named variable when you want to call several methods on the same entity, pass it to
another process, or keep it across `io.input()` waits. Use the chained form for one-off work.

## Factories, not path arguments

An entity always starts from a factory function. The old 0.0.4 style of passing the path, PID,
hash, or delivery id to each call was removed in 0.0.5.

```fcl
// Files and directories
f = file.open("/docs/report.txt")     // existing node; File handle
f = file.create("/docs/new.txt", "hello")
d = file.mkdir("/docs/archive")

// Paths that may not exist yet
maybe = file.path("/docs/not-there.txt")
io.println(maybe.exists())            // false, no exception

// Processes
p = process.find(process.getPID())
io.println(p.status())

// Swap pools and variables
pool = swapPool.open("job-42")   // pool names are simple ids, not VFS paths
v = pool.variable("counter")
pool.add("counter", 1)

// Messaging
channel = ipc.createChannel("work")
channel.send({"job": 1})
envelope = ipc.receive()
if envelope != null {
    ipc.delivery(envelope["deliveryId"]).consume()
}

// Packages and their private data
pkg = package.find("<database-file-sha256>")
io.println(pkg.info())
space = pkg.data()
io.println(space.usage())

// Restricted administration objects
me = user.find(user.getCurrentUser())
io.println(me.info())
```

The factory may itself require a capability (for example `package.find` requires
`PACKAGE_IMPORT`). It also requires the entity to exist, except for the explicit
`file.path` value and the `create` factories.

## Handles survive save and restore

Every committed execution slice stores the full FCL continuation, including handles held in
variables, call-frame arguments, and pending method results. That is why this works:

```fcl
note = file.create("/durable.txt", "before")
io.input("press enter to suspend the process> ")
note.append(" after")
io.println(note.read())   // before after
```

While the process waits for input, the Runtime may be restarted. After it resumes, the
`note` handle still names the same VFS node — not a path, not a copy. If the file was removed
in the meantime (for example by another process), the next call fails with `StaleHandle`; it
never silently targets a different file at the same path.

Persisted handles contain only the logical identity. Restoring a continuation never re-opens a
database connection or reuses a Java object; each call performs a fresh lookup.

## Rename, delete, and stale handles

```fcl
h = file.create("/notes/draft.txt", "text")
h.rename("draft-2.txt")
io.println(h.read())                     // text — same node after rename

h.remove()
io.println(file.path("/notes/draft.txt").exists())   // false

// A later file at the same path is a NEW entity with a NEW identity:
fresh = file.create("/notes/draft.txt", "new")
// old h is still stale; fresh is the only live handle
```

Rules:

- **Rename keeps identity.** A file handle addresses the VFS node ID, so renaming does not
  invalidate it.
- **Delete invalidates.** Removing the entity makes every handle stale, even if a new entity
  appears later at the same path or PID.
- **Type changes invalidate.** If the entity is replaced by a different kind of entity, the old
  handle is rejected as stale instead of being re-interpreted.

## Permission is re-checked per call

A handle carries no permission. Consider two users and an administrator-visible file: the
administrator opens the file handle, stores it, then loses the cross-user capability. The next
method call is rejected, because the check happens now, not when the handle was created.

The same rule applies to leases: knowing a fencing token is not enough. The token must belong to
the current logical process and still be the current fencing token. After the lease expires, a
renewal throws `ExpiredLease` and another process may acquire the lock with a newer token.

```fcl
lease = file.open("/shared/counter.txt").lock(30000)
if lease == null {
    io.println("busy; try again")
} else {
    lease.renew(30000)    // fails after expiry with ExpiredLease
    lease.release()
}
```

## Chained calls and suspension

```fcl
pid = process.find(process.getPID()).kill()   // self termination returns true
```

A method call may suspend the process (for example `Process.wait()`,
`SwapVariable.waitFor()`, or a chained `ipc.receive()`). Values already produced before the
suspension point are recorded in the continuation, so a replay after a crash does not repeat
earlier calls in the same statement. The suspended method itself resumes through the normal wait
machinery. Always make externally visible effects idempotent: a delivery can be reserved again
after a crash before `Delivery.consume()` commits, so treat duplicate delivery as possible.

## Package private data

`package.find(hash).data()` returns a `PackageSpace`. The owner may read, export, import,
clear, and check usage/quota. Operations that can modify package-private helper files
(`seedResource`, `append`, `mkdir`, `remove`, `rename`, ...) require the call to execute in
linked code of that exact package, with the `package.data` capability:

```fcl
// Inside linked package code only:
space = packageData.current()
workerPath = "runtime/worker.fcl"
if !space.exists(workerPath) {
    space.seedResource("worker.fcl", workerPath)
}
process.run(space.root() + workerPath, [])
```

Private data is not a VFS path: arbitrary `file.*` calls cannot reach it, and other users'
spaces are unreachable. Every call re-checks that the exact package database file is still
installed for the current user.

## What changed from 0.0.4

- Old path/PID/hash-taking functions such as `file.read(path)`, `process.kill(pid)`,
  `ipc.consume(id)`, and `package.info(hash)` are **removed**. They are not callable
  and do not appear in `system.list()`. See the
  [removed API table](fcl-function-reference.md#removed-004-apis-and-replacements).
- Programs and continuations use format `5`, packages use database format `3`, and the
  database schema is `V005`. Old data are refused, never converted. See
  [old-data refusal](fcl-function-reference.md#005-compatibility-and-old-data-refusal).
- A user-class instance is still a by-value object. Only entity handles keep identity when
  copied; `b link a` keeps its name-following meaning.

## Practical checklist

1. Get the entity once: `h = file.open(path)` or `p = process.find(pid)`.
2. Reuse `h` for related calls; do not pass the path/PID again.
3. Handle `StaleHandle` and `ExpiredLease` explicitly in long-running programs.
4. Treat chained calls as one-shot convenience, and named handles as durable references.
5. Never assume a handle grants access; the Runtime checks permissions on every call.
6. Use `system.list()` to confirm which functions your JAR exposes.
