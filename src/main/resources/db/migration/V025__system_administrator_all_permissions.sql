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
-- user-scoped runtime resource. Auth tables stay behind the typed V024 APIs to
-- avoid recursive identity-policy evaluation.
-- name: migration.V025.system_administrator_rls
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
