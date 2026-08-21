# Release Version Source

The single editable CilExec release version is in [`.mvn/maven.config`](../.mvn/maven.config):

```text
-Drevision=0.0.3
```

Do not edit release numbers in Maven files, Docker files, Compose files, CI workflows, or runtime
code. They either read this Maven property directly or obtain it through `tools/Version.sh`.

For the current `0.0.N` release line, `N` is derived as the runtime-format and database schema
number. Thus `0.0.3` requires the immutable Flyway migration `V003` and writes runtime format
`3`. Existing migration class names and old persisted data are historical identities and must not
be renamed.

Before a new release, add its immutable `V00N` migration first (a no-DDL version fence when the
relational schema itself is unchanged), then change only `-Drevision=0.0.N`, and run the normal
verification and release workflow. The tests reject an invalid version mapping.
