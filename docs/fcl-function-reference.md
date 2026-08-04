# FCL Function & Terminal Command Reference

This file mirrors the actual function registry of the current CilExec Runtime. A full
terminal input without a leading `:` is executed as FCL; always write the fully
qualified name, for example `file.read("/note.txt")`, to avoid ambiguity with
same-named short aliases.

`[]` marks optional arguments, `<...>` marks required ones. Paths resolve relative to
the current terminal working directory by default; use `:cd` to change it. There are no
`path.cd()`, `path.ls()` and similar FCL functions.

## FCL Language Essentials

FCL is compiled statement by statement. Every executed line is persisted before the
next one runs, so the engine can be killed and restarted without losing state.

- **Comments.** Only `//` line comments exist; everything from `//` to the end of the
  line is ignored. `#` has nothing to do with comments.
- **Length operator.** `#` is the only unary length operator. `#value` returns the
  character count of a string and the element count of a list or map. Every use of
  `#`, including forms like `#(...)`, is this operator; there is no comment syntax
  involving `#`. `#null` is `0`, and a single non-collection value has length `1`.
- **Reserved words.** `func`, `if`, `else`, `while`, `break`, `continue`, `return`,
  `import`, `include`, `as`, `and`, `or`, `true`, `false`, `null` cannot be used as
  identifiers, function names, or parameter names; doing so is a compile error.
- **String escapes.** Only `\n`, `\t`, `\r`, `\\`, `\"` are valid. Any other escape
  (for example `\q` or `\u0041`) is a compile error.
- **Multiline strings.** String literals may span lines: a literal newline inside the
  quotes becomes part of the value (line-ending `\r\n` or `\r` is normalized to `\n`).
  The REPL continues reading the line while a string is open.
- **else-if chains.** `else if (...) { ... }` chains are supported.
- **import/include placement.** `import` and `include` are only valid at the top
  level; inside a function body they are a compile error.
- **Top-level return.** `return` is legal at the top level, and its value becomes the
  program result. REPL expressions and the `package.run` wrapper rely on this.
- **Integer arithmetic.** `+`, `-`, `*` and unary negation throw a runtime error on
  overflow (for example negating `Long.MIN_VALUE`); results never silently wrap.
- **Mixed-type comparisons.** Comparing a string with a number using `<`, `<=`, `>`,
  `>=` throws a runtime error; comparisons between values of the same type work
  normally.
- **Map numeric keys.** `1` and `1.0` are the same map key; numerically equivalent
  keys are normalized.
- **util.fromJson.** Integer JSON numbers are parsed as `long` (or `BigInteger` when
  out of `long` range) instead of being converted to `double` and losing precision.
- **path.getParentPath.** Returns `"."` for a relative path without a slash; `..`
  segments are preserved in relative paths.
- **math.pow.** Throws a runtime error when the result is not finite (NaN or
  ±Infinity).
- **String indexing.** `"abc"[1]` returns the character at that index; equality with
  `"b"` holds and stays consistent across persistence and restart.
- **Function resolution.** User-defined or exported functions take precedence over
  built-in functions of the same name (dot-call resolution).

## Start With These Examples

```fcl
file.createDir("/demo")
file.write("/demo/hello.txt", "Hello")
file.append("/demo/hello.txt", " world")
file.read("/demo/hello.txt")

pkg = package.build("/demo/package.json", "/packages/demo.db")
package.install("/packages/demo.db", "demo")
package.run("demo")
```

Administrators do not need a separate `file.admin*` function family. File functions
accept an optional target user as the last argument, for example
`file.read("/home/a.txt", "alice")`; ordinary users can only access their own files,
while a user with `SYSTEM_ADMIN` can access any user's files, leaving an audit trail.

## Terminal Colon Commands (Not FCL Functions)

