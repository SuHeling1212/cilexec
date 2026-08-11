-- ============================================================================
-- Component: durable FCL environment
-- ============================================================================
SET ROLE cilexec_owner;

CREATE TABLE auth.environment_variable (
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE CASCADE,
    variable_name text NOT NULL CHECK (variable_name ~ '^[A-Z_][A-Z0-9_]{0,127}$'),
    variable_value text NOT NULL CHECK (octet_length(variable_value) <= 65536),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (owner_id, variable_name)
);

CREATE TABLE auth.shared_environment_variable (
    variable_name text PRIMARY KEY CHECK (variable_name ~ '^[A-Z_][A-Z0-9_]{0,127}$'),
    variable_value text NOT NULL CHECK (octet_length(variable_value) <= 65536),
    set_by uuid REFERENCES auth.user_account(user_id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE auth.shared_environment_policy (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    policy_mode text NOT NULL CHECK (policy_mode IN ('ALLOWLIST', 'DENYLIST')),
    variable_names text[] NOT NULL DEFAULT '{}',
    set_by uuid REFERENCES auth.user_account(user_id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (cardinality(variable_names) <= 1024)
);

INSERT INTO auth.shared_environment_policy(singleton, policy_mode, variable_names)
VALUES (true, 'DENYLIST', '{}');

ALTER TABLE auth.environment_variable ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.environment_variable FORCE ROW LEVEL SECURITY;
ALTER TABLE auth.shared_environment_variable ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.shared_environment_variable FORCE ROW LEVEL SECURITY;
ALTER TABLE auth.shared_environment_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.shared_environment_policy FORCE ROW LEVEL SECURITY;

CREATE POLICY environment_variable_owner_control ON auth.environment_variable
    TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY environment_variable_runtime_control ON auth.environment_variable
    TO cilexec_runtime USING (true) WITH CHECK (true);

CREATE POLICY shared_environment_variable_owner_control ON auth.shared_environment_variable
    TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY shared_environment_variable_runtime_control ON auth.shared_environment_variable
    TO cilexec_runtime USING (true) WITH CHECK (true);

CREATE POLICY shared_environment_policy_owner_control ON auth.shared_environment_policy
    TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY shared_environment_policy_runtime_control ON auth.shared_environment_policy
    TO cilexec_runtime USING (true) WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE, DELETE ON auth.environment_variable,
    auth.shared_environment_variable, auth.shared_environment_policy
    TO cilexec_runtime, PUBLIC;

INSERT INTO meta.table_security_classification
    (schema_name, table_name, classification, owner_column, rationale)
VALUES
    ('auth', 'environment_variable', 'USER_SCOPED', 'owner_id',
     'durable per-user FCL environment variables'),
    ('auth', 'shared_environment_variable', 'SYSTEM_RUNTIME', NULL,
     'administrator-managed environment defaults readable by all users'),
    ('auth', 'shared_environment_policy', 'SYSTEM_RUNTIME', NULL,
     'singleton allowlist or denylist for shared environment names');

COMMENT ON TABLE auth.environment_variable IS
    'Per-user durable FCL environment; a user value overrides a shared default';
COMMENT ON TABLE auth.shared_environment_variable IS
    'Shared FCL environment defaults managed by a system administrator';
COMMENT ON TABLE auth.shared_environment_policy IS
    'Singleton allowlist or denylist controlling shared environment variable names';

SELECT meta.assert_security_invariants();

RESET ROLE;

-- ============================================================================
-- Component: system administrator all permissions
-- ============================================================================
SET ROLE cilexec_owner;
-- SYSTEM_ADMIN is CilExec's application superuser. It satisfies every coarse
-- capability and receives an explicit RLS policy over all non-auth user data.
-- PostgreSQL cluster privileges and host operating-system privileges are not
-- changed by this migration.

SET ROLE cilexec_owner;

-- name: auth.is_system_administrator_as
CREATE FUNCTION auth.is_system_administrator_as(
    p_database_role name,
    p_claim text
)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
DECLARE
    actor uuid;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RETURN false;
    END IF;
    RETURN EXISTS (
        SELECT 1
        FROM auth.user_capability AS assignment
        JOIN auth.capability AS capability USING (capability_id)
        WHERE assignment.user_id = actor
          AND capability.capability_key = 'system_admin'
          AND (assignment.expires_at IS NULL OR assignment.expires_at > clock_timestamp())
        UNION ALL
        SELECT 1
        FROM auth.group_member AS member
        JOIN auth.group_account AS group_account
          ON group_account.group_id = member.group_id
         AND group_account.owner_id = member.owner_id
         AND group_account.status = 'ACTIVE'
        JOIN auth.group_capability AS assignment
          ON assignment.group_id = member.group_id
         AND assignment.owner_id = member.owner_id
        JOIN auth.capability AS capability USING (capability_id)
        WHERE member.member_user_id = actor
          AND capability.capability_key = 'system_admin'
          AND (assignment.expires_at IS NULL OR assignment.expires_at > clock_timestamp())
    );
END
$function$;

-- name: auth.current_cilexec_user_is_system_administrator
CREATE FUNCTION auth.current_cilexec_user_is_system_administrator()
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, auth
AS $function$
    SELECT auth.is_system_administrator_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), '')
    )
$function$;

