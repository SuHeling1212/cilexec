# FCL Function & Terminal Command Reference

This file mirrors the actual function registry of the current CilExec Runtime. A full
terminal input without a leading `:` is executed as FCL; always write the fully
qualified name, for example `file.read("/note.txt")`, to avoid ambiguity with
same-named short aliases.

`[]` marks optional arguments, `<...>` marks required ones. Paths resolve relative to
the current terminal working directory by default; use `:cd` to change it. There are no
`path.cd()`, `path.ls()` and similar FCL functions.

## FCL Language Essentials

FCL is compiled into interpreter instructions. Each execution slice is one database
transaction and one recovery checkpoint. Every process may batch up to 4,096 steps or 20 ms
per slice and stops when it suspends, executes a directive, completes, or fails. A crash rolls back the whole uncommitted
slice, which may then replay from the preceding checkpoint.

- **Comments.** Only `//` line comments exist; everything from `//` to the end of the
  line is ignored. `#` has nothing to do with comments.
- **Length operator.** `#` is the only unary length operator. `#value` returns the
  UTF-16 code-unit count of a string and the element count of a list or map. Every use of
  `#`, including forms like `#(...)`, is this operator; there is no comment syntax
  involving `#`. `#null` is `0`, and a single non-collection value has length `1`.
- **Reserved words.** `func`, `if`, `else`, `while`, `break`, `continue`, `return`,
  `import`, `include`, `as`, `and`, `or`, `not`, `public`, `private`, `class`, `extends`,
  `new`, `this`, `super`, `try`, `catch`, `true`, `false`, `null` cannot be used as
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
- **Booleans are not ordered.** `<`, `<=`, `>`, `>=` on `bool` values throw a
  runtime error (`bool cannot be ordered`), following Java, where `boolean` has no
  ordering. `==`, `!=`, `and`, `or`, and `not` still work on booleans.
- **Value display.** All "FCL value → text" paths (string concatenation, `text.join`,
  `util.toString`) share one display rule: top-level strings are used verbatim (never
  quoted), numbers and booleans use their plain text, `null` renders as `null`, and
  arrays and maps render in FCL literal syntax with JSON-style quoting and escaping
  inside containers, for example `[1,"a"]` and `{"x":1}`. Host Java
  `toString()` representations never leak into FCL text.
- **Map numeric keys.** `1` and `1.0` are the same map key; numerically equivalent
  keys are normalized.
- **util.fromJson.** Integer JSON numbers are parsed as `long` (or `BigInteger` when
  out of `long` range) instead of being converted to `double` and losing precision.
- **path.getParentPath.** Returns `"."` for a relative path without a slash; `..`
  segments are preserved in relative paths.
- **math.pow.** Throws a runtime error when the result is not finite (NaN or
  ±Infinity).
- **String indexing.** String length, indexing, and text slice/search positions use Java
  UTF-16 code units. `"abc"[1]` returns `"b"`; a supplementary Unicode character such as
  an emoji occupies two positions and can be split by indexing or slicing.
- **Function resolution.** User-defined or exported functions take precedence over
  built-in functions of the same name (dot-call resolution).
- **Boolean operators.** `not value` is the textual spelling of `!value`; `and` and
  `or` short-circuit and use FCL truthiness (`0`, `null`, `false`, and `""` are false).
- **Function scopes.** Every invocation has fresh parameters and locals. Base-program functions
  then resolve names from their own program's top-level scope, while imported-module functions
  resolve names only from their module's persisted globals. No function can read a caller's
  locals. Assignments always write the current invocation. Root bindings and call frames are
  persisted across suspension and recovery.

- **Exceptions.** `try { ... } catch (e) { ... }` catches only deterministic FCL program
  failures. `e` is catch-local and is an immutable Runtime value, not an FCL object: its stable
  fields are `e.type`, `e.message`, and `e.stack`; each `StackFrame` provides `function`,
  `source`, `line`, and `column`. The same exception value propagates across FCL calls. Runtime
  shutdown, forced process termination, database consistency failures, and other kernel faults
  cannot be caught. Exception values and active handlers are included in every continuation
  checkpoint, so a suspended `try` retains its catch after restart. `throw`, `finally`, and
  typed/multiple catches are not part of v0.0.4.
- **Module visibility.** `public func` exports a module function (and is the default for
  an unqualified `func`); `private func` is an implementation function. Private functions
  remain callable by module functions but are not callable from imported module clients.

## FCL v0.0.4 Object-Oriented Programming

For a complete beginner-oriented explanation, see the English
[object-oriented guide](fcl-object-oriented-guide.md) or the Chinese
[object-oriented guide](fcl-object-oriented-guide.zh-CN.md).

Classes are top-level declarations. Fields and methods are `public` by default; use `private`
for implementation details. Objects are objects: assigning one object name to another creates
an independent copy.

```fcl
class Counter {
    value = 0

    init(initial) {
        this.value = initial
    }

    func increment() {
        this.value = this.value + 1
        return this.value
    }
}

counter = new Counter(10)
copy = counter
copy.increment()
io.println(counter.value) // 10
io.println(copy.value)    // 11
```

Use `private` to prevent callers outside the declaring class from reading a field or calling a
method. A `private class` is not exported by `import`; a `public class` is importable.

```fcl
public class User {
    private password = ""

    init(password) { this.password = password }
    func hasPassword() { return this.password != "" }
}
```

FCL supports single inheritance and overload resolution by method name plus argument count.
`super(...)` calls a parent constructor; `super.method(...)` selects the parent implementation.

```fcl
class Person {
    name = ""
    init(name) { this.name = name }
    func label() { return "Person: " + this.name }
}

class Admin extends Person {
    init(name) { super(name) }
    func label() { return super.label() + " (admin)" }
}

user = new Admin("Ada")
io.println(user.label())
```

