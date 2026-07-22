SET ROLE cilexec_owner;

-- One host session routes input to at most one process at a time.
CREATE UNIQUE INDEX ux_terminal_one_active_attachment
    ON terminal.attachment(session_id)
    WHERE status = 'ATTACHED';

ALTER TABLE terminal.session
    ADD CONSTRAINT ck_terminal_activity_order
        CHECK (last_activity_at >= opened_at),
    ADD CONSTRAINT ck_terminal_close_activity_order
        CHECK (closed_at IS NULL OR closed_at >= last_activity_at);

RESET ROLE;
