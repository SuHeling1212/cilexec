package com.follarce.domain.vfs;

import com.follarce.domain.Invariant;

import java.time.Instant;

/** Immutable content-addressed object. */
public record StoredObject(
        ObjectHash objectHash,
        long byteSize,
        String mediaType,
        BinaryContent content,
        Instant createdAt
) {
    public StoredObject {
        Invariant.required(objectHash, "objectHash");
        Invariant.nonNegative(byteSize, "byteSize");
        mediaType = Invariant.text(mediaType, "mediaType");
        Invariant.required(content, "content");
        Invariant.required(createdAt, "createdAt");
        Invariant.check(byteSize == content.size(), "byteSize must match content length");
        Invariant.check(objectHash.equals(ObjectHash.sha256(content)),
                "objectHash must match content bytes");
    }

    public static StoredObject create(BinaryContent content, String mediaType, Instant createdAt) {
        Invariant.required(content, "content");
        return new StoredObject(ObjectHash.sha256(content), content.size(), mediaType,
                content, createdAt);
    }
}
