package com.follarce.domain.process;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable process aggregate guarded by state version and execution epoch. */
public record CilProcess(
        ProcessIdentity identity,
        UUID ownerId,
        Status status,
        long stateVersion,
        long executionEpoch,
        Continuation continuation,
        Optional<UUID> parentProcessUid,
        Instant createdAt,
        Instant updatedAt
) {
    private static final Map<Status, Set<Status>> TRANSITIONS = transitions();

    public CilProcess {
        Invariant.required(identity, "identity");
        Invariant.required(ownerId, "ownerId");
        Invariant.required(status, "status");
        Invariant.nonNegative(stateVersion, "stateVersion");
        Invariant.nonNegative(executionEpoch, "executionEpoch");
        Invariant.required(continuation, "continuation");
        parentProcessUid = Invariant.required(parentProcessUid, "parentProcessUid");
        Invariant.check(parentProcessUid.isEmpty()
                        || !parentProcessUid.get().equals(identity.processUid()),
                "process cannot be its own parent");
        Invariant.required(createdAt, "createdAt");
        Invariant.required(updatedAt, "updatedAt");
        Invariant.check(!updatedAt.isBefore(createdAt), "updatedAt must not precede createdAt");
    }

    public boolean acceptsCommit(long expectedStateVersion, long expectedExecutionEpoch) {
        return stateVersion == expectedStateVersion && executionEpoch == expectedExecutionEpoch;
    }

    public CilProcess claim(long nextExecutionEpoch, Instant at) {
        Invariant.check(status == Status.READY, "only READY processes can be claimed");
        Invariant.check(nextExecutionEpoch > executionEpoch,
                "claim execution epoch must advance");
        return copy(Status.RUNNING, stateVersion + 1, nextExecutionEpoch, continuation, at);
    }

    public CilProcess transitionTo(Status target, Instant at) {
        Invariant.required(target, "target");
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new IllegalStateException("illegal process transition: " + status + " -> " + target);
        }
        return copy(target, stateVersion + 1, executionEpoch, continuation, at);
    }

    /**
     * Atomically installs the next program in an already suspended interactive process.
     * The process identity and execution epoch are retained; only a PAUSED process can
     * accept new work, which prevents terminal input from racing active execution.
     */
    public CilProcess acceptSubmission(Continuation submittedContinuation, Instant at) {
        Invariant.check(status == Status.PAUSED,
                "only a PAUSED process can accept a new submission");
        Invariant.required(submittedContinuation, "submittedContinuation");
        Invariant.check(submittedContinuation.waitState().isEmpty(),
                "a new submission cannot start in a wait state");
        return copy(Status.READY, stateVersion + 1, executionEpoch,
                submittedContinuation, at);
    }

    public CilProcess commitStatement(
            Continuation committedContinuation,
            Status target,
            long expectedStateVersion,
            long expectedExecutionEpoch,
            Instant at
    ) {
        if (!acceptsCommit(expectedStateVersion, expectedExecutionEpoch)) {
            throw new IllegalStateException("process version or execution epoch is stale");
        }
        Invariant.required(committedContinuation, "committedContinuation");
        Invariant.check(TRANSITIONS.getOrDefault(status, Set.of()).contains(target)
                        || status == target,
                "statement cannot commit illegal process status");
        return copy(target, stateVersion + 1, executionEpoch, committedContinuation, at);
    }

    public boolean isTerminal() {
        return status == Status.TERMINATED || status == Status.FAILED
                || status == Status.FAILED_RECOVERY;
    }

    private CilProcess copy(Status target, long version, long epoch,
                            Continuation nextContinuation, Instant at) {
        Invariant.required(at, "at");
        Invariant.check(!at.isBefore(updatedAt), "process update time must not move backwards");
        return new CilProcess(identity, ownerId, target, version, epoch, nextContinuation,
                parentProcessUid, createdAt, at);
    }

    private static Map<Status, Set<Status>> transitions() {
        Map<Status, Set<Status>> values = new EnumMap<>(Status.class);
        values.put(Status.CREATED, EnumSet.of(Status.READY, Status.FAILED));
        values.put(Status.READY, EnumSet.of(Status.RUNNING, Status.PAUSED,
                Status.TERMINATING, Status.FAILED));
        values.put(Status.RUNNING, EnumSet.of(Status.READY, Status.WAITING_IPC,
                Status.WAITING_TIMER, Status.WAITING_EFFECT, Status.WAITING_INPUT,
                Status.PAUSED, Status.TERMINATING, Status.TERMINATED, Status.FAILED));
        for (Status waiting : EnumSet.of(Status.WAITING_IPC, Status.WAITING_TIMER,
                Status.WAITING_EFFECT, Status.WAITING_INPUT)) {
            values.put(waiting, EnumSet.of(Status.READY, Status.PAUSED,
                    Status.TERMINATING, Status.FAILED));
        }
        values.put(Status.PAUSED, EnumSet.of(Status.READY, Status.WAITING_IPC,
                Status.WAITING_TIMER, Status.WAITING_EFFECT, Status.WAITING_INPUT,
                Status.TERMINATING, Status.FAILED));
        values.put(Status.TERMINATING, EnumSet.of(Status.TERMINATED, Status.FAILED_RECOVERY));
        values.put(Status.TERMINATED, EnumSet.noneOf(Status.class));
        values.put(Status.FAILED, EnumSet.noneOf(Status.class));
        values.put(Status.FAILED_RECOVERY, EnumSet.noneOf(Status.class));
        return Map.copyOf(values);
    }

    public enum Status {
        CREATED,
        READY,
        RUNNING,
        WAITING_IPC,
        WAITING_TIMER,
        WAITING_EFFECT,
        WAITING_INPUT,
        PAUSED,
        TERMINATING,
        TERMINATED,
        FAILED,
        FAILED_RECOVERY
    }

    /** Derives the durable status to restore after a paused process is resumed. */
    public static Status statusFor(Optional<Continuation.WaitState> waitState) {
        if (waitState.isEmpty()) return Status.READY;
        return switch (waitState.orElseThrow().kind()) {
            case IPC, CHILD, PROCESS -> Status.WAITING_IPC;
            case TIMER -> Status.WAITING_TIMER;
            case EFFECT -> Status.WAITING_EFFECT;
            case INPUT -> Status.WAITING_INPUT;
        };
    }
}