| Command | Effect |
| --- | --- |
| `:help` | Show terminal help. |
| `:cd <path>` | Change and persist the current VFS working directory. The target must be a directory. |
| `:pwd` | Show the current working directory. |
| `:ls [path]` | List the current or the given directory; directory names end with `/`. |
| `:logout` | Return to the login screen, keeping the user's terminal state and working directory. |
| `:exit` | Disconnect the current terminal connection; the shared Runtime and background processes keep running. |
| `:shutdown` | Prompt for the current administrator's password and shut down the shared Runtime; only users with `SYSTEM_ADMIN` can do this. |

In a real interactive terminal, `↑` / `↓` pick previous FCL or colon commands entered
by the current user, and `←` / `→` move the cursor within the current input line.
History is persisted per user and survives re-login, Runtime restart, and container
restart. Usernames, passwords, and raw input passed to `io.input()` never enter
history.

The terminal offers no operation recording or script export. FCL process contexts,
working directories, and the last 200 arrow-key history entries are persisted
separately; to get an executable script, create an FCL file directly.

All online users share one Runtime JVM, one database connection pool, and bounded
worker pools. By default there are 10 scheduler workers and 6 effect workers.
Processes beyond the worker count wait in a persisted FIFO queue; terminal processes
run at most 4096 pure steps or 20 ms per time slice, then persist and re-queue. A
separate event-driven Ctrl+C interrupt worker wakes only on PostgreSQL interrupt
notifications; it never polls. Processes with a persistent interrupt flag are excluded
from the regular scheduler workers and can only be picked up by the interrupt worker
and cancelled at a safe point.

The editor is not a colon command and is not built into the Runtime. It is an SQLite
FCL package distributed through the local market on the host. On first use it is
downloaded and installed; afterwards it can be called directly from a persistent
terminal context:

```fcl
market.configure("http://host.docker.internal:8787")
market.update()
market.install("1fac4ef3472a90cbc3eb7b2e2042b50bb4197859a89a3129f0e7474089b96557")
import "editor"
editor.open("notes.txt")
```

Its package coordinate is `cilexec/editor/1.0.12`, its binding is `editor`, and its
public function is `editor.open(path)`. Package imports accept a binding name from the
current user's default environment or the 64-character SHA-256 of an installed `.db`
file, but not a `namespace/name/version` coordinate.

## General Data & Permission Rules

| Item | Description |
| --- | --- |
| Strings | Double-quoted, for example `"hello"`; may span multiple lines. |
| Arrays | Use `[]`, for example `["VFS_READ", "VFS_WRITE"]`. |
| Objects | Use `{}`, for example `{"name":"demo"}`. |
| Paths | Relative paths are based on the `:pwd` result; a leading `/` is an absolute VFS path. |
| Administrators | The initial `local` account has `SYSTEM_ADMIN`. Functions authorize as the currently logged-in user. |
| External operations | Input, printing, HTTP, sockets, and system commands suspend the FCL process and resume it automatically when done. |
| Releasing variables | `memory.destroy("name")` recursively clears the array/object container immediately and removes the variable binding from the current scope; after this statement commits, the value no longer appears in the persisted continuation. Returns `true` if the variable was actually removed, `false` if it did not exist. FCL values are deep-copied on assignment; there are no shared object aliases. It does not delete VFS files or packages. |
| Listing real names | `system.ls()` returns every qualified function name and alias callable in this Runtime. |
| Java extensions | `system.extensions()` returns the fixed list of extensions sealed into the system at build time. |

Common capability names: `PROCESS_CREATE`, `PROCESS_CONTROL_OWN`, `PROCESS_CONTROL_ANY`,
`VFS_READ`, `VFS_WRITE`, `PACKAGE_IMPORT`, `PACKAGE_BIND`, `EFFECT_REQUEST`,
`TERMINAL_ATTACH`, `AUDIT_READ`, `SYSTEM_ADMIN`. Ordinary users receive the common
capabilities within their own scope at registration; administrators have all of them.

## Math: `math`

