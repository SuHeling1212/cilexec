# Copying Host Files into the CilExec VFS

> **0.0.5 note.** `tools/HostMove.sh` is a Docker-era wrapper and is deprecated as of 0.0.5.
> The standalone JAR does not require Docker; the application command described below remains
> available when CilExec runs against an externally managed PostgreSQL database.

CilExec deliberately keeps host-to-VFS transfer outside FCL: it is an explicitly invoked host
tool, not a function a script can call. The result is a real CilExec VFS file node with its
database objects, not a temporary file left in a container filesystem.

## Application command

With an external PostgreSQL instance (set `CILEXEC_DATABASE_URL` and the configured
credential files), run the JAR's application command from the host:

```bash
java -jar cilexec-app.jar host move /absolute/host/report.pdf /documents/report.pdf alice
```

The Docker-era wrapper hid the database connection and mounted the single source file read-only
into a disposable tool container:

```bash
./tools/HostMove.sh /absolute/host/report.pdf /documents/report.pdf alice   # historical
```

The target user is a required argument. It must be an active, named CilExec user holding
the `VFS_MOUNT_HOST` and `VFS_WRITE` capabilities; the `local` superuser is rejected as the
import target unconditionally. Importing to `local` is refused even when requested explicitly.

The target VFS parent directory must already exist, and the target file must not exist.
The source file must be a regular file; symlinks are rejected. The single-file limit matches
the VFS contract at 1 GiB.

## Persistence and Failure Behavior

1. The importer streams the one source file into PostgreSQL as immutable objects in 4 MiB
   chunks.
2. Before streaming any content, it pre-checks the target in the database: the target user and
   capabilities are verified and the target path is resolved, so a failing pre-check leaves no
   orphaned object chunks behind.
3. After all bytes are written, the final database transaction creates the VFS node and records
   the audit event.
4. The host source file remains; the VFS keeps an independent database copy.

Any read, permission, path, or database error fails the command and does not modify the host
source file. If the target file already exists, the command fails before any content is written.

The internal Java command is:

```text
cilexec host move <source-file> <absolute-vfs-path> <username>
```

The actual behavior is a copy. It requires no host database port exposure. Never mount the
Docker Socket or a broad host directory for this feature.

## Single-JAR mode

A standalone JAR owns its own `cilexec-data/` directory while it is running, so it cannot run a
second `host move` process against the same data directory at the same time. For host-file import
in that mode, either use the externally managed database mode above, or move the file into the
VFS from FCL when the content is available inside the script (for example via an exported
file/package workflow). The runtime never reads arbitrary host paths from FCL.
