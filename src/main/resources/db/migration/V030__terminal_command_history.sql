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