Numeric variables, public fields, and list/Map elements support postfix `++` and `--`.
An update expression evaluates to its updated value; there is deliberately no prefix form such
as `++count`. Updating a non-number or a private/immutable value is rejected.

```fcl
count = 1
before = count++ // before = 2, count = 2
after = count++  // after = 3, count = 3
count--

counter.value++
items[0]--
```

Normal assignment, arguments, returns, and collection writes create independent copies; methods
still modify the object at their receiver location. The Runtime may use Copy-on-Write internally.

`b link a` is the one explicit exception: it makes `b` follow the name `a`, so writes or a later
assignment through either name change what both names represent. It is a persisted name-to-name
relationship, not a default object-reference model and not an object ID, JVM pointer, or reference
count. `link` is reserved and must appear as the standalone middle word; `b linka` and
`linkb a` are not link expressions.

`memory.destroy(target)` is the sole deletion API. It deletes a current-scope variable or an
array/Map element; it does not destroy an object copied by ordinary assignment because default
object sharing does not exist. It returns `true` when something was removed and `false` for a
missing variable or map key. A `link` is the explicit exception: destroying either linked name
destroys their shared object, so the source and every name `link`ed to it are all removed.

```fcl
user = new User()
copy = user
memory.destroy(user)
// user is undefined; copy remains a complete User value
```

The deliberately excluded v0.0.4 features are static members, interfaces, abstract classes,
generics, multiple inheritance, reflection, object cloning, `throw`, `finally`, and typed catch.

## Start With These Examples

