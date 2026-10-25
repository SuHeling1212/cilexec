package com.follarce.application;

import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.port.AuthRepository;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclSystemHandle;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FclUserRuntimeFunctionsTest {
    @Test
    void registrationRetainsOwnerIdentityButRechecksPrivilegesOnEachCall() {
        UUID owner = UUID.randomUUID();
        UserAccount account = UserAccount.active(owner, "local",
                Instant.parse("2026-01-01T00:00:00Z"));
        AtomicReference<Set<Capability>> capabilities = new AtomicReference<>(
                Set.of(Capability.SYSTEM_ADMIN));
        AuthRepository auth = (AuthRepository) Proxy.newProxyInstance(
                AuthRepository.class.getClassLoader(), new Class<?>[] {AuthRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("capabilities")) {
                        assertEquals(owner, arguments[0]);
                        return capabilities.get();
                    }
                    if (method.getName().equals("findUser")) {
                        if (arguments[0] instanceof UUID id && id.equals(owner)) {
                            return Optional.of(account);
                        }
                        if (arguments[0] instanceof String name && name.equals(owner.toString())) {
                            return Optional.of(account);
                        }
                        return Optional.empty();
                    }
                    throw new AssertionError("Unexpected authentication operation: " + method);
                });
        FclFunctionRegistry registry = new FclFunctionRegistry();
        new FclUserRuntimeFunctions(auth, owner, Instant.EPOCH, registry).registerUsers();

        assertEquals(owner.toString(), invoke(registry, "user.getCurrentUser", List.of()));
        assertEquals(true, invoke(registry, "user.isLocal", List.of()));
        capabilities.set(Set.of());
        assertEquals(false, invoke(registry, "user.isLocal", List.of()));

        // user.find is a factory that returns a restricted handle; the target is re-checked on
        // every call and an unknown or invisible identity is rejected.
        Object found = invoke(registry, "user.find", List.of(owner.toString()));
        assertTrue(found instanceof FclSystemHandle);
        assertEquals("User", ((FclSystemHandle) found).kind());
        assertEquals(owner.toString(), ((FclSystemHandle) found).id());
        assertThrows(FclRuntimeException.class,
                () -> invoke(registry, "user.find", List.of(UUID.randomUUID().toString())));

        // The replaced user entity APIs are no longer callable from FCL.
        assertFalse(registry.qualifiedNames().contains("user.validateUser"));
        assertFalse(registry.qualifiedNames().contains("user.disable"));
        assertFalse(registry.qualifiedNames().contains("user.remove"));
        assertThrows(FclRuntimeException.class,
                () -> invoke(registry, "user.validateUser", List.of(owner.toString())));
    }

    private static Object invoke(FclFunctionRegistry registry, String name, List<Object> args) {
        return registry.resolve(name).function().invoke(args, null);
    }
}
