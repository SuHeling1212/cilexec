SET ROLE cilexec_owner;

-- The terminal owns cwd. FCL processes receive a snapshot for relative-path resolution,
-- but no FCL function can mutate terminal navigation state.
ALTER TABLE terminal.session
    ADD COLUMN working_directory text NOT NULL DEFAULT '/'
    CHECK (working_directory LIKE '/%');

COMMENT ON COLUMN terminal.session.working_directory IS
    'Durable absolute VFS working directory for colon cd/ls commands and REPL submissions';

RESET ROLE;
