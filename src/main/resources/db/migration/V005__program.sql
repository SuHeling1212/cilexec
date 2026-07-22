SET ROLE cilexec_owner;

-- name: migration.V005.create_program
CREATE TABLE program.program (
    program_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    program_hash bytea NOT NULL CHECK (octet_length(program_hash) = 32),
    language_version text NOT NULL CHECK (btrim(language_version) <> ''),
    runtime_format_version integer NOT NULL CHECK (runtime_format_version > 0),
    source_object_hash bytea NOT NULL REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    compiled_object_hash bytea REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    statement_count integer NOT NULL CHECK (statement_count >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (program_id, owner_id),
    UNIQUE (owner_id, program_hash, language_version, runtime_format_version)
);

-- name: migration.V005.create_statement
CREATE TABLE program.statement (
    program_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    statement_index integer NOT NULL CHECK (statement_index >= 0),
    statement_kind text NOT NULL CHECK (btrim(statement_kind) <> ''),
    source_text text NOT NULL,
    compiled_json jsonb,
    source_line integer NOT NULL CHECK (source_line > 0),
    source_column integer NOT NULL CHECK (source_column > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (program_id, statement_index),
    FOREIGN KEY (program_id, owner_id) REFERENCES program.program(program_id, owner_id) ON DELETE RESTRICT
);

-- name: migration.V005.create_module_binding
CREATE TABLE program.module_binding (
    program_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    import_name text NOT NULL CHECK (btrim(import_name) <> ''),
    module_name text NOT NULL CHECK (btrim(module_name) <> ''),
    module_program_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (program_id, import_name),
    FOREIGN KEY (program_id, owner_id) REFERENCES program.program(program_id, owner_id) ON DELETE RESTRICT,
    FOREIGN KEY (module_program_id, owner_id) REFERENCES program.program(program_id, owner_id) ON DELETE RESTRICT
);

-- name: migration.V005.program_indexes
CREATE INDEX ix_program_owner_created ON program.program(owner_id, created_at DESC);
CREATE INDEX ix_module_binding_target ON program.module_binding(module_program_id);

-- name: migration.V005.program_immutability
CREATE TRIGGER program_reject_update_delete
BEFORE UPDATE OR DELETE ON program.program
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();
CREATE TRIGGER statement_reject_update_delete
BEFORE UPDATE OR DELETE ON program.statement
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();
CREATE TRIGGER module_binding_reject_update_delete
BEFORE UPDATE OR DELETE ON program.module_binding
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();

-- name: migration.V005.program_rls
ALTER TABLE program.program ENABLE ROW LEVEL SECURITY;
ALTER TABLE program.program FORCE ROW LEVEL SECURITY;
ALTER TABLE program.statement ENABLE ROW LEVEL SECURITY;
ALTER TABLE program.statement FORCE ROW LEVEL SECURITY;
ALTER TABLE program.module_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE program.module_binding FORCE ROW LEVEL SECURITY;

CREATE POLICY program_owner_control ON program.program TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY program_runtime_control ON program.program TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY program_readonly_control ON program.program FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY program_principal ON program.program TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

CREATE POLICY statement_owner_control ON program.statement TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY statement_runtime_control ON program.statement TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY statement_readonly_control ON program.statement FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY statement_principal ON program.statement TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

CREATE POLICY module_binding_owner_control ON program.module_binding TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY module_binding_runtime_control ON program.module_binding TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY module_binding_readonly_control ON program.module_binding FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY module_binding_principal ON program.module_binding TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

-- name: migration.V005.program_grants
GRANT SELECT, INSERT ON program.program, program.statement, program.module_binding TO cilexec_runtime;
GRANT SELECT ON program.program, program.statement, program.module_binding TO cilexec_readonly;

RESET ROLE;
