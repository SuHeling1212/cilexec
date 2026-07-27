-- Human terminal accounts use memorable passwords. Machine service secrets retain
-- their independent 16-character bootstrap policy in docker/postgres/init.
RESET ROLE;

-- name: auth.provision_login_role
CREATE OR REPLACE FUNCTION auth.provision_login_role(p_user_id uuid, p_password text)
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
    IF p_password IS NULL OR length(p_password) < 8 THEN
        RAISE EXCEPTION 'user login password must contain at least 8 characters';
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

-- name: auth.admin_create_user_as
CREATE OR REPLACE FUNCTION auth.admin_create_user_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_user_id uuid,
    p_username text,
    p_password text,
    p_capabilities text[],
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF auth.user_account
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, audit
AS $function$
DECLARE
    actor uuid;
    mapped_role name;
    account auth.user_account%ROWTYPE;
BEGIN
    actor := auth.require_system_administrator_as(
        p_database_role, p_claim, p_administrator_id
    );
    IF p_user_id IS NULL OR p_event_id IS NULL OR p_at IS NULL
       OR p_username IS NULL OR btrim(p_username) = ''
       OR p_password IS NULL OR length(p_password) < 8
       OR p_capabilities IS NULL THEN
        RAISE EXCEPTION 'invalid administrator user creation request'
            USING ERRCODE = '22000';
    END IF;
    IF EXISTS (
        SELECT 1 FROM unnest(p_capabilities) AS requested(capability_key)
        LEFT JOIN auth.capability AS capability USING (capability_key)
        WHERE capability.capability_id IS NULL
    ) THEN
        RAISE EXCEPTION 'unknown CilExec capability requested' USING ERRCODE = '22000';
    END IF;

    mapped_role := ('cilexec_user_' || replace(p_user_id::text, '-', ''))::name;
    INSERT INTO auth.user_account(
        user_id, username, postgres_role_name, status,
        credential_version, created_at, updated_at
    ) VALUES (
        p_user_id, p_username, mapped_role, 'ACTIVE', 1, p_at, p_at
    )
    RETURNING * INTO account;

    IF auth.provision_principal(p_user_id, p_password) IS DISTINCT FROM mapped_role THEN
        RAISE EXCEPTION 'database provisioned an unexpected CilExec role';
    END IF;

    INSERT INTO auth.user_capability(
        user_id, owner_id, capability_id, granted_by
    )
    SELECT p_user_id, p_user_id, capability.capability_id, actor
    FROM auth.capability AS capability
    WHERE capability.capability_key = ANY (p_capabilities);

    INSERT INTO audit.event(
        event_id, owner_id, actor_type, actor_id, action,
        resource_type, resource_id, result, details_json, created_at
    ) VALUES (
        p_event_id, p_user_id, 'ADMINISTRATOR', actor::text,
        'auth.user.create', 'auth.user', p_user_id::text, 'SUCCEEDED',
        jsonb_build_object('username', p_username, 'status', 'ACTIVE'), p_at
    );
    RETURN NEXT account;
END
$function$;

REVOKE ALL ON FUNCTION auth.admin_create_user_as(
    name, text, uuid, uuid, text, text, text[], uuid, timestamptz
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.admin_create_user_as(
    name, text, uuid, uuid, text, text, text[], uuid, timestamptz
) TO PUBLIC;

SET ROLE cilexec_owner;
SELECT meta.assert_security_invariants();
RESET ROLE;
