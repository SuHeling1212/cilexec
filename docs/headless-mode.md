# Headless Mode

Headless mode lets a host terminal execute a single FCL snippet directly, without entering
CilExec's login menu and interactive Shell. It still connects to the already-running shared
Runtime JVM, so it neither starts a JVM per invocation nor creates a new FCL process per
instruction.

Initial install and administrator account creation still use:

```bash
./tools/Install.sh
```

Afterwards, consecutive invocations in the same host terminal:

```bash
./tools/Headless.sh 'counter = 1'
./tools/Headless.sh 'counter = counter + 1; io.println(counter)'
```

The second command prints `2`. Invocations from the same host terminal reuse the same
persistent REPL session, including its suspended FCL processes, variables, functions,
imports, and current working directory. A separate host terminal gets an independent
context.

The script derives the context ID from the SHA-256 digest of the host TTY path and never
sends the TTY path to CilExec. The username defaults to `local` and can be changed with
`CILEXEC_TERMINAL_USERNAME`. The password is read via a no-echo prompt and sent over
standard input and the in-container loopback socket; it never appears in command-line
arguments or environment variables.

CI or TTY-less environments must explicitly set a stable, non-sensitive context ID:

```bash
  CILEXEC_HEADLESS_CONTEXT=build-42 ./tools/Headless.sh 'io.println("done")'
```

Different context IDs do not share variables. Do not treat the context ID as an
authentication credential; every invocation must still supply the CilExec user's password.
The protocol frame is capped at 4 MiB of UTF-8 source bytes. The shared REPL submission limit
is lower: at most 256 KiB characters. Both limits apply.

The session protocol uses an in-container loopback socket. Unlike the interactive terminal,
the current headless request path does not run a concurrent disconnect pump while FCL is
executing. If the client disconnects during execution, the submitted work may continue until
it completes, suspends, fails, or is interrupted through another control path.

Automation without a TTY can supply the one-line password from protected standard input;
do not append anything after it, because the FCL source is already provided by the script
argument:

```bash
printf '%s\n' "$SECRET_FROM_SAFE_STORE" | \
CILEXEC_HEADLESS_CONTEXT=build-42 ./tools/Headless.sh 'io.println("done")'
```
