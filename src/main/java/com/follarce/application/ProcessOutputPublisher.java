package com.follarce.application;

/**
 * Publishes a disposable process-output hint after commit.
 *
 * <p>The publisher is an adapter boundary, not a source of process truth. Implementations may
 * drop output when no consumer is attached or when a consumer is slow.
 */
@FunctionalInterface
public interface ProcessOutputPublisher {
    void publish(ProcessOutput output);

    static ProcessOutputPublisher discarding() {
        return output -> { };
    }
}
