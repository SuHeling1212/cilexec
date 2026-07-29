-- ============================================================================
-- Component: object store
-- ============================================================================
SET ROLE cilexec_owner;

-- name: meta.reject_immutable_mutation
CREATE FUNCTION meta.reject_immutable_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
    RAISE EXCEPTION 'immutable relation %.% does not permit %', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END
$function$;

-- name: baseline.create_object
CREATE TABLE object_store.object (
    object_hash bytea PRIMARY KEY CHECK (octet_length(object_hash) = 32),
    byte_size bigint NOT NULL CHECK (byte_size >= 0),
    media_type text NOT NULL CHECK (btrim(media_type) <> ''),
    content bytea NOT NULL,
    created_by uuid REFERENCES auth.user_account(user_id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (byte_size = octet_length(content)),
    CHECK (object_hash = pg_catalog.sha256(content))
);

-- name: baseline.object_immutability
CREATE TRIGGER object_reject_update_delete
BEFORE UPDATE OR DELETE ON object_store.object
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();

-- This is a shared system relation. Raw content is never granted to user LOGIN roles.
-- name: baseline.object_grants
GRANT SELECT, INSERT ON object_store.object TO cilexec_runtime;
GRANT SELECT (object_hash, byte_size, media_type, created_by, created_at)
    ON object_store.object TO cilexec_readonly;

COMMENT ON TABLE object_store.object IS
    'Immutable SHA-256 addressed bytes shared by VFS, programs, payloads, and SQLite packages';

RESET ROLE;

-- ============================================================================
-- Component: program
-- ============================================================================
SET ROLE cilexec_owner;

-- name: baseline.create_program
CREATE TABLE program.program (
    program_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    program_hash bytea NOT NULL CHECK (octet_length(program_hash) = 32),
    language_version text NOT NULL CHECK (btrim(language_version) <> ''),
    runtime_format_version integer NOT NULL CHECK (runtime_format_version > 0),
    source_object_hash bytea NOT NULL REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    compiled_object_hash bytea REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    statement_count integer NOT NULL CHECK (statement_count >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (program_id, owner_id),
    UNIQUE (owner_id, program_hash, language_version, runtime_format_version)
);

-- name: baseline.create_statement
CREATE TABLE program.statement (
    program_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    statement_index integer NOT NULL CHECK (statement_index >= 0),
    statement_kind text NOT NULL CHECK (btrim(statement_kind) <> ''),
    source_text text NOT NULL,
    compiled_json jsonb,
    source_line integer NOT NULL CHECK (source_line > 0),
    source_column integer NOT NULL CHECK (source_column > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (program_id, statement_index),
    FOREIGN KEY (program_id, owner_id) REFERENCES program.program(program_id, owner_id) ON DELETE RESTRICT
);

-- name: baseline.create_module_binding
CREATE TABLE program.module_binding (
    program_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    import_name text NOT NULL CHECK (btrim(import_name) <> ''),
    module_name text NOT NULL CHECK (btrim(module_name) <> ''),
    module_program_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (program_id, import_name),
    FOREIGN KEY (program_id, owner_id) REFERENCES program.program(program_id, owner_id) ON DELETE RESTRICT,
    FOREIGN KEY (module_program_id, owner_id) REFERENCES program.program(program_id, owner_id) ON DELETE RESTRICT
);

-- name: baseline.program_indexes
CREATE INDEX ix_program_owner_created ON program.program(owner_id, created_at DESC);
CREATE INDEX ix_module_binding_target ON program.module_binding(module_program_id);

-- name: baseline.program_immutability
CREATE TRIGGER program_reject_update_delete
BEFORE UPDATE OR DELETE ON program.program
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();
CREATE TRIGGER statement_reject_update_delete
BEFORE UPDATE OR DELETE ON program.statement
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();
CREATE TRIGGER module_binding_reject_update_delete
BEFORE UPDATE OR DELETE ON program.module_binding
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();

-- name: baseline.program_rls
ALTER TABLE program.program ENABLE ROW LEVEL SECURITY;
ALTER TABLE program.program FORCE ROW LEVEL SECURITY;
ALTER TABLE program.statement ENABLE ROW LEVEL SECURITY;
ALTER TABLE program.statement FORCE ROW LEVEL SECURITY;
ALTER TABLE program.module_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE program.module_binding FORCE ROW LEVEL SECURITY;

CREATE POLICY program_owner_control ON program.program TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY program_runtime_control ON program.program TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY program_readonly_control ON program.program FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY program_principal ON program.program TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

CREATE POLICY statement_owner_control ON program.statement TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY statement_runtime_control ON program.statement TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY statement_readonly_control ON program.statement FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY statement_principal ON program.statement TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

CREATE POLICY module_binding_owner_control ON program.module_binding TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY module_binding_runtime_control ON program.module_binding TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY module_binding_readonly_control ON program.module_binding FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY module_binding_principal ON program.module_binding TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

-- name: baseline.program_grants
GRANT SELECT, INSERT ON program.program, program.statement, program.module_binding TO cilexec_runtime;
GRANT SELECT ON program.program, program.statement, program.module_binding TO cilexec_readonly;

RESET ROLE;

-- ============================================================================
-- Component: process continuation
-- ============================================================================
SET ROLE cilexec_owner;

-- PID sequence values are intentionally not transactional and are never reused.
-- name: baseline.create_pid_sequence
CREATE SEQUENCE process.pid_sequence
    AS bigint
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    NO MAXVALUE
    NO CYCLE;

-- name: baseline.create_process
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

-- A shared JVM must not be exhaustible by one account creating unbounded processes.
CREATE FUNCTION process.enforce_owner_process_quota()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, process
AS $function$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.owner_id::text, 701));
    IF (SELECT count(*) FROM process.process WHERE owner_id = NEW.owner_id) >= 64 THEN
        RAISE EXCEPTION 'per-user process quota of 64 has been reached'
            USING ERRCODE = '54000';
    END IF;
    RETURN NEW;
END
$function$;
CREATE TRIGGER process_owner_quota
BEFORE INSERT ON process.process
FOR EACH ROW EXECUTE FUNCTION process.enforce_owner_process_quota();
REVOKE ALL ON FUNCTION process.enforce_owner_process_quota() FROM PUBLIC;

-- name: baseline.create_call_frame
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

-- name: baseline.create_scope
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

-- name: baseline.create_variable
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

-- name: baseline.create_exception_frame
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

-- name: baseline.create_wait_state
CREATE TABLE process.wait_state (
    process_uid uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    wait_kind text NOT NULL CHECK (wait_kind IN ('IPC', 'TIMER', 'EFFECT', 'INPUT', 'CHILD', 'PROCESS')),
    wait_object_id uuid,
    wait_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    entered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE
);

-- name: baseline.create_relationship
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

-- name: baseline.create_process_event
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

-- name: baseline.process_indexes
CREATE INDEX ix_process_owner_status ON process.process(owner_id, status, updated_at);
CREATE INDEX ix_process_parent ON process.process(parent_process_uid) WHERE parent_process_uid IS NOT NULL;
CREATE INDEX ix_process_waiting ON process.process(status, wait_reason) WHERE status LIKE 'WAITING_%';
CREATE INDEX ix_variable_process_scope ON process.variable(process_uid, scope_id);
CREATE INDEX ix_process_event_history ON process.event(process_uid, created_at DESC);

-- name: baseline.event_immutability
CREATE TRIGGER process_event_reject_update_delete
BEFORE UPDATE OR DELETE ON process.event
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();

-- name: baseline.process_rls
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

-- name: baseline.process_grants
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

-- ============================================================================
-- Component: scheduler
-- ============================================================================
SET ROLE cilexec_owner;

-- name: baseline.create_runner
CREATE TABLE scheduler.runner (
    runner_id uuid PRIMARY KEY,
    boot_id uuid NOT NULL REFERENCES meta.boot(boot_id) ON DELETE RESTRICT,
    runner_kind text NOT NULL CHECK (runner_kind IN ('SCHEDULER', 'EFFECT', 'TIMER', 'RECOVERY')),
    status text NOT NULL CHECK (status IN ('STARTING', 'ACTIVE', 'DRAINING', 'STOPPED', 'FENCED')),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    heartbeat_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    stopped_at timestamptz,
    CHECK ((status = 'STOPPED' AND stopped_at IS NOT NULL) OR status <> 'STOPPED')
);

-- name: baseline.create_queue
CREATE TABLE scheduler.queue (
    process_uid uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    queue_state text NOT NULL CHECK (queue_state IN ('READY', 'CLAIMED', 'BLOCKED', 'REMOVED')),
    ready_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    enqueued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    claimed_at timestamptz,
    claimed_by uuid REFERENCES scheduler.runner(runner_id) ON DELETE SET NULL,
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    CHECK ((queue_state = 'CLAIMED' AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL)
        OR queue_state <> 'CLAIMED')
);

-- name: baseline.create_lease
CREATE TABLE scheduler.lease (
    process_uid uuid PRIMARY KEY REFERENCES process.process(process_uid) ON DELETE CASCADE,
    owner_id uuid NOT NULL,
    runner_id uuid NOT NULL REFERENCES scheduler.runner(runner_id) ON DELETE RESTRICT,
    boot_id uuid NOT NULL REFERENCES meta.boot(boot_id) ON DELETE RESTRICT,
    execution_epoch bigint NOT NULL CHECK (execution_epoch > 0),
    claimed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    heartbeat_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    CHECK (heartbeat_at >= claimed_at),
    CHECK (expires_at > heartbeat_at)
);

-- name: scheduler.claimNext.index
CREATE INDEX ix_queue_claim_next
    ON scheduler.queue(enqueued_at, process_uid) INCLUDE (ready_at)
    WHERE queue_state = 'READY';
-- name: scheduler.expiredLease.index
CREATE INDEX ix_lease_expired ON scheduler.lease(expires_at, process_uid);
CREATE INDEX ix_runner_heartbeat ON scheduler.runner(status, heartbeat_at);

-- queue is user-visible; runner and lease are explicitly system-owned.
-- name: baseline.queue_rls
ALTER TABLE scheduler.queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE scheduler.queue FORCE ROW LEVEL SECURITY;
CREATE POLICY queue_owner_control ON scheduler.queue TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY queue_runtime_control ON scheduler.queue TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY queue_readonly_control ON scheduler.queue FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY queue_principal ON scheduler.queue TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

-- name: baseline.scheduler_grants
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduler.queue TO cilexec_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduler.runner, scheduler.lease TO cilexec_runtime;
GRANT SELECT ON scheduler.runner, scheduler.queue, scheduler.lease TO cilexec_readonly;

COMMENT ON INDEX scheduler.ix_queue_claim_next IS
    'Stable FIFO claim order for SELECT FOR UPDATE SKIP LOCKED';
COMMENT ON TABLE scheduler.lease IS
    'System-only lease; execution_epoch fences stale workers from committing';

RESET ROLE;

-- ============================================================================
-- Component: ipc
-- ============================================================================
SET ROLE cilexec_owner;

-- name: baseline.create_channel
CREATE TABLE ipc.channel (
    channel_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    channel_name text NOT NULL CHECK (btrim(channel_name) <> ''),
    status text NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    closed_at timestamptz,
    UNIQUE (owner_id, channel_name),
    UNIQUE (channel_id, owner_id),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

-- name: baseline.create_topic
CREATE TABLE ipc.topic (
    topic_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    topic_name text NOT NULL CHECK (btrim(topic_name) <> ''),
    status text NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    closed_at timestamptz,
    UNIQUE (owner_id, topic_name),
    UNIQUE (topic_id, owner_id),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

-- A subscription covers both competing channel consumers and topic fan-out.
-- name: baseline.create_subscription
CREATE TABLE ipc.subscription (
    subscription_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    subscriber_process_uid uuid NOT NULL,
    source_kind text NOT NULL CHECK (source_kind IN ('CHANNEL', 'TOPIC')),
    channel_id uuid,
    topic_id uuid,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    cancelled_at timestamptz,
    FOREIGN KEY (subscriber_process_uid, owner_id)
        REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (channel_id, owner_id) REFERENCES ipc.channel(channel_id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (topic_id, owner_id) REFERENCES ipc.topic(topic_id, owner_id) ON DELETE CASCADE,
    CHECK ((source_kind = 'CHANNEL' AND channel_id IS NOT NULL AND topic_id IS NULL)
        OR (source_kind = 'TOPIC' AND topic_id IS NOT NULL AND channel_id IS NULL)),
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_subscription_channel_process
    ON ipc.subscription(channel_id, subscriber_process_uid) WHERE source_kind = 'CHANNEL';
CREATE UNIQUE INDEX ux_subscription_topic_process
    ON ipc.subscription(topic_id, subscriber_process_uid) WHERE source_kind = 'TOPIC';

-- name: baseline.create_message
CREATE TABLE ipc.message (
    message_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    sender_process_uid uuid,
    message_kind text NOT NULL CHECK (message_kind IN ('DIRECT', 'CHANNEL', 'TOPIC', 'BROADCAST')),
    channel_id uuid,
    topic_name text,
    payload_type text NOT NULL CHECK (btrim(payload_type) <> ''),
    payload_json jsonb,
    payload_object_hash bytea REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz,
    FOREIGN KEY (sender_process_uid, owner_id)
        REFERENCES process.process(process_uid, owner_id) ON DELETE RESTRICT,
    FOREIGN KEY (channel_id, owner_id) REFERENCES ipc.channel(channel_id, owner_id) ON DELETE RESTRICT,
    CHECK (num_nonnulls(payload_json, payload_object_hash) = 1),
    CHECK (expires_at IS NULL OR expires_at > created_at),
    CHECK ((message_kind = 'DIRECT' AND channel_id IS NULL AND topic_name IS NULL)
        OR (message_kind = 'CHANNEL' AND channel_id IS NOT NULL AND topic_name IS NULL)
        OR (message_kind IN ('TOPIC', 'BROADCAST') AND channel_id IS NULL
            AND topic_name IS NOT NULL AND btrim(topic_name) <> ''))
);

-- name: baseline.create_delivery
CREATE TABLE ipc.delivery (
    delivery_id uuid PRIMARY KEY,
    message_id uuid NOT NULL REFERENCES ipc.message(message_id) ON DELETE CASCADE,
    owner_id uuid NOT NULL,
    receiver_process_uid uuid NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING', 'RESERVED', 'CONSUMED', 'FAILED', 'DEAD')),
    -- Delivery consumers have their own durable identity and need not be
    -- scheduler runners (terminal and service consumers also reserve rows).
    reserved_by uuid,
    reserved_at timestamptz,
    consumed_at timestamptz,
    failed_at timestamptz,
    failure_reason text,
    delivery_attempts integer NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (receiver_process_uid, owner_id)
        REFERENCES process.process(process_uid, owner_id) ON DELETE RESTRICT,
    CHECK ((status = 'RESERVED' AND reserved_by IS NOT NULL AND reserved_at IS NOT NULL)
        OR status <> 'RESERVED'),
    CHECK ((status = 'CONSUMED' AND consumed_at IS NOT NULL) OR status <> 'CONSUMED'),
    CHECK ((status IN ('FAILED', 'DEAD') AND failed_at IS NOT NULL AND failure_reason IS NOT NULL)
        OR status NOT IN ('FAILED', 'DEAD')),
    CHECK (
        (status = 'PENDING' AND reserved_by IS NULL AND reserved_at IS NULL
            AND consumed_at IS NULL AND failed_at IS NULL AND failure_reason IS NULL)
        OR (status = 'RESERVED' AND reserved_by IS NOT NULL AND reserved_at IS NOT NULL
            AND consumed_at IS NULL AND failed_at IS NULL AND failure_reason IS NULL)
        OR (status = 'CONSUMED' AND reserved_by IS NOT NULL AND reserved_at IS NOT NULL
            AND consumed_at IS NOT NULL AND failed_at IS NULL AND failure_reason IS NULL)
        OR (status = 'FAILED' AND reserved_by IS NOT NULL AND reserved_at IS NOT NULL
            AND consumed_at IS NULL AND failed_at IS NOT NULL AND failure_reason IS NOT NULL)
        OR (status = 'DEAD' AND consumed_at IS NULL
            AND failed_at IS NOT NULL AND failure_reason IS NOT NULL)
    )
);
CREATE UNIQUE INDEX ux_delivery_message_receiver
    ON ipc.delivery(message_id, receiver_process_uid);

-- name: ipc.claimDelivery.index
CREATE INDEX ix_delivery_claim
    ON ipc.delivery(created_at, delivery_id)
    WHERE status IN ('PENDING', 'RESERVED');
CREATE INDEX ix_message_expiration ON ipc.message(expires_at) WHERE expires_at IS NOT NULL;

-- name: baseline.ipc_rls
DO $rls$
DECLARE
    relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY['channel', 'topic', 'subscription', 'message', 'delivery']
    LOOP
        EXECUTE format('ALTER TABLE ipc.%I ENABLE ROW LEVEL SECURITY', relation_name);
        EXECUTE format('ALTER TABLE ipc.%I FORCE ROW LEVEL SECURITY', relation_name);
        EXECUTE format('CREATE POLICY %I ON ipc.%I TO cilexec_owner USING (true) WITH CHECK (true)',
            relation_name || '_owner_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON ipc.%I TO cilexec_runtime USING (true) WITH CHECK (true)',
            relation_name || '_runtime_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON ipc.%I FOR SELECT TO cilexec_readonly USING (true)',
            relation_name || '_readonly_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON ipc.%I TO PUBLIC USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id())',
            relation_name || '_principal', relation_name);
    END LOOP;
END
$rls$;

-- name: baseline.ipc_grants
GRANT SELECT, INSERT, UPDATE, DELETE ON ipc.channel, ipc.topic, ipc.subscription,
    ipc.message, ipc.delivery TO cilexec_runtime;
GRANT SELECT ON ipc.channel, ipc.topic, ipc.subscription, ipc.message, ipc.delivery TO cilexec_readonly;

COMMENT ON TABLE ipc.delivery IS
    'Exactly-once database consumption unit; topic and broadcast create one row per subscriber';

RESET ROLE;

-- ============================================================================
-- Component: timer
-- ============================================================================
SET ROLE cilexec_owner;

-- name: baseline.create_timer
CREATE TABLE process.timer (
    timer_id uuid PRIMARY KEY,
    process_uid uuid NOT NULL,
    owner_id uuid NOT NULL,
    wake_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('SCHEDULED', 'CLAIMED', 'FIRED', 'CANCELLED')),
    claimed_by uuid,
    claimed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    fired_at timestamptz,
    cancelled_at timestamptz,
    payload_json jsonb NOT NULL DEFAULT 'null'::jsonb,
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    CHECK ((status = 'CLAIMED' AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL) OR status <> 'CLAIMED'),
    CHECK ((status = 'FIRED' AND fired_at IS NOT NULL) OR status <> 'FIRED'),
    CHECK ((status = 'CANCELLED' AND cancelled_at IS NOT NULL) OR status <> 'CANCELLED')
);

-- name: timer.claimDue.index
CREATE INDEX ix_timer_claim_due ON process.timer(wake_at, timer_id) WHERE status = 'SCHEDULED';
CREATE INDEX ix_timer_process ON process.timer(process_uid, status);

-- name: baseline.timer_rls
ALTER TABLE process.timer ENABLE ROW LEVEL SECURITY;
ALTER TABLE process.timer FORCE ROW LEVEL SECURITY;
CREATE POLICY timer_owner_control ON process.timer TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY timer_runtime_control ON process.timer TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY timer_readonly_control ON process.timer FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY timer_principal ON process.timer TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

-- name: baseline.timer_grants
GRANT SELECT, INSERT, UPDATE, DELETE ON process.timer TO cilexec_runtime;
GRANT SELECT ON process.timer TO cilexec_readonly;

COMMENT ON TABLE process.timer IS 'Authoritative timer state; JVM sleeps are only an optimization';

RESET ROLE;

