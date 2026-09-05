package com.follarce.application;

import com.follarce.auth.AccountCapabilityProfiles;
import com.follarce.auth.PasswordPolicy;
import com.follarce.auth.UsernamePolicy;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.fcl.FclRuntimeException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.follarce.application.FclRuntimeFunctionSupport.arity;
import static com.follarce.application.FclRuntimeFunctionSupport.string;
import static com.follarce.application.FclRuntimeFunctionSupport.uuid;
import static com.follarce.application.FclRuntimeFunctionSupport.unavailable;

/** Installs identity and administrator-delegation functions for one FCL execution slice. */
final class FclUserRuntimeFunctions {
    private final com.follarce.domain.port.AuthRepository auth;
    private final UUID ownerId;
    private final java.time.Instant now;
    private final com.follarce.fcl.FclFunctionRegistry registry;

    FclUserRuntimeFunctions(com.follarce.domain.port.AuthRepository auth, UUID ownerId,
                            java.time.Instant now, com.follarce.fcl.FclFunctionRegistry registry) {
        this.auth = java.util.Objects.requireNonNull(auth, "auth");
        this.ownerId = java.util.Objects.requireNonNull(ownerId, "ownerId");
        this.now = java.util.Objects.requireNonNull(now, "now");
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
    }

    private boolean isAdministrator() {
        return auth.capabilities(ownerId).contains(Capability.SYSTEM_ADMIN);
    }

    void registerUsers() {
        registry.register("user", "getCurrentUser", args -> {
                    arity(args, 0, "user.getCurrentUser");
                    return ownerId.toString();
                })
                .register("user", "isLocal", args -> {
                    arity(args, 0, "user.isLocal");
                    return auth.capabilities(ownerId)
                            .contains(Capability.SYSTEM_ADMIN);
                })
                .register("user", "validateUser", args -> {
                    arity(args, 1, "user.validateUser");
                    String value = string(args.getFirst(), "user.validateUser identity");
                    try {
                        UUID identity = UUID.fromString(value);
                        if (identity.equals(ownerId)) return true;
                        if (!isAdministrator()) return false;
                        return auth.findUsersByAdministrator(ownerId)
                                .stream().anyMatch(user -> user.userId().equals(identity));
                    } catch (IllegalArgumentException ignored) {
                        if (auth.findVisibleUsername(ownerId)
                                .map(username -> username.equalsIgnoreCase(value)).orElse(false)) {
                            return true;
                        }
                        if (!isAdministrator()) return false;
                        return auth.findUsersByAdministrator(ownerId)
                                .stream().anyMatch(user -> user.username().equalsIgnoreCase(value));
                    }
                })
                .register("user", "list", args -> {
                    arity(args, 0, "user.list");
                    return auth.findUsersByAdministrator(ownerId).stream()
                            .map(FclUserRuntimeFunctions::userMap).toList();
                })
                .register("user", "create", args -> {
                    if (args.size() < 2 || args.size() > 3) {
                        throw new FclRuntimeException("user.create expects 2 or 3 arguments, got "
                                + args.size());
                    }
                    String username = string(args.get(0), "user.create username");
                    String password = string(args.get(1), "user.create password");
                    Set<Capability> capabilities = AccountCapabilityProfiles.USER;
                    String administratorUsername = null;
                    String administratorPassword = null;
                    if (args.size() > 2) {
                        // Creating an administrator is a delegation: an existing
                        // administrator's identity and password must be supplied, and
                        // the database re-checks that identity's current effective
                        // SYSTEM_ADMIN atomically with the creation.
                        if (!(args.get(2) instanceof List<?> credentials)
                                || credentials.size() != 2) {
                            throw new FclRuntimeException(
                                    "user.create administrator credentials must be "
                                            + "[administratorUsername, administratorPassword]");
                        }
                        administratorUsername = string(credentials.get(0),
                                "user.create administrator username");
                        administratorPassword = string(credentials.get(1),
                                "user.create administrator password");
                        capabilities = AccountCapabilityProfiles.ADMIN;
                    }
                    String normalized = UsernamePolicy.normalize(username);
                    char[] secret = password.toCharArray();
                    char[] secretAdmin = administratorPassword == null
                            ? null : administratorPassword.toCharArray();
                    try {
                        PasswordPolicy.require(secret);
                        UserAccount created = auth.createUserByCredential(
                                administratorUsername, secretAdmin,
                                UUID.randomUUID(), normalized, secret, capabilities,
                                UUID.randomUUID(), now);
                        // The VFS root is provisioned idempotently on the new user's first login
                        // (TerminalAccessService.ensureRoot); user transactions cannot insert a
                        // node owned by another user under forced RLS.
                        return userMap(created);
                    } finally {
                        Arrays.fill(secret, '\0');
                        if (secretAdmin != null) Arrays.fill(secretAdmin, '\0');
                    }
                })
                .register("user", "disable", args -> {
                    arity(args, 1, "user.disable");
                    UUID userId = uuid(args.getFirst(), "user.disable user");
                    return userMap(auth.disableUserByAdministrator(
                            ownerId, userId, UUID.randomUUID(), now));
                })
                .register("user", "remove", args -> {
                    arity(args, 1, "user.remove");
                    UUID userId = uuid(args.getFirst(), "user.remove user");
                    return auth.removeUserByAdministrator(
                            ownerId, userId, UUID.randomUUID(), now);
                })
                .register("user", "switchUser", args -> unavailable("user.switchUser",
                        "a durable process identity cannot be changed in place"));
    }

    private static Map<String, Object> userMap(UserAccount user) {
        return Map.of("userId", user.userId().toString(), "username", user.username(),
                "status", user.status().name(), "credentialVersion", user.credentialVersion());
    }
}
