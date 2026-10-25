package com.follarce.vfs;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Resolves the administrator's virtual /Users tree without materializing recursive nodes. */
public final class AdministratorUserPath {
    private AdministratorUserPath() {
    }

    public static Resolved route(String absolutePath, UUID administratorId,
                                 Function<String, UUID> findUserId) {
        Objects.requireNonNull(absolutePath, "absolutePath");
        Objects.requireNonNull(administratorId, "administratorId");
        Objects.requireNonNull(findUserId, "findUserId");
        String path = absolutePath;
        while (path.startsWith("/Users/")) {
            String remainder = path.substring("/Users/".length());
            int slash = remainder.indexOf('/');
            String username = slash < 0 ? remainder : remainder.substring(0, slash);
            String userPath = slash < 0 ? "/" : remainder.substring(slash);
            UUID userId = findUserId.apply(username);
            if (!userId.equals(administratorId)) {
                return new Resolved(userId, userPath);
            }
            // The local administrator's home is its own root. Each self-reference
            // removes one path prefix, so even repeated aliases terminate.
            path = userPath;
        }
        return new Resolved(administratorId, path);
    }

    public record Resolved(UUID ownerId, String path) {
    }
}
