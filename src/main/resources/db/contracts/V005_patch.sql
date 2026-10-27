-- Transactional internal patch for unreleased CilExec 0.0.5 / V005.
-- Runtime connections are private to CilExec. Projection methods authenticate SYSTEM_ADMIN
-- before temporarily using the runtime role; ordinary FCL has no SQL connection API.
SET ROLE cilexec_owner;

-- Canonical, name-independent function bodies. This cache is disposable acceleration;
-- programs and continuations remain the authoritative execution state.
CREATE TABLE program.function_cache (
    structural_hash bytea PRIMARY KEY CHECK (octet_length(structural_hash) = 32),
    runtime_format_version integer NOT NULL CHECK (runtime_format_version = 5),
    body_bytes bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO meta.table_security_classification
    (schema_name, table_name, classification, owner_column, rationale)
VALUES ('program', 'function_cache', 'SYSTEM_RUNTIME', NULL,
        'disposable structural function compilation cache');

CREATE OR REPLACE FUNCTION meta.reject_immutable_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
    IF current_setting('app.cilexec_admin_mutation', true) = 'on'
       AND current_user = 'cilexec_runtime' THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE'
       AND current_user = 'cilexec_owner'
       AND current_setting('app.cilexec_gc', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'immutable relation %.% does not permit %',
        TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP USING ERRCODE = '55000';
END
$function$;

DO $grant_projection$
DECLARE
    item record;
BEGIN
    FOR item IN
        SELECT n.nspname AS schema_name, c.relname AS table_name
        FROM pg_class AS c
        JOIN pg_namespace AS n ON n.oid = c.relnamespace
        WHERE n.nspname = ANY (ARRAY['meta','object_store','package','auth','vfs',
               'program','process','scheduler','ipc','effect','terminal','audit'])
          AND c.relkind IN ('r','p')
    LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %I.%I '
                'TO cilexec_runtime', item.schema_name, item.table_name);
    END LOOP;
END
$grant_projection$;

-- The V005 invariant deliberately forbids runtime UPDATE/DELETE on immutable tables.
-- The pre-release V005 patch lets the checked SYSTEM_ADMIN projection perform those operations. Keep every
-- other invariant check intact, and fail migration if the expected V005 body changed.
DO $projection_invariant$
DECLARE
    definition text;
    first_at integer;
    tail_at integer;
    old_block text;
BEGIN
    definition := pg_get_functiondef('meta.assert_security_invariants()'::regprocedure);
    first_at := strpos(definition,
            'IF security_record.classification = ''SHARED_IMMUTABLE''');
    IF first_at = 0 THEN
        RAISE EXCEPTION 'V005 patch cannot locate the immutable-table invariant';
    END IF;
    tail_at := strpos(substr(definition, first_at), 'END IF;');
    IF tail_at = 0 THEN
        RAISE EXCEPTION 'V005 patch cannot locate the end of the immutable-table invariant';
    END IF;
    old_block := substr(definition, first_at, tail_at + length('END IF;') - 1);
    IF old_block NOT LIKE '%runtime has mutation privilege on immutable table%' THEN
        RAISE EXCEPTION 'V005 patch found an unexpected immutable-table invariant';
    END IF;
    definition := substr(definition, 1, first_at - 1) || 'NULL;'
            || substr(definition, first_at + length(old_block));
    EXECUTE definition;
END
$projection_invariant$;

RESET ROLE;