| Call | Effect |
| --- | --- |
| `math.sin(x)` / `math.cos(x)` / `math.tan(x)` | Trigonometric functions; arguments are numbers. |
| `math.sqrt(x)` | Square root; `x` must be non-negative. |
| `math.log(x)` | Natural logarithm; `x` must be positive. |
| `math.abs(x)` | Absolute value. |
| `math.round(x)` / `math.floor(x)` / `math.ceil(x)` | Round, floor, and ceiling. |
| `math.pow(base, exponent)` | Power. Throws a runtime error if the result is not finite (NaN or ±Infinity). |
| `math.max(a, b)` / `math.min(a, b)` | Larger and smaller of two numbers. |
| `math.pi()` / `math.e()` | Pi and Euler's number. |
| `math.random()` | Random number between 0 and 1. |
| `math.random(lower, upper)` | Random integer in `[lower, upper)`; `upper` must be greater than `lower`. |

## General Utilities: `util`

| Call | Effect |
| --- | --- |
| `util.toJson(value)` | Encode a value as JSON text. |
| `util.fromJson(text)` | Parse JSON text. Integer JSON numbers become `long` values (or `BigInteger` beyond `long` range), never lossy `double`s. |
| `util.typeOf(value)` | Return the FCL type name. |
| `util.isArray(value)` / `util.isMap(value)` | Check for an array or an object. |
| `util.isNumber(value)` / `util.isString(value)` / `util.isBool(value)` | Check for a number, string, or boolean. |
| `util.toString(value)` | Convert to FCL display text. Alias: `util.string(value)`. |
| `util.length(value)` | Return the length of a string, array, object, and so on; the unary `#` operator is equivalent. |
| `util.getTime()` | Current Runtime time as a Unix millisecond timestamp. |
| `util.which(functionName)` | Look up the origin of a function. Runtime built-ins and compile-time Java extensions return `0`; functions from an imported external FCL package return the 64-character SHA-256 of the package `.db` file; unknown names or functions defined by the current source return `null`. |
| `util.print(value)` / `util.println(value)` | Print to the terminal without / with a newline. Equivalent to `io.print` / `io.println`. |
| `util.input([prompt])` | Optionally show a prompt and wait for one line of user input. Equivalent to `io.input`. |
| `util.sleep(milliseconds)` | Suspend the current process for the given milliseconds, then resume. |
| `util.exit([result])` | End the current FCL process normally, optionally returning a result value. |

## Paths & Aliases: `path`

These functions only manipulate path strings or aliases in the current FCL process;
switch directories with `:cd`.

| Call | Effect |
| --- | --- |
| `path.normalize(path)` | Normalize `.`, `..`, and duplicate slashes. |
| `path.resolve(path)` | Normalize a path string; does not change the terminal directory. |
| `path.getFileName(path)` | Return the last path segment. |
| `path.getParentPath(path)` | Return the parent path. Returns `"."` for a relative path without a slash; `..` segments are preserved in relative paths. Alias: `path.getParent(path)`. |
| `path.isAbsolute(path)` | Check whether the path starts with `/`. |
| `path.join(part1, part2, ...)` | Join and normalize path segments; no arguments returns `/`. |
| `path.setAlias(name, path)` | Store a path alias in the current FCL context. |
| `path.removeAlias(name)` | Remove an alias; returns whether it was removed. |
| `path.getAlias(name)` | Read an alias; returns `null` if not found. |
| `path.listAliases()` | Return all current FCL aliases. |

## Persistent Environment Variables: `env`

Variable names are case-insensitive and are normalized to uppercase when stored.
User variables take precedence over same-named shared variables; values are capped at
64 KiB. Ordinary users can only manage their own variables; administrators may pass a
username or user UUID as the last argument to view, set, or remove any user's
variables.

`PWD`, `USER`, `USER_ID`, and `PID` are read-only dynamic environment variables
provided by the Java Runtime; they cannot be changed via `env.set`, `env.remove`, or
the shared environment interface. `PWD` is updated by the terminal's `:cd`; FCL
processes can only read it. VFS functions require absolute paths and do not use `PWD`
automatically. Scripts that need the current directory must spell it out:

