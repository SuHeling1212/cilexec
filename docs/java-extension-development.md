# Developing CilExec Java Source Extensions

## 1. What This Extension System Solves

CilExec lets a capable developer add Java extensions **while the project source is still
in hand**. Extensions can:

- register new FCL namespaces and functions;
- register the external-side-effect handlers those functions require;
- read and write CilExec repositories inside the PostgreSQL transaction of the current
  FCL statement;
- persist their own state in the current process's durable continuation;
- wait on external operations through the effect journal and resume the same expression
  when the outcome arrives.

It is not a runtime plugin system. CilExec scans no plugin directories, does not use
`ServiceLoader`, accepts no JAR paths, and exposes no terminal functions to install,
uninstall, or hot-reload Java extensions. The single entry point is the source file
[`SourceExtensionIndex.java`](../src/main/java/com/follarce/extension/SourceExtensionIndex.java).
The index is compiled into an immutable catalog when the JVM initializes.

The flow for adding an extension to a release is therefore fixed:

```text
write Java source → register explicitly in SourceExtensionIndex → test → rebuild JAR/image
```

Once the JAR or image is built, CilExec itself has no way to modify the extension set.
Adding or removing an extension means going back to the source, re-testing, and building a
new system version.

> "Closed" means there is no supported runtime loading path, not that it is tamper-proof
> against host administrators. Anyone who can replace the JAR, rewrite the image layers, or
> control container runtime arguments can replace the whole program. Production deployments
> should use a read-only container filesystem, image digests, and a controlled registry.

## 2. Directories and Responsibilities

| Location | Responsibility |
| --- | --- |
| `com.follarce.extension.api` | The public Java contracts used by extension developers. |
| `JavaExtensionCatalog` | Validates declarations, freezes the catalog, and wires functions/effects into the Runtime. |
| `SourceExtensionIndex` | The only production source list of extensions. |
| The extension's own package | The extension implementation; an organization name such as `com.acme.cilexec` is recommended. |

An extension implements `CilExecExtension`, returns a fixed descriptor, and declares
functions and effects in `register`. Extension instances and effect handlers may be used by
multiple virtual threads at once; class fields must be immutable or thread-safe.

## 3. Minimal Function Extension

Create `src/main/java/com/acme/cilexec/GreetingExtension.java`:

```java
package com.acme.cilexec;

import com.follarce.extension.api.CilExecExtension;
import com.follarce.extension.api.ExtensionDescriptor;
import com.follarce.extension.api.ExtensionRegistrar;

public final class GreetingExtension implements CilExecExtension {
    @Override
    public ExtensionDescriptor descriptor() {
        return new ExtensionDescriptor(
                "acme.greeting",
                "1.0.0",
                "Acme greeting functions"
        );
    }

    @Override
    public void register(ExtensionRegistrar registrar) {
        registrar.function("greeting", "hello", context -> {
            context.requireArity(1);
            return "Hello, " + context.argument(0);
        }, "hi");
    }
}
```

Then add exactly one constructor entry to `SourceExtensionIndex.sourceExtensions()`:

```java
private static List<CilExecExtension> sourceExtensions() {
    return List.of(
            new GreetingExtension()
    );
}
```

After a rebuild the functions are registered with the Runtime and can be called directly
from FCL; `import` is reserved for importing FCL packages by `.db` file SHA-256 and cannot
load Java extensions:

```fcl
message = greeting.hello("CilExec")
shortMessage = greeting.hi("developer")
```

Installation is rejected if an extension namespace collides with a CilExec built-in
namespace, with another extension's qualified function name or alias, or with an existing
**bare** function name (the unqualified spelling used without a namespace). Invalid names
and duplicate registrations fail at catalog construction or Runtime binding time; an
original function is never silently overwritten.

Extension function arguments are positional and may be `null`: FCL `null` is a first-class
value, so the argument list handed to the callback may contain `null` elements.

## 4. Persistent State and Database Writes

`ExtensionFunctionContext` provides:

| API | Meaning |
| --- | --- |
| `arguments()` / `argument(i)` | The current FCL arguments. |
| `processUid()` / `pid()` / `ownerId()` | The current durable process and user identity. |
| `expressionId()` / `executionEpoch()` | The current expression and execution-fence identity. |
| `now()` | The Runtime time fixed for this statement. |
| `state()` | Extension-private state persisted with the continuation. |
| `transaction()` | Repository view of the current statement transaction, without `commit`, `rollback`, or `close`. |
| `awaitEffect(...)` | Durably registers an external effect, suspends, and resumes when the outcome arrives. |

Persistent counter example:

```java
registrar.function("counter", "next", context -> {
    context.requireArity(0);
    long previous = context.state().find("value")
            .map(Number.class::cast)
            .map(Number::longValue)
            .orElse(0L);
    long next = previous + 1;
    context.state().put("value", next);
    return next;
});
```

`state()` keys are namespaced per extension and saved by the existing continuation codec.
Each key is stored under the format
`cilexec.extension.<extensionId.length>.<extensionId>.<key>`; the length prefix on the
extension ID keeps keys of different extensions from colliding. Allowed values are the same
as FCL values: `null`, strings, booleans, numbers, arrays, and string-keyed objects. Do not
store connections, streams, threads, file handles, arbitrary Java objects, or instances
that only make sense in memory.

