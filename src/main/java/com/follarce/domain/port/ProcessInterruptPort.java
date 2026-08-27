package com.follarce.domain.port;

import com.follarce.domain.process.ProcessInterrupt;
import java.util.UUID;

/** Durable process-interrupt signal independent of the adapter that requested it. */
public interface ProcessInterruptPort {
    void requestInterrupt(ProcessInterrupt interrupt);

    /** Atomically consumes one interrupt request at an execution safe point. */
    boolean consumeInterrupt(UUID processUid);
}
