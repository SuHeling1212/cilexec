-- CilExec schema baseline. Cluster roles and the database are created by
-- docker/postgres/init/00-cilexec-bootstrap.sh (or by an external DBA).

-- name: migration.V001.require_bootstrap_roles
DO $cilexec$
DECLARE
    required_role text;
BEGIN
    FOREACH required_role IN ARRAY ARRAY[
        'cilexec_owner',
        'cilexec_migrator',
        'cilexec_runtime',
        'cilexec_effect_worker',
        'cilexec_readonly'
    ]
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = required_role) THEN
            RAISE EXCEPTION 'required CilExec role % is missing; run cluster bootstrap first', required_role;
        END IF;
    END LOOP;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles
        WHERE rolname IN ('cilexec_runtime', 'cilexec_effect_worker')
          AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole)
    ) THEN
        RAISE EXCEPTION 'runtime and effect worker have forbidden cluster privileges';
    END IF;
END
$cilexec$;

SET ROLE cilexec_owner;

-- name: migration.V001.lock_down_public
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
DO $database_privileges$
BEGIN
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
END
$database_privileges$;

-- name: migration.V001.create_schemas
CREATE SCHEMA meta AUTHORIZATION cilexec_owner;
CREATE SCHEMA auth AUTHORIZATION cilexec_owner;
CREATE SCHEMA object_store AUTHORIZATION cilexec_owner;
CREATE SCHEMA vfs AUTHORIZATION cilexec_owner;
CREATE SCHEMA program AUTHORIZATION cilexec_owner;
CREATE SCHEMA process AUTHORIZATION cilexec_owner;
CREATE SCHEMA scheduler AUTHORIZATION cilexec_owner;
CREATE SCHEMA ipc AUTHORIZATION cilexec_owner;
CREATE SCHEMA effect AUTHORIZATION cilexec_owner;
CREATE SCHEMA package AUTHORIZATION cilexec_owner;
CREATE SCHEMA terminal AUTHORIZATION cilexec_owner;
CREATE SCHEMA audit AUTHORIZATION cilexec_owner;

-- name: migration.V001.schema_usage
GRANT USAGE ON SCHEMA meta, auth, object_store, vfs, program, process,
    scheduler, ipc, effect, package, terminal, audit TO cilexec_runtime;
GRANT USAGE ON SCHEMA meta, effect, process, audit TO cilexec_effect_worker;
GRANT USAGE ON SCHEMA meta, auth, object_store, vfs, program, process,
    scheduler, ipc, effect, package, terminal, audit TO cilexec_readonly;

-- name: migration.V001.default_public_revocation
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA meta REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA auth REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA object_store REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA vfs REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA program REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA process REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA scheduler REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA ipc REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA effect REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA package REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA terminal REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA audit REVOKE ALL ON TABLES FROM PUBLIC;

COMMENT ON SCHEMA meta IS 'CilExec instance, runtime boot, and security metadata';
COMMENT ON SCHEMA auth IS 'CilExec principals, capabilities, and PostgreSQL role mapping';
COMMENT ON SCHEMA object_store IS 'Immutable content-addressed byte objects';
COMMENT ON SCHEMA program IS 'Immutable FCL programs and compiled statements';
COMMENT ON SCHEMA process IS 'Durable process continuation and timer state';
COMMENT ON SCHEMA scheduler IS 'FIFO queue, runners, and fenced leases';
COMMENT ON SCHEMA ipc IS 'Persistent direct, channel, topic, and broadcast delivery';
COMMENT ON SCHEMA vfs IS 'Database-backed virtual filesystem';
COMMENT ON SCHEMA package IS 'Immutable SQLite package releases and environments';
COMMENT ON SCHEMA effect IS 'Journal for all external side effects';
COMMENT ON SCHEMA terminal IS 'Committed terminal input and process attachments';
COMMENT ON SCHEMA audit IS 'Append-only structured audit events';

RESET ROLE;
