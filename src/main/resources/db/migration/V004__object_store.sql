SET ROLE cilexec_owner;

-- name: meta.reject_immutable_mutation
CREATE FUNCTION meta.reject_immutable_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
    RAISE EXCEPTION 'immutable relation %.% does not permit %', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END
$function$;

-- name: migration.V004.create_object
CREATE TABLE object_store.object (
    object_hash bytea PRIMARY KEY CHECK (octet_length(object_hash) = 32),
    byte_size bigint NOT NULL CHECK (byte_size >= 0),
    media_type text NOT NULL CHECK (btrim(media_type) <> ''),
    content bytea NOT NULL,
    created_by uuid REFERENCES auth.user_account(user_id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (byte_size = octet_length(content)),
    CHECK (object_hash = pg_catalog.sha256(content))
);

-- name: migration.V004.object_immutability
CREATE TRIGGER object_reject_update_delete
BEFORE UPDATE OR DELETE ON object_store.object
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();

-- This is a shared system relation. Raw content is never granted to user LOGIN roles.
-- name: migration.V004.object_grants
GRANT SELECT, INSERT ON object_store.object TO cilexec_runtime;
GRANT SELECT (object_hash, byte_size, media_type, created_by, created_at)
    ON object_store.object TO cilexec_readonly;

COMMENT ON TABLE object_store.object IS
    'Immutable SHA-256 addressed bytes shared by VFS, programs, payloads, and SQLite packages';

RESET ROLE;
