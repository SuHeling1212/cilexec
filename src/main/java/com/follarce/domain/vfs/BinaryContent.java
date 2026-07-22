package com.follarce.domain.vfs;

import com.follarce.domain.Invariant;

import java.util.Arrays;

/** Immutable binary value used by object storage and package artifacts. */
public final class BinaryContent {
    private final byte[] bytes;

    public BinaryContent(byte[] bytes) {
        this.bytes = Invariant.required(bytes, "bytes").clone();
    }

    public int size() {
        return bytes.length;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof BinaryContent content && Arrays.equals(bytes, content.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "BinaryContent[" + bytes.length + " bytes]";
    }
}
