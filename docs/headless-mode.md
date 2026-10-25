# Headless Mode (historical)

> **0.0.5 note.** The supported distribution is the standalone `cilexec-app.jar`. The
> Docker-era host wrapper `tools/Headless.sh` and its `Install.sh` prerequisite are deprecated
> as of 0.0.5 and are not part of the standalone-JAR acceptance path. The durable terminal
> protocol described below still exists inside the Runtime for host tools that connect to the
> loopback terminal port, but the standalone JAR does not ship a replacement headless CLI.

Headless mode lets a host terminal execute a single FCL snippet directly, without entering
CilExec's login menu and interactive Shell. It still connects to an already-running shared
Runtime JVM, so it neither starts a JVM per invocation nor creates a new FCL process per
instruction.

In the Docker-era wrapper, consecutive invocations in the same host terminal reused one
persistent REPL session (variables, imports, functions, and working directory):

```bash
./tools/Headless.sh 'counter = 1'
./tools/Headless.sh 'counter = counter + 1; io.println(counter)'
```

The protocol frame is capped at 4 MiB of UTF-8 source bytes; the shared REPL submission limit
is lower (at most 256 KiB characters). The session protocol uses a loopback socket. The
wrapper derived its context ID from the host TTY digest and read the password via a no-echo
prompt over protected standard input.

For 0.0.5 the intended host integration is: start the standalone JAR in its own directory,
keep `cilexec-data/` beside it, and use the interactive terminal or a host client that speaks
the loopback terminal protocol. Treat the old wrapper scripts as historical tooling.
