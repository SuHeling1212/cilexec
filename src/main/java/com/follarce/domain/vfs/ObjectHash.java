package com.follarce.domain.vfs;

import com.follarce.domain.Invariant;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Lowercase SHA-256 content identity. */
public record ObjectHash(String value) implements Comparable<ObjectHash> {
    public ObjectHash {
        Invariant.text(value, "value");
        Invariant.check(value.matches("[0-9a-f]{64}"),
                "object hash must be a lowercase SHA-256 value");
    }

    public static ObjectHash sha256(BinaryContent content) {
        Invariant.required(content, "content");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.bytes());
            return new ObjectHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Override
    public int compareTo(ObjectHash other) {
        return value.compareTo(other.value);
    }
}
