SET ROLE cilexec_owner;

-- name: migration.V003.create_user_account
CREATE TABLE auth.user_account (
    user_id uuid PRIMARY KEY,
    username text NOT NULL CHECK (btrim(username) <> ''),
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

-- name: migration.V003.create_group_and_capability
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

-- name: migration.V003.seed_capabilities
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

REVOKE ALL ON FUNCTION auth.resolve_cilexec_user_id(name, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.resolve_cilexec_user_id(name, text)
    TO cilexec_runtime, cilexec_effect_worker, cilexec_readonly;
GRANT EXECUTE ON FUNCTION auth.current_cilexec_user_id()
    TO cilexec_runtime, cilexec_effect_worker, cilexec_readonly;

-- name: migration.V003.auth_rls
ALTER TABLE auth.user_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.user_account FORCE ROW LEVEL SECURITY;
CREATE POLICY user_account_owner_control ON auth.user_account TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY user_account_migrator_control ON auth.user_account TO cilexec_migrator USING (true) WITH CHECK (true);
CREATE POLICY user_account_runtime_control ON auth.user_account TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY user_account_readonly_control ON auth.user_account FOR SELECT TO cilexec_readonly USING (true);
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
CREATE POLICY group_account_readonly_control ON auth.group_account FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY group_account_principal ON auth.group_account TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

CREATE POLICY group_member_owner_control ON auth.group_member TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY group_member_runtime_control ON auth.group_member TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY group_member_readonly_control ON auth.group_member FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY group_member_principal ON auth.group_member TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

CREATE POLICY user_capability_owner_control ON auth.user_capability TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY user_capability_runtime_control ON auth.user_capability TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY user_capability_readonly_control ON auth.user_capability FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY user_capability_principal ON auth.user_capability FOR SELECT TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id());

CREATE POLICY group_capability_owner_control ON auth.group_capability TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY group_capability_runtime_control ON auth.group_capability TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY group_capability_readonly_control ON auth.group_capability FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY group_capability_principal ON auth.group_capability FOR SELECT TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id());

-- capability is a global readonly dictionary and is classified explicitly in V015.
-- name: migration.V003.auth_grants
GRANT SELECT, INSERT, UPDATE ON auth.user_account, auth.group_account, auth.group_member,
    auth.user_capability, auth.group_capability TO cilexec_runtime;
GRANT DELETE ON auth.group_member, auth.user_capability, auth.group_capability TO cilexec_runtime;
GRANT SELECT ON auth.capability TO cilexec_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA auth TO cilexec_readonly;

-- Role creation is cluster DDL, so this function is created by the already
-- bootstrapped migrator. Runtime later receives only the V015 composite API.
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
    IF p_password IS NULL OR length(p_password) < 16 THEN
        RAISE EXCEPTION 'database login password must contain at least 16 characters';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = mapped_role) THEN
        EXECUTE format(
            'ALTER ROLE %I LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
            mapped_role, p_password
        );
    ELSE
        EXECUTE format(
            'CREATE ROLE %I LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
            mapped_role, p_password
        );
    END IF;

    EXECUTE format('GRANT %I TO cilexec_runtime', mapped_role);
    RETURN mapped_role;
END
$function$;
REVOKE ALL ON FUNCTION auth.provision_login_role(uuid, text) FROM PUBLIC;

SET ROLE cilexec_owner;
COMMENT ON FUNCTION auth.current_cilexec_user_id() IS
    'Returns an identity only when current_user, transaction GUC, and user_account mapping all agree';

RESET ROLE;
