-- Component: suspend terminal processes
-- ============================================================================
SET ROLE cilexec_owner;

-- Older runtimes ended one process for every REPL submission. The newest active
-- attachment becomes the user's permanent suspended terminal process during upgrade.
UPDATE process.process AS process
SET status = 'PAUSED',
    state_version = process.state_version + 1,
    updated_at = clock_timestamp(),
    terminated_at = NULL,
    exit_code = NULL,
    failure_code = NULL,
    failure_message = NULL
FROM terminal.attachment AS attachment
WHERE attachment.process_uid = process.process_uid
  AND attachment.owner_id = process.owner_id
  AND attachment.status = 'ATTACHED'
  AND process.status IN ('TERMINATED', 'FAILED', 'FAILED_RECOVERY');

RESET ROLE;

-- ============================================================================
-- Component: terminal command history
-- ============================================================================
SET ROLE cilexec_owner;

-- Arrow-key history is user-owned durable state. It is intentionally separate from
-- terminal.input, whose rows may be consumed by a process waiting on io.input().
CREATE TABLE terminal.command_history (
    history_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE CASCADE,
    command_text text NOT NULL CHECK (btrim(command_text) <> ''),
    submitted_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_terminal_command_history_owner
    ON terminal.command_history(owner_id, history_id DESC);

ALTER TABLE terminal.command_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE terminal.command_history FORCE ROW LEVEL SECURITY;

CREATE POLICY command_history_owner_control ON terminal.command_history
    TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY command_history_runtime_control ON terminal.command_history
    TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY command_history_readonly_control ON terminal.command_history
    FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY command_history_principal ON terminal.command_history
    TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id())
    WITH CHECK (owner_id = auth.current_cilexec_user_id());

GRANT SELECT, INSERT, DELETE ON terminal.command_history TO cilexec_runtime;
GRANT USAGE, SELECT ON SEQUENCE terminal.command_history_history_id_seq TO cilexec_runtime;
GRANT SELECT ON terminal.command_history TO cilexec_readonly;

-- Existing and future per-user LOGIN roles use these ACLs through PUBLIC. RLS still
-- binds every visible or writable row to auth.current_cilexec_user_id().
GRANT SELECT, INSERT, DELETE ON terminal.command_history TO PUBLIC;
GRANT USAGE, SELECT ON SEQUENCE terminal.command_history_history_id_seq TO PUBLIC;

INSERT INTO meta.table_security_classification
    (schema_name, table_name, classification, owner_column, rationale)
VALUES ('terminal', 'command_history', 'USER_SCOPED', 'owner_id',
        'durable per-user arrow-key command history');

COMMENT ON TABLE terminal.command_history IS
    'Complete REPL and colon commands only; never passwords or attached process input';

SELECT meta.assert_security_invariants();

RESET ROLE;

-- ============================================================================
-- Component: bounded chunk reads
-- ============================================================================
SET ROLE cilexec_owner;
-- Keep FCL range reads bounded to one download-sized chunk without changing the baseline after release.
CREATE OR REPLACE FUNCTION object_store.read_object_range_as(
    p_database_role name,
    p_claim text,
    p_object_hash bytea,
    p_offset bigint,
    p_maximum integer
)
RETURNS bytea
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store
AS $function$
DECLARE
    result bytea;
