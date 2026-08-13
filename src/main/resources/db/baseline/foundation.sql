-- CilExec 1.0 database baseline. It is immutable after the 1.0 release.


-- ============================================================================
-- Component: schemas extensions and security baseline
-- ============================================================================
SET ROLE cilexec_owner;
-- CilExec schema baseline. Cluster roles and the database are created by
-- docker/postgres/init/00-cilexec-bootstrap.sh (or by an external DBA).

-- name: baseline.require_bootstrap_roles
DO $cilexec$
DECLARE
    required_role text;
BEGIN
    FOREACH required_role IN ARRAY ARRAY[
        'cilexec_owner',
        'cilexec_migrator',
        'cilexec_runtime',
        'cilexec_effect_worker',
        'cilexec_readonly',
        'cilexec_exporter'
    ]
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = required_role) THEN
            RAISE EXCEPTION 'required CilExec role % is missing; run cluster bootstrap first', required_role;
        END IF;
    END LOOP;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles
        WHERE rolname IN (
            'cilexec_runtime', 'cilexec_effect_worker',
            'cilexec_readonly', 'cilexec_exporter'
        )
          AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole)
    ) THEN
        RAISE EXCEPTION 'runtime and effect worker have forbidden cluster privileges';
    END IF;
END
$cilexec$;

SET ROLE cilexec_owner;

-- name: baseline.lock_down_public
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
DO $database_privileges$
BEGIN
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
END
$database_privileges$;

-- name: baseline.create_schemas
CREATE SCHEMA meta AUTHORIZATION cilexec_owner;
CREATE SCHEMA auth AUTHORIZATION cilexec_owner;
CREATE SCHEMA object_store AUTHORIZATION cilexec_owner;
CREATE SCHEMA vfs AUTHORIZATION cilexec_owner;
CREATE SCHEMA program AUTHORIZATION cilexec_owner;
CREATE SCHEMA process AUTHORIZATION cilexec_owner;
CREATE SCHEMA scheduler AUTHORIZATION cilexec_owner;
CREATE SCHEMA ipc AUTHORIZATION cilexec_owner;
CREATE SCHEMA effect AUTHORIZATION cilexec_owner;
CREATE SCHEMA package AUTHORIZATION cilexec_owner;
CREATE SCHEMA terminal AUTHORIZATION cilexec_owner;
CREATE SCHEMA audit AUTHORIZATION cilexec_owner;
CREATE SCHEMA diagnostic AUTHORIZATION cilexec_owner;

-- name: baseline.schema_usage
GRANT USAGE ON SCHEMA meta, auth, object_store, vfs, program, process,
    scheduler, ipc, effect, package, terminal, audit TO cilexec_runtime;
GRANT USAGE ON SCHEMA meta, scheduler, effect, process, audit TO cilexec_effect_worker;
GRANT USAGE ON SCHEMA meta, diagnostic TO cilexec_readonly;

-- name: baseline.default_public_revocation
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA meta REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA auth REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA object_store REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA vfs REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA program REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA process REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA scheduler REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA ipc REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA effect REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA package REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA terminal REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA audit REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_owner IN SCHEMA diagnostic REVOKE ALL ON TABLES FROM PUBLIC;

COMMENT ON SCHEMA meta IS 'CilExec instance, runtime boot, and security metadata';
COMMENT ON SCHEMA auth IS 'CilExec principals, capabilities, and PostgreSQL role mapping';
COMMENT ON SCHEMA object_store IS 'Immutable content-addressed byte objects';
COMMENT ON SCHEMA program IS 'Immutable FCL programs and compiled statements';
COMMENT ON SCHEMA process IS 'Durable process continuation and timer state';
COMMENT ON SCHEMA scheduler IS 'FIFO queue, runners, and fenced leases';
COMMENT ON SCHEMA ipc IS 'Persistent direct, channel, topic, and broadcast delivery';
COMMENT ON SCHEMA vfs IS 'Database-backed virtual filesystem';
COMMENT ON SCHEMA package IS 'Immutable SQLite package releases and environments';
COMMENT ON SCHEMA effect IS 'Journal for all external side effects';
COMMENT ON SCHEMA terminal IS 'Committed terminal input and process attachments';
COMMENT ON SCHEMA audit IS 'Append-only structured audit events';
COMMENT ON SCHEMA diagnostic IS 'Redacted operational views for the readonly service role';

