SET ROLE cilexec_owner;

-- name: migration.V002.create_instance
CREATE TABLE meta.instance (
    instance_id uuid PRIMARY KEY,
    singleton boolean NOT NULL DEFAULT true UNIQUE CHECK (singleton),
    instance_name text NOT NULL CHECK (btrim(instance_name) <> ''),
    advisory_lock_key bigint NOT NULL UNIQUE,
    status text NOT NULL CHECK (status IN ('INITIALIZING', 'ACTIVE', 'FENCED', 'STOPPED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- name: migration.V002.create_kernel_instance
CREATE TABLE meta.kernel_instance (
    kernel_instance_id uuid PRIMARY KEY,
    instance_id uuid NOT NULL REFERENCES meta.instance(instance_id) ON DELETE RESTRICT,
    runtime_version text NOT NULL,
    fcl_runtime_format_version integer NOT NULL CHECK (fcl_runtime_format_version > 0),
    hostname text NOT NULL,
    container_identity text,
    status text NOT NULL CHECK (status IN ('STARTING', 'ACTIVE', 'DRAINING', 'FENCED', 'STOPPED')),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_seen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    stopped_at timestamptz,
    CHECK ((status = 'STOPPED' AND stopped_at IS NOT NULL) OR status <> 'STOPPED')
);

-- name: migration.V002.create_boot
CREATE TABLE meta.boot (
    boot_id uuid PRIMARY KEY,
    instance_id uuid NOT NULL REFERENCES meta.instance(instance_id) ON DELETE RESTRICT,
    kernel_instance_id uuid NOT NULL REFERENCES meta.kernel_instance(kernel_instance_id) ON DELETE RESTRICT,
    status text NOT NULL CHECK (status IN ('STARTING', 'RECOVERING', 'ACTIVE', 'CLEAN', 'CRASHED', 'FENCED')),
    runtime_version text NOT NULL,
    schema_version text NOT NULL,
    fcl_runtime_format_version integer NOT NULL CHECK (fcl_runtime_format_version > 0),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    recovery_completed_at timestamptz,
    ready_at timestamptz,
    ended_at timestamptz,
    shutdown_reason text,
    CHECK (ready_at IS NULL OR recovery_completed_at IS NOT NULL),
    CHECK (ended_at IS NULL OR status IN ('CLEAN', 'CRASHED', 'FENCED'))
);

-- name: migration.V002.meta_indexes
CREATE INDEX ix_kernel_instance_active
    ON meta.kernel_instance(instance_id, status, started_at DESC)
    WHERE status IN ('STARTING', 'ACTIVE', 'DRAINING');
CREATE INDEX ix_boot_recovery
    ON meta.boot(instance_id, status, started_at DESC)
    WHERE status IN ('STARTING', 'RECOVERING', 'ACTIVE');

-- These are instance-global system tables. They are deliberately not RLS tables.
-- Runtime writes only lifecycle state; it cannot change their schema.
-- name: migration.V002.meta_grants
GRANT SELECT, INSERT, UPDATE ON meta.instance, meta.kernel_instance, meta.boot TO cilexec_runtime;
GRANT SELECT ON meta.instance, meta.kernel_instance, meta.boot TO cilexec_effect_worker;
GRANT SELECT ON meta.instance, meta.kernel_instance, meta.boot TO cilexec_readonly;

COMMENT ON TABLE meta.instance IS 'Singleton authoritative CilExec database instance identity';
COMMENT ON TABLE meta.kernel_instance IS 'A concrete Java Runtime incarnation';
COMMENT ON TABLE meta.boot IS 'Crash-recoverable startup and shutdown lifecycle';

RESET ROLE;
