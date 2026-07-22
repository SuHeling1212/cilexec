package com.follarce.domain.process;

import com.follarce.domain.Invariant;

import java.util.UUID;

/** Internal stable UUID plus user-visible, monotonically allocated PID. */
public record ProcessIdentity(UUID processUid, long pid) {
    public ProcessIdentity {
        Invariant.required(processUid, "processUid");
        Invariant.positive(pid, "pid");
    }
}