```fcl
absolute = path.join(env.get("PWD"), "note.txt")
content = file.read(absolute)
```

| Call | Effect |
| --- | --- |
| `env.get(name [, targetUser])` | Read an environment variable; the current process can directly read the read-only `PWD`, `USER`, `USER_ID`, `PID`; other names read the user value or the shared default. |
| `env.set(name, value [, targetUser])` | Persistently set a user variable. |
| `env.remove(name [, targetUser])` | Remove a user variable. |
| `env.list([targetUser])` | Return the full environment with user values overriding shared defaults. |
| `env.getShared(name)` / `env.listShared()` | Read shared variables available to all users. |
| `env.setShared(name, value)` / `env.removeShared(name)` | Manage shared variables; administrators only. |
| `env.getSharedPolicy()` | View the shared-variable name policy. |
| `env.setSharedPolicy(mode, names)` | Set `ALLOWLIST` or `DENYLIST`; administrators only. |

## Terminal Styling: `term`

These functions return ANSI control text, usually combined with `io.print()` /
`io.println()`.

| Call | Effect |
| --- | --- |
| `term.color(color, value)` | Wrap text in a color. Alias: `term.paint(color, value)`. Colors: `black`, `red`, `green`, `yellow`, `blue`, `magenta`, `cyan`, `white`. |
| `term.red(value)`, `term.green(value)`, `term.yellow(value)`, `term.blue(value)`, `term.magenta(value)`, `term.cyan(value)`, `term.white(value)` | Shortcuts for the corresponding colors. |
| `term.bold(value)` / `term.dim(value)` | Bold / dim text. |
| `term.reset()` | Return the style-reset control sequence. |
| `term.clear()` / `term.eraseLine()` | Return the clear-screen / clear-line control sequences. |
| `term.cursorUp(n)` / `term.cursorDown(n)` | Return control sequences moving the cursor up / down `n` lines. |
| `term.cursorForward(n)` / `term.cursorBack(n)` | Return control sequences moving the cursor right / left `n` columns. |
| `term.cursorTo(row, column)` | Return an absolute cursor-position control sequence; rows and columns start at 1. |
| `term.inverse(value)` | Wrap text in reverse-video style. |
| `term.hideCursor()` / `term.showCursor()` | Hide / show the terminal cursor. |
| `term.displayWidth(value)` | Return the terminal display columns the text occupies; CJK, full-width characters, and emoji usually take two columns, and ANSI style sequences count as zero width. |
| `term.truncate(value, width)` | Truncate text safely by terminal display columns without splitting Unicode code points. |
| `term.getSize()` | Return the current terminal character size, for example `{"width":120,"height":40}`. Alias: `term.size()`. Full-screen TUIs waiting for keys refresh every 100 ms, so size changes appear on the next redraw. |

## Array Handling: `array`

| Call | Effect |
| --- | --- |
| `array.insert(values, index, value)` | Return a new array with the value inserted at the given position; insertion at the end of the array is allowed. |
| `array.removeAt(values, index)` | Return a new array with the element at the given position removed. |

Both operations complete within a single FCL instruction, which suits TUIs modifying
large arrays and avoids copying item by item in FCL loops while repeatedly persisting
intermediate state.

## Text Processing: `text`

| Call | Effect |
| --- | --- |
| `text.slice(value, start [, end])` | Slice a string by character index. |
| `text.split(value, delimiter)` | Split a string, keeping trailing empty items. |
| `text.join(values, delimiter)` | Join an array with a delimiter. |
| `text.indexOf(value, search [, start])` | Search forward from the given position; returns `-1` when not found. |
| `text.lastIndexOf(value, search [, start])` | Search backward from the given position; returns `-1` when not found. |
| `text.repeat(value, count)` | Repeat a string. |
| `text.replace(value, search, replacement)` | Replace all matching text. |

## Input & Output: `io`