`transaction()` exposes the existing domain repositories but does not let an extension end
the transaction itself. Writes made through it become visible in the same commit as the
continuation, process state, and scheduler queue; a rollback undoes them as well. Extensions
must still use the current `ownerId` for authorization and resource ownership and must not
bypass capabilities, RLS, or audit.

## 5. External Effects

Operations outside the database — network requests, host files, message sending, child
processes — must not be written directly inside an FCL function callback. The correct
approach is to have the function call `awaitEffect` and register a matching
`ExtensionEffectHandler`:

```java
import com.follarce.extension.api.ExtensionEffectHandler;
import com.follarce.extension.api.ExtensionEffectPolicy;
import java.util.Map;
import java.util.Optional;

registrar.function("delivery", "send", context -> {
    context.requireArity(1);
    return context.awaitEffect(
            "acme.delivery-send",
            Map.of("message", context.argument(0)),
            ExtensionEffectPolicy.manual()
    );
});

registrar.effect(new ExtensionEffectHandler() {
    @Override
    public String effectType() {
        return "acme.delivery-send";
    }

    @Override
    public Object execute(Object request, Optional<String> idempotencyKey) throws Exception {
        // Call the real external system here and return a persistable FCL value.
        return Map.of("accepted", true);
    }
});
```

The effect request, the process's `WAITING_EFFECT` state, and the continuation are committed
in one database transaction; effect workers cannot see the request before the commit. The
handler runs outside the transaction, its result is written back to the effect journal, and
then delivered to the same durable process. When the expression resumes, a second call to
`awaitEffect` consumes the delivered result instead of creating a new request.

The handler's return value **must be encodable as an FCL value**; an unencodable return
(streams, threads, arbitrary Java objects, and so on) is recorded as `UNKNOWN` rather than
`FAILED`, so the configured recovery path is preserved instead of failing the effect
permanently. Extension effects also do not support object-backed (large-object) payloads;
request and result are limited to regular JSON-encodable values.

Recovery policies:

| Policy | Behavior after a crash | When to use |
| --- | --- | --- |
| `manual()` | No automatic retry when an effect started but its outcome is unknown; the effect stays `UNKNOWN`. | Manual handling is preferable to automatic duplication. |
| `retryIdempotent(key)` | Automatic retry is allowed when the outcome is unknown. | The remote side must truly persist and deduplicate this key. Declaring idempotency locally is not enough. |
| `queryRemote()` | Calls the handler's `queryOutcome` to check the remote outcome first. | The remote side must be able to confirm the original operation's result by durable identity. |

"Exactly once" cannot be guaranteed between two independent systems with local Java code
alone. If the remote side has no idempotency key or outcome query, use `manual()`: it avoids
automatic duplication, but an administrator may need to confirm success or failure during a
crash window.

## 6. Persistence Development Rules

CilExec cannot prove from the language level that third-party Java code respects the
persistence model, so extension authors must follow these rules:

1. `register` may only declare functions and handlers: no networking, file writes, thread
   starts, or reads of changing environment state.
2. FCL function callbacks may re-execute because of transaction conflicts or crashes; apart
   from `state()` and `transaction()` operations, treat them as replayable computations.
3. Never hold semantic state in `static` fields, instance fields, `ThreadLocal`, caches, or
   background threads.
4. Out-of-database operations must go through `awaitEffect`; never send requests or run
   commands directly inside a function callback.
5. Repository writes, state updates, and audit stay in the same statement transaction; do
   not acquire JDBC connections yourself.
6. Return values, effect requests, effect results, and extension state must all be
   encodable FCL values.
7. Handlers must be thread-safe and set explicit timeouts and data-size limits; never wait
   indefinitely or read unbounded data into memory.
8. When a handler receives an idempotency key, it may claim retryable idempotency only if
   the key is passed to the remote side and deduplicated there.
9. If an extension upgrade changes the persistent state format, keep backward reading or
   ship a tested database migration.
10. Extensions must not rely on uncommitted data being immediately visible to other
    threads, connections, or external systems.

## 7. Dependencies, Build, and Release

Add third-party library requirements as ordinary Maven dependencies in the project
`pom.xml`. They are locked, tested, and packaged into the final shaded JAR together with
CilExec; do not download Java dependencies at runtime. Recommended flow:

```bash
mvn clean test
mvn clean package
java -jar target/cilexec-app.jar terminal
```

When using a container, rebuild explicitly:

```bash
./tools/Install.sh --rebuild
```

`system.extensions()` returns the extension IDs, versions, and descriptions sealed into the
current build; `system.list()` returns the actual function names, including extensions.
Bump the extension version when behavior or the persistent format changes, and record it
together with the CilExec image digest.

## 8. Pre-Release Checklist

- Extension ID, function namespace, function names, and effect types are stable and
  conflict-free (including against built-in namespaces and bare names);
- Tests cover the happy path, argument errors, permission denials, transaction rollbacks,
  and Runtime crashes;
- No in-memory semantic state and no direct external effects inside functions;
- The choice among `manual`, `retryIdempotent`, and `queryRemote` matches the remote side's
  real capabilities;
- Handlers are thread-safe with timeouts and size limits;
- Database writes use the current user identity and keep the necessary audit;
- `mvn clean test` passes, the image is published by digest, and it runs with a read-only
  filesystem.
