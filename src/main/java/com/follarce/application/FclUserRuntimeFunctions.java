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

/** Installs identity and administrator-delegation functions for one FCL execution slice. */
final class FclUserRuntimeFunctions extends FclRuntimeFunctions {
    FclUserRuntimeFunctions(FclRuntimeFunctions source) {
        super(source);
    }

    void registerUsers() {
        registry.register("user", "getCurrentUser", args -> {
                    arity(args, 0, "user.getCurrentUser");
                    return process.ownerId().toString();
                })
                .register("user", "isLocal", args -> {
                    arity(args, 0, "user.isLocal");
                    return transaction.auth().capabilities(process.ownerId())
                            .contains(Capability.SYSTEM_ADMIN);
                })
                .register("user", "validateUser", args -> {
                    arity(args, 1, "user.validateUser");
                    String value = string(args.getFirst(), "user.validateUser identity");
                    try {
                        UUID identity = UUID.fromString(value);
                        if (identity.equals(process.ownerId())) return true;
                        if (!isAdministrator()) return false;
                        return transaction.auth().findUsersByAdministrator(process.ownerId())
                                .stream().anyMatch(user -> user.userId().equals(identity));
                    } catch (IllegalArgumentException ignored) {
                        if (transaction.auth().findVisibleUsername(process.ownerId())
                                .map(username -> username.equalsIgnoreCase(value)).orElse(false)) {
                            return true;
                        }
                        if (!isAdministrator()) return false;
                        return transaction.auth().findUsersByAdministrator(process.ownerId())
                                .stream().anyMatch(user -> user.username().equalsIgnoreCase(value));
                    }
                })
                .register("user", "list", args -> {
                    arity(args, 0, "user.list");
                    return transaction.auth().findUsersByAdministrator(process.ownerId()).stream()
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
                        UserAccount created = transaction.auth().createUserByCredential(
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
                    return userMap(transaction.auth().disableUserByAdministrator(
                            process.ownerId(), userId, UUID.randomUUID(), now));
                })
                .register("user", "remove", args -> {
                    arity(args, 1, "user.remove");
                    UUID userId = uuid(args.getFirst(), "user.remove user");
                    return transaction.auth().removeUserByAdministrator(
                            process.ownerId(), userId, UUID.randomUUID(), now);
                })
                .register("user", "switchUser", args -> unavailable("user.switchUser",
                        "a durable process identity cannot be changed in place"));
    }

    private static Map<String, Object> userMap(UserAccount user) {
        return Map.of("userId", user.userId().toString(), "username", user.username(),
                "status", user.status().name(), "credentialVersion", user.credentialVersion());
    }
}
