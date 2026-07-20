package com.follarce.kernel.process;

/** Persistent FCL process lifecycle state. */
public enum ProcessState {
    NEW,
    READY,
    RUNNING,
    BLOCKED,
    PAUSED,
    TERMINATED,
    FAILED;

    public boolean isTerminal() {
        return this == TERMINATED || this == FAILED;
    }

    public boolean canTransitionTo(ProcessState next) {
        if (next == this) return true;
        return switch (this) {
            case NEW -> next == READY || next == TERMINATED || next == FAILED;
            case READY -> next == RUNNING || next == PAUSED || next == TERMINATED || next == FAILED;
            case RUNNING -> next == READY || next == BLOCKED || next == PAUSED
                    || next == TERMINATED || next == FAILED;
            case BLOCKED -> next == READY || next == PAUSED || next == TERMINATED || next == FAILED;
            case PAUSED -> next == READY || next == BLOCKED || next == TERMINATED || next == FAILED;
            case TERMINATED, FAILED -> false;
        };
    }

    public static ProcessState restore(Object value) {
        if (value != null) {
            try {
                return valueOf(value.toString());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return NEW;
    }
}