REVOKE ALL ON FUNCTION auth.is_system_administrator_as(name, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION auth.current_cilexec_user_is_system_administrator() FROM PUBLIC;
-- The *_as entry point remains safe for PUBLIC because the supplied role and
-- claim are cryptographically bound to the real invoker by the identity resolver.
GRANT EXECUTE ON FUNCTION auth.is_system_administrator_as(name, text) TO PUBLIC;
GRANT EXECUTE ON FUNCTION auth.current_cilexec_user_is_system_administrator() TO PUBLIC;

-- name: auth.effective_capabilities_as
CREATE OR REPLACE FUNCTION auth.effective_capabilities_as(
    p_database_role name,
    p_claim text,
    p_user_id uuid
)
RETURNS TABLE (capability_key text)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
DECLARE
    actor uuid;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF NOT (
           p_database_role::text IN ('cilexec_runtime', 'cilexec_owner')
           AND p_database_role = CASE
               WHEN NULLIF(current_setting('role', true), 'none') IS NULL
                   THEN session_user::name
               ELSE current_setting('role', true)::name
           END
       ) AND actor IS DISTINCT FROM p_user_id THEN
        RAISE EXCEPTION 'effective capability lookup is not authorized'
            USING ERRCODE = '42501';
    END IF;

    IF actor IS NOT NULL
       AND actor = p_user_id
       AND auth.is_system_administrator_as(p_database_role, p_claim) THEN
        RETURN QUERY
        SELECT capability.capability_key
        FROM auth.capability AS capability
        ORDER BY capability.capability_key;
        RETURN;
    END IF;

    RETURN QUERY
    SELECT capability.capability_key
    FROM auth.user_capability AS assignment
    JOIN auth.capability AS capability USING (capability_id)
    WHERE assignment.user_id = p_user_id
      AND (assignment.expires_at IS NULL OR assignment.expires_at > clock_timestamp())
    UNION
    SELECT capability.capability_key
    FROM auth.group_member AS member
    JOIN auth.group_account AS group_account
      ON group_account.group_id = member.group_id
     AND group_account.owner_id = member.owner_id
     AND group_account.status = 'ACTIVE'
    JOIN auth.group_capability AS assignment
      ON assignment.group_id = member.group_id
     AND assignment.owner_id = member.owner_id
    JOIN auth.capability AS capability USING (capability_id)
    WHERE member.member_user_id = p_user_id
      AND (assignment.expires_at IS NULL OR assignment.expires_at > clock_timestamp());
END
$function$;

-- Administrator policies are additive. Owner policies continue to isolate all
-- ordinary users, while verified SYSTEM_ADMIN users can see and mutate every
-- user-scoped runtime resource. Auth tables stay behind the typed the baseline APIs to
-- avoid recursive identity-policy evaluation.
-- name: baseline.system_administrator_rls
DO $administrator_rls$
DECLARE
    classified record;
BEGIN
    FOR classified IN
        SELECT schema_name::text AS schema_name, table_name::text AS table_name
        FROM meta.table_security_classification
        WHERE classification = 'USER_SCOPED'
          AND schema_name <> 'auth'::name
        ORDER BY schema_name, table_name
    LOOP
        EXECUTE format(
            'CREATE POLICY system_administrator_access ON %I.%I TO PUBLIC '
            'USING (auth.current_cilexec_user_is_system_administrator()) '
            'WITH CHECK (auth.current_cilexec_user_is_system_administrator())',
            classified.schema_name, classified.table_name
        );
    END LOOP;
END
$administrator_rls$;

-- The scheduler release API had an owner-only fence. Expand it narrowly so an
-- administrator process control operation can pause or terminate foreign work.
-- name: scheduler.release_process_as
CREATE OR REPLACE FUNCTION scheduler.release_process_as(
    p_database_role name,
    p_claim text,
    p_process_uid uuid,
    p_execution_epoch bigint
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, process, scheduler
AS $function$
DECLARE
    actor uuid;
    process_owner uuid;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    SELECT owner_id INTO process_owner
    FROM process.process
    WHERE process_uid = p_process_uid;
    IF process_owner IS NULL THEN
        RETURN;
    END IF;
    IF NOT (
           p_database_role::text IN ('cilexec_runtime', 'cilexec_owner')
           AND p_database_role = CASE
               WHEN NULLIF(current_setting('role', true), 'none') IS NULL
                   THEN session_user::name
               ELSE current_setting('role', true)::name
           END
       ) AND actor IS DISTINCT FROM process_owner
         AND NOT auth.is_system_administrator_as(p_database_role, p_claim) THEN
        RAISE EXCEPTION 'process lease release is not authorized' USING ERRCODE = '42501';
    END IF;

    DELETE FROM scheduler.lease
    WHERE process_uid = p_process_uid AND execution_epoch = p_execution_epoch;
    UPDATE scheduler.queue
    SET queue_state = CASE WHEN EXISTS (
            SELECT 1 FROM process.process AS current_process
            WHERE current_process.process_uid = p_process_uid
              AND current_process.status = 'READY'
        ) THEN 'READY' ELSE 'REMOVED' END,
        claimed_at = NULL,
        claimed_by = NULL,
        enqueued_at = clock_timestamp()
    WHERE process_uid = p_process_uid;
END
$function$;

SELECT meta.assert_security_invariants();

RESET ROLE;

-- ============================================================================
