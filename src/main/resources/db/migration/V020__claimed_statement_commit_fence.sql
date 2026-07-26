SET ROLE cilexec_owner;

-- A boot records the exact PostgreSQL session that owns the singleton
-- advisory lock. PID alone is insufficient because backend identifiers can be
-- reused after a disconnect, so the backend start timestamp is recorded and a
-- random second advisory lock acts as an unforgeable, live proof for the boot.
-- name: migration.V020.control_backend_identity
ALTER TABLE meta.boot
    ADD COLUMN control_backend_pid integer,
    ADD COLUMN control_backend_started_at timestamptz,
    ADD COLUMN control_proof_lock_key bigint,
    ADD CONSTRAINT ck_boot_control_backend_identity CHECK (
        (control_backend_pid IS NULL) = (control_backend_started_at IS NULL)
        AND (control_backend_pid IS NULL) = (control_proof_lock_key IS NULL)
        AND (control_backend_pid IS NULL OR control_backend_pid > 0)
    );

-- The final statement UPDATE calls this function in the same transaction as
-- the continuation write. An execution epoch is not sufficient by itself: the
-- matching lease must still be unexpired and the boot's control session must
-- still hold this database's configured advisory lock.
-- name: scheduler.claim_authorizes_commit_as
CREATE FUNCTION scheduler.claim_authorizes_commit_as(
    p_database_role name,
    p_claim text,
    p_process_uid uuid,
    p_owner_id uuid,
    p_runner_id uuid,
    p_boot_id uuid,
    p_execution_epoch bigint
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, meta, scheduler
AS $function$
DECLARE
    actor uuid;
    actual_role name;
BEGIN
    actual_role := CASE
        WHEN NULLIF(current_setting('role', true), 'none') IS NULL
            THEN session_user::name
        ELSE current_setting('role', true)::name
    END;
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF NOT (
           p_database_role::text IN ('cilexec_runtime', 'cilexec_owner')
           AND p_database_role = actual_role
       ) AND actor IS DISTINCT FROM p_owner_id THEN
        RAISE EXCEPTION 'claimed statement commit is not authorized'
            USING ERRCODE = '42501';
    END IF;

    RETURN EXISTS (
        SELECT 1
        FROM scheduler.lease AS lease
        JOIN scheduler.runner AS runner
          ON runner.runner_id = lease.runner_id
         AND runner.boot_id = lease.boot_id
        JOIN meta.boot AS boot ON boot.boot_id = lease.boot_id
        JOIN meta.kernel_instance AS runtime
          ON runtime.kernel_instance_id = boot.kernel_instance_id
         AND runtime.instance_id = boot.instance_id
        JOIN meta.instance AS instance ON instance.instance_id = boot.instance_id
        JOIN pg_catalog.pg_locks AS control_lock
          ON control_lock.pid = boot.control_backend_pid
         AND control_lock.locktype = 'advisory'
         AND control_lock.database = (
             SELECT database_oid.oid
             FROM pg_catalog.pg_database AS database_oid
             WHERE database_oid.datname = current_database()
         )
         AND control_lock.classid::bigint
             = ((instance.advisory_lock_key >> 32) & 4294967295::bigint)
         AND control_lock.objid::bigint
             = (instance.advisory_lock_key & 4294967295::bigint)
         AND control_lock.objsubid = 1
         AND control_lock.mode = 'ExclusiveLock'
         AND control_lock.granted
        WHERE lease.process_uid = p_process_uid
          AND lease.owner_id = p_owner_id
          AND lease.runner_id = p_runner_id
          AND lease.boot_id = p_boot_id
          AND lease.execution_epoch = p_execution_epoch
          AND lease.expires_at > clock_timestamp()
          AND runner.status = 'ACTIVE'
          AND boot.status IN ('RECOVERING', 'ACTIVE')
          AND runtime.status IN ('STARTING', 'ACTIVE')
          AND instance.status IN ('INITIALIZING', 'ACTIVE')
          AND boot.control_proof_lock_key IS DISTINCT FROM instance.advisory_lock_key
          AND EXISTS (
              SELECT 1
              FROM pg_catalog.pg_locks AS proof_lock
              WHERE proof_lock.pid = boot.control_backend_pid
                AND proof_lock.locktype = 'advisory'
                AND proof_lock.database = control_lock.database
                AND proof_lock.classid::bigint
                    = ((boot.control_proof_lock_key >> 32) & 4294967295::bigint)
                AND proof_lock.objid::bigint
                    = (boot.control_proof_lock_key & 4294967295::bigint)
                AND proof_lock.objsubid = 1
                AND proof_lock.mode = 'ExclusiveLock'
                AND proof_lock.granted
          )
    );
END
$function$;

-- name: scheduler.claim_authorizes_commit
CREATE FUNCTION scheduler.claim_authorizes_commit(
    p_process_uid uuid,
    p_owner_id uuid,
    p_runner_id uuid,
    p_boot_id uuid,
    p_execution_epoch bigint
)
RETURNS boolean
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, scheduler
AS $function$
    SELECT scheduler.claim_authorizes_commit_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_process_uid,
        p_owner_id,
        p_runner_id,
        p_boot_id,
        p_execution_epoch
    )