| Call | Effect |
| --- | --- |
| `io.print(value)` | Print without a newline. Alias: `util.print(value)`. |
| `io.println(value)` | Print with a newline. Alias: `util.println(value)`. |
| `io.input([prompt])` | Wait for one full line of input. Alias: `util.input([prompt])`. While a process waits for input, the terminal prompt becomes `pid:?`. |
| `io.readChar()` | Wait for input and return the first character; empty input returns an empty string. |
| `io.readKey()` | Full-screen FCL programs only: read one key immediately, normalizing arrow keys, Ctrl combinations, and so on to names. |
| `io.readFile(path [, targetUser])` | Alias of `file.read`. |
| `io.writeFile(path, content [, targetUser])` | Alias of `file.write`. |

## Files & Directories: `file`

`targetUser` can be a username or a user UUID. Only administrators can pass another
user; otherwise the current user is used. All reads and writes happen in the VFS and
do not correspond to real host paths. Path arguments must be absolute; these
functions do not read `PWD` automatically.

| Call | Effect |
| --- | --- |
| `file.read(path [, targetUser])` | Read UTF-8 file text. When a single FCL string would exceed the JVM limit, use `file.readChunk` instead. |
| `file.readChunk(path, offset, maximumBytes [, targetUser])` | Read a UTF-8 range; `offset` must be non-negative and each call reads at most 4 MiB. |
| `file.size(path [, targetUser])` | Return the logical file size in bytes. |
| `file.exists(path [, targetUser])` | Check whether a path exists. |
| `file.listdir([path [, targetUser]])` | Return an array of metadata for the directory's children; without a path, list the VFS root `/`. |
| `file.readMetaData(path [, targetUser])` | Return node metadata such as `nodeId`, `ownerId`, `type`, `objectHash`. |
| `file.write(path, content [, targetUser])` | Create or overwrite a text file. |
| `file.append(path, content [, targetUser])` | Append text using chunked storage; does not load the whole old file. |
| `file.createFile(path [, content [, targetUser]])` | Create the file only if it does not exist. To specify only a target user, pass an empty string for the content position. |
| `file.createDir(path [, targetUser])` | Create a directory. |
| `file.removeFile(path [, targetUser])` | Delete a file. |
| `file.removeDir(path [, targetUser])` | Delete an empty directory. |
| `file.rename(path, newName [, targetUser])` | Rename within the same directory; `newName` cannot contain `/`. |
| `file.link(linkPath, targetPath)` | Create a symbolic-link node whose content is the target path; only within the current user's scope. `file.read`, `file.readChunk`, and `file.size` follow links to the target file (chain limit 16 hops; cycles error out). |
| `file.lock(path, leaseMilliseconds)` | Acquire a file lease lock; on success returns `{fencingToken, leaseUntil}`, on failure `null`. |
| `file.renewLock(path, fencingToken, leaseMilliseconds)` | Renew a file lock. |
| `file.unlock(path, fencingToken)` | Release a file lock held by the current process. |

A single VFS file supports at least 1 GiB. Write large files with repeated
`file.append` calls and inspect or read them in segments with `file.size` and
`file.readChunk`; do not load them into a JVM string with one `file.read`.

## Processes: `process`

Ordinary users only see their own processes. Administrators see all processes and can
control other users' processes.

| Call | Effect |
| --- | --- |
| `process.getPID()` / `process.getPPID()` | Return the current PID / parent PID; the PPID is `0` when there is no parent. |
| `process.getListOfChildProcess()` | Return an array of the current process's child PIDs. |
| `process.getList()` | Return an array of visible process metadata. Alias: `process.getListOfProcess()`. |
| `process.kill(pid)` | Terminate the given process; killing yourself is equivalent to `util.exit()`. Alias: `system.kill(pid)`. |
| `process.pause(pid)` | Pause another controllable process. |
| `process.continue(pid)` | Resume a paused controllable process. |
| `process.fork()` | Copy the current FCL execution context into a child process and return the child PID. |
| `process.exec(path)` | Compile the FCL file at an absolute path in the current user's VFS and execute it in the current PID; the PID, process UID, owner, and parent-child relationships stay unchanged, and the old program's instructions after `exec` do not run. Scripts that want the current directory must pass `path.join(env.get("PWD"), relativePath)` explicitly. Terminal processes keep global variables, package bindings, and the working directory, and return to the same terminal when the target program ends; ordinary background processes terminate when the target program ends. |
| `process.wait()` | Wait for a still-running child process; if there is no active child, return an empty array. |
| `process.waitPID(pid)` | Wait for the accessible given PID and return `{pid, status}` when it ends. |

