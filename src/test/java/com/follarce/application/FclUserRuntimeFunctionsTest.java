package com.follarce.application;

import com.follarce.domain.auth.Capability;
import com.follarce.domain.port.AuthRepository;
import com.follarce.fcl.FclFunctionRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FclUserRuntimeFunctionsTest {
    @Test
    void registrationRetainsOwnerIdentityButRechecksPrivilegesOnEachCall() {
        UUID owner = UUID.randomUUID();
        AtomicReference<Set<Capability>> capabilities = new AtomicReference<>(
                Set.of(Capability.SYSTEM_ADMIN));
        AuthRepository auth = (AuthRepository) Proxy.newProxyInstance(
                AuthRepository.class.getClassLoader(), new Class<?>[] {AuthRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("capabilities")) {
                        assertEquals(owner, arguments[0]);
                        return capabilities.get();
                    }
                    throw new AssertionError("Unexpected authentication operation: " + method);
                });
        FclFunctionRegistry registry = new FclFunctionRegistry();
        new FclUserRuntimeFunctions(auth, owner, Instant.EPOCH, registry).registerUsers();

        assertEquals(owner.toString(), invoke(registry, "user.getCurrentUser", List.of()));
        assertEquals(true, invoke(registry, "user.isLocal", List.of()));
        capabilities.set(Set.of());
        assertEquals(false, invoke(registry, "user.isLocal", List.of()));
        assertEquals(true, invoke(registry, "user.validateUser", List.of(owner.toString())));
        assertEquals(false, invoke(registry, "user.validateUser",
                List.of(UUID.randomUUID().toString())));
    }

    private static Object invoke(FclFunctionRegistry registry, String name, List<Object> args) {
        return registry.resolve(name).function().invoke(args, null);
    }
}
