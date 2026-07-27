package com.follarce.domain.port;

import com.follarce.domain.terminal.TerminalSession;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface TerminalRepository {
    void saveSession(TerminalSession session);

    Optional<TerminalSession> findSession(UUID sessionId);

    /** Most recently active durable session for one authenticated user. */
    default Optional<TerminalSession> findOpenSession(UUID ownerId) {
        throw new UnsupportedOperationException("Open terminal lookup is not implemented");
    }

    /** Durable working directory owned by the terminal session, not an FCL function. */
    default String workingDirectory(UUID sessionId) {
        return "/";
    }

    default boolean changeWorkingDirectory(UUID sessionId, String expected,
                                           String replacement, Instant at) {
        throw new UnsupportedOperationException("Working-directory changes are not implemented");
    }

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
