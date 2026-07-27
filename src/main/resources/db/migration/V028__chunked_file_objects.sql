SET ROLE cilexec_owner;

-- A VFS file may point at a small immutable manifest whose tail objects are bounded
-- bytea values. The linked manifest chain lets append remain O(1) in memory and
-- allows total logical size to grow up to bigint without one PostgreSQL/JVM value
-- containing the whole file.
CREATE TABLE object_store.chunk_manifest (
    manifest_hash bytea PRIMARY KEY REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    previous_manifest_hash bytea REFERENCES object_store.chunk_manifest(manifest_hash) ON DELETE RESTRICT,
    base_object_hash bytea REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    tail_object_hash bytea NOT NULL REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    total_size bigint NOT NULL CHECK (total_size >= 0),
    tail_size integer NOT NULL CHECK (tail_size >= 0),
    CHECK ((previous_manifest_hash IS NULL) <> (base_object_hash IS NULL))
);

CREATE TRIGGER chunk_manifest_reject_update_delete
BEFORE UPDATE OR DELETE ON object_store.chunk_manifest
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();

INSERT INTO meta.table_security_classification
    (schema_name, table_name, classification, owner_column, rationale)
VALUES ('object_store', 'chunk_manifest', 'SHARED_IMMUTABLE', NULL,
        'content-addressed append chain whose rows cannot be updated or deleted');

-- Administrators use the same object API as ordinary users. Identity is checked
-- here instead of exposing a second administrator-named FCL function.
CREATE OR REPLACE FUNCTION object_store.read_object_as(
    p_database_role name,
    p_claim text,
    p_object_hash bytea
)
RETURNS TABLE (
    object_hash bytea,
    byte_size bigint,
    media_type text,
    content bytea,
    created_at timestamptz
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store, vfs, program, package, process
AS $function$
    SELECT stored.object_hash, stored.byte_size, stored.media_type, stored.content, stored.created_at
    FROM object_store.object AS stored
    WHERE stored.object_hash = p_object_hash
      AND (
          (p_database_role::text IN ('cilexec_runtime', 'cilexec_owner', 'cilexec_migrator')
           AND p_database_role = CASE
               WHEN NULLIF(current_setting('role', true), 'none') IS NULL
                   THEN session_user::name
               ELSE current_setting('role', true)::name
           END)
          OR auth.is_system_administrator_as(p_database_role, p_claim)
          OR stored.created_by = auth.resolve_cilexec_user_id(p_database_role, p_claim)
          OR EXISTS (SELECT 1 FROM vfs.node AS node
                     WHERE node.current_object_hash = stored.object_hash
                       AND node.owner_id = auth.resolve_cilexec_user_id(p_database_role, p_claim))
          OR EXISTS (SELECT 1 FROM vfs.file_revision AS revision
                     WHERE revision.object_hash = stored.object_hash
                       AND revision.owner_id = auth.resolve_cilexec_user_id(p_database_role, p_claim))
          OR EXISTS (SELECT 1 FROM program.program AS source_program
                     WHERE (source_program.source_object_hash = stored.object_hash
                            OR source_program.compiled_object_hash = stored.object_hash)
                       AND source_program.owner_id = auth.resolve_cilexec_user_id(p_database_role, p_claim))
          OR EXISTS (SELECT 1 FROM package.release AS release
                     JOIN package.binding AS binding ON binding.package_hash = release.package_hash
                     WHERE release.database_object_hash = stored.object_hash
                       AND binding.owner_id = auth.resolve_cilexec_user_id(p_database_role, p_claim))
          OR EXISTS (SELECT 1 FROM package.release AS release
                     JOIN process.package_binding AS binding ON binding.package_hash = release.package_hash
                     WHERE release.database_object_hash = stored.object_hash
                       AND binding.owner_id = auth.resolve_cilexec_user_id(p_database_role, p_claim))
      )
$function$;

CREATE FUNCTION object_store.append_chunk_manifest_as(
    p_database_role name,
    p_claim text,
    p_manifest_hash bytea,
    p_current_hash bytea,
    p_tail_hash bytea
)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store
AS $function$
DECLARE
    current_size bigint;
    appended_size integer;
    current_is_manifest boolean;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM object_store.read_object_as(
            p_database_role, p_claim, p_current_hash)) THEN
        RAISE EXCEPTION 'current object is not accessible' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM object_store.read_object_as(
            p_database_role, p_claim, p_tail_hash)) THEN
        RAISE EXCEPTION 'tail object is not accessible' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM object_store.read_object_as(
            p_database_role, p_claim, p_manifest_hash)) THEN
        RAISE EXCEPTION 'manifest object is not accessible' USING ERRCODE = '42501';
    END IF;

    SELECT EXISTS (SELECT 1 FROM object_store.chunk_manifest
                   WHERE manifest_hash = p_current_hash)
    INTO current_is_manifest;
    IF current_is_manifest THEN
        SELECT total_size INTO current_size FROM object_store.chunk_manifest
        WHERE manifest_hash = p_current_hash;
    ELSE
        SELECT byte_size INTO current_size FROM object_store.object
        WHERE object_hash = p_current_hash;
    END IF;
    SELECT byte_size::integer INTO appended_size FROM object_store.object
    WHERE object_hash = p_tail_hash;
    IF current_size IS NULL OR appended_size IS NULL THEN
        RAISE EXCEPTION 'chunk manifest references an unknown object' USING ERRCODE = '23503';
    END IF;

    INSERT INTO object_store.chunk_manifest(
        manifest_hash, previous_manifest_hash, base_object_hash,
        tail_object_hash, total_size, tail_size)
    VALUES (
        p_manifest_hash,
        CASE WHEN current_is_manifest THEN p_current_hash ELSE NULL END,
        CASE WHEN current_is_manifest THEN NULL ELSE p_current_hash END,
        p_tail_hash,
        current_size + appended_size,
        appended_size)
    ON CONFLICT (manifest_hash) DO NOTHING;
    RETURN current_size + appended_size;
