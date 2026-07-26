-- Administrator calls made from FCL must commit with the process continuation.
-- These narrow SECURITY DEFINER APIs preserve ordinary owner-scoped RLS while
-- authorizing a verified LOGIN role that owns the SYSTEM_ADMIN capability.

SET ROLE cilexec_owner;

-- name: auth.require_system_administrator_as
CREATE FUNCTION auth.require_system_administrator_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid
)
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
DECLARE
    actor uuid;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL OR actor IS DISTINCT FROM p_administrator_id THEN
        RAISE EXCEPTION 'a verified matching CilExec administrator is required'
            USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
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
    ) THEN
        RAISE EXCEPTION 'system_admin capability is required' USING ERRCODE = '42501';
    END IF;
    RETURN actor;
END
$function$;
REVOKE ALL ON FUNCTION auth.require_system_administrator_as(name, text, uuid) FROM PUBLIC;

-- Cross-user account queries remain capability checked and are safe to call in
-- the same user transaction as an FCL statement.
-- name: auth.admin_list_users_as
CREATE FUNCTION auth.admin_list_users_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid
)
RETURNS SETOF auth.user_account
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
BEGIN
    PERFORM auth.require_system_administrator_as(
        p_database_role, p_claim, p_administrator_id
    );
    RETURN QUERY
    SELECT account.*
    FROM auth.user_account AS account
    ORDER BY lower(account.username), account.user_id;
END
$function$;

-- name: auth.admin_list_users
CREATE FUNCTION auth.admin_list_users(p_administrator_id uuid)
RETURNS SETOF auth.user_account
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, auth
AS $function$
    SELECT * FROM auth.admin_list_users_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_administrator_id
    )
$function$;

