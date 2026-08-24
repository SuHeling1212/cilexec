package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V003 / CilExec 0.0.3: durable FCLB executable artifacts and explicit resource deletion.
 *
 * <p>V003 changes the Program write format from V002's JSON source envelope to the versioned
 * {@code FCLB} instruction artifact. V002 artifacts and continuations remain readable; newly
 * compiled Programs are V003. It deliberately adds no time-based data retention: persistent
 * user data and history are removed only by explicit user operations.
 */
public final class V003 extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            // This function must remain owned by cilexec_migrator: only that internal role
            // has CREATEROLE, and the ordinary disable path must stay reversible.  The
            // removal-only GUC is set solely by auth.admin_remove_user_as below.
            statement.execute("""
                    CREATE OR REPLACE FUNCTION auth.disable_principal(p_user_id uuid)
                    RETURNS name
                    LANGUAGE plpgsql
                    SECURITY DEFINER
                    SET search_path = pg_catalog, auth
                    AS $function$
                    DECLARE
                        mapped_role name;
                        schema_name name;
                    BEGIN
                        SELECT postgres_role_name INTO STRICT mapped_role
                        FROM auth.user_account
                        WHERE user_id = p_user_id;
                        IF mapped_role::text <> 'cilexec_user_' || replace(p_user_id::text, '-', '') THEN
                            RAISE EXCEPTION 'invalid stable PostgreSQL role mapping for user %', p_user_id;
                        END IF;
                        IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = mapped_role) THEN
                            EXECUTE format('ALTER ROLE %I NOLOGIN', mapped_role);
                            IF current_setting('app.cilexec_remove_principal', true) = 'on' THEN
                                EXECUTE format('REVOKE %I FROM cilexec_runtime', mapped_role);
                                FOREACH schema_name IN ARRAY ARRAY[
                                    'meta', 'auth', 'object_store', 'vfs', 'program', 'process',
                                    'scheduler', 'ipc', 'effect', 'package', 'terminal', 'audit',
                                    'diagnostic'
                                ]::name[] LOOP
                                    EXECUTE format('REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA %I FROM %I',
                                        schema_name, mapped_role);
                                    EXECUTE format('REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA %I FROM %I',
                                        schema_name, mapped_role);
                                    EXECUTE format('REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA %I FROM %I',
                                        schema_name, mapped_role);
                                    EXECUTE format('REVOKE ALL PRIVILEGES ON SCHEMA %I FROM %I',
                                        schema_name, mapped_role);
                                END LOOP;
                                EXECUTE format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM %I',
                                    current_database(), mapped_role);
                                EXECUTE format('DROP ROLE %I', mapped_role);
                            END IF;
                        END IF;
                        DELETE FROM auth.user_credential WHERE user_id = p_user_id;
                        RETURN mapped_role;
                    END
                    $function$
                    """);
            statement.execute("SET ROLE cilexec_owner");
            // A volatile calculation has no durable process state of its own, but a durable
            // caller may persist a minimal wait marker while it receives the in-memory result.
            statement.execute("ALTER TABLE process.wait_state "
                    + "DROP CONSTRAINT wait_state_wait_kind_check");
            statement.execute("ALTER TABLE process.wait_state "
                    + "ADD CONSTRAINT wait_state_wait_kind_check "
                    + "CHECK (wait_kind IN ('IPC', 'TIMER', 'EFFECT', 'VOLATILE', "
                    + "'INPUT', 'CHILD', 'PROCESS'))");
            // V001 contains a now-retired automatic retention mechanism.  V003 uses only
            // explicit audit.purge calls, so remove both its callable entry point and state.
            statement.execute("DROP FUNCTION audit.purge_expired_events(integer)");
            statement.execute("DROP TABLE audit.retention_policy");
            statement.execute("DELETE FROM meta.table_security_classification "
                    + "WHERE schema_name = 'audit'::name AND table_name = 'retention_policy'::name");
            statement.execute("""
                    CREATE OR REPLACE FUNCTION package.data_list_as(
                        p_database_role name, p_claim text, p_file_sha256 bytea, p_path text
                    ) RETURNS jsonb
                    LANGUAGE plpgsql STABLE SECURITY DEFINER
                    SET search_path = pg_catalog, auth, package
                    AS $function$
                    DECLARE
                        actor uuid;
                        space package.data_space%ROWTYPE;
                        prefix text;
                    BEGIN
                        actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
                        IF actor IS NULL THEN
                            RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
                        END IF;
                        SELECT * INTO space FROM package.data_space
                        WHERE owner_id = actor AND database_file_hash = p_file_sha256;
                        IF NOT FOUND THEN
                            RAISE EXCEPTION 'package data space is missing; the package is not installed'
                                USING ERRCODE = '55006';
                        END IF;
                        IF p_path IS NULL OR p_path = '' OR p_path = '.' THEN
                            prefix := '';
                        ELSE
                            IF left(p_path, 1) = '/' OR right(p_path, 1) = '/'
                               OR p_path ~ '(^|/)(\\.|\\.\\.)(/|$)'
                               OR position(chr(92) IN p_path) <> 0 THEN
                                RAISE EXCEPTION 'invalid package data path' USING ERRCODE = '22000';
                            END IF;
                            prefix := p_path || '/';
                        END IF;
                        RETURN (
                            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                                'name', CASE WHEN prefix = '' THEN entry.relative_path
                                             ELSE substr(entry.relative_path, char_length(prefix) + 1) END,
                                'type', entry.entry_type, 'size', entry.byte_size,
                                'version', entry.state_version
                            ) ORDER BY entry.relative_path), '[]'::jsonb)
                            FROM package.data_entry AS entry
                            WHERE entry.space_id = space.space_id
                              AND (prefix = '' OR left(entry.relative_path, char_length(prefix)) = prefix)
                              AND CASE WHEN prefix = '' THEN strpos(entry.relative_path, '/') = 0
                                       ELSE strpos(substr(entry.relative_path, char_length(prefix) + 1), '/') = 0
                                  END
                        );
                    END
                    $function$
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION package.data_remove_as(
                        p_database_role name, p_claim text, p_file_sha256 bytea, p_path text
                    ) RETURNS jsonb
                    LANGUAGE plpgsql SECURITY DEFINER
                    SET search_path = pg_catalog, auth, package
                    AS $function$
                    DECLARE
                        actor uuid;
                        space package.data_space%ROWTYPE;
                        existing package.data_entry%ROWTYPE;
                        children integer;
                        freed bigint;
                    BEGIN
                        actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
                        IF actor IS NULL THEN
                            RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
                        END IF;
                        IF NOT EXISTS (
                            SELECT 1 FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
                            WHERE capability.capability_key = 'package_bind'
                        ) THEN
                            RAISE EXCEPTION 'package_bind capability is required' USING ERRCODE = '42501';
                        END IF;
                        SELECT * INTO space FROM package.data_space
                        WHERE owner_id = actor AND database_file_hash = p_file_sha256 FOR UPDATE;
                        IF NOT FOUND THEN
                            RAISE EXCEPTION 'package data space is missing; the package is not installed'
                                USING ERRCODE = '55006';
                        END IF;
                        SELECT * INTO existing FROM package.data_entry
                        WHERE space_id = space.space_id AND relative_path = p_path FOR UPDATE;
                        IF NOT FOUND THEN
                            RETURN jsonb_build_object('removed', false);
                        END IF;
                        SELECT count(*) INTO children FROM package.data_entry AS entry
                        WHERE entry.space_id = space.space_id
                          AND left(entry.relative_path, char_length(p_path) + 1) = p_path || '/';
                        IF children > 0 THEN
                            RAISE EXCEPTION 'package data path is not empty' USING ERRCODE = '55006';
                        END IF;
                        freed := existing.byte_size;
                        DELETE FROM package.data_entry
                        WHERE space_id = space.space_id AND relative_path = p_path;
                        UPDATE package.data_space
                        SET logical_bytes = GREATEST(0, logical_bytes - freed), updated_at = clock_timestamp()
                        WHERE space_id = space.space_id;
                        RETURN jsonb_build_object('removed', true, 'freed', freed);
                    END
                    $function$
                    """);
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
                          AND left(relative_path, char_length(p_path) + 1) = p_path || '/';
                        DELETE FROM package.data_entry
                        WHERE space_id = space.space_id
                          AND left(relative_path, char_length(p_path) + 1) = p_path || '/';
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
                        PERFORM set_config('app.cilexec_remove_principal', 'on', true);
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
            statement.execute("""
                    CREATE FUNCTION program.admin_remove_program_as(
                        p_database_role name,
                        p_claim text,
                        p_administrator_id uuid,
                        p_program_id uuid,
                        p_event_id uuid,
                        p_at timestamptz
                    )
                    RETURNS jsonb
                    LANGUAGE plpgsql
                    SECURITY DEFINER
                    SET search_path = pg_catalog, auth, audit, process, program
                    AS $function$
                    DECLARE
                        actor uuid;
                        blocking_processes jsonb;
                        imported_by jsonb;
                        process_count integer;
                        importer_count integer;
                    BEGIN
                        actor := auth.require_system_administrator_as(
                            p_database_role, p_claim, p_administrator_id);
                        IF p_program_id IS NULL THEN
                            RAISE EXCEPTION 'a program identity is required'
                                USING ERRCODE = '22000';
                        END IF;
                        IF NOT EXISTS (
                            SELECT 1 FROM program.program WHERE program_id = p_program_id
                        ) THEN
                            RAISE EXCEPTION 'program does not exist' USING ERRCODE = '22000';
                        END IF;
                        SELECT COALESCE(jsonb_agg(jsonb_build_object(
                                       'pid', listed.pid, 'status', listed.status) ORDER BY listed.pid),
                               '[]'::jsonb)
                        INTO blocking_processes
                        FROM (SELECT pid, status FROM process.process
                              WHERE program_id = p_program_id
                              ORDER BY pid LIMIT 25) AS listed;
                        SELECT count(*) INTO process_count
                        FROM process.process WHERE program_id = p_program_id;
                        SELECT COALESCE(jsonb_agg(DISTINCT listed.importer ORDER BY listed.importer),
                               '[]'::jsonb)
                        INTO imported_by
                        FROM (SELECT importer.program_id::text AS importer
                              FROM program.module_binding importer
                              WHERE importer.module_program_id = p_program_id
                              LIMIT 25) AS listed;
                        SELECT count(DISTINCT importer.program_id) INTO importer_count
                        FROM program.module_binding importer
                        WHERE importer.module_program_id = p_program_id;
                        IF process_count > 0 OR importer_count > 0 THEN
                            RETURN jsonb_build_object('removed', false,
                                'processCount', process_count,
                                'importedByCount', importer_count,
                                'processes', blocking_processes,
                                'importedBy', imported_by);
                        END IF;
                        -- Program rows are append-only; explicit administrator removal is the
                        -- one deliberate exception and never runs as a background task.
                        PERFORM set_config('app.cilexec_gc', 'on', true);
                        DELETE FROM program.module_binding WHERE program_id = p_program_id;
                        DELETE FROM program.statement WHERE program_id = p_program_id;
                        DELETE FROM program.program WHERE program_id = p_program_id;
                        INSERT INTO audit.event(
                            event_id, owner_id, actor_type, actor_id, action, resource_type,
                            resource_id, result, details_json, created_at
                        ) VALUES (
                            p_event_id, NULL, 'ADMINISTRATOR', actor::text, 'program.remove',
                            'program', p_program_id::text, 'SUCCEEDED', '{}'::jsonb, p_at
                        );
                        RETURN jsonb_build_object('removed', true,
                            'processCount', 0, 'importedByCount', 0,
                            'processes', '[]'::jsonb, 'importedBy', '[]'::jsonb);
                    EXCEPTION WHEN OTHERS THEN
                        RAISE EXCEPTION 'program removal failed: %', SQLERRM
                            USING ERRCODE = SQLSTATE;
                    END
                    $function$
                    """);
            statement.execute("""
                    CREATE FUNCTION program.admin_remove_program(
                        p_administrator_id uuid, p_program_id uuid, p_event_id uuid, p_at timestamptz
                    ) RETURNS jsonb
                    LANGUAGE sql SECURITY INVOKER
                    SET search_path = pg_catalog, program
                    AS $function$
                        SELECT program.admin_remove_program_as(
                            current_user::name,
                            NULLIF(current_setting('app.cilexec_user_id', true), ''),
                            p_administrator_id, p_program_id, p_event_id, p_at
                        )
                    $function$
                    """);
            statement.execute("""
                    CREATE FUNCTION audit.admin_purge_before_as(
                        p_database_role name,
                        p_claim text,
                        p_administrator_id uuid,
                        p_before timestamptz,
                        p_limit integer
                    )
                    RETURNS integer
                    LANGUAGE plpgsql
                    SECURITY DEFINER
                    SET search_path = pg_catalog, auth, audit
                    AS $function$
                    DECLARE
                        deleted_count integer;
                    BEGIN
                        PERFORM auth.require_system_administrator_as(
                            p_database_role, p_claim, p_administrator_id);
                        IF p_before IS NULL OR p_before >= clock_timestamp() THEN
                            RAISE EXCEPTION 'audit purge requires a cutoff strictly in the past'
                                USING ERRCODE = '22000';
                        END IF;
                        IF p_limit IS NOT NULL AND (p_limit < 1 OR p_limit > 100000) THEN
                            RAISE EXCEPTION 'audit purge limit must be between 1 and 100000'
                                USING ERRCODE = '22023';
                        END IF;
                        PERFORM set_config('app.cilexec_gc', 'on', true);
                        WITH doomed AS MATERIALIZED (
                            SELECT candidate.event_id
                            FROM audit.event AS candidate
                            WHERE candidate.created_at < p_before
                            ORDER BY candidate.created_at, candidate.event_id
                            LIMIT p_limit
                            FOR UPDATE OF candidate SKIP LOCKED
                        )
                        DELETE FROM audit.event AS target
                        USING doomed
                        WHERE target.event_id = doomed.event_id;
                        GET DIAGNOSTICS deleted_count = ROW_COUNT;
                        RETURN deleted_count;
                    END
                    $function$
                    """);
            statement.execute("""
                    CREATE FUNCTION audit.admin_purge_before(
                        p_administrator_id uuid, p_before timestamptz, p_limit integer
                    ) RETURNS integer
                    LANGUAGE sql SECURITY INVOKER
                    SET search_path = pg_catalog, audit
                    AS $function$
                        SELECT audit.admin_purge_before_as(
                            current_user::name,
                            NULLIF(current_setting('app.cilexec_user_id', true), ''),
                            p_administrator_id, p_before, p_limit
                        )
                    $function$
                    """);
            statement.execute("REVOKE ALL ON FUNCTION program.admin_remove_program_as(name, text, uuid, uuid, uuid, timestamp with time zone) FROM PUBLIC");
            statement.execute("REVOKE ALL ON FUNCTION program.admin_remove_program(uuid, uuid, uuid, timestamp with time zone) FROM PUBLIC");
            statement.execute("""
                    INSERT INTO meta.security_definer_public_allowlist(function_signature, rationale)
                    VALUES (
                        'program.admin_remove_program_as(name,text,uuid,uuid,uuid,timestamp with time zone)'::regprocedure::text,
                        'verified explicit administrator removal of an unreachable program'
                    ),
                    (
                        'audit.admin_purge_before_as(name,text,uuid,timestamp with time zone,integer)'::regprocedure::text,
                        'verified explicit administrator audit history purge'
                    )
                    """);
            statement.execute("GRANT EXECUTE ON FUNCTION program.admin_remove_program_as(name, text, uuid, uuid, uuid, timestamp with time zone) TO PUBLIC");
            statement.execute("GRANT EXECUTE ON FUNCTION program.admin_remove_program(uuid, uuid, uuid, timestamp with time zone) TO PUBLIC");
            statement.execute("GRANT EXECUTE ON FUNCTION audit.admin_purge_before_as(name, text, uuid, timestamp with time zone, integer) TO PUBLIC");
            statement.execute("GRANT EXECUTE ON FUNCTION audit.admin_purge_before(uuid, timestamp with time zone, integer) TO PUBLIC");
            statement.execute("RESET ROLE");
        }
    }
}
