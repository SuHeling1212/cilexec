-- CilExec V004 remains unreleased. Besides validating the V003 contract, it completes the
-- owner-scoped package-binding privilege needed by process.exec to discard the old image pins.
SET ROLE cilexec_owner;

GRANT DELETE ON process.package_binding TO cilexec_runtime;

DO $grant_existing_users$
DECLARE
    account record;
BEGIN
    FOR account IN
        SELECT user_id, postgres_role_name
        FROM auth.user_account
        WHERE status = 'ACTIVE'
    LOOP
        IF account.postgres_role_name::text = 'cilexec_user_'
                || replace(account.user_id::text, '-', '')
           AND EXISTS (SELECT 1 FROM pg_catalog.pg_roles
                       WHERE rolname = account.postgres_role_name) THEN
            EXECUTE format('GRANT DELETE ON process.package_binding TO %I',
                    account.postgres_role_name);
        END IF;
    END LOOP;
END
$grant_existing_users$;

ALTER FUNCTION auth.grant_login_role_access(uuid)
    RENAME TO grant_login_role_access_v003;

CREATE FUNCTION auth.grant_login_role_access(p_user_id uuid)
RETURNS name
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
DECLARE
    mapped_role name;
BEGIN
    mapped_role := auth.grant_login_role_access_v003(p_user_id);
    EXECUTE format('GRANT DELETE ON process.package_binding TO %I', mapped_role);
    RETURN mapped_role;
END
$function$;

REVOKE ALL ON FUNCTION auth.grant_login_role_access(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.grant_login_role_access(uuid) TO cilexec_migrator;

RESET ROLE;

DO $migration$
DECLARE
    wait_constraint text;
BEGIN
    IF to_regprocedure('auth.disable_principal(uuid)') IS NULL THEN
        RAISE EXCEPTION 'V004 requires the V003 auth.disable_principal(uuid) contract';
    END IF;
    IF to_regprocedure('package.data_list_as(name,text,bytea,text)') IS NULL THEN
        RAISE EXCEPTION 'V004 requires the V003 package.data_list_as contract';
    END IF;
    IF to_regprocedure('program.admin_remove_program_as(name,text,uuid,uuid,uuid,timestamp with time zone)') IS NULL THEN
        RAISE EXCEPTION 'V004 requires the V003 program removal contract';
    END IF;
    IF to_regprocedure('audit.admin_purge_before_as(name,text,uuid,timestamp with time zone,integer)') IS NULL THEN
        RAISE EXCEPTION 'V004 requires the V003 audit purge contract';
    END IF;
    IF to_regclass('audit.retention_policy') IS NOT NULL THEN
        RAISE EXCEPTION 'V004 requires explicit audit retention; automatic retention still exists';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_attribute
        WHERE attrelid = 'program.program'::regclass
          AND attname = 'compiled_object_hash'
          AND NOT attisdropped
    ) THEN
        RAISE EXCEPTION 'V004 requires program.program.compiled_object_hash';
    END IF;

    SELECT pg_catalog.pg_get_constraintdef(oid)
    INTO wait_constraint
    FROM pg_catalog.pg_constraint
    WHERE conrelid = 'process.wait_state'::regclass
      AND conname = 'wait_state_wait_kind_check';
    IF wait_constraint IS NULL
       OR position('VOLATILE' IN wait_constraint) = 0
       OR position('INPUT' IN wait_constraint) = 0
       OR position('CHILD' IN wait_constraint) = 0
       OR position('PROCESS' IN wait_constraint) = 0 THEN
        RAISE EXCEPTION 'V004 requires the complete V003 process wait-kind contract';
    END IF;
END
$migration$;

SELECT meta.assert_security_invariants();