END
$function$;

CREATE FUNCTION object_store.append_chunk_manifest(
    p_manifest_hash bytea,
    p_current_hash bytea,
    p_tail_hash bytea
)
RETURNS bigint
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, object_store
AS $function$
    SELECT object_store.append_chunk_manifest_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_manifest_hash, p_current_hash, p_tail_hash)
$function$;

CREATE FUNCTION object_store.logical_object_size_as(
    p_database_role name,
    p_claim text,
    p_object_hash bytea
)
RETURNS bigint
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store
AS $function$
DECLARE
    result bigint;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM object_store.read_object_as(
            p_database_role, p_claim, p_object_hash)) THEN
        RETURN NULL;
    END IF;
    SELECT COALESCE(manifest.total_size, stored.byte_size)
    INTO result
    FROM object_store.object AS stored
    LEFT JOIN object_store.chunk_manifest AS manifest
      ON manifest.manifest_hash = stored.object_hash
    WHERE stored.object_hash = p_object_hash;
    RETURN result;
END
$function$;

CREATE FUNCTION object_store.logical_object_size(p_object_hash bytea)
RETURNS bigint
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, auth, object_store
AS $function$
    SELECT object_store.logical_object_size_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_object_hash)
$function$;

CREATE FUNCTION object_store.read_object_range_as(
    p_database_role name,
    p_claim text,
    p_object_hash bytea,
    p_offset bigint,
    p_maximum integer
)
RETURNS bytea
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store
AS $function$
DECLARE
    result bytea;
BEGIN
    IF p_offset < 0 OR p_maximum < 0 OR p_maximum > 67108864 THEN
        RAISE EXCEPTION 'invalid bounded object range' USING ERRCODE = '22023';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM object_store.read_object_as(
            p_database_role, p_claim, p_object_hash)) THEN
        RETURN NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM object_store.chunk_manifest
                   WHERE manifest_hash = p_object_hash) THEN
        IF p_offset > 2147483646 THEN
            RETURN ''::bytea;
        END IF;
        SELECT substring(content FROM (p_offset + 1)::integer FOR p_maximum)
        INTO result FROM object_store.object WHERE object_hash = p_object_hash;
        RETURN COALESCE(result, ''::bytea);
    END IF;

    WITH RECURSIVE chain AS (
        SELECT manifest_hash, previous_manifest_hash, base_object_hash,
               tail_object_hash, total_size, tail_size
        FROM object_store.chunk_manifest WHERE manifest_hash = p_object_hash
        UNION ALL
        SELECT parent.manifest_hash, parent.previous_manifest_hash, parent.base_object_hash,
               parent.tail_object_hash, parent.total_size, parent.tail_size
        FROM object_store.chunk_manifest AS parent
        JOIN chain AS child ON parent.manifest_hash = child.previous_manifest_hash
    ), parts AS (
        SELECT base_object_hash AS part_hash, 0::bigint AS part_offset
        FROM chain WHERE base_object_hash IS NOT NULL
        UNION ALL
        SELECT tail_object_hash, total_size - tail_size
        FROM chain
    ), overlapping AS (
        SELECT part.part_offset, stored.content,
               GREATEST(p_offset - part.part_offset, 0)::integer AS local_offset,
               LEAST(stored.byte_size - GREATEST(p_offset - part.part_offset, 0),
                     p_offset + p_maximum - GREATEST(part.part_offset, p_offset))::integer AS take
        FROM parts AS part
        JOIN object_store.object AS stored ON stored.object_hash = part.part_hash
        WHERE part.part_offset < p_offset + p_maximum
          AND part.part_offset + stored.byte_size > p_offset
    )
    SELECT string_agg(substring(content FROM local_offset + 1 FOR take), ''::bytea
                      ORDER BY part_offset)
    INTO result FROM overlapping WHERE take > 0;
    RETURN COALESCE(result, ''::bytea);
END
$function$;

CREATE FUNCTION object_store.read_object_range(
    p_object_hash bytea,
    p_offset bigint,
    p_maximum integer
)
RETURNS bytea
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, auth, object_store
AS $function$
    SELECT object_store.read_object_range_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_object_hash, p_offset, p_maximum)
$function$;

REVOKE ALL ON TABLE object_store.chunk_manifest FROM PUBLIC;
REVOKE ALL ON FUNCTION object_store.append_chunk_manifest_as(name,text,bytea,bytea,bytea) FROM PUBLIC;
REVOKE ALL ON FUNCTION object_store.logical_object_size_as(name,text,bytea) FROM PUBLIC;
REVOKE ALL ON FUNCTION object_store.read_object_range_as(name,text,bytea,bigint,integer) FROM PUBLIC;
-- These entry points authenticate current_user plus the transaction identity claim
-- internally. Granting the two layers avoids per-user role ACL drift after account creation.
GRANT EXECUTE ON FUNCTION object_store.append_chunk_manifest_as(name,text,bytea,bytea,bytea),
    object_store.logical_object_size_as(name,text,bytea),
    object_store.read_object_range_as(name,text,bytea,bigint,integer),
    object_store.append_chunk_manifest(bytea,bytea,bytea),
    object_store.logical_object_size(bytea),
    object_store.read_object_range(bytea,bigint,integer)
TO PUBLIC;

SELECT meta.assert_security_invariants();

RESET ROLE;
