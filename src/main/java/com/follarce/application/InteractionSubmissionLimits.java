package com.follarce.application;

/** Bounds durable interactive source input before it enters the continuation pipeline. */
public final class InteractionSubmissionLimits {
    public static final int MAX_SUBMISSION_CHARACTERS = 256 * 1024;

    private InteractionSubmissionLimits() { }
}