```fcl
file.createDir("/demo")
file.write("/demo/hello.txt", "Hello")
file.append("/demo/hello.txt", " world")
file.read("/demo/hello.txt")

pkg = package.build("/demo/package.json", "/packages/demo.db")
package.install("/packages/demo.db")
package.run("<package-db-sha256>")
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
| `:clear` (alias `:cls`) | Clear the terminal screen. |
| `:logout` | Return to the login screen, keeping the user's terminal state and working directory. |
| `:exit` (alias `:quit`) | Disconnect the current terminal connection; the shared Runtime and background processes keep running. |
| `:shutdown` | Prompt for the current administrator's password and shut down the shared Runtime; only users with `SYSTEM_ADMIN` can do this. |

In a real interactive terminal, `↑` / `↓` pick previous FCL or colon commands entered
by the current user, and `←` / `→` move the cursor within the current input line.
History is persisted per user and survives re-login, Runtime restart, and container
restart. Usernames, passwords, and raw input passed to `io.input()` never enter
history.

Multiline input combines delimiter matching, Modifier+Enter, and C-style backslash
continuations: unclosed `{`/`(`/`[` blocks or quotes continue on the `...>` prompt;
`Shift+Enter` inserts a line break without submitting, so a continued line works even
when every delimiter is already balanced; and a trailing `\` before Enter also keeps
the submission open — the backslash and line break are removed before compilation,
exactly like the C preprocessor joins lines.

The terminal negotiates the kitty keyboard protocol (`CSI > 1 u`) at the start of
every editable submission, so terminals that support it — **iTerm2, kitty, WezTerm,
Windows Terminal, Foot, Konsole** — send `Shift+Enter` (`CSI 13;2u`) natively with
zero configuration, and that key inserts a line break. Modifier-key sequences are
consumed whole and never reach the editor as text.

**Terminal.app (macOS) does not implement the protocol and sends a plain `\r` for
Shift+Enter**, which is indistinguishable from Enter; use the trailing `\`
continuation there. On xterm, a modifyOtherKeys-capable setup sends `CSI 13;2~`,
which is equally accepted.

The history keeps the raw typed lines.

The terminal offers no operation recording or script export. FCL process contexts,
working directories, and the last 200 arrow-key history entries are persisted
separately; to get an executable script, create an FCL file directly.

All online users share one Runtime JVM, one database connection pool, and bounded
worker pools. By default there are 10 scheduler workers and 6 effect workers.
Processes beyond the worker count wait in a persisted FIFO queue; terminal processes
run at most 4096 interpreter steps or 20 ms per time slice, stopping early on suspension,
directive, completion, or failure, then persist and re-queue when ready. A
separate event-driven Ctrl+C interrupt worker wakes only on PostgreSQL interrupt
notifications; it never polls. Processes with a persistent interrupt flag are excluded
from the regular scheduler workers and can only be picked up by the interrupt worker
and cancelled at a safe point.

The editor is not a colon command and is not built into the Runtime. It is an SQLite
FCL package distributed through the local market on the host. On first use it is
downloaded and installed; afterwards it can be called directly from a persistent
terminal context. The example below is the repository's current editor release; use the
hash returned by your configured market if it publishes a newer release:

```fcl
market.configure("http://host.docker.internal:8787")
market.update()
market.install("c8b8a024847aa873de9655443280104f4cc185b1770b6308ca073999b1503bff")
import "c8b8a024847aa873de9655443280104f4cc185b1770b6308ca073999b1503bff" as "editor"
editor.open("notes.txt")
```

Its package coordinate is `cilexec/editor/0.0.3`, and its public function is
`editor.open(path)`. A package is identified by the SHA-256 of its `.db` file; two
different hashes are two independent packages. `import` accepts either the 64-character
SHA-256 of an installed `.db` file or a VFS `.fcl` source path. Package imports are
immutable installed releases. A source import captures the source and its private
initialized bindings in the current process; the source file may later change, but the
captured binding changes only when that process imports it again. Neither form accepts a
human-readable package coordinate.

The editor itself is implemented with the current FCL object syntax (`class`, `new`, fields,
methods, and postfix updates). Ordinary one-line typing, deletion, and cursor movement update
only the changed terminal rows instead of clearing and rebuilding the entire screen. Resizing,
scrolling, multi-line edits, search prompts, and exit prompts still use a complete redraw so the
display remains correct. For a very large document (over 10,000 lines) or a full frame above
12,000 terminal cells, its normal-mode full-frame string construction runs in a package-private
`process.run` worker. The terminal process still owns input and output; the worker only returns
the completed ANSI string. The worker source is seeded once from the immutable package resource
into that exact installed editor version's private data and is recreated if that cache is cleared.
Editor frames use `term.render`, so they are published directly after the same transaction that
commits the editor state instead of creating an `io.output` effect and requiring a second process
resume. Printable input is grouped for at most 8 ms, and an already-buffered held Backspace key is
reported as one repeat event; CilEdit applies the common same-line deletion with one string update.

The bundled Snake example is another FCL application package at `cilexec/snake/0.0.3`. It uses
an ordinary persistent FCL object as the complete game state and `term.render` for disposable
frames, so a crash resumes from the last committed movement rather than persisting terminal
escape sequences. See [`snake-game.md`](snake-game.md) for installation and controls.

## General Data & Permission Rules

| Item | Description |
| --- | --- |
| Strings | Double-quoted, for example `"hello"`; may span multiple lines. |
| Arrays | Use `[]`, for example `["VFS_READ", "VFS_WRITE"]`. |
| Objects | Use `{}`, for example `{"name":"demo"}`. |
| Paths | Relative paths are based on the `:pwd` result; a leading `/` is an absolute VFS path. |
| Administrators | The initial `local` account has `SYSTEM_ADMIN`. Functions authorize as the currently logged-in user. |
| External operations | Input, printing, HTTP, sockets, and system commands suspend the FCL process and resume it automatically when done. |
| Symbol view | `memory.list([includeParents])` returns a snapshot `{variables, functions}` of current-process visible variables and callable functions. Variables are only from the current scope unless `includeParents` is true, in which case lexical root values are included with local shadowing. Pass `{"includeRuntime":true}` (and optionally `"includeParents":true`) to include immutable built-ins, aliases, and extensions. Functions list `name`, `kind`, `origin`, and `mutable` (`false`). |
| Deleting values | `memory.destroy(target)` is the only deletion API. It removes a current-scope variable, or a specified array/Map element. Object values are copied on assignment, so deleting `a` never affects `b = a`. A missing symbol or map key returns `false`. String literals are not accepted as target names. It never deletes parent variables, functions, VFS files, packages, built-ins, Java extensions, or reserved runtime state (`cilexec.*`, inbox keys). |
| Listing real names | `system.list()` returns every qualified function name and alias callable in this Runtime. |
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
| `util.toString(value)` | Convert to FCL display text (the shared value display rule). Alias: `util.string(value)`. |
| `util.length(value)` | Return the length of a string, array, object, and so on; the unary `#` operator is equivalent. |
| `util.getTime()` | Current Runtime time as a Unix millisecond timestamp. |
| `util.which(functionName)` | Look up the origin of a function. Runtime built-ins and compile-time Java extensions return `0`; functions from an imported external FCL package return the 64-character SHA-256 of the package `.db` file; unknown names or functions defined by the current source return `null`. |
| `util.print(value)` / `util.println(value)` | Print to the terminal without / with a newline. Equivalent to `io.print` / `io.println`. |
| `util.input([prompt])` | Optionally show a prompt and wait for one line of user input. Equivalent to `io.input`. |
| `util.sleep(milliseconds)` | Suspend the current process for the given milliseconds, then resume. |
| `util.exit([result])` | End the current FCL process normally, optionally returning a result value. |
| `storage.purgeUnreferenced([limit])` | Administrator-only immediate garbage collection of unreferenced object-store entries that have been unreachable from every durable root for at least one hour. The optional `limit` (1–10000, default 1000) bounds rows per invocation. It never deletes VFS files, packages, processes, or Program rows itself. Nothing is deleted on a time schedule. |

## Paths & Aliases: `path`

These functions only manipulate path strings or aliases in the current FCL process;
switch directories with `:cd`.

| Call | Effect |
| --- | --- |
| `path.normalize(path)` | Normalize `.`, `..`, and duplicate slashes. |
| `path.resolve(path)` | Normalize a path string; does not change the terminal directory. |
| `path.getFileName(path)` | Return the last path segment. |
| `path.getParentPath(path)` | Return the parent path. Returns `"."` for a relative path without a slash; `..` segments are preserved in relative paths. Alias: `path.getParent(path)`. |
| `path.isAbsolute(path)` | Check whether the normalized path starts with `/`; a leading backslash is normalized to `/` and is also treated as absolute. |
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
processes can only read it. Relative VFS paths resolve against the process's durable `PWD`;
scripts may join it explicitly when they need the resolved absolute path as data:

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
| `term.getSize()` | Return the current terminal character size, for example `{"width":120,"height":40}`. Alias: `term.size()`. Docker terminal clients push a structured `resize` input event when this size changes, so full-screen programs do not need a polling timeout. |
| `term.sanitize(value)` | Return text with terminal control characters replaced by `?` and tabs expanded to four spaces. |
| `term.render(frame)` | Queue one ANSI terminal frame for the terminal that started this process. The frame is published only after the enclosing FCL state transaction commits and does not suspend through the external-effect worker. It is a disposable TUI repaint, not a durable output journal: use `io.print`/`io.println` when the output itself must retain external-effect recovery semantics. Calls from processes without a terminal output route fail. |
| `term.alternate(active)` | Return the enter/leave alternate-screen-buffer sequence so a full-screen program does not pollute the terminal scrollback. |
| `term.mouse(active)` | Return the enable/disable mouse-reporting sequences (press + wheel events in SGR format). |
| `term.paste(active)` | Return the enable/disable bracketed-paste sequences. |
| `term.focus(active)` | Return the enable/disable focus-event sequences. |
| `term.underline(value)` / `term.strikethrough(value)` | Wrap text in underline / strikethrough style. |
| `term.bg(color, value)` | Wrap text in a background color (same color names as `term.color`). |
| `term.color256(index, value)` / `term.bg256(index, value)` | Wrap text in a 256-color palette color, index 0-255. |
| `term.trueColor(red, green, blue, value)` / `term.bgTrueColor(red, green, blue, value)` | Wrap text in a 24-bit RGB color; components are 0-255. |

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
| `text.slice(value, start [, end])` | Slice a string by UTF-16 code-unit index. |
| `text.split(value, delimiter)` | Split a string, keeping trailing empty items. |
| `text.join(values, delimiter)` | Join an array with a delimiter; each item uses the FCL value display rule. |
| `text.indexOf(value, search [, start])` | Search forward from the given position; returns `-1` when not found. |
| `text.lastIndexOf(value, search [, start])` | Search backward from the given position; returns `-1` when not found. |
| `text.commonPrefixLength(first, second)` | Return the UTF-16 length of the longest shared prefix without splitting a Unicode code point. |
| `text.commonSuffixLength(first, second)` | Return the UTF-16 length of the longest shared suffix without splitting a Unicode code point. |
| `text.repeat(value, count)` | Repeat a string. |
| `text.replace(value, search, replacement)` | Replace all matching text. |

## Input & Output: `io`

| Call | Effect |
| --- | --- |
| `io.print(value)` | Print without a newline. Alias: `util.print(value)`. |
| `io.println(value)` | Print with a newline. Alias: `util.println(value)`. |
| `io.input([prompt])` | Wait for one full line of input. Alias: `util.input([prompt])`. While a process waits for input, the terminal prompt becomes `pid:?`. |
| `io.readChar()` | Wait for input and return the first character; empty input returns an empty string. |
| `io.readKey([timeoutMs[, coalesceText]])`, `io.readKey(coalesceText)` | Read one input event as a structured object. `{"kind":"key","key":"UP","shift":false,"ctrl":false,"alt":false,"text":""}` for keys; `{"kind":"mouse","button":"LEFT","action":"PRESS|RELEASE|MOVE|SCROLL","scroll":n,"x":n,"y":n,...}` for mouse; `{"kind":"paste","text":"..."}` for bracketed paste; `{"kind":"paste_rejected","limit":262144}` when one bracketed paste exceeds 256 KiB (the full terminal sequence is consumed and none of it is inserted); `{"kind":"focus","focus":true}` for focus events; `{"kind":"resize"}` when a Docker terminal reports a changed character size; `{"kind":"raw","sequence":"..."}` for unrecognized escape sequences. With `timeoutMs`, an idle wait returns `{"kind":"timeout"}` after that many milliseconds (0-86400000); without it the call blocks until an event arrives. Passing one Boolean, for example `io.readKey(true)`, enables text coalescing without creating a timeout timer. When `coalesceText` is `true`, up to 64 printable characters arriving within 8 milliseconds of the first are returned as one paste event; an already-buffered Backspace run is returned as `{"kind":"repeat","key":"BACKSPACE","count":n}`. The deadline never resets and control events keep their order. It bounds only the wait for further bytes; text already buffered when the batch is checked is always included. |
| `io.readFile(path [, targetUser])` | Alias of `file.read`. |
| `io.writeFile(path, content [, targetUser])` | Alias of `file.write`. |

## Files & Directories: `file`

`targetUser` can be a username or a user UUID. Only administrators can pass another
user; otherwise the current user is used. All reads and writes happen in the VFS and
do not correspond to real host paths. Relative paths resolve against the process's
durable `PWD`, which `:cd` changes; paths beginning with `/` remain absolute.

| Call | Effect |
| --- | --- |
| `file.read(path [, targetUser])` | Read a complete UTF-8 file into one FCL string, capped at 16 MiB. Use `file.readChunk` for larger files. |
| `file.readChunk(path, offset, maximumBytes [, targetUser])` | Read a UTF-8 range; `offset` must be non-negative and each call reads at most 4 MiB. |
| `file.size(path [, targetUser])` | Return the logical file size in bytes. |
| `file.exists(path [, targetUser])` | Check whether a path exists. |
| `file.list([path [, targetUser]])` | Return an array of metadata for the directory's children; without a path, list the VFS root `/`. Local administrators additionally see the virtual `/Users` directory. |
| `file.readMetaData(path [, targetUser])` | Return node metadata such as `nodeId`, `ownerId`, `type`, `objectHash`. |
| `file.write(path, content [, targetUser])` | Create or overwrite a text file. |
| `file.append(path, content [, targetUser])` | Append text. Own-user append uses chunked storage without loading the old file; administrator cross-user append currently loads the old content and therefore refuses an existing file over 16 MiB. |
| `file.createFile(path [, content [, targetUser]])` | Create the file only if it does not exist. To specify only a target user, pass an empty string for the content position. |
| `file.createDir(path [, targetUser])` | Create a directory. |
| `file.remove(path [, targetUser])` | Delete a file, symbolic link, or empty directory. Roots, mounts, versioned files, and non-empty directories are rejected. |
| `file.clear(path [, targetUser])` | Keep the directory and recursively delete everything inside it. The target must be an existing non-root directory; a versioned file or mount anywhere inside aborts the whole call and nothing is removed. Returns the number of entries removed. |
| `file.rename(path, newName [, targetUser])` | Rename within the same directory; `newName` cannot contain `/`. |
| `file.link(linkPath, targetPath)` | Create a symbolic-link node whose content is the target path; only within the current user's scope. `file.read`, `file.readChunk`, and `file.size` follow links to the target file (fewer than 16 links; cycles error out). |
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
| `process.listChildren()` | Return an array of the current process's child PIDs. |
| `process.list()` | Return an array of visible process metadata. |
| `process.kill(pid)` | Terminate the given process; killing yourself is equivalent to `util.exit()`. Alias: `system.kill(pid)`. |
| `process.pause(pid)` | Pause another controllable process. |
| `process.continue(pid)` | Resume a paused controllable process. |
| `process.fork()` | Copy the current FCL execution context; the parent receives the child PID and the child resumes with `0`. The child does not inherit the terminal lifecycle: it runs to the end of its program (or a `util.exit()`) and then reaches `TERMINATED`, so `process.waitPID`, `process.kill`, and `process.removeFinished` behave exactly like for an ordinary background process. A child of a terminal-started process does inherit that terminal's output route, so its `io.print*` output remains visible there; only the interactive terminal root itself pauses between submissions and receives input. |
| `process.run(path, arguments)` | Start a new **volatile** calculation from an FCL source file in the current user's VFS. `arguments` is a required array, copied into the new context. The caller waits for the calculation's top-level `return`; the returned FCL value becomes the call result. Installed package code may also pass a URI returned by `packageData.root()`; it can execute only a source file in that same package's private data space. |
| `process.exec(path [, arguments])` | Replace the current FCL program image while retaining its PID, process UID, owner, parent relationship, working directory, and terminal transport. The target begins with a clean FCL context: all old variables, objects, functions, classes, call/control state, package pins, and REPL library declarations are discarded. `arguments` defaults to `[]` and become the target program's startup arguments. |
| `process.args()` / `process.arg(index)` | Return a copy of this program's startup-argument array / one argument. `process.arg` rejects an out-of-range index. Ordinary initial processes have `[]`; `exec` and `run` establish new arguments. |
| `process.wait()` | Wait for a still-running child process; if there is no active child, return an empty array. |
| `process.waitPID(pid)` | Wait for the accessible given PID and return `{pid, status}` when it ends. |
| `process.removeFinished([pid])` | Explicitly remove a terminal process that has ended (`TERMINATED` or `FAILED`) and its persisted state (continuation, variables, events, timers, package pins). Without a PID it removes every finished terminal process and returns the count removed; with a PID it removes only that process and returns `true`. Running, suspended, and waiting processes are never removed. Administrator (`SYSTEM_ADMIN`) only. |

### Volatile calculation processes

Use a volatile process for disposable, CPU-heavy work whose result does not need to survive
and does not need to be returned to the caller:

```fcl
// /calculate.fcl
limit = process.arg(0)
total = 0
index = 0
while index < limit {
    total = total + index
    index++
}
return total

result = process.run("/calculate.fcl", [1000000])
```

The source file itself remains an ordinary VFS file, but the process created by
`process.run` has **no database identity or saved runtime data**: it has no PID, no program
row, no continuation, no variables, no scheduler entry, and no audit event. It never has a
result record: its top-level `return` is passed in memory directly to the waiting caller.
It never appears in `process.list()`, cannot be waited for, paused, killed, or cleaned up with
the normal `process.*` lifecycle functions. Runtime shutdown, restart, or a forced kill simply
discards it. A caller waiting for its result resumes with `VolatileProcessLost`; CilExec never
automatically reruns the calculation. The caller stores only a minimal volatile-wait marker;
if it assigns the returned value to an ordinary FCL name, that value is naturally persisted as
the caller's own data after it resumes.

Volatile source may use FCL language features and only the side-effect-free built-ins
(`math`, `array`, `text`, `path`, `util`, formatting-only `term` helpers, and the read-only
`process.args`/`process.arg`). It cannot import packages or modules, read input, print output,
access files or package data, create processes, use IPC/timers/networking, or request effects.
These limits guarantee that the volatile process does not save or externally change any CilExec
data.

A package-private worker is useful when an application first generates a pure helper source in
its private data space and then repeatedly calls `process.run(packageData.root() + "worker.fcl",
arguments)`. The Runtime verifies that the URI belongs to the calling installed package and uses
a bounded in-memory cache keyed by the helper source bytes. The cache is only an optimisation:
after restart the helper is read and compiled again, while the private source remains ordinary
package data and is removed with that package's private space on uninstallation.

## Users: `user`

| Call | Effect |
| --- | --- |
| `user.getCurrentUser()` | Return the current user's UUID. |
| `user.isLocal()` | Check whether the current user has `SYSTEM_ADMIN`. |
| `user.validateUser(usernameOrUuid)` | Verify that a user exists and is visible to the current user; ordinary users only validate themselves. |
| `user.list()` | Return basic information for all users; requires administrator identity. |
| `user.create(username, password [, administratorCredentials])` | Create a new user. Without the third argument the account receives the ordinary user capabilities (any user may self-register, matching terminal registration). To create an administrator pass an array `[administratorUsername, administratorPassword]`: the named administrator must be ACTIVE, match the password, and currently hold effective `SYSTEM_ADMIN` (direct or group derived, expiry aware) - the database verifies all of this atomically with the creation, so a revoked or expired capability can never mint a fresh administrator. An audit event records the administrator actor (or the self-registering user). The new user's VFS root is provisioned on first login. |
| `user.disable(userUuid)` | Disable a user account without deleting its data; requires administrator identity. |
| `user.remove(userUuid)` | Permanently remove a user account, every record owned by that user, and its no-login PostgreSQL tenant role in one database transaction; requires administrator identity. The calling administrator cannot remove itself. |
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
| `network.download(url, vfsPath)` | Download binary content in persisted 4 MiB chunks and write it unchanged to the current user's VFS; returns `{nodeId, path, url, status, bytes, mediaType}`. A single file is capped at 1 GiB; the server must support HTTP Range to download files larger than 4 MiB. An empty remote file (HTTP 416 with `Content-Range: bytes */0`) is a successful zero-byte download, not an error. Multi-chunk downloads resume with `If-Range`, so the server must return an ETag or Last-Modified validator or the download fails rather than silently mixing old and new content. |
| `socket.connect(host, port)` | Verify connectivity, return endpoint information, then close the connection. |
| `socket.send(host, port, data)` | Connect, send UTF-8 data, close, and return the number of bytes written. Also accepts `socket.send({"host":"…","port":123}, data)`. |
| `socket.receive(host, port [, maximumBytes])` | Connect, read text, and close. |
| `socket.close(...)` | Returns `true`; sockets are already closed automatically on every call. |
| `socket.bind([port])` | Bind a port briefly and return `{host, port, oneShot}`; no persistent listening. |
| `socket.accept(port [, maximumBytes])` | Listen briefly and accept one connection, waiting at most 30 seconds; returns the remote endpoint and the data read. |

### Network target policy

Every `network.*` request is resolved and pinned before any bytes are sent:

- **Default-deny SSRF policy.** Loopback, link-local, site-local, multicast, NAT64, 6to4,
  documentation, and other special-use ranges are blocked; `localhost` and
  `host.docker.internal` hostnames are blocked unless explicitly allowed. DNS is resolved
  once, every returned address is validated, and the connection is pinned to a validated
  address while the original host name is preserved as the `Host` header (and TLS peer
  name). Multi-address hostnames may fail over across address families.
- **Explicit allowlists.** `CILEXEC_NETWORK_ALLOW_PRIVATE_HOSTS` (comma-separated host
  names) and `CILEXEC_NETWORK_ALLOW_PRIVATE_HTTP_ORIGINS` (comma-separated `scheme://host:port`
  origins without paths) permit deliberately private targets. Host-name allowlist entries
  are matched against the resolved address set, not just the literal name.
- **Trusted-proxy mode.** When `CILEXEC_NETWORK_TRUST_PROXY=true`, all requests are routed
  through `CILEXEC_NETWORK_PROXY` (`host:port`), which becomes the network boundary and owns
  name resolution. Name-based targets are delegated without classification; literal IP
  targets are still blocked unless private (fake-IP ranges such as `198.18.0.0/15` are
  accepted for fake-IP DNS modes). HTTPS through the proxy uses CONNECT tunneling and the
  TLS peer name remains the original host name.
- **TLS pinning.** HTTPS connections validate the certificate against the originally
  requested host name, never the resolved IP literal.
- **Bounded exchanges.** HTTP exchanges are capped at 4 MiB for `httpGet`/`httpPost`;
  `network.download` reads bounded 4 MiB ranges and each whole exchange is bounded by a
  total deadline.

## Packages: `package`

Packages are immutable SQLite `package.db` files. The recommended flow is
`package.build` → `package.install` → `import` or `package.run`. A hash import accepts
the SHA-256 of an installed package database file; a private process alias is optional.
`import "path.fcl" [as "alias"]` initializes a source module once for that import binding. It
executes the module's top-level code, saves its resulting globals with the process continuation,
and links its functions. Imported functions resolve globals from that saved module namespace,
never from the importing program. Only `public func` declarations are exported; `private func`
declarations remain available to their module's public functions as internal implementation.
Without an alias public functions are bare names; with one they are `alias.function`. Imported
source may not contain `import` or `include` directives and may not suspend during initialization.
`include "path.fcl"` remains source splice: its top-level statements execute and its public
function declarations persist in a terminal context. `package.json` must
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
| `package.info(coordinateOrDatabaseFileSha256)` | Look up a package with its `kind`, dependencies, entrypoint, exports, and capability list. The argument can be `namespace/name/version` or the complete SHA-256 of the installed `.db` file; `(namespace, name, version)` as three arguments also works. |
| `package.list()` | Return the releases effectively installed for the current user. |
| `package.install(vfsPath)` | Install from a `.db` file in the VFS; the package identity is the SHA-256 of its bytes. Creates the current user's installation root, the exact dependency closure, and a private data space. |
| `package.uninstall(databaseFileSha256)` | Atomically remove the package for the current user. There is no force option: dependent installations are reported as a coordinate list and active process bindings are reported with their PIDs, so the caller can run `process.removeFinished(pid)` and `package.uninstall(dependentHash)` first. It removes the package's private data and unreferenced package payloads, but never ordinary user files. Returns a summary map. |
| `package.build(manifestPath, outputPath)` | Read the `package.json` and declared files in the VFS and build a `.db` in the VFS. |
| `package.run(databaseFileSha256 [, entrypoint])` | Create a child process running a package entrypoint. This call uses the installed `.db` file `sha256`; the default entrypoint is `run`, and PID and other information are returned. |
| `package.verify(coordinateOrDatabaseFileSha256)` | Verify that the package database object still matches the file hash recorded at install time. The three-part coordinate also works as arguments. |
| `package.resource(coordinateOrDatabaseFileSha256, resourcePath)` | Read a declared text resource from the package. |
| `package.pin(coordinateOrDatabaseFileSha256)` | Validate that a release is installed and return its metadata. Despite the legacy name, this call does not create or change a process binding. |

Package `import` accepts the full SHA-256 of an installed package database, optionally
with a private per-process alias; the qualifier is the hash itself when no alias is
given. Only packages effectively installed for the current user can be imported or
run. Importing the same package alias again in the same session binds it to the newest
requested hash (last wins). Source imports resolve relative to the process PWD, remain
private to that process, and persist as exact loaded source bindings across recovery:

```fcl
import "c8b8a024847aa873de9655443280104f4cc185b1770b6308ca073999b1503bff" as "e"
import "c8b8a024847aa873de9655443280104f4cc185b1770b6308ca073999b1503bff"
value = "c8b8a024847aa873de9655443280104f4cc185b1770b6308ca073999b1503bff".open("x.txt")
```

Process aliases and captured source imports are private to one process continuation.
Source imports resolve relative to the process PWD; re-importing the same source binding
captures its current source and initialization again. Reinstalling the same release is
idempotent and preserves the package's private data.

| Call | Effect |
| --- | --- |
| `package.recover()` | Administrator consistency report over package lifecycle state. Returns `{ok, issues}`; a healthy database reports an empty issue list. Detects data usage mismatches, installations without releases, bindings without installations, and spaces without installations. |

## Private Package Data: `packageData` and user data management

Every user and exact installed package release owns an isolated private data space
at `package-data://<database-file-sha256>/`. It is not an ordinary VFS path: other
packages and arbitrary `file.*` paths cannot reach it, and different package
versions never share data automatically.

| Call | Effect |
| --- | --- |
| `packageData.root()` | Return the current package's virtual root URI. |
| `packageData.exists(path)` | Return whether a file or directory entry exists. |
| `packageData.read(path)` | Read one private file as text. |
| `packageData.readChunk(path, offset, maximumBytes)` | Bounded random-access read. |
| `packageData.write(path, value)` | Create or CAS-replace a private file. |
| `packageData.seedResource(resourcePath, destinationPath)` | Copy one declared immutable package text resource into a missing private-data file. Returns `true` when it created the file and `false` when that destination already exists. This is intended for versioned, package-private helper sources such as pure `process.run` workers. |
| `packageData.append(path, value)` | CAS-append text to a private file. |
| `packageData.mkdir(path)` | Create a private directory entry. |
| `packageData.list(path)` | List direct children of a private directory. The supplied path is literal: `%` and `_` are ordinary filename characters, never patterns. |
| `packageData.remove(path)` | Remove a file or empty directory entry. Paths are literal, including `%` and `_`. |
| `packageData.clear(path)` | Delete every entry inside an existing private directory, but keep that directory. The path is literal, so `packageData.clear("cache_%")` cannot affect `cacheX`. |
| `packageData.rename(source, destination)` | Rename inside the same private space. |
| `packageData.size(path)` | Return a private file's byte size. |
| `packageData.usage()` | Return `{logicalBytes, quota, files}` for the private space. |

`packageData.*` can only be called from installed package code; the calling package's
identity comes from the linked function provenance, and a package can never address
another package's space. The package manifest must declare the `package.data`
capability. Top-level user FCL calls are rejected.

Users manage their own package data through the `package` namespace:

| Call | Effect |
| --- | --- |
| `package.dataInfo(databaseFileSha256)` | Usage, quota, file count, and timestamps. |
| `package.dataList(databaseFileSha256 [, path])` | List private entries. |
| `package.dataRead(databaseFileSha256, path)` | Read one private file as text. |
| `package.dataExport(databaseFileSha256, destinationVfsPath)` | Write a deterministic SQLite archive of the private space to the user's VFS. The archive is an ordinary user file and survives uninstallation. |
| `package.dataImport(databaseFileSha256, sourceVfsPath)` | Merge an exported archive into the private space of the installed package. |
| `package.clearData(databaseFileSha256)` | Delete every private entry but keep the empty space. |
| `package.dataQuota(databaseFileSha256)` | Return the effective quota in bytes. |
| `package.setDataQuota(user, databaseFileSha256, bytes)` | Administrator quota override; cannot be set below current usage. |
| `package.removeDataQuota(user, databaseFileSha256)` | Remove an administrator quota override. |

The default quota is 256 MiB per user and exact package. Exact-version isolation is
deliberate: version migration is an explicit `package.dataExport` +
`package.dataImport` pair.

Uninstallation removes the private data space, Market caches, installation records,
and process bindings; ordinary user documents and exported archives are never
deleted.

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
| `market.search(text)` | Search the local index with whitespace-separated AND terms. Each term prefix-matches a package name, namespace, kind, `namespace/name`, tag, or summary/description word. A term of at least eight characters may also prefix-match the package SHA-256; version numbers are not searched. If no index exists, update first. |
| `market.info(sha256)` | Look up a package record by the full SHA-256 of its distribution file. |
| `market.download(sha256)` | Download in 4 MiB chunks and recompute the full file hash. |
| `market.install(sha256)` | Recursively download and register exact-hash dependencies, then save a market receipt. It does not create an import alias. |
| `market.list()` | List the current user's MARKET installations from the installation ledger. |
| `market.help()` | Return help text for the market functions. |
| `market.run()` | Return the built-in client version and help without requiring a configured mirror. |

Except for `market.configure`, `market.origin`, `market.help`, `market.run`, and the
local-only `market.list`, the operations require a configured mirror.
When it is missing, an explicit error names
the configuration command instead of silently falling back to raw input mode.

## Swap Pool (Inter-process Data): `swapPool`

The swap pool belongs to the current user and suits inter-process values, signals,
and locking.

| Call | Effect |
| --- | --- |
| `swapPool.create(path)` / `swapPool.remove(path)` | Create / remove a swap pool. |
| `swapPool.exists(path)` | Check whether a swap pool exists. |
| `swapPool.list([pool])` | Without arguments, list all of the current user's swap pools; with a pool path, list the variables in that pool. |
| `swapPool.add("name:value", pool [, option...])` | Add a variable. Optional `"type:sync"` or `"type:times(n)"` control how it is retained. |
| `swapPool.get(pool, variable)` | Read a variable value; `null` when not currently readable. A default `ALWAYS` value stays readable until explicit removal. `type:sync` clears its changed flag (a later `add`/`signal` makes it readable again), while `type:times(n)` decrements the remaining reads and becomes unreadable at zero. Neither mode deletes the variable automatically. |
| `swapPool.update(pool, variable, value [, fencingToken])` | Update a variable; pass the fencing token when locked. |
| `swapPool.remove(pool [, variable [, fencingToken]])` | Remove one pool, or one variable inside a pool. A variable removal accepts the fencing token when the variable is locked. |
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

## Messaging: `ipc`

| Call | Effect |
| --- | --- |
| `ipc.sendDirect(pid, payload [, expiresAt])` | Send to one process. `expiresAt` is an optional ISO-8601 instant. |
| `ipc.createChannel(name)` / `ipc.createTopic(name)` | Create a durable channel or topic. Channel creation returns a generated `channelId`; later channel calls use that UUID. |
| `ipc.removeChannel(channelId)` / `ipc.removeTopic(topic)` | Remove a durable channel or topic. |
| `ipc.subscribeChannel(channelId)` / `ipc.subscribeTopic(topic)` | Subscribe the current process to the channel or topic. |
| `ipc.receive()` / `ipc.poll()` | Receive the next owned message; `receive` blocks until one arrives, `poll` returns the current head immediately. |
| `ipc.consume(deliveryId)` | Mark a received delivery as consumed. |
| `ipc.sendChannel(channelId, payload [, expiresAt])` | Send to one active channel consumer. |
| `ipc.publishTopic(topic, payload [, expiresAt])` | Publish to the active topic subscribers. |
| `ipc.broadcast(topic, payload [, expiresAt])` | Broadcast to the active topic subscribers. |
| `ipc.purge(olderThan [, limit])` | Delete up to `limit` owned messages older than the ISO-8601 cutoff when all deliveries are terminal or the message has expired; the default is 1000 and the maximum is 10000. |

Message rows are durable after consumption. Use `ipc.purge` to release retained
payload references and message quota; pending unexpired deliveries are never purged.
The database guarantees that one delivery can make the committed transition to
`CONSUMED` at most once. `ipc.poll()` is reserve/ack: if application work happens after
reservation but before `ipc.consume()` and the Runtime crashes, startup resets the
unconsumed reservation and the message can be delivered again.

## System: `system`

| Call | Effect |
| --- | --- |
| `system.list()` | Return every function name and alias registered in the current Runtime. Directory listings belong to `file.list`. |
| `system.extensions()` | Return the `id`, `version`, and `description` of the Java extensions sealed in at source-build time. Extensions cannot be added, removed, or replaced at runtime. |
| `system.kill(pid)` | Alias of `process.kill(pid)`. |
| `system.exec(command)` | Administrator: execute a command from the host allow-list. `command` can be a string or an array of strings; it does not go through a shell, and `CILEXEC_FCL_EXEC_ALLOWLIST` must be set — no program is allowed by default. |
| `system.invoke(qualifiedFunction [, argumentArray])` | Administrator: call a function registered in the Runtime registry by string, for example `system.invoke("file.read", ["/x.txt", "alice"])`. It cannot call itself or user-defined, package-exported, or source-imported functions. |
| `system.resolveEffect(...)` | **Currently unavailable.** External effects are handled by the Runtime control plane. |
| `system.reset(...)` | **Currently unavailable.** Runtime reset is not exposed to FCL. |

## Programs: `program`

| Call | Effect |
| --- | --- |
| `program.remove(programId)` | Administrator: explicitly remove one immutable Program together with its source and FCLB objects' reachability. If any process of any state still references the Program, or any other Program imports it as a module, nothing is deleted and the call returns `{removed: false, processCount, importedByCount, processes, importedBy}` so the caller can resolve the references first (for example with `process.removeFinished`). On success it returns `{removed: true, ...}`; the bytes become unreachable and are reclaimed later by `storage.purgeUnreferenced`. |

## Terminal Sessions: `terminal`

| Call | Effect |
| --- | --- |
| `terminal.remove(sessionId)` | Administrator: permanently remove one CLOSED terminal session together with its committed input rows and attachments. An OPEN session is never removed. Returns `true` when the session was removed. |

## Timers: `timer`

| Call | Effect |
| --- | --- |
| `timer.purge(before [, limit])` | Administrator: delete FIRED and CANCELLED timer history whose last activity is older than the ISO-8601 instant `before`; returns the number removed. The optional `limit` (1–100000) bounds rows per invocation. SCHEDULED and CLAIMED timers are never touched, and nothing is purged automatically. |

## Audit: `audit`

| Call | Effect |
| --- | --- |
| `audit.purge(before [, limit])` | Administrator: explicitly delete audit events created before the ISO-8601 instant `before`; returns the number removed. The optional `limit` (1–100000) bounds rows per invocation. This is the only way audit history shrinks — there is no background retention task — and the purge itself is recorded in the audit trail. |

## Quick Lookup & Troubleshooting

```fcl
system.list()                        // see the functions actually loaded
env.get("PWD")                     // current working directory (Java-managed, read-only)
user.getCurrentUser()               // current user UUID
user.isLocal()                      // whether the current user is an administrator
process.list()                       // visible processes
file.list(".")                      // metadata of the current directory
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
- `src/main/java/com/follarce/application/FclRuntimeFunctions.java`: registry composition,
  environment, utility, user functions, and shared durable-operation helpers.
- `src/main/java/com/follarce/application/FclFileRuntimeFunctions.java`,
  `FclProcessRuntimeFunctions.java`, `FclNetworkRuntimeFunctions.java`, and
  `FclPackageRuntimeFunctions.java`: the corresponding database-aware function domains.
- `src/main/java/com/follarce/application/FclMemoryFunctions.java`: process-local memory
  inspection and value deletion through `memory.destroy`.
- `src/main/java/com/follarce/terminal/DatabaseTerminalControl.java`: terminal colon
  commands.
