package com.follarce.domain.process;

import com.follarce.domain.Invariant;

/** Immutable allocation cursor; issued values can only move forward. */
public record PidSequence(long lastIssued) {
    public PidSequence {
        Invariant.nonNegative(lastIssued, "lastIssued");
    }

    public Allocation issue() {
        if (lastIssued == Long.MAX_VALUE) {
            throw new IllegalStateException("PID space is exhausted");
        }
        long pid = lastIssued + 1;
        return new Allocation(pid, new PidSequence(pid));
    }

    public record Allocation(long pid, PidSequence sequence) {
        public Allocation {
            Invariant.positive(pid, "pid");
            Invariant.required(sequence, "sequence");
            Invariant.check(sequence.lastIssued == pid,
                    "allocation sequence must retain the issued PID");
        }
    }
}