$function$;

REVOKE ALL ON FUNCTION scheduler.claim_authorizes_commit_as(
    name, text, uuid, uuid, uuid, uuid, bigint
) FROM PUBLIC;
REVOKE ALL ON FUNCTION scheduler.claim_authorizes_commit(
    uuid, uuid, uuid, uuid, bigint
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION scheduler.claim_authorizes_commit_as(
    name, text, uuid, uuid, uuid, uuid, bigint
) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION scheduler.claim_authorizes_commit(
    uuid, uuid, uuid, uuid, bigint
) TO cilexec_runtime;

-- Existing and future per-user LOGIN roles receive only the identity-bound
-- wrapper. They never receive visibility into scheduler.lease or pg_locks.
-- name: auth.grant_claim_commit_guard
CREATE FUNCTION auth.grant_claim_commit_guard(p_user_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
DECLARE
    mapped_role name;
BEGIN
    SELECT postgres_role_name INTO STRICT mapped_role
    FROM auth.user_account
    WHERE user_id = p_user_id AND status = 'ACTIVE';
    IF mapped_role::text <> 'cilexec_user_' || replace(p_user_id::text, '-', '')
       OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = mapped_role) THEN
        RAISE EXCEPTION 'validated LOGIN role is missing for user %', p_user_id;
    END IF;
    EXECUTE format(
        'GRANT EXECUTE ON FUNCTION scheduler.claim_authorizes_commit(uuid, uuid, uuid, uuid, bigint) TO %I',
        mapped_role
    );
    -- The invoker wrapper captures current_user and the identity GUC before
    -- calling the identity-bound definer. PostgreSQL still checks EXECUTE on
    -- the inner function for an invoker wrapper, so principals need this
    -- narrow grant as well; claim_authorizes_commit_as rejects forged roles,
    -- users and owners against the actual SET ROLE identity.
    EXECUTE format(
        'GRANT EXECUTE ON FUNCTION scheduler.claim_authorizes_commit_as(name, text, uuid, uuid, uuid, uuid, bigint) TO %I',
        mapped_role
    );
END
$function$;

REVOKE ALL ON FUNCTION auth.grant_claim_commit_guard(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.grant_claim_commit_guard(uuid) TO cilexec_migrator;

DO $existing_principals$
DECLARE
    principal uuid;
BEGIN
    FOR principal IN
        SELECT user_id FROM auth.user_account WHERE status = 'ACTIVE'
    LOOP
        IF EXISTS (
            SELECT 1 FROM pg_catalog.pg_roles
            WHERE rolname = 'cilexec_user_' || replace(principal::text, '-', '')
        ) THEN
            PERFORM auth.grant_claim_commit_guard(principal);
        END IF;
    END LOOP;
END
$existing_principals$;

RESET ROLE;

-- Preserve the existing principal provisioning flow and append the new,
-- narrowly scoped execution grant for roles created after this migration.
-- name: auth.provision_principal
CREATE OR REPLACE FUNCTION auth.provision_principal(p_user_id uuid, p_password text)
RETURNS name
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
DECLARE
    mapped_role name;
BEGIN
    mapped_role := auth.provision_login_role(p_user_id, p_password);
    PERFORM auth.grant_login_role_access(p_user_id);
    PERFORM auth.grant_claim_commit_guard(p_user_id);
    RETURN mapped_role;
END
$function$;

REVOKE ALL ON FUNCTION auth.provision_principal(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.provision_principal(uuid, text) TO cilexec_runtime;

SET ROLE cilexec_owner;
COMMENT ON FUNCTION scheduler.claim_authorizes_commit(
    uuid, uuid, uuid, uuid, bigint
) IS 'Final same-transaction guard for active control lock, boot, runner, epoch, and unexpired lease';
RESET ROLE;