REVOKE ALL ON FUNCTION auth.admin_list_users_as(name, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.admin_list_users(uuid) TO PUBLIC;

-- Account lifecycle includes PostgreSQL role DDL, so it is owned by the
-- CREATEROLE migrator. PostgreSQL role DDL is transactional and rolls back
-- together with the FCL statement when its continuation cannot commit.
RESET ROLE;

-- name: auth.admin_create_user_as
CREATE FUNCTION auth.admin_create_user_as(
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
       OR p_password IS NULL OR length(p_password) < 16
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

-- name: auth.admin_create_user
CREATE FUNCTION auth.admin_create_user(
    p_administrator_id uuid,
    p_user_id uuid,
    p_username text,
    p_password text,
    p_capabilities text[],
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF auth.user_account
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth
AS $function$
    SELECT * FROM auth.admin_create_user_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_administrator_id, p_user_id, p_username, p_password,
        p_capabilities, p_event_id, p_at
    )
$function$;

-- name: auth.admin_disable_user_as
CREATE FUNCTION auth.admin_disable_user_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_user_id uuid,
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
    account auth.user_account%ROWTYPE;
BEGIN
    actor := auth.require_system_administrator_as(
        p_database_role, p_claim, p_administrator_id
    );
    IF p_user_id IS NULL OR p_event_id IS NULL OR p_at IS NULL
       OR p_user_id = actor THEN
        RAISE EXCEPTION 'an administrator cannot disable its active identity'
            USING ERRCODE = '22000';
    END IF;

    UPDATE auth.user_account
    SET status = 'DISABLED', disabled_at = p_at, updated_at = p_at
    WHERE user_id = p_user_id AND status IN ('ACTIVE', 'LOCKED')
    RETURNING * INTO account;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'active target user does not exist' USING ERRCODE = '22000';
    END IF;
    PERFORM auth.disable_principal(p_user_id);

    INSERT INTO audit.event(
        event_id, owner_id, actor_type, actor_id, action,
        resource_type, resource_id, result, details_json, created_at
    ) VALUES (
        p_event_id, p_user_id, 'ADMINISTRATOR', actor::text,
        'auth.user.disable', 'auth.user', p_user_id::text, 'SUCCEEDED',
        jsonb_build_object('username', account.username, 'status', account.status), p_at
    );
    RETURN NEXT account;
END
$function$;

-- name: auth.admin_disable_user
CREATE FUNCTION auth.admin_disable_user(
    p_administrator_id uuid,
    p_user_id uuid,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF auth.user_account
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth
AS $function$
    SELECT * FROM auth.admin_disable_user_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_administrator_id, p_user_id, p_event_id, p_at
    )
$function$;

REVOKE ALL ON FUNCTION auth.admin_create_user_as(
    name, text, uuid, uuid, text, text, text[], uuid, timestamptz
) FROM PUBLIC;
REVOKE ALL ON FUNCTION auth.admin_disable_user_as(
    name, text, uuid, uuid, uuid, timestamptz
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.admin_create_user(
    uuid, uuid, text, text, text[], uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION auth.admin_disable_user(
    uuid, uuid, uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION auth.admin_create_user_as(
    name, text, uuid, uuid, text, text, text[], uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION auth.admin_disable_user_as(
    name, text, uuid, uuid, uuid, timestamptz
) TO PUBLIC;

SET ROLE cilexec_owner;

-- name: vfs.require_admin_target
CREATE FUNCTION vfs.require_admin_target(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_target_user_id uuid
)
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, vfs
AS $function$
DECLARE
    actor uuid;
BEGIN
    actor := auth.require_system_administrator_as(
        p_database_role, p_claim, p_administrator_id
    );
    IF NOT EXISTS (
        SELECT 1 FROM auth.user_account WHERE user_id = p_target_user_id
    ) THEN
        RAISE EXCEPTION 'target CilExec user does not exist' USING ERRCODE = '22000';
    END IF;
    RETURN actor;
END
$function$;
REVOKE ALL ON FUNCTION vfs.require_admin_target(name, text, uuid, uuid) FROM PUBLIC;

-- name: vfs.admin_list_nodes_as
CREATE FUNCTION vfs.admin_list_nodes_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF vfs.node
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, vfs, audit
AS $function$
DECLARE
    actor uuid;
    node_count bigint;
BEGIN
    actor := vfs.require_admin_target(
        p_database_role, p_claim, p_administrator_id, p_target_user_id
    );
    SELECT count(*) INTO node_count FROM vfs.node WHERE owner_id = p_target_user_id;
    INSERT INTO audit.event(
        event_id, owner_id, actor_type, actor_id, action,
        resource_type, resource_id, result, details_json, created_at
    ) VALUES (
        p_event_id, p_target_user_id, 'ADMINISTRATOR', actor::text,
        'vfs.admin.list', 'auth.user_account', p_target_user_id::text, 'SUCCEEDED',
        jsonb_build_object('nodeCount', node_count::text,
                           'targetUserId', p_target_user_id::text), p_at
    );
    RETURN QUERY
    SELECT candidate.* FROM vfs.node AS candidate
    WHERE candidate.owner_id = p_target_user_id
    ORDER BY candidate.parent_node_id NULLS FIRST, candidate.node_name, candidate.node_id;
END
$function$;

-- name: vfs.admin_list_nodes
CREATE FUNCTION vfs.admin_list_nodes(
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF vfs.node
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, vfs
AS $function$
    SELECT * FROM vfs.admin_list_nodes_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_administrator_id, p_target_user_id, p_event_id, p_at
    )
$function$;

-- name: vfs.admin_read_file_as
CREATE FUNCTION vfs.admin_read_file_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_node_id uuid,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS TABLE (
    object_hash bytea,
    byte_size bigint,
    media_type text,
    content bytea,
    created_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store, vfs, audit
AS $function$
DECLARE
    actor uuid;
    target vfs.node%ROWTYPE;
BEGIN
    actor := vfs.require_admin_target(
        p_database_role, p_claim, p_administrator_id, p_target_user_id
    );
    SELECT candidate.* INTO target FROM vfs.node AS candidate
    WHERE candidate.node_id = p_node_id AND candidate.owner_id = p_target_user_id;
    IF NOT FOUND OR target.node_type NOT IN ('FILE', 'SYMLINK') THEN
        RAISE EXCEPTION 'target VFS content node does not exist' USING ERRCODE = '22000';
    END IF;

    INSERT INTO audit.event(
        event_id, owner_id, actor_type, actor_id, action,
        resource_type, resource_id, result, details_json, created_at
    ) VALUES (
        p_event_id, p_target_user_id, 'ADMINISTRATOR', actor::text,
        'vfs.admin.read', 'vfs.node', p_node_id::text, 'SUCCEEDED',
        jsonb_build_object('name', target.node_name,
                           'targetUserId', p_target_user_id::text), p_at
    );
    RETURN QUERY
    SELECT object.object_hash, object.byte_size, object.media_type,
           object.content, object.created_at
    FROM object_store.object AS object
    WHERE object.object_hash = target.current_object_hash;
END
$function$;

-- name: vfs.admin_read_file
CREATE FUNCTION vfs.admin_read_file(
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_node_id uuid,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS TABLE (
    object_hash bytea,
    byte_size bigint,
    media_type text,
    content bytea,
    created_at timestamptz
)
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, vfs
AS $function$
    SELECT * FROM vfs.admin_read_file_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_administrator_id, p_target_user_id, p_node_id, p_event_id, p_at
    )
$function$;

-- name: vfs.admin_replace_file_as
CREATE FUNCTION vfs.admin_replace_file_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_node_id uuid,
    p_object_hash bytea,
    p_media_type text,
    p_content bytea,
    p_revision_id uuid,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF vfs.node
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store, vfs, audit
AS $function$
DECLARE
    actor uuid;
    target vfs.node%ROWTYPE;
    changed vfs.node%ROWTYPE;
    next_revision bigint;
BEGIN
    actor := vfs.require_admin_target(
        p_database_role, p_claim, p_administrator_id, p_target_user_id
    );
    IF p_object_hash IS DISTINCT FROM pg_catalog.sha256(p_content)
       OR p_media_type IS NULL OR btrim(p_media_type) = '' THEN
        RAISE EXCEPTION 'invalid administrator VFS content' USING ERRCODE = '22000';
    END IF;
    SELECT candidate.* INTO target FROM vfs.node AS candidate
    WHERE candidate.node_id = p_node_id AND candidate.owner_id = p_target_user_id
    FOR UPDATE;
    IF NOT FOUND OR target.node_type NOT IN ('FILE', 'SYMLINK') THEN
        RAISE EXCEPTION 'target VFS content node does not exist' USING ERRCODE = '22000';
    END IF;

    INSERT INTO object_store.object(
        object_hash, byte_size, media_type, content, created_by, created_at
    ) VALUES (
        p_object_hash, octet_length(p_content), p_media_type, p_content, actor, p_at
    ) ON CONFLICT (object_hash) DO NOTHING;
    IF NOT EXISTS (
        SELECT 1 FROM object_store.object
        WHERE object_hash = p_object_hash
          AND byte_size = octet_length(p_content) AND content = p_content
    ) THEN
        RAISE EXCEPTION 'existing object does not match supplied bytes' USING ERRCODE = '23505';
    END IF;

    UPDATE vfs.node
    SET current_object_hash = p_object_hash,
        state_version = state_version + 1,
        updated_at = p_at
    WHERE node_id = p_node_id AND owner_id = p_target_user_id
    RETURNING * INTO changed;

    IF target.revision_enabled THEN
        IF p_revision_id IS NULL THEN
            RAISE EXCEPTION 'versioned administrator write requires a revision identity'
                USING ERRCODE = '22000';
        END IF;
        SELECT COALESCE(max(revision_number), 0) + 1 INTO next_revision
        FROM vfs.file_revision WHERE node_id = p_node_id;
        INSERT INTO vfs.file_revision(
            revision_id, node_id, owner_id, revision_number,
            object_hash, created_by, created_at
        ) VALUES (
            p_revision_id, p_node_id, p_target_user_id, next_revision,
            p_object_hash, actor, p_at
        );
    END IF;

    INSERT INTO audit.event(
        event_id, owner_id, actor_type, actor_id, action,
        resource_type, resource_id, result, details_json, created_at
    ) VALUES (
        p_event_id, p_target_user_id, 'ADMINISTRATOR', actor::text,
        'vfs.admin.write', 'vfs.node', p_node_id::text, 'SUCCEEDED',
        jsonb_build_object('name', target.node_name,
                           'bytes', octet_length(p_content)::text,
                           'targetUserId', p_target_user_id::text), p_at
    );
    RETURN NEXT changed;
END
$function$;

-- name: vfs.admin_replace_file
CREATE FUNCTION vfs.admin_replace_file(
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_node_id uuid,
    p_object_hash bytea,
    p_media_type text,
    p_content bytea,
    p_revision_id uuid,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF vfs.node
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, vfs
AS $function$
    SELECT * FROM vfs.admin_replace_file_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_administrator_id, p_target_user_id, p_node_id, p_object_hash,
        p_media_type, p_content, p_revision_id, p_event_id, p_at
    )
$function$;

-- name: vfs.admin_create_directory_as
CREATE FUNCTION vfs.admin_create_directory_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_node_id uuid,
    p_parent_node_id uuid,
    p_node_name text,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF vfs.node
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, vfs, audit
AS $function$
DECLARE
    actor uuid;
    created vfs.node%ROWTYPE;
BEGIN
    actor := vfs.require_admin_target(
        p_database_role, p_claim, p_administrator_id, p_target_user_id
    );
    IF p_node_name IS NULL OR btrim(p_node_name) = ''
       OR p_node_name IN ('.', '..') OR position('/' IN p_node_name) <> 0
       OR NOT EXISTS (
           SELECT 1 FROM vfs.node
           WHERE node_id = p_parent_node_id AND owner_id = p_target_user_id
             AND node_type = 'DIRECTORY'
       ) THEN
        RAISE EXCEPTION 'invalid administrator VFS directory request' USING ERRCODE = '22000';
    END IF;
    INSERT INTO vfs.node(
        node_id, owner_id, parent_node_id, node_name, node_type,
        capability_json, revision_enabled, created_at, updated_at
    ) VALUES (
        p_node_id, p_target_user_id, p_parent_node_id, p_node_name, 'DIRECTORY',
        '[]'::jsonb, false, p_at, p_at
    ) RETURNING * INTO created;
    INSERT INTO audit.event(
        event_id, owner_id, actor_type, actor_id, action,
        resource_type, resource_id, result, details_json, created_at
    ) VALUES (
        p_event_id, p_target_user_id, 'ADMINISTRATOR', actor::text,
        'vfs.admin.directory.create', 'vfs.node', p_node_id::text, 'SUCCEEDED',
        jsonb_build_object('name', p_node_name,
                           'targetUserId', p_target_user_id::text), p_at
    );
    RETURN NEXT created;
END
$function$;

-- name: vfs.admin_create_directory
CREATE FUNCTION vfs.admin_create_directory(
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_node_id uuid,
    p_parent_node_id uuid,
    p_node_name text,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF vfs.node
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, vfs
AS $function$
    SELECT * FROM vfs.admin_create_directory_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_administrator_id, p_target_user_id, p_node_id,
        p_parent_node_id, p_node_name, p_event_id, p_at
    )
$function$;

-- name: vfs.admin_create_file_as
CREATE FUNCTION vfs.admin_create_file_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_node_id uuid,
    p_parent_node_id uuid,
    p_node_name text,
    p_object_hash bytea,
    p_media_type text,
    p_content bytea,
    p_revision_enabled boolean,
    p_revision_id uuid,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF vfs.node
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store, vfs, audit
AS $function$
DECLARE
    actor uuid;
    created vfs.node%ROWTYPE;
BEGIN
    actor := vfs.require_admin_target(
        p_database_role, p_claim, p_administrator_id, p_target_user_id
    );
    IF p_node_name IS NULL OR btrim(p_node_name) = ''
       OR p_node_name IN ('.', '..') OR position('/' IN p_node_name) <> 0
       OR p_object_hash IS DISTINCT FROM pg_catalog.sha256(p_content)
       OR p_media_type IS NULL OR btrim(p_media_type) = ''
       OR (p_revision_enabled AND p_revision_id IS NULL)
       OR NOT EXISTS (
           SELECT 1 FROM vfs.node
           WHERE node_id = p_parent_node_id AND owner_id = p_target_user_id
             AND node_type = 'DIRECTORY'
       ) THEN
        RAISE EXCEPTION 'invalid administrator VFS file request' USING ERRCODE = '22000';
    END IF;
    INSERT INTO object_store.object(
        object_hash, byte_size, media_type, content, created_by, created_at
    ) VALUES (
        p_object_hash, octet_length(p_content), p_media_type, p_content, actor, p_at
    ) ON CONFLICT (object_hash) DO NOTHING;
    IF NOT EXISTS (
        SELECT 1 FROM object_store.object
        WHERE object_hash = p_object_hash
          AND byte_size = octet_length(p_content) AND content = p_content
    ) THEN
        RAISE EXCEPTION 'existing object does not match supplied bytes' USING ERRCODE = '23505';
    END IF;
    INSERT INTO vfs.node(
        node_id, owner_id, parent_node_id, node_name, node_type,
        current_object_hash, capability_json, revision_enabled, created_at, updated_at
    ) VALUES (
        p_node_id, p_target_user_id, p_parent_node_id, p_node_name, 'FILE',
        p_object_hash, '[]'::jsonb, p_revision_enabled, p_at, p_at
    ) RETURNING * INTO created;
    IF p_revision_enabled THEN
        INSERT INTO vfs.file_revision(
            revision_id, node_id, owner_id, revision_number,
            object_hash, created_by, created_at
        ) VALUES (
            p_revision_id, p_node_id, p_target_user_id, 1,
            p_object_hash, actor, p_at
        );
    END IF;
    INSERT INTO audit.event(
        event_id, owner_id, actor_type, actor_id, action,
        resource_type, resource_id, result, details_json, created_at
    ) VALUES (
        p_event_id, p_target_user_id, 'ADMINISTRATOR', actor::text,
        'vfs.admin.file.create', 'vfs.node', p_node_id::text, 'SUCCEEDED',
        jsonb_build_object('name', p_node_name,
                           'bytes', octet_length(p_content)::text,
                           'targetUserId', p_target_user_id::text), p_at
    );
    RETURN NEXT created;
END
$function$;

-- name: vfs.admin_create_file
CREATE FUNCTION vfs.admin_create_file(
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_node_id uuid,
    p_parent_node_id uuid,
    p_node_name text,
    p_object_hash bytea,
    p_media_type text,
    p_content bytea,
    p_revision_enabled boolean,
    p_revision_id uuid,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF vfs.node
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, vfs
AS $function$
    SELECT * FROM vfs.admin_create_file_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_administrator_id, p_target_user_id, p_node_id, p_parent_node_id,
        p_node_name, p_object_hash, p_media_type, p_content,
        p_revision_enabled, p_revision_id, p_event_id, p_at
    )
$function$;

-- name: vfs.admin_rename_as
CREATE FUNCTION vfs.admin_rename_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_node_id uuid,
    p_node_name text,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF vfs.node
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, vfs, audit
AS $function$
DECLARE
    actor uuid;
    changed vfs.node%ROWTYPE;
BEGIN
    actor := vfs.require_admin_target(
        p_database_role, p_claim, p_administrator_id, p_target_user_id
    );
    IF p_node_name IS NULL OR btrim(p_node_name) = ''
       OR p_node_name IN ('.', '..') OR position('/' IN p_node_name) <> 0 THEN
        RAISE EXCEPTION 'invalid administrator VFS node name' USING ERRCODE = '22000';
    END IF;
    UPDATE vfs.node
    SET node_name = p_node_name, state_version = state_version + 1, updated_at = p_at
    WHERE node_id = p_node_id AND owner_id = p_target_user_id
      AND parent_node_id IS NOT NULL
    RETURNING * INTO changed;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'target VFS node does not exist or is a root' USING ERRCODE = '22000';
    END IF;
    INSERT INTO audit.event(
        event_id, owner_id, actor_type, actor_id, action,
        resource_type, resource_id, result, details_json, created_at
    ) VALUES (
        p_event_id, p_target_user_id, 'ADMINISTRATOR', actor::text,
        'vfs.admin.rename', 'vfs.node', p_node_id::text, 'SUCCEEDED',
        jsonb_build_object('name', p_node_name,
                           'targetUserId', p_target_user_id::text), p_at
    );
    RETURN NEXT changed;
END
$function$;

-- name: vfs.admin_rename
CREATE FUNCTION vfs.admin_rename(
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_node_id uuid,
    p_node_name text,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS SETOF vfs.node
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, vfs
AS $function$
    SELECT * FROM vfs.admin_rename_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_administrator_id, p_target_user_id, p_node_id,
        p_node_name, p_event_id, p_at
    )
$function$;

-- name: vfs.admin_delete_as
CREATE FUNCTION vfs.admin_delete_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_node_id uuid,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, vfs, audit
AS $function$
DECLARE
    actor uuid;
    target vfs.node%ROWTYPE;
BEGIN
    actor := vfs.require_admin_target(
        p_database_role, p_claim, p_administrator_id, p_target_user_id
    );
    SELECT candidate.* INTO target FROM vfs.node AS candidate
    WHERE candidate.node_id = p_node_id AND candidate.owner_id = p_target_user_id
    FOR UPDATE;
    IF NOT FOUND OR target.parent_node_id IS NULL OR target.node_type = 'MOUNT'
       OR EXISTS (SELECT 1 FROM vfs.node WHERE parent_node_id = p_node_id)
       OR EXISTS (SELECT 1 FROM vfs.file_revision WHERE node_id = p_node_id) THEN
        RAISE EXCEPTION 'target is a root, mount, non-empty directory, or versioned file'
            USING ERRCODE = '22000';
    END IF;
    DELETE FROM vfs.node WHERE node_id = p_node_id AND owner_id = p_target_user_id;
    INSERT INTO audit.event(
        event_id, owner_id, actor_type, actor_id, action,
        resource_type, resource_id, result, details_json, created_at
    ) VALUES (
        p_event_id, p_target_user_id, 'ADMINISTRATOR', actor::text,
        'vfs.admin.delete', 'vfs.node', p_node_id::text, 'SUCCEEDED',
        jsonb_build_object('name', target.node_name,
                           'targetUserId', p_target_user_id::text), p_at
    );
    RETURN true;
END
$function$;

-- name: vfs.admin_delete
CREATE FUNCTION vfs.admin_delete(
    p_administrator_id uuid,
    p_target_user_id uuid,
    p_node_id uuid,
    p_event_id uuid,
    p_at timestamptz
)
RETURNS boolean
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, vfs
AS $function$
    SELECT vfs.admin_delete_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_administrator_id, p_target_user_id, p_node_id, p_event_id, p_at
    )
$function$;

REVOKE ALL ON FUNCTION vfs.admin_list_nodes_as(
    name, text, uuid, uuid, uuid, timestamptz
) FROM PUBLIC;
REVOKE ALL ON FUNCTION vfs.admin_read_file_as(
    name, text, uuid, uuid, uuid, uuid, timestamptz
) FROM PUBLIC;
REVOKE ALL ON FUNCTION vfs.admin_replace_file_as(
    name, text, uuid, uuid, uuid, bytea, text, bytea, uuid, uuid, timestamptz
) FROM PUBLIC;
REVOKE ALL ON FUNCTION vfs.admin_create_directory_as(
    name, text, uuid, uuid, uuid, uuid, text, uuid, timestamptz
) FROM PUBLIC;
REVOKE ALL ON FUNCTION vfs.admin_create_file_as(
    name, text, uuid, uuid, uuid, uuid, text, bytea, text, bytea,
    boolean, uuid, uuid, timestamptz
) FROM PUBLIC;
REVOKE ALL ON FUNCTION vfs.admin_rename_as(
    name, text, uuid, uuid, uuid, text, uuid, timestamptz
) FROM PUBLIC;
REVOKE ALL ON FUNCTION vfs.admin_delete_as(
    name, text, uuid, uuid, uuid, uuid, timestamptz
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION vfs.admin_list_nodes(uuid, uuid, uuid, timestamptz) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_read_file(uuid, uuid, uuid, uuid, timestamptz) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_replace_file(
    uuid, uuid, uuid, bytea, text, bytea, uuid, uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_create_directory(
    uuid, uuid, uuid, uuid, text, uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_create_file(
    uuid, uuid, uuid, uuid, text, bytea, text, bytea,
    boolean, uuid, uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_rename(
    uuid, uuid, uuid, text, uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_delete(uuid, uuid, uuid, uuid, timestamptz) TO PUBLIC;

-- SECURITY INVOKER wrappers preserve the real current_user. PostgreSQL checks
-- nested function EXECUTE privileges as that invoker, so the verified *_as
-- entry points must also be executable. They are safe for PUBLIC because every
-- one binds p_database_role/p_claim through resolve_cilexec_user_id and then
-- requires the matching SYSTEM_ADMIN identity before touching protected rows.
GRANT EXECUTE ON FUNCTION auth.require_system_administrator_as(name, text, uuid) TO PUBLIC;
GRANT EXECUTE ON FUNCTION auth.admin_list_users_as(name, text, uuid) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.require_admin_target(name, text, uuid, uuid) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_list_nodes_as(
    name, text, uuid, uuid, uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_read_file_as(
    name, text, uuid, uuid, uuid, uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_replace_file_as(
    name, text, uuid, uuid, uuid, bytea, text, bytea, uuid, uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_create_directory_as(
    name, text, uuid, uuid, uuid, uuid, text, uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_create_file_as(
    name, text, uuid, uuid, uuid, uuid, text, bytea, text, bytea,
    boolean, uuid, uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_rename_as(
    name, text, uuid, uuid, uuid, text, uuid, timestamptz
) TO PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.admin_delete_as(
    name, text, uuid, uuid, uuid, uuid, timestamptz
) TO PUBLIC;

SELECT meta.assert_security_invariants();

RESET ROLE;
