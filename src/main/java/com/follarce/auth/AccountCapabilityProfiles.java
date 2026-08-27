package com.follarce.auth;

import com.follarce.domain.auth.Capability;
import java.util.Set;

/** Default grants for application-created ordinary and administrator accounts. */
public final class AccountCapabilityProfiles {
    public static final Set<Capability> USER = Set.of(
            Capability.PROCESS_CREATE,
            Capability.PROCESS_CONTROL_OWN,
            Capability.VFS_READ,
            Capability.VFS_WRITE,
            Capability.TERMINAL_ATTACH,
            Capability.AUDIT_READ);
    public static final Set<Capability> ADMIN;

    static {
        java.util.EnumSet<Capability> capabilities = java.util.EnumSet.copyOf(USER);
        capabilities.add(Capability.SYSTEM_ADMIN);
        ADMIN = Set.copyOf(capabilities);
    }

    private AccountCapabilityProfiles() { }
}