## Users: `user`

| Call | Effect |
| --- | --- |
| `user.getCurrentUser()` | Return the current user's UUID. |
| `user.isLocal()` | Check whether the current user has `SYSTEM_ADMIN`. |
| `user.validateUser(usernameOrUuid)` | Verify that a user exists and is visible to the current user; ordinary users only validate themselves. |
| `user.getListOfUsers()` | Return basic information for all users; requires administrator identity. |
| `user.removeUser(userUuid)` | Deactivate a user; requires administrator identity. |
| `user.switchUser(...)` | **Currently unavailable.** A persisted process cannot change identity in place; use `:logout` and log in again. |

## Networking & One-shot Sockets: `network`, `socket`

These functions are external effects that wait for a result before resuming. Plain
`httpGet`/`httpPost` responses are capped at 4 MiB; `network.download` uses the
separate chunking limits described below. Sockets are one-shot operations and keep no
connection handles that could be reused across a crash.

| Call | Effect |
| --- | --- |
| `network.httpGet(url)` | Perform an HTTP/HTTPS GET and return `{status, body, headers}`. Alias: `network.webget(url)`. |
| `network.httpPost(url, body)` | Perform an HTTP/HTTPS POST and return `{status, body, headers}`. Alias: `network.webpost(url, body)`. |
| `network.download(url, vfsPath)` | Download binary content in persisted 4 MiB chunks and write it unchanged to the current user's VFS; returns the path, status code, size, and media type. A single file is capped at 1 GiB; the server must support HTTP Range to download files larger than 4 MiB. |
| `socket.connect(host, port)` | Verify connectivity, return endpoint information, then close the connection. |
| `socket.send(host, port, data)` | Connect, send UTF-8 data, close, and return the number of bytes written. Also accepts `socket.send({"host":"…","port":123}, data)`. |
| `socket.receive(host, port [, maximumBytes])` | Connect, read text, and close. |
| `socket.close(...)` | Returns `true`; sockets are already closed automatically on every call. |
| `socket.bind([port])` | Bind a port briefly and return `{host, port, oneShot}`; no persistent listening. |
| `socket.accept(port [, maximumBytes])` | Listen briefly and accept one connection, waiting at most 30 seconds; returns the remote endpoint and the data read. |

## Packages: `package`

Packages are immutable SQLite `package.db` files. The recommended flow is
`package.build` → `package.install` → `import` or `package.run`. `import` only
imports a package; the target can be a binding name in the current user's default
environment or the SHA-256 of an installed package database file; an alias is
optional. Plain FCL source files use `include "path.fcl"`, which splices the file
in place before compilation; `import` cannot be used for that. `package.json` must
declare `kind`. An `application` must provide a zero-argument generic `run`
entrypoint; a `library` is meant for import or dependency use and may have no
entrypoint. The dependency manifest is stored in full inside the package; each entry
holds the complete SHA-256 of the dependency `.db` file and whether it is optional.
A normal install rejects required dependencies that are not yet installed; a market
install recursively installs required dependencies by hash and rejects dependency
cycles. At runtime, exported symbols of dependencies are linked recursively along
the hash dependency graph; package source calls them as `<full dependency SHA-256>.<export name>`.

The host market's default index is
`http://127.0.0.1:8787/market/v1/index.json`; inside a container it is
`http://host.docker.internal:8787/market/v1/index.json`. The full design is in
`docs/package-market.md`.

