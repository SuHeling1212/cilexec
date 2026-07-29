package com.follarce.domain.vfs;

/** One logical-size limit for every file represented by the CilExec VFS. */
public final class VfsFileLimits {
    public static final long MAX_FILE_BYTES = 1L * 1024 * 1024 * 1024;

    private VfsFileLimits() {
    }

    public static void requireWithinLimit(long bytes) {
        if (bytes < 0 || bytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("VFS file exceeds the 1 GiB limit");
        }
    }

    public static long checkedAppendSize(long existingBytes, long appendedBytes) {
        try {
            long total = Math.addExact(existingBytes, appendedBytes);
            requireWithinLimit(total);
            return total;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("VFS file exceeds the 1 GiB limit", overflow);
        }
    }
}
