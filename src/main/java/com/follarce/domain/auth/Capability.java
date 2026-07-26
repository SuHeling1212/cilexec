package com.follarce.domain.auth;

/** Coarse domain capabilities refined by resource ownership and policy checks. */
public enum Capability {
    PROCESS_CREATE,
    PROCESS_CONTROL_OWN,
    PROCESS_CONTROL_ANY,
    VFS_READ,
    VFS_WRITE,
    VFS_MOUNT_HOST,
    PACKAGE_IMPORT,
    PACKAGE_BIND,
    EFFECT_REQUEST,
    TERMINAL_ATTACH,
    AUDIT_READ,
    SYSTEM_ADMIN
}