| Call | Effect |
| --- | --- |
| `package.info(coordinateOrHash)` | Look up a package with its `kind`, dependencies, entrypoint, exports, and capability list. The argument can be `namespace/name/version` or a 64-character package hash; `(namespace, name, version)` as three arguments also works. |
| `package.list()` | Return registered package releases. |
| `package.install(vfsPath [, binding])` | Install from a `.db` file in the VFS into the current user's default environment. |
| `package.install(vfsPath, environmentUuid, binding)` | Install and bind to the given environment. |
| `package.build(manifestPath, outputPath)` | Read the `package.json` and declared files in the VFS and build a `.db` in the VFS. |
| `package.run(binding [, entrypoint])` | Create a child process running a bound package entrypoint; the default entrypoint is `run`, and PID and other information are returned. |
| `package.createEnvironment(name)` | Create a package environment for the current user. |
| `package.environments()` | List the current user's package environments. |
| `package.verify(coordinateOrHash)` | Verify that the package database objects still match the SHA-256 hash recorded at install time. The three-part coordinate also works as arguments. |
| `package.resource(coordinateOrHash, resourcePath)` | Read a declared text resource from the package. |
| `package.pin(environmentUuid, binding, coordinateOrHash)` | Pin an environment binding to a release. The coordinate can also be split into the last three arguments. |
| `package.unpin(environmentUuid, binding)` | Remove an environment binding. |

`import` accepts a binding name from the current user's default environment or the
full SHA-256 of a package database:

```fcl
import "editor" as "e"
import "1fac4ef3472a90cbc3eb7b2e2042b50bb4197859a89a3129f0e7474089b96557" as "exactEditor"
```

Binding names are unique within one package environment. Reinstalling the same
release is idempotent; a normal install cannot replace a binding with another
release. To intentionally change a binding, use the explicit package management
operations.

| Call | Effect |
| --- | --- |
| `package.remove(environmentUuid, binding)` | Synonym of `unpin`; removes a binding. |
| `package.gc()` | Administrator interface; the current immutable packages are never actually deleted and it returns `0`. |
| `package.recover()` | Administrator recovery-check entrypoint; currently returns `true`. |

## Built-in Market: `market`

The market client is a Java feature built into the Runtime, not an FCL package; no
`market.db` download and no `import` are needed. The mirror address is stored in the
current user's persistent environment variable `MARKET_ORIGIN`; an administrator-set
shared value acts as the default, and the user value wins. The full protocol and the
standalone server are described in `docs/package-market.md`.

| Call | Effect |
| --- | --- |
| `market.configure(origin)` | Set the current user's single HTTP/HTTPS mirror origin. |
| `market.origin()` | Return the active mirror origin; `null` when not configured. |
| `market.update()` | Download, verify, and persist the full market index. |
| `market.search(text)` | Search the local index by prefix over names, tags, and description words; version numbers are not searched. If no index exists, update first. |
| `market.info(sha256)` | Look up a package record by the full SHA-256 of its distribution file. |
| `market.download(sha256)` | Download in 4 MiB chunks and recompute the full file hash. |
| `market.install(sha256)` | Recursively install exact-hash dependencies and create the default binding. |
| `market.list()` | List the current user's install records managed by the market. |
| `market.upgrade()` | Refresh the index and upgrade market installs that have newer versions. |
| `market.uninstall(sha256)` | Remove the market install binding and the downloaded file. |
| `market.help()` | Return help text for the market functions. |
| `market.run()` | Return the built-in client version and help without requiring a configured mirror. |

Except for `market.configure`, `market.origin`, `market.help`, and `market.run`, the
operations require a configured mirror. When it is missing, an explicit error names
the configuration command instead of silently falling back to raw input mode.

## Swap Pool (Inter-process Data): `swapPool`

The swap pool belongs to the current user and suits inter-process values, signals,
and locking.

