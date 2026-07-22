package com.follarce.domain.vfs;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/** A database declaration for one separately configured container bind mount. */
public record VfsMount(
        UUID mountId,
        UUID nodeId,
        UUID ownerId,
        String hostSourceKey,
        String containerPath,
        String requiredCapability,
        boolean readOnly,
        Status status,
        Instant createdAt
) {
    public static final String HOST_CAPABILITY = "vfs_mount_host";
    private static final Pattern HOST_SOURCE_KEY = Pattern.compile("[A-Za-z0-9_.-]+");

    public VfsMount {
        Invariant.required(mountId, "mountId");
        Invariant.required(nodeId, "nodeId");
        Invariant.required(ownerId, "ownerId");
        hostSourceKey = validateHostSourceKey(hostSourceKey);
        containerPath = validateContainerPath(containerPath);
        requiredCapability = Invariant.text(requiredCapability, "requiredCapability");
        Invariant.check(HOST_CAPABILITY.equals(requiredCapability),
                "host mounts must require the vfs_mount_host capability");
        Invariant.required(status, "status");
        Invariant.required(createdAt, "createdAt");
    }

    public static VfsMount declareReadOnly(
            UUID mountId,
            UUID nodeId,
            UUID ownerId,
            String hostSourceKey,
            String containerPath,
            Instant createdAt
    ) {
        return new VfsMount(mountId, nodeId, ownerId, hostSourceKey, containerPath,
                HOST_CAPABILITY, true, Status.ACTIVE, createdAt);
    }

    public VfsMount disable() {
        if (status == Status.DISABLED) return this;
        return new VfsMount(mountId, nodeId, ownerId, hostSourceKey, containerPath,
                requiredCapability, readOnly, Status.DISABLED, createdAt);
    }

    public static String validateHostSourceKey(String value) {
        value = Invariant.text(value, "hostSourceKey");
        Invariant.check(HOST_SOURCE_KEY.matcher(value).matches(),
                "hostSourceKey must be a configured symbolic key");
        Invariant.check(!value.equals(".") && !value.equals(".."),
                "hostSourceKey cannot be a path traversal component");
        return value;
    }

    public static String validateContainerPath(String value) {
        value = Invariant.text(value, "containerPath");
        Invariant.check(value.startsWith("/"), "containerPath must be absolute");
        Invariant.check(!value.equals("/"), "containerPath cannot be the container root");
        Invariant.check(!value.endsWith("/"), "containerPath must be canonical");
        Invariant.check(value.indexOf('\\') < 0,
                "containerPath must use container POSIX separators");
        Invariant.check(value.chars().noneMatch(Character::isISOControl),
                "containerPath contains control characters");
        String[] components = value.substring(1).split("/", -1);
        for (String component : components) {
            Invariant.check(!component.isBlank() && !component.equals(".")
                            && !component.equals(".."),
                    "containerPath must be canonical and traversal-free");
        }
        return value;
    }

    public enum Status {
        ACTIVE,
        DISABLED
    }
}
