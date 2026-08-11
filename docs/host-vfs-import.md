# Copying Host Files into the CilExec VFS

`tools/HostMove.sh` is a single-command importer on the host side. It hides the Docker tool
container and the database connection details; the final result is a real CilExec VFS file
node with its database objects, not a temporary file left in the container filesystem.

## Usage

Run it from the host project directory. The script generates internal database keys as
needed, builds a missing CilExec image, starts PostgreSQL if it is stopped, and validates
and migrates the database before every import:

```bash
./tools/HostMove.sh /absolute/host/report.pdf /documents/report.pdf alice
```

The target user is a required argument. It must be an active, named CilExec user holding
the `VFS_MOUNT_HOST` and `VFS_WRITE` capabilities; the `local` superuser is rejected as the
import target by default. Importing to it would be refused even when requested explicitly.

The target VFS parent directory must already exist, and the target file must not exist
(see failure behavior below). The source file must be a regular file; symlinks are
rejected. The single-file limit matches the VFS contract at 1 GiB.

## Persistence and Failure Behavior

1. The script mounts the one source file read-only into a disposable Docker tool container.
2. The Java importer writes the content into PostgreSQL as immutable objects in 4 MiB
   chunks.
3. Before streaming any content, the importer pre-checks the target in the database: the
   target user and capabilities are verified and the target path is resolved, so a failing
   pre-check leaves no orphaned object chunks behind.
4. After all bytes are written, the final database transaction creates the VFS node and
   records the audit event.
5. Once the tool container exits successfully, the host source file remains; the VFS keeps
   an independent database copy.

Any read, permission, path, or database error fails the command and does not modify the
host source file. If the target file already exists, the command fails before any content
is written.

The internal Java command is:

```text
cilexec host move <container-source-file> <absolute-vfs-path> <username>
```

It is meant for `tools/HostMove.sh`; `move` is the internal name kept for compatibility with
existing commands, and the actual behavior is a copy. It requires no host database port
exposure and never mounts the Docker Socket.
