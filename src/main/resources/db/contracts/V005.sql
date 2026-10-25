-- CilExec V005 / 0.0.5: object-runtime format fence.
--
-- 0.0.5 starts the FCL object runtime: entity functions are replaced by object methods and
-- system handles, and the FCLB/continuation format identity becomes 5. Handles are runtime-only
-- values carried inside committed FCL continuations whose runtime format version already has
-- dedicated columns, so the object-runtime upgrade adds no table, row, column, function, or
-- privilege. V005 is therefore a pure forward fence: it validates the complete V004 database
-- contract and then lets Flyway record schema version 5 as the 0.0.5 identity. A database created
-- through V001..V005 is exactly what a 0.0.5 Runtime accepts; older data are refused before any
-- migration runs instead of being upgraded in place.

DO $migration$
DECLARE
    wait_constraint text;
BEGIN
    IF to_regprocedure('auth.disable_principal(uuid)') IS NULL THEN
        RAISE EXCEPTION 'V005 requires the V004 auth.disable_principal(uuid) contract';
    END IF;
    IF to_regprocedure('package.data_list_as(name,text,bytea,text)') IS NULL THEN
        RAISE EXCEPTION 'V005 requires the V004 package.data_list_as contract';
    END IF;
    IF to_regprocedure('program.admin_remove_program_as(name,text,uuid,uuid,uuid,timestamp with time zone)') IS NULL THEN
        RAISE EXCEPTION 'V005 requires the V004 program removal contract';
    END IF;
    IF to_regprocedure('audit.admin_purge_before_as(name,text,uuid,timestamp with time zone,integer)') IS NULL THEN
        RAISE EXCEPTION 'V005 requires the V004 audit purge contract';
    END IF;
    IF to_regclass('audit.retention_policy') IS NOT NULL THEN
        RAISE EXCEPTION 'V005 requires explicit audit retention; automatic retention still exists';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_attribute
        WHERE attrelid = 'program.program'::regclass
          AND attname = 'compiled_object_hash'
          AND NOT attisdropped
    ) THEN
        RAISE EXCEPTION 'V005 requires program.program.compiled_object_hash';
    END IF;
    IF to_regprocedure('auth.grant_login_role_access(uuid)') IS NULL THEN
        RAISE EXCEPTION 'V005 requires the V004 auth.grant_login_role_access(uuid) wrapper';
    END IF;
    IF NOT has_function_privilege('cilexec_migrator',
            'auth.grant_login_role_access(uuid)', 'EXECUTE') THEN
        RAISE EXCEPTION 'V005 requires the V004 login-role provisioning privilege';
    END IF;
    IF NOT has_table_privilege('cilexec_runtime', 'process.package_binding', 'DELETE') THEN
        RAISE EXCEPTION 'V005 requires the V004 process.package_binding privilege completion';
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
        RAISE EXCEPTION 'V005 requires the complete V003 process wait-kind contract';
    END IF;
END
$migration$;

SELECT meta.assert_security_invariants();
