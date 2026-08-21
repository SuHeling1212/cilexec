package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V003 / CilExec 0.0.3: durable FCLB executable artifacts and explicit package-data clearing.
 *
 * <p>V003 changes the Program write format from V002's JSON source envelope to the versioned
 * {@code FCLB} instruction artifact. V002 artifacts and continuations remain readable; newly
 * compiled Programs are V003. It deliberately adds no time-based data retention: persistent
 * user data is removed only by an explicit user operation.
 */
public final class V003 extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("SET ROLE cilexec_owner");
            statement.execute("""
                    CREATE FUNCTION package.data_clear_path_as(
                        p_database_role name,
                        p_claim text,
                        p_file_sha256 bytea,
                        p_path text
                    )
                    RETURNS jsonb
                    LANGUAGE plpgsql
                    SECURITY DEFINER
                    SET search_path = pg_catalog, auth, package
                    AS $function$
                    DECLARE
                        actor uuid;
                        stage text := 'authorization';
                        space package.data_space%ROWTYPE;
                        removed_entries integer;
                        freed_bytes bigint;
                    BEGIN
                        actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
                        IF actor IS NULL THEN
                            RAISE EXCEPTION 'a verified CilExec user identity is required'
                                USING ERRCODE = '42501';
                        END IF;
                        IF NOT EXISTS (
                            SELECT 1 FROM auth.effective_capabilities_as(
                                p_database_role, p_claim, actor) AS capability
                            WHERE capability.capability_key = 'package_bind'
                        ) THEN
                            RAISE EXCEPTION 'package_bind capability is required'
                                USING ERRCODE = '42501';
                        END IF;
                        IF p_path IS NULL OR p_path = '' OR p_path = '.'
                           OR left(p_path, 1) = '/' OR right(p_path, 1) = '/'
                           OR position(chr(92) IN p_path) <> 0 OR p_path ~ '[[:cntrl:]]'
                           OR p_path = '..' OR p_path LIKE '../%' OR p_path LIKE '%/../%'
                           OR p_path LIKE '%/..' OR p_path LIKE './%' OR p_path LIKE '%/./%'
                           OR p_path LIKE '%/.'
                           OR char_length(p_path) > 1024 THEN
                            RAISE EXCEPTION 'invalid package data path' USING ERRCODE = '22000';
                        END IF;
                        SELECT * INTO space
                        FROM package.data_space
                        WHERE owner_id = actor AND database_file_hash = p_file_sha256
                        FOR UPDATE;
                        IF NOT FOUND THEN
                            RAISE EXCEPTION 'package data space is missing; the package is not installed'
                                USING ERRCODE = '55006';
                        END IF;
                        IF NOT EXISTS (
                            SELECT 1 FROM package.data_entry
                            WHERE space_id = space.space_id
                              AND relative_path = p_path
                              AND entry_type = 'DIRECTORY'
                        ) THEN
                            RAISE EXCEPTION 'package data path is not a directory' USING ERRCODE = '55006';
                        END IF;
                        SELECT COALESCE(sum(byte_size), 0) INTO freed_bytes
                        FROM package.data_entry
                        WHERE space_id = space.space_id
                          AND relative_path LIKE p_path || '/%';
                        DELETE FROM package.data_entry
                        WHERE space_id = space.space_id
                          AND relative_path LIKE p_path || '/%';
                        GET DIAGNOSTICS removed_entries = ROW_COUNT;
                        UPDATE package.data_space
                        SET logical_bytes = GREATEST(0, logical_bytes - freed_bytes),
                            updated_at = clock_timestamp()
                        WHERE space_id = space.space_id;
                        RETURN jsonb_build_object('entriesRemoved', removed_entries,
                            'freedBytes', freed_bytes);
                    END
                    $function$
                    """);
            statement.execute("REVOKE ALL ON FUNCTION package.data_clear_path_as(name, text, bytea, text) FROM PUBLIC");
            statement.execute("""
                    INSERT INTO meta.security_definer_public_allowlist(function_signature, rationale)
                    VALUES (
                        'package.data_clear_path_as(name,text,bytea,text)'::regprocedure::text,
                        'verified package-owned private-directory clearing'
                    )
                    """);
            statement.execute("GRANT EXECUTE ON FUNCTION package.data_clear_path_as(name, text, bytea, text) TO PUBLIC");
            statement.execute("""
                    CREATE FUNCTION package.data_clear_path(p_file_sha256 bytea, p_path text)
                    RETURNS jsonb
                    LANGUAGE sql
                    SECURITY INVOKER
                    SET search_path = pg_catalog, package
                    AS $function$
                        SELECT package.data_clear_path_as(
                            current_user::name,
                            NULLIF(current_setting('app.cilexec_user_id', true), ''),
                            p_file_sha256,
                            p_path
                        )
                    $function$
                    """);
            statement.execute("REVOKE ALL ON FUNCTION package.data_clear_path(bytea, text) FROM PUBLIC");
            statement.execute("GRANT EXECUTE ON FUNCTION package.data_clear_path(bytea, text) TO PUBLIC");
            statement.execute("""
                    CREATE FUNCTION auth.admin_remove_user_as(
                        p_database_role name,
                        p_claim text,
                        p_administrator_id uuid,
                        p_user_id uuid,
                        p_event_id uuid,
                        p_at timestamptz
                    )
                    RETURNS boolean
                    LANGUAGE plpgsql
                    SECURITY DEFINER
                    SET search_path = pg_catalog, auth, audit, effect, ipc, package, process,
                        program, scheduler, terminal, vfs
                    AS $function$
                    DECLARE
                        actor uuid;
                        stage text := 'authorization';
                    BEGIN
                        actor := auth.require_system_administrator_as(
                            p_database_role, p_claim, p_administrator_id);
                        IF p_user_id IS NULL OR p_event_id IS NULL OR p_at IS NULL
                           OR p_user_id = actor THEN
                            RAISE EXCEPTION 'an administrator cannot remove its active identity'
                                USING ERRCODE = '22000';
                        END IF;
                        IF NOT EXISTS (SELECT 1 FROM auth.user_account WHERE user_id = p_user_id) THEN
                            RAISE EXCEPTION 'target user does not exist' USING ERRCODE = '22000';
                        END IF;

                        -- This operation is the one deliberate exception to append-only history:
                        -- it is an explicit full-account erase, never a background retention task.
                        stage := 'disable principal';
                        PERFORM set_config('app.cilexec_gc', 'on', true);
                        PERFORM auth.admin_disable_user_as(
                            p_database_role, p_claim, actor, p_user_id, p_event_id, p_at);
                        stage := 'transfer grants';
                        UPDATE auth.group_member SET granted_by = actor WHERE granted_by = p_user_id;
                        UPDATE auth.user_capability SET granted_by = actor WHERE granted_by = p_user_id;
                        UPDATE auth.group_capability SET granted_by = actor WHERE granted_by = p_user_id;

                        stage := 'terminal data';
                        DELETE FROM terminal.attachment WHERE owner_id = p_user_id;
                        DELETE FROM terminal.input WHERE owner_id = p_user_id;
                        DELETE FROM terminal.session WHERE owner_id = p_user_id;
                        DELETE FROM terminal.command_history WHERE owner_id = p_user_id;
                        stage := 'effects';
                        DELETE FROM effect.effect WHERE owner_id = p_user_id;
                        stage := 'IPC';
                        DELETE FROM ipc.delivery WHERE owner_id = p_user_id;
                        DELETE FROM ipc.message WHERE owner_id = p_user_id;
                        DELETE FROM ipc.subscription WHERE owner_id = p_user_id;
                        DELETE FROM ipc.channel WHERE owner_id = p_user_id;
                        DELETE FROM ipc.topic WHERE owner_id = p_user_id;
                        DELETE FROM ipc.swap_pool WHERE owner_id = p_user_id;
                        stage := 'processes';
                        DELETE FROM vfs.node_lock WHERE owner_id = p_user_id;
                        DELETE FROM scheduler.lease
                        WHERE process_uid IN (SELECT process_uid FROM process.process WHERE owner_id = p_user_id);
                        DELETE FROM scheduler.queue WHERE owner_id = p_user_id;
                        DELETE FROM process.timer WHERE owner_id = p_user_id;
                        DELETE FROM process.process WHERE owner_id = p_user_id;

                        stage := 'packages and VFS';
                        DELETE FROM package.managed_node WHERE owner_id = p_user_id;
                        DELETE FROM package.data_quota_override WHERE owner_id = p_user_id;
                        DELETE FROM package.data_space WHERE owner_id = p_user_id;
                        DELETE FROM package.installation_root WHERE owner_id = p_user_id;
                        DELETE FROM vfs.file_revision WHERE owner_id = p_user_id;
                        UPDATE vfs.node SET symlink_target_node_id = NULL WHERE owner_id = p_user_id;
                        DELETE FROM vfs.node WHERE owner_id = p_user_id;

                        stage := 'programs and account';
                        DELETE FROM program.module_binding WHERE owner_id = p_user_id;
                        DELETE FROM program.statement WHERE owner_id = p_user_id;
                        DELETE FROM program.program WHERE owner_id = p_user_id;
                        DELETE FROM auth.environment_variable WHERE owner_id = p_user_id;
                        DELETE FROM auth.group_account WHERE owner_id = p_user_id;
                        DELETE FROM audit.event WHERE owner_id = p_user_id;
                        DELETE FROM auth.user_account WHERE user_id = p_user_id;

                        stage := 'audit';
                        INSERT INTO audit.event(
                            event_id, owner_id, actor_type, actor_id, action, resource_type,
                            resource_id, result, details_json, created_at
                        ) VALUES (
                            p_event_id, NULL, 'ADMINISTRATOR', actor::text, 'auth.user.remove',
                            'auth.user', p_user_id::text, 'SUCCEEDED', '{}'::jsonb, p_at
                        );
                        RETURN true;
                    EXCEPTION WHEN OTHERS THEN
                        RAISE EXCEPTION 'user removal failed during %: %', stage, SQLERRM
                            USING ERRCODE = SQLSTATE;
                    END
                    $function$
                    """);
            statement.execute("""
                    CREATE FUNCTION auth.admin_remove_user(
                        p_administrator_id uuid, p_user_id uuid, p_event_id uuid, p_at timestamptz
                    ) RETURNS boolean LANGUAGE sql SECURITY INVOKER
                    SET search_path = pg_catalog, auth
                    AS $function$
                        SELECT auth.admin_remove_user_as(
                            current_user::name,
                            NULLIF(current_setting('app.cilexec_user_id', true), ''),
                            p_administrator_id, p_user_id, p_event_id, p_at
                        )
                    $function$
                    """);
            statement.execute("REVOKE ALL ON FUNCTION auth.admin_remove_user_as(name, text, uuid, uuid, uuid, timestamptz) FROM PUBLIC");
            statement.execute("REVOKE ALL ON FUNCTION auth.admin_remove_user(uuid, uuid, uuid, timestamptz) FROM PUBLIC");
            statement.execute("""
                    INSERT INTO meta.security_definer_public_allowlist(function_signature, rationale)
                    VALUES (
                        'auth.admin_remove_user_as(name,text,uuid,uuid,uuid,timestamp with time zone)'::regprocedure::text,
                        'verified explicit administrator account removal'
                    )
                    """);
            statement.execute("GRANT EXECUTE ON FUNCTION auth.admin_remove_user_as(name, text, uuid, uuid, uuid, timestamptz) TO PUBLIC");
            statement.execute("GRANT EXECUTE ON FUNCTION auth.admin_remove_user(uuid, uuid, uuid, timestamptz) TO PUBLIC");
            statement.execute("RESET ROLE");
        }
    }
}
