package com.follarce.domain.port;

/**
 * Storage-neutral classification exposed by a persistence adapter when a caller needs to
 * decide whether a failed operation is a duplicate race or a runtime-fencing failure.
 *
 * <p>The concrete database exception remains adapter-owned. Kernel services must use this
 * narrow capability instead of importing a JDBC/PostgreSQL exception type.</p>
 */
public interface DurableStorageFailure {
    boolean isUniqueConflict();

    boolean stopsRuntime();
}
