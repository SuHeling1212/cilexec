# Production Backup And Restore Runbook

All CilExec runtime state is in PostgreSQL. The SQLite logical export is useful for inspection,
but it is not a disaster-recovery backup: it does not preserve PostgreSQL roles, ownership,
WAL history, or a point-in-time recovery chain.

## Service Objectives

Set and approve targets before deployment. A reasonable starting point is RPO <= 5 minutes
(continuous WAL archiving with an alert after one failed archive interval) and RTO <= 60
minutes for a regional restore. These are objectives, not properties of CilExec. Measure them
with quarterly restores of production-sized encrypted backups and record the achieved WAL
replay point, data-loss window, and time to healthy readiness.

## Backup Set

Use a PostgreSQL-aware physical backup tool such as pgBackRest, WAL-G, or `pg_basebackup` plus
a separately managed WAL archive. Keep at least one full base backup inside the retention
window and retain every WAL segment needed from that backup to the desired recovery point.
The tool must verify checksums/manifests and must not copy a live `PGDATA` directory with a
generic filesystem copier.

Back up these items as one documented recovery set:

- Physical cluster backup and continuous WAL archive. A physical backup includes CilExec
  roles and password verifiers as cluster-global state.
- An encrypted `pg_dumpall --roles-only` export and `pg_dump --format=custom cilexec` archive
  as a portability fallback. Treat the roles export as a secret.
- The exact CilExec JAR/configuration revision and non-secret environment configuration.
- Service password files and TLS trust material through the organization's secret manager.
  PostgreSQL password verifiers cannot recreate the password files needed by the application.

Encrypt backups before they leave the database host, using independent repository keys held
in KMS/HSM or the enterprise secret manager. Enforce TLS with certificate verification in
transit, separate backup-writer and restore credentials, immutable/offline retention, and
audited key access. Test key recovery independently; a backup without its key is not usable.

## Credential Rotation

For the bundled persistent Compose deployment, rotate all six database passwords with:

```bash
./docker/rotate-secrets.sh
```

The command stops a running Runtime, changes all role passwords in one PostgreSQL transaction,
atomically replaces the Compose-managed files, recreates PostgreSQL so every secret mount uses
the new inode, verifies each role over TLS, runs the migrator, and restores the Runtime to its
previous running state. Use `--force` only for non-interactive, controlled automation. If a
process kill leaves `docker/secrets/.rotation.lock`, first confirm no rotation process is active,
then run `./docker/rotate-secrets.sh --recover`. Recovery reapplies the retained values through
the local trusted database socket before publishing them, so it is safe whether the interrupted
transaction committed or not. Never delete a nonempty recovery directory before reconciliation.

The command refuses custom `CILEXEC_*_PASSWORD_FILE` paths. Rotate those through the external
secret provider and use its coordinated PostgreSQL password workflow. The command does not
rotate the PostgreSQL CA or server identity; perform certificate rotation as a separate planned
trust-rollover operation so old and new CA trust can overlap.

## PITR Configuration

Enable PostgreSQL checksums and continuous archiving. The exact archive commands depend on the
selected backup product; they must return nonzero until a WAL segment is durably and uniquely
stored. Monitor `pg_stat_archiver`, archive age, repository capacity, backup age, and restore
verification results. Never use a command that overwrites a WAL object with different bytes.

For recovery, restore a base backup to an isolated PostgreSQL 17 cluster, configure the
product's `restore_command`, and set one explicit target such as
`recovery_target_time = '2026-08-09 10:15:00+00'` plus
`recovery_target_action = 'promote'`. Start PostgreSQL, confirm the reached timeline and target
in its logs, then archive the old primary and prevent it from accepting CilExec traffic.

## Physical Restore

1. Stop and disable CilExec. Fence the failed primary at the network and PostgreSQL levels so
   two runtimes cannot target divergent databases.
2. Provision an empty PostgreSQL 17 cluster with at least the original locale, encoding,
   extensions, storage capacity, checksums, and TLS policy. Do not run CilExec migrations.
3. Decrypt and restore the selected physical base backup and replay archived WAL to the target.
   For latest recovery, replay through the newest verified segment; for operator error, stop
   immediately before the bad transaction/time.
4. Verify PostgreSQL starts cleanly, role attributes remain `NOSUPERUSER` and `NOBYPASSRLS`,
   and `cilexec_owner`, `cilexec_migrator`, `cilexec_runtime`,
   `cilexec_effect_worker`, `cilexec_readonly`, and `cilexec_exporter` exist.
5. Restore the matching application secret files, or generate new files and run
   `ops/systemd/bootstrap-postgres.sh` to rotate service role passwords. Never infer passwords
   from the roles export.
6. Start one CilExec instance. Require HTTP 200 from `/health/ready`; verify `database`,
   `controlLock`, `schedulerLoop`, `effectWorkers`, `timerLoop`, and `workListener` are true.
   In terminal mode also require `terminalServer=true`.
7. Run a read/write smoke test, inspect pending scheduler/effect/timer rows, and retain the old
   cluster until the recovery owner signs off. Resume backups on the new timeline immediately.

## Logical Restore To A New Cluster

Use this path for portability, not PITR. Logical restore loses the original WAL timeline.

1. Provision PostgreSQL 17 and keep CilExec stopped. Decrypt the roles and database archives
   only onto restricted temporary storage.
2. Restore reviewed cluster roles first with
   `psql --set ON_ERROR_STOP=1 --dbname postgres --file roles.sql`. Remove unrelated cluster
   roles from a shared-cluster export before use.
3. Run `ops/systemd/bootstrap-postgres.sh` with the restored or newly generated secret files.
   This enforces the expected role flags, ownership membership, database grants, and passwords.
4. Restore into the empty `cilexec` database with
   `pg_restore --exit-on-error --clean --if-exists --dbname cilexec cilexec.dump`. The named
   owner roles must exist before this step; do not use `--no-owner` because object ownership is
   part of the security model.
5. Run the bootstrap script once more to reassert connection/schema grants, then run the
   one-shot `migrate` command to apply every immutable forward migration supported by the target
   release. Never edit or repair an already applied migration checksum in production.
6. Recreate stable tenant NOLOGIN roles and runtime membership with
   `psql --dbname cilexec --file ops/systemd/restore-user-roles.sql`, then run
   `SELECT meta.assert_security_invariants()` as the migrator.
7. Remove decrypted temporary files, start exactly one runtime, and complete the same health,
   RLS, process/effect/timer, and backup-resumption checks as a physical restore.

## Restore Drill Record

For every drill retain the backup ID, base-backup timestamp, first/last WAL segment, requested
and achieved recovery target, checksum verification output, secret-key version, PostgreSQL and
CilExec versions, measured RPO/RTO, health response, smoke-test evidence, and approver. Alerting
must page before the approved RPO is exceeded, not merely when the next scheduled full backup
fails.