RESET ROLE;

-- ============================================================================
-- Component: meta
-- ============================================================================
SET ROLE cilexec_owner;

-- name: baseline.create_instance
CREATE TABLE meta.instance (
    instance_id uuid PRIMARY KEY,
    singleton boolean NOT NULL DEFAULT true UNIQUE CHECK (singleton),
    instance_name text NOT NULL CHECK (btrim(instance_name) <> ''),
    advisory_lock_key bigint NOT NULL UNIQUE,
    status text NOT NULL CHECK (status IN ('INITIALIZING', 'ACTIVE', 'FENCED', 'STOPPED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- name: baseline.create_kernel_instance
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

-- name: baseline.create_boot
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

-- name: baseline.meta_indexes
CREATE INDEX ix_kernel_instance_active
    ON meta.kernel_instance(instance_id, status, started_at DESC)
    WHERE status IN ('STARTING', 'ACTIVE', 'DRAINING');
CREATE INDEX ix_boot_recovery
    ON meta.boot(instance_id, status, started_at DESC)
    WHERE status IN ('STARTING', 'RECOVERING', 'ACTIVE');

-- These are instance-global system tables. They are deliberately not RLS tables.
-- Runtime writes only lifecycle state; it cannot change their schema.
-- name: baseline.meta_grants
GRANT SELECT, INSERT, UPDATE ON meta.instance, meta.kernel_instance, meta.boot TO cilexec_runtime;
GRANT SELECT ON meta.instance, meta.kernel_instance, meta.boot TO cilexec_effect_worker;

COMMENT ON TABLE meta.instance IS 'Singleton authoritative CilExec database instance identity';
COMMENT ON TABLE meta.kernel_instance IS 'A concrete Java Runtime incarnation';
COMMENT ON TABLE meta.boot IS 'Crash-recoverable startup and shutdown lifecycle';

RESET ROLE;

-- ============================================================================
-- Component: auth
-- ============================================================================
SET ROLE cilexec_owner;

-- name: baseline.create_user_account
CREATE TABLE auth.user_account (
    user_id uuid PRIMARY KEY,
    username text NOT NULL CHECK (
        username = lower(username) AND username ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    postgres_role_name name NOT NULL UNIQUE,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED', 'DELETED')),
    credential_version bigint NOT NULL DEFAULT 1 CHECK (credential_version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    disabled_at timestamptz,
    CHECK (postgres_role_name::text ~ '^cilexec_user_[0-9a-f]{32}$'),
    CHECK ((status IN ('DISABLED', 'DELETED')) = (disabled_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_user_account_username_ci ON auth.user_account(lower(username));

-- Terminal credentials are application verifiers. They are never PostgreSQL LOGIN secrets.
CREATE TABLE auth.user_credential (
    user_id uuid PRIMARY KEY REFERENCES auth.user_account(user_id) ON DELETE CASCADE,
    password_hash text NOT NULL CHECK (password_hash ~ '^pbkdf2-sha256[$][0-9]+[$]'),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- name: baseline.create_group_and_capability
CREATE TABLE auth.group_account (
    group_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    group_name text NOT NULL CHECK (btrim(group_name) <> ''),
    status text NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (owner_id, group_name),
    UNIQUE (group_id, owner_id)
);

CREATE TABLE auth.group_member (
    group_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    member_user_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE CASCADE,
    granted_by uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (group_id, member_user_id),
    FOREIGN KEY (group_id, owner_id) REFERENCES auth.group_account(group_id, owner_id) ON DELETE CASCADE
);

CREATE TABLE auth.capability (
    capability_id uuid PRIMARY KEY,
    capability_key text NOT NULL UNIQUE CHECK (capability_key ~ '^[a-z][a-z0-9_.:-]*$'),
    description text NOT NULL,
    system_capability boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE auth.user_capability (
    user_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE CASCADE,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE CASCADE,
    capability_id uuid NOT NULL REFERENCES auth.capability(capability_id) ON DELETE RESTRICT,
    granted_by uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, capability_id),
    CHECK (user_id = owner_id)
);

CREATE TABLE auth.group_capability (
    group_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    capability_id uuid NOT NULL REFERENCES auth.capability(capability_id) ON DELETE RESTRICT,
    granted_by uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (group_id, capability_id),
    FOREIGN KEY (group_id, owner_id) REFERENCES auth.group_account(group_id, owner_id) ON DELETE CASCADE
);

-- name: baseline.seed_capabilities
INSERT INTO auth.capability(capability_id, capability_key, description, system_capability)
VALUES
    ('00000000-0000-4000-8000-000000000001', 'process_create', 'Create an owned process', false),
    ('00000000-0000-4000-8000-000000000002', 'process_control_own', 'Control owned processes', false),
    ('00000000-0000-4000-8000-000000000003', 'process_control_any', 'Control any process', true),
    ('00000000-0000-4000-8000-000000000004', 'vfs_read', 'Read authorized VFS content', false),
    ('00000000-0000-4000-8000-000000000005', 'vfs_write', 'Write authorized VFS content', false),
    ('00000000-0000-4000-8000-000000000006', 'vfs_mount_host', 'Use an explicitly declared host mount', true),
    ('00000000-0000-4000-8000-000000000007', 'package_import', 'Import immutable SQLite packages', false),
    ('00000000-0000-4000-8000-000000000008', 'package_bind', 'Manage package environment bindings', false),
    ('00000000-0000-4000-8000-000000000009', 'effect_request', 'Request a declared external effect', false),
    ('00000000-0000-4000-8000-00000000000a', 'terminal_attach', 'Attach a terminal to an owned process', false),
    ('00000000-0000-4000-8000-00000000000b', 'audit_read', 'Read authorized audit history', false);

-- A SECURITY INVOKER wrapper captures the real current_user before the private
-- definer function reads the protected mapping table.
-- name: auth.resolve_cilexec_user_id
CREATE FUNCTION auth.resolve_cilexec_user_id(p_database_role name, p_claim text)
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
    SELECT account.user_id
    FROM auth.user_account AS account
    WHERE account.postgres_role_name = p_database_role
      AND account.status = 'ACTIVE'
      -- SECURITY DEFINER changes current_user, but SET ROLE remains visible in
      -- the role GUC. Bind the supplied role to the actual invoker so callers
      -- cannot ask the resolver to impersonate another LOGIN role.
      AND p_database_role = CASE
          WHEN NULLIF(current_setting('role', true), 'none') IS NULL
              THEN session_user::name
          ELSE current_setting('role', true)::name
      END
      AND (p_database_role = session_user::name
          OR pg_has_role(session_user, p_database_role, 'MEMBER'))
      AND p_claim IS NOT NULL
      AND p_claim ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND account.user_id = p_claim::uuid
$function$;

-- name: auth.current_cilexec_user_id
CREATE FUNCTION auth.current_cilexec_user_id()
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, auth
AS $function$
    SELECT auth.resolve_cilexec_user_id(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), '')
    )
$function$;

-- Returns only the username belonging to the authenticated CilExec identity. The expected UUID
-- prevents a caller from using this narrow helper as an account enumeration interface.
-- name: auth.resolve_visible_username
CREATE FUNCTION auth.resolve_visible_username(
    p_database_role name,
    p_claim text,
    p_expected_user_id uuid
)
RETURNS text
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
    SELECT account.username
    FROM auth.user_account AS account
    WHERE account.user_id = auth.resolve_cilexec_user_id(p_database_role, p_claim)
      AND account.user_id = p_expected_user_id
      AND account.status = 'ACTIVE'
$function$;

-- name: auth.visible_username
CREATE FUNCTION auth.visible_username(p_expected_user_id uuid)
RETURNS text
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, auth
AS $function$
    SELECT auth.resolve_visible_username(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_expected_user_id
    )
$function$;

REVOKE ALL ON FUNCTION auth.resolve_cilexec_user_id(name, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION auth.resolve_visible_username(name, text, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION auth.visible_username(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.resolve_cilexec_user_id(name, text)
    TO cilexec_runtime, cilexec_effect_worker;
GRANT EXECUTE ON FUNCTION auth.current_cilexec_user_id()
    TO cilexec_runtime, cilexec_effect_worker;
GRANT EXECUTE ON FUNCTION auth.resolve_visible_username(name, text, uuid)
    TO cilexec_runtime, cilexec_effect_worker;
GRANT EXECUTE ON FUNCTION auth.visible_username(uuid)
    TO cilexec_runtime, cilexec_effect_worker;

-- name: baseline.auth_rls
ALTER TABLE auth.user_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.user_account FORCE ROW LEVEL SECURITY;
CREATE POLICY user_account_owner_control ON auth.user_account TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY user_account_migrator_control ON auth.user_account TO cilexec_migrator USING (true) WITH CHECK (true);
CREATE POLICY user_account_runtime_control ON auth.user_account TO cilexec_runtime USING (true) WITH CHECK (true);
-- User LOGIN roles intentionally have no direct user_account policy. The
-- identity resolver reads this table as cilexec_owner; adding a policy that
-- calls the resolver here would create recursive RLS. Users call the verified
-- identity function instead.

ALTER TABLE auth.group_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.group_account FORCE ROW LEVEL SECURITY;
ALTER TABLE auth.group_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.group_member FORCE ROW LEVEL SECURITY;
ALTER TABLE auth.user_capability ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.user_capability FORCE ROW LEVEL SECURITY;
ALTER TABLE auth.group_capability ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.group_capability FORCE ROW LEVEL SECURITY;

CREATE POLICY group_account_owner_control ON auth.group_account TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY group_account_runtime_control ON auth.group_account TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY group_account_principal ON auth.group_account TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

CREATE POLICY group_member_owner_control ON auth.group_member TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY group_member_runtime_control ON auth.group_member TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY group_member_principal ON auth.group_member TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

CREATE POLICY user_capability_owner_control ON auth.user_capability TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY user_capability_runtime_control ON auth.user_capability TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY user_capability_principal ON auth.user_capability FOR SELECT TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id());

CREATE POLICY group_capability_owner_control ON auth.group_capability TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY group_capability_runtime_control ON auth.group_capability TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY group_capability_principal ON auth.group_capability FOR SELECT TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id());

-- capability is a global readonly dictionary and is classified explicitly in the baseline.
-- name: baseline.auth_grants
GRANT SELECT, INSERT, UPDATE ON auth.user_account, auth.group_account, auth.group_member,
    auth.user_capability, auth.group_capability TO cilexec_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON auth.user_credential
    TO cilexec_runtime, cilexec_migrator;
GRANT DELETE ON auth.group_member, auth.user_capability, auth.group_capability TO cilexec_runtime;
GRANT SELECT ON auth.capability TO cilexec_runtime;

-- Role creation is cluster DDL, so this function is created by the already
-- bootstrapped migrator. Runtime later receives only the the baseline composite API.
-- name: auth.provision_login_role
RESET ROLE;
CREATE FUNCTION auth.provision_login_role(p_user_id uuid, p_password text)
RETURNS name
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
DECLARE
    mapped_role name;
BEGIN
    SELECT postgres_role_name INTO STRICT mapped_role
    FROM auth.user_account
    WHERE user_id = p_user_id;

    IF mapped_role::text <> 'cilexec_user_' || replace(p_user_id::text, '-', '') THEN
        RAISE EXCEPTION 'invalid stable PostgreSQL role mapping for user %', p_user_id;
    END IF;
    IF p_password IS NULL OR p_password !~ '^pbkdf2-sha256[$][0-9]+[$]' THEN
        RAISE EXCEPTION 'invalid application credential verifier';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = mapped_role) THEN
        IF EXISTS (
            SELECT 1 FROM pg_catalog.pg_roles
            WHERE rolname = mapped_role
              AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
        ) THEN
            RAISE EXCEPTION 'mapped PostgreSQL role has forbidden privileged attributes';
        END IF;
        EXECUTE format(
            'ALTER ROLE %I NOLOGIN NOINHERIT PASSWORD NULL',
            mapped_role
        );
    ELSE
        EXECUTE format(
            'CREATE ROLE %I NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
            mapped_role
        );
    END IF;

    INSERT INTO auth.user_credential(user_id, password_hash, updated_at)
    VALUES (p_user_id, p_password, clock_timestamp())
    ON CONFLICT (user_id) DO UPDATE
    SET password_hash = EXCLUDED.password_hash, updated_at = EXCLUDED.updated_at;

    EXECUTE format('GRANT %I TO cilexec_runtime', mapped_role);
    RETURN mapped_role;
END
$function$;
REVOKE ALL ON FUNCTION auth.provision_login_role(uuid, text) FROM PUBLIC;

SET ROLE cilexec_owner;
COMMENT ON FUNCTION auth.current_cilexec_user_id() IS
    'Returns an identity only when current_user, transaction GUC, and user_account mapping all agree';

RESET ROLE;