BEGIN
    IF p_offset < 0 OR p_maximum < 0 OR p_maximum > 4194304 THEN
        RAISE EXCEPTION 'invalid bounded object range' USING ERRCODE = '22023';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM object_store.read_object_as(
            p_database_role, p_claim, p_object_hash)) THEN
        RETURN NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM object_store.chunk_manifest
                   WHERE manifest_hash = p_object_hash) THEN
        IF p_offset > 2147483646 THEN
            RETURN ''::bytea;
        END IF;
        SELECT substring(content FROM (p_offset + 1)::integer FOR p_maximum)
        INTO result FROM object_store.object WHERE object_hash = p_object_hash;
        RETURN COALESCE(result, ''::bytea);
    END IF;

    WITH RECURSIVE chain AS (
        SELECT manifest_hash, previous_manifest_hash, base_object_hash,
               tail_object_hash, total_size, tail_size
        FROM object_store.chunk_manifest WHERE manifest_hash = p_object_hash
        UNION ALL
        SELECT parent.manifest_hash, parent.previous_manifest_hash, parent.base_object_hash,
               parent.tail_object_hash, parent.total_size, parent.tail_size
        FROM object_store.chunk_manifest AS parent
        JOIN chain AS child ON parent.manifest_hash = child.previous_manifest_hash
    ), parts AS (
        SELECT base_object_hash AS part_hash, 0::bigint AS part_offset
        FROM chain WHERE base_object_hash IS NOT NULL
        UNION ALL
        SELECT tail_object_hash, total_size - tail_size
        FROM chain
    ), overlapping AS (
        SELECT part.part_offset, stored.content,
               GREATEST(p_offset - part.part_offset, 0)::integer AS local_offset,
               LEAST(stored.byte_size - GREATEST(p_offset - part.part_offset, 0),
                     p_offset + p_maximum - GREATEST(part.part_offset, p_offset))::integer AS take
        FROM parts AS part
        JOIN object_store.object AS stored ON stored.object_hash = part.part_hash
        WHERE part.part_offset < p_offset + p_maximum
          AND part.part_offset + stored.byte_size > p_offset
    )
    SELECT string_agg(substring(content FROM local_offset + 1 FOR take), ''::bytea
                      ORDER BY part_offset)
    INTO result FROM overlapping WHERE take > 0;
    RETURN COALESCE(result, ''::bytea);
END
$function$;

SELECT meta.assert_security_invariants();

-- ============================================================================
-- Component: terminal export capture
-- ============================================================================
SET ROLE cilexec_owner;

-- Export capture is durable across disconnects and Runtime restarts, but is deleted after export.
CREATE TABLE terminal.export_capture (
    capture_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL UNIQUE REFERENCES auth.user_account(user_id) ON DELETE CASCADE,
    target_path text NOT NULL CHECK (left(target_path, 1) = '/'),
    status text NOT NULL CHECK (status IN ('CAPTURING', 'FINALIZING')),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (capture_id, owner_id)
);

CREATE TABLE terminal.export_capture_operation (
    operation_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    capture_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    operation_text text NOT NULL CHECK (btrim(operation_text) <> ''),
    submitted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (capture_id, owner_id)
        REFERENCES terminal.export_capture(capture_id, owner_id) ON DELETE CASCADE
);

CREATE INDEX ix_terminal_export_capture_operation
    ON terminal.export_capture_operation(capture_id, operation_id);

ALTER TABLE terminal.export_capture ENABLE ROW LEVEL SECURITY;
ALTER TABLE terminal.export_capture FORCE ROW LEVEL SECURITY;
ALTER TABLE terminal.export_capture_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE terminal.export_capture_operation FORCE ROW LEVEL SECURITY;

DO $rls$
DECLARE
    relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY['export_capture', 'export_capture_operation']
    LOOP
        EXECUTE format('CREATE POLICY %I ON terminal.%I TO cilexec_owner USING (true) WITH CHECK (true)',
            relation_name || '_owner_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON terminal.%I TO cilexec_runtime USING (true) WITH CHECK (true)',
            relation_name || '_runtime_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON terminal.%I FOR SELECT TO cilexec_readonly USING (true)',
            relation_name || '_readonly_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON terminal.%I TO PUBLIC USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id())',
            relation_name || '_principal', relation_name);
    END LOOP;
END
$rls$;

GRANT SELECT, INSERT, UPDATE, DELETE ON terminal.export_capture TO cilexec_runtime, PUBLIC;
GRANT SELECT, INSERT, DELETE ON terminal.export_capture_operation TO cilexec_runtime, PUBLIC;
GRANT USAGE, SELECT ON SEQUENCE terminal.export_capture_operation_operation_id_seq
    TO cilexec_runtime, PUBLIC;
GRANT SELECT ON terminal.export_capture, terminal.export_capture_operation TO cilexec_readonly;

INSERT INTO meta.table_security_classification
    (schema_name, table_name, classification, owner_column, rationale)
VALUES
    ('terminal', 'export_capture', 'USER_SCOPED', 'owner_id',
     'temporary durable per-user script export capture'),
    ('terminal', 'export_capture_operation', 'USER_SCOPED', 'owner_id',
     'operations retained only while one script export capture is active');

COMMENT ON TABLE terminal.export_capture IS
    'One durable temporary export capture per user; deleted after successful exp-end';
COMMENT ON TABLE terminal.export_capture_operation IS
    'FCL and colon operations between exp-start and exp-end only';

SELECT meta.assert_security_invariants();

RESET ROLE;
