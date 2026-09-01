-- CilExec V004 is a validation-only version fence. Do not add mutable repair behavior here.
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
