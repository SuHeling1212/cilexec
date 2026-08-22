# FCL Executable Artifacts (V003)

CilExec 0.0.3 introduces the V003 executable-program artifact. This changes how a program is
persisted and restored; it does not change FCL source syntax. The language version therefore
is now `fcl-0.0.3`; V002 packages using `fcl-0.0.2` remain import-compatible because this
release did not change source semantics.

| Item | Version |
| --- | --- |
| CilExec release | `0.0.3` |
| Database migration | `V003` |
| Executable artifact format | `FCLB` v3 |
| FCL source language | `fcl-0.0.3` |

## What is saved

Every new program has two immutable VFS objects:

```text
source object (.fcl)
  └── the exact readable FCL source

executable object (.fclb)
  ├── FCLB magic bytes
  ├── executable-format version (3)
  ├── SHA-256 of the separate source object
  ├── flat instruction table
  ├── expression trees
  ├── function metadata
  └── class, field, and method metadata
```

The executable object intentionally does not duplicate the source text. Its VFS content hash
protects the whole artifact, and its embedded source hash binds it to the separately retained
source object. The program database row stores both object hashes and the executable format
version.

On recovery, the Runtime loads the executable object directly. It checks the FCLB header, its
format version, its embedded source hash, the source object, and the program-row identity before
executing any instruction. It does not parse or compile the source again.

The object graph is encoded by CilExec's own versioned codec. Java object serialization is never
used as a persisted format.

## Continuations and recovery

The program counter in a continuation refers to the immutable FCLB instruction table that was
created with that program. A V003 continuation is stored with the matching versioned continuation
envelope. The normal PostgreSQL transaction rules are unchanged: a slice commits the continuation
and all semantic state together, or none of it commits.

## Upgrade from V002

Before upgrading, create a verified logical backup. Then install the 0.0.3 Runtime and apply
`V003` using the ordinary migration workflow. Version 0.0.3 accepts only database schema version
3 at startup.

Existing V002 programs are deliberately not rewritten in place. Their immutable JSON source
envelopes and V002 continuations remain readable, so an upgraded Runtime can finish them safely.
Newly created programs, including newly submitted terminal code, always use FCLB v3. To convert
an existing V002 program deliberately, submit or create it again from its retained source; this
creates a new immutable V003 program identity and FCLB object.

## Identical source submissions

After `include` expansion, CilExec hashes the exact source bytes before compiling. If the current
user already owns a program with that source hash, language version, and FCLB format, CilExec
returns that existing program and directly reuses its source and FCLB objects. Compilation occurs
only for a new identity. Concurrent identical submissions may both begin compiling, but the
database uniqueness rule still stores one program identity.

There is no automatic downgrade. Restoring a pre-upgrade backup is the rollback procedure.

## Explicit program removal

FCLB is a durable recovery artifact, and CilExec never deletes programs on a schedule. A
Program row, its source object, and its FCLB object persist until an administrator explicitly
removes them:

```fcl
program.remove(programId)
```

The call refuses to remove a Program that is still referenced: if any process of any state
uses it, or any other Program imports it as a module, it returns a reference report
(`removed=false` with the blocking PIDs and importing Program IDs) instead of deleting.
When removal succeeds, the source and FCLB objects become unreachable; the administrator-run
`storage.purgeUnreferenced([limit])` collector reclaims their bytes after its independent
one-hour safety window. `process.removeFinished()` remains the explicit way to remove ended
process state when it is no longer needed.

## Command-line artifact creation

```bash
./tools/fcl_compile.sh program.fcl program.fclb
```

This writes a standalone FCLB executable artifact. It is useful for inspection and tooling, but a
Runtime program still retains its source object and validates the source hash when loading it.
