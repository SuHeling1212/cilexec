SET ROLE cilexec_owner;

-- name: migration.V013.create_terminal_session
CREATE TABLE terminal.session (
    session_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    status text NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    terminal_type text NOT NULL DEFAULT 'HOST' CHECK (terminal_type IN ('HOST', 'API')),
    next_input_sequence bigint NOT NULL DEFAULT 1 CHECK (next_input_sequence > 0),
    opened_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_activity_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    closed_at timestamptz,
    UNIQUE (session_id, owner_id),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

-- name: migration.V013.create_terminal_input
CREATE TABLE terminal.input (
    input_id uuid PRIMARY KEY,
    session_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    input_sequence bigint NOT NULL CHECK (input_sequence > 0),
    submitted_text text NOT NULL,
    submitted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    accepted_at timestamptz,
    target_process_uid uuid,
    UNIQUE (session_id, input_sequence),
    FOREIGN KEY (session_id, owner_id) REFERENCES terminal.session(session_id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (target_process_uid, owner_id)
        REFERENCES process.process(process_uid, owner_id) ON DELETE RESTRICT,
    CHECK ((accepted_at IS NOT NULL) = (target_process_uid IS NOT NULL)),
    CHECK (accepted_at IS NULL OR accepted_at >= submitted_at)
);

-- name: migration.V013.create_terminal_attachment
CREATE TABLE terminal.attachment (
    attachment_id uuid PRIMARY KEY,
    session_id uuid NOT NULL,
    process_uid uuid NOT NULL,
    owner_id uuid NOT NULL,
    status text NOT NULL CHECK (status IN ('ATTACHED', 'DETACHED')),
    attached_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    detached_at timestamptz,
    UNIQUE (session_id, process_uid),
    FOREIGN KEY (session_id, owner_id) REFERENCES terminal.session(session_id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    CHECK ((status = 'DETACHED') = (detached_at IS NOT NULL))
);

-- name: migration.V013.terminal_indexes
CREATE INDEX ix_terminal_input_pending ON terminal.input(session_id, input_sequence) WHERE accepted_at IS NULL;
CREATE INDEX ix_terminal_attachment_process ON terminal.attachment(process_uid) WHERE status = 'ATTACHED';

-- name: migration.V013.terminal_rls
DO $rls$
DECLARE
    relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY['session', 'input', 'attachment']
    LOOP
        EXECUTE format('ALTER TABLE terminal.%I ENABLE ROW LEVEL SECURITY', relation_name);
        EXECUTE format('ALTER TABLE terminal.%I FORCE ROW LEVEL SECURITY', relation_name);
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

-- name: migration.V013.terminal_grants
GRANT SELECT, INSERT, UPDATE ON terminal.session, terminal.input, terminal.attachment TO cilexec_runtime;
GRANT SELECT ON terminal.session, terminal.input, terminal.attachment TO cilexec_readonly;

COMMENT ON TABLE terminal.input IS 'One row per fully submitted input; individual keystrokes are never persisted';

RESET ROLE;
