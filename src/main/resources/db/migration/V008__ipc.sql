SET ROLE cilexec_owner;

-- name: migration.V008.create_channel
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

-- name: migration.V008.create_topic
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
-- name: migration.V008.create_subscription
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

-- name: migration.V008.create_message
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

-- name: migration.V008.create_delivery
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

-- name: migration.V008.ipc_rls
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

-- name: migration.V008.ipc_grants
GRANT SELECT, INSERT, UPDATE, DELETE ON ipc.channel, ipc.topic, ipc.subscription,
    ipc.message, ipc.delivery TO cilexec_runtime;
GRANT SELECT ON ipc.channel, ipc.topic, ipc.subscription, ipc.message, ipc.delivery TO cilexec_readonly;

COMMENT ON TABLE ipc.delivery IS
    'Exactly-once database consumption unit; topic and broadcast create one row per subscriber';

RESET ROLE;
