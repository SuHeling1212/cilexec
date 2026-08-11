# Non-Docker Linux Deployment

These templates target a systemd host with Java 26, PostgreSQL 17, `psql`, and `runuser`.
Review paths and hardening directives against the local distribution before installation.

1. Install `cilexec-app.jar` as `/opt/cilexec/cilexec-app.jar`, owned by `root:root` and
   mode `0644`.
2. Install `cilexec.sysusers` under `/usr/lib/sysusers.d/` and `cilexec.tmpfiles` under
   `/usr/lib/tmpfiles.d/`, then run `systemd-sysusers` and `systemd-tmpfiles --create`.
3. Copy `cilexec.env.example` to `/etc/cilexec/cilexec.env`, set the PostgreSQL TLS URL,
   and install it as `root:root` mode `0600`. systemd reads this file before changing the
   service identity.
4. Generate five independent secrets with `openssl rand -hex 32`: runtime, effect worker,
    migrator, readonly, and exporter. Put them at the paths in the environment/bootstrap files, owned by
    `root:root`, mode `0400`. Do not place passwords directly in the environment file. The
    units use `LoadCredential=` to expose only runtime/effect passwords to `cilexec` and only
    the migrator password to the separate `cilexec-migrate` account.
5. Install and run `bootstrap-postgres.sh` as root. It invokes local `psql` as the
   `postgres` OS user, creates or repairs only the CilExec roles/database, and rotates their
   passwords to the protected files.
6. Compile `docker/terminal-client.c` with `cc -std=c11 -O2` and install the resulting
   `cilexec-terminal-client` in `/usr/local/bin`; it is also the health probe used by containers.
7. Install the two unit files under `/etc/systemd/system/`, run `systemctl daemon-reload`,
   then execute `systemctl start cilexec-migrate.service`.
8. Start the runtime with `systemctl enable --now cilexec.service`. Verify
   `curl --fail http://127.0.0.1:8080/health/ready` and inspect JSON logs with
   `journalctl -u cilexec.service`.

The management and terminal listeners remain loopback-only. Use host monitoring or an
authenticated local proxy rather than opening either socket directly to a network. Run the
role bootstrap only for initial provisioning or intentional credential rotation; migrations
remain a separate, auditable one-shot unit.