| Call | Effect |
| --- | --- |
| `swapPool.create(path)` / `swapPool.remove(path)` | Create / remove a swap pool. |
| `swapPool.exists(path)` | Check whether a swap pool exists. |
| `swapPool.list()` | List all of the current user's swap pools. |
| `swapPool.ls(path)` | List the variables in a pool. |
| `swapPool.add("name:value", pool [, option...])` | Add a variable. Optional `"type:sync"` or `"type:times(n)"` control how it is retained. |
| `swapPool.get(pool, variable)` | Read and consume a variable value; `null` when not found. |
| `swapPool.update(pool, variable, value [, fencingToken])` | Update a variable; pass the fencing token when locked. |
| `swapPool.removeVar(pool, variable [, fencingToken])` | Remove a variable. |
| `swapPool.clear(pool)` | Clear the pool. |
| `swapPool.lock(pool, variable, leaseMilliseconds)` | Acquire a variable lock; on success returns `{fencingToken, leaseUntil}`. |
| `swapPool.renewLock(pool, variable, fencingToken, leaseMilliseconds)` | Renew a variable lock. |
| `swapPool.unlock(pool, variable, fencingToken)` | Release a variable lock. |
| `swapPool.signal(pool, variable)` | Send a variable signal. |
| `swapPool.waitFor(pool, variable)` | Wait for a signal; returns `true` when received. |

Swap-pool locks belong to the logical process that created them, not to a single
scheduler time slice. The same PID can update, renew, or release the lock when it is
re-scheduled, when a paused terminal process receives the next instruction, or when
the same Headless context submits code again, as long as the lease has not expired
and the current fencing token is used. No other process can use that token, even
when it knows the variable name.

## System: `system`

| Call | Effect |
| --- | --- |
| `system.ls()` | Return every function name and alias registered in the current Runtime. |
| `system.ls(path)` | List node metadata for the given directory of the current user; use `:ls` for a more readable directory listing in the terminal. |
| `system.extensions()` | Return the `id`, `version`, and `description` of the Java extensions sealed in at source-build time. Extensions cannot be added, removed, or replaced at runtime. |
| `system.kill(pid)` | Alias of `process.kill(pid)`. |
| `system.exec(command)` | Administrator: execute a command from the host allow-list. `command` can be a string or an array of strings; it does not go through a shell, and `CILEXEC_FCL_EXEC_ALLOWLIST` must be set — no program is allowed by default. |
| `system.invoke(qualifiedFunction [, argumentArray])` | Administrator: call another FCL function by string, for example `system.invoke("file.read", ["/x.txt", "alice"])`. It cannot call itself. |
| `system.forceRemove(path)` | Administrator: delete the current user's file or directory by path. |
| `system.forceRemove(targetUserUuid, nodeUuid)` | Administrator: force-delete a node of the given user. |
| `system.resolveEffect(...)` | **Currently unavailable.** External effects are handled by the Runtime control plane. |
| `system.reset(...)` | **Currently unavailable.** Runtime reset is not exposed to FCL. |

## Quick Lookup & Troubleshooting

```fcl
system.ls()                         // see the functions actually loaded
env.get("PWD")                     // current working directory (Java-managed, read-only)
user.getCurrentUser()               // current user UUID
user.isLocal()                      // whether the current user is an administrator
process.getList()                   // visible processes
file.listdir(path.join(env.get("PWD"), ".")) // metadata of the current directory
package.list()                      // registered packages
```

If a function reports missing permissions, first run `user.isLocal()` and
`user.getCurrentUser()`; ordinary users cannot bypass permissions by passing another
username. If `io.input()` is waiting for input, type directly at the `pid:?` prompt;
to pass raw text starting with a colon, use `::text`.

## Implementation Sources

This manual was compiled from the current code registration points:

- `src/main/java/com/follarce/fcl/FclBuiltins.java`: pure math, utility, path, and
  terminal styling functions.
- `src/main/java/com/follarce/application/FclRuntimeFunctions.java`: file, process,
  user, package, network, swap-pool, and system functions.
- `src/main/java/com/follarce/terminal/DatabaseTerminalControl.java`: terminal colon
  commands.
