SET ROLE cilexec_owner;

-- PID sequence values are intentionally not transactional and are never reused.
-- name: migration.V006.create_pid_sequence
CREATE SEQUENCE process.pid_sequence
    AS bigint
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    NO MAXVALUE
    NO CYCLE;

-- name: migration.V006.create_process
CREATE TABLE process.process (
    process_uid uuid PRIMARY KEY,
    pid bigint NOT NULL DEFAULT nextval('process.pid_sequence') UNIQUE CHECK (pid > 0),
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    program_id uuid NOT NULL,
    parent_process_uid uuid,
    status text NOT NULL CHECK (status IN (
        'CREATED', 'READY', 'RUNNING', 'PAUSED', 'WAITING_IPC', 'WAITING_TIMER',
        'WAITING_EFFECT', 'WAITING_INPUT', 'TERMINATING', 'TERMINATED',
        'FAILED', 'FAILED_RECOVERY'
    )),
    program_counter integer NOT NULL DEFAULT 0 CHECK (program_counter >= 0),
    state_version bigint NOT NULL DEFAULT 0 CHECK (state_version >= 0),
    execution_epoch bigint NOT NULL DEFAULT 0 CHECK (execution_epoch >= 0),
    interrupt_requested boolean NOT NULL DEFAULT false,
    wait_reason text,
    wait_object_id uuid,
    runtime_format_version integer NOT NULL CHECK (runtime_format_version > 0),
    language_version text NOT NULL CHECK (btrim(language_version) <> ''),
    continuation_json jsonb NOT NULL,
    last_boot_id uuid REFERENCES meta.boot(boot_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    terminated_at timestamptz,
    exit_code integer,
    failure_code text,
    failure_message text,
    UNIQUE (process_uid, owner_id),
    FOREIGN KEY (program_id, owner_id) REFERENCES program.program(program_id, owner_id) ON DELETE RESTRICT,
    FOREIGN KEY (parent_process_uid, owner_id)
        REFERENCES process.process(process_uid, owner_id) ON DELETE SET NULL (parent_process_uid),
    CHECK ((status IN ('TERMINATED', 'FAILED', 'FAILED_RECOVERY') AND terminated_at IS NOT NULL)
        OR status NOT IN ('TERMINATED', 'FAILED', 'FAILED_RECOVERY'))
);

-- name: migration.V006.create_call_frame
CREATE TABLE process.call_frame (
    frame_id uuid PRIMARY KEY,
    process_uid uuid NOT NULL,
    owner_id uuid NOT NULL,
    frame_depth integer NOT NULL CHECK (frame_depth >= 0),
    function_name text NOT NULL,
    return_program_counter integer CHECK (return_program_counter >= 0),
    frame_state jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (process_uid, frame_depth),
    UNIQUE (frame_id, process_uid, owner_id),
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE
);

-- name: migration.V006.create_scope
CREATE TABLE process.scope (
    scope_id uuid PRIMARY KEY,
    process_uid uuid NOT NULL,
    owner_id uuid NOT NULL,
    frame_id uuid NOT NULL,
    parent_scope_id uuid,
    scope_depth integer NOT NULL CHECK (scope_depth >= 0),
    scope_kind text NOT NULL CHECK (scope_kind IN ('GLOBAL', 'FUNCTION', 'BLOCK', 'LOOP', 'CATCH')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (scope_id, process_uid, owner_id),
    UNIQUE (process_uid, frame_id, scope_depth),
    FOREIGN KEY (frame_id, process_uid, owner_id)
        REFERENCES process.call_frame(frame_id, process_uid, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (parent_scope_id, process_uid, owner_id)
        REFERENCES process.scope(scope_id, process_uid, owner_id) ON DELETE CASCADE
);

-- name: migration.V006.create_variable
CREATE TABLE process.variable (
    variable_id uuid PRIMARY KEY,
    process_uid uuid NOT NULL,
    owner_id uuid NOT NULL,
    scope_id uuid NOT NULL,
    variable_name text NOT NULL CHECK (btrim(variable_name) <> ''),
    value_type text NOT NULL CHECK (btrim(value_type) <> ''),
    value_json jsonb,
    value_object_hash bytea REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (process_uid, scope_id, variable_name),
    FOREIGN KEY (scope_id, process_uid, owner_id)
        REFERENCES process.scope(scope_id, process_uid, owner_id) ON DELETE CASCADE,
    CHECK ((value_type = 'null' AND value_json IS NULL AND value_object_hash IS NULL)
        OR (value_type <> 'null' AND num_nonnulls(value_json, value_object_hash) = 1))
);

-- name: migration.V006.create_exception_frame
CREATE TABLE process.exception_frame (
    exception_frame_id uuid PRIMARY KEY,
    process_uid uuid NOT NULL,
    owner_id uuid NOT NULL,
    frame_depth integer NOT NULL CHECK (frame_depth >= 0),
    handler_program_counter integer NOT NULL CHECK (handler_program_counter >= 0),
    finally_program_counter integer CHECK (finally_program_counter >= 0),
    exception_type text,
    state_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (process_uid, frame_depth),
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE
);

-- name: migration.V006.create_wait_state
CREATE TABLE process.wait_state (
    process_uid uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    wait_kind text NOT NULL CHECK (wait_kind IN ('IPC', 'TIMER', 'EFFECT', 'INPUT', 'CHILD', 'PROCESS')),
    wait_object_id uuid,
    wait_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    entered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE
);

-- name: migration.V006.create_relationship
CREATE TABLE process.relationship (
    process_uid uuid NOT NULL,
    owner_id uuid NOT NULL,
    related_process_uid uuid NOT NULL,
    relationship_type text NOT NULL CHECK (relationship_type IN ('PARENT', 'CHILD', 'WAITS_FOR', 'ATTACHED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (process_uid, related_process_uid, relationship_type),
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (related_process_uid, owner_id)
        REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    CHECK (process_uid <> related_process_uid)
);

-- name: migration.V006.create_process_event
CREATE TABLE process.event (
    event_id uuid PRIMARY KEY,
    process_uid uuid NOT NULL,
    owner_id uuid NOT NULL,
    event_type text NOT NULL CHECK (btrim(event_type) <> ''),
    state_version bigint NOT NULL CHECK (state_version >= 0),
    details_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE
);

-- name: migration.V006.process_indexes
CREATE INDEX ix_process_owner_status ON process.process(owner_id, status, updated_at);
CREATE INDEX ix_process_parent ON process.process(parent_process_uid) WHERE parent_process_uid IS NOT NULL;
CREATE INDEX ix_process_waiting ON process.process(status, wait_reason) WHERE status LIKE 'WAITING_%';
CREATE INDEX ix_variable_process_scope ON process.variable(process_uid, scope_id);
CREATE INDEX ix_process_event_history ON process.event(process_uid, created_at DESC);

-- name: migration.V006.event_immutability
CREATE TRIGGER process_event_reject_update_delete
BEFORE UPDATE OR DELETE ON process.event
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();

-- name: migration.V006.process_rls
DO $rls$
DECLARE
    relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY[
        'process', 'call_frame', 'scope', 'variable', 'exception_frame',
        'wait_state', 'relationship', 'event'
    ]
    LOOP
        EXECUTE format('ALTER TABLE process.%I ENABLE ROW LEVEL SECURITY', relation_name);
        EXECUTE format('ALTER TABLE process.%I FORCE ROW LEVEL SECURITY', relation_name);
        EXECUTE format(
            'CREATE POLICY %I ON process.%I TO cilexec_owner USING (true) WITH CHECK (true)',
            relation_name || '_owner_control', relation_name
        );
        EXECUTE format(
            'CREATE POLICY %I ON process.%I TO cilexec_runtime USING (true) WITH CHECK (true)',
            relation_name || '_runtime_control', relation_name
        );
        EXECUTE format(
            'CREATE POLICY %I ON process.%I FOR SELECT TO cilexec_readonly USING (true)',
            relation_name || '_readonly_control', relation_name
        );
        EXECUTE format(
            'CREATE POLICY %I ON process.%I TO PUBLIC USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id())',
            relation_name || '_principal', relation_name
        );
    END LOOP;
END
$rls$;

-- name: migration.V006.process_grants
GRANT USAGE, SELECT ON SEQUENCE process.pid_sequence TO cilexec_runtime;
GRANT SELECT, INSERT, UPDATE ON process.process, process.call_frame, process.scope,
    process.variable, process.exception_frame, process.wait_state, process.relationship TO cilexec_runtime;
GRANT DELETE ON process.call_frame, process.scope, process.variable, process.exception_frame,
    process.wait_state, process.relationship TO cilexec_runtime;
GRANT SELECT, INSERT ON process.event TO cilexec_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA process TO cilexec_readonly;

COMMENT ON SEQUENCE process.pid_sequence IS 'Monotonic, non-cycling user-visible PID allocator; values are never reused';
COMMENT ON TABLE process.process IS 'Authoritative process identity, status, program counter, and fencing versions';

RESET ROLE;
