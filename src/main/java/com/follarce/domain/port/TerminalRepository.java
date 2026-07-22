package com.follarce.domain.port;

import com.follarce.domain.terminal.TerminalSession;

import java.util.Optional;
import java.util.UUID;

public interface TerminalRepository {
    void saveSession(TerminalSession session);

    Optional<TerminalSession> findSession(UUID sessionId);

    void appendInput(TerminalSession.Input input);

    void saveAttachment(TerminalSession.Attachment attachment);

    Optional<TerminalSession.Attachment> findAttachment(UUID sessionId, UUID processUid);

    Optional<TerminalSession.Attachment> findActiveAttachment(UUID sessionId);

    /** Claims the oldest complete, unaccepted input attached to this process. */
    Optional<TerminalSession.Input> acceptPendingInput(UUID processUid, java.time.Instant at);

    void requestInterrupt(TerminalSession.Interrupt interrupt);

    /** Atomically consumes the durable Ctrl+C flag at a statement safe point. */
    boolean consumeInterrupt(UUID processUid);
}
