package com.follarce.application;

import java.util.UUID;

/**
 * Best-effort post-commit hint that a process changed durable state.
 *
 * <p>Consumers must always retain a durable polling or reconciliation path: losing this
 * notification may delay observation, but may never make a committed state unreachable.
 */
@FunctionalInterface
public interface ProcessChangeNotifier {
    void signal(UUID ownerId, UUID processUid);

    static ProcessChangeNotifier discarding() {
        return (ownerId, processUid) -> { };
    }
}
