package com.follarce.extension.builtin;

import com.follarce.extension.builtin.SwapFunctionProvider;
import com.follarce.kernel.Constants;
import com.follarce.kernel.api.function.EffectPolicy;
import com.follarce.kernel.api.function.FunctionContext;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SwapTransactionTest {
    @TempDir Path root;

    private SwapFunctionProvider provider;

    @BeforeEach
    void initializeSwapDirectory() throws Exception {
        PathUtil.setVfsRoot(root.toFile());
        Files.createDirectories(root.resolve("system/swap"));
        UserUtil.setCurrentUser("local");
        provider = new SwapFunctionProvider();
    }

    @AfterEach
    void clearUser() {
        UserUtil.clearCurrentUser();
    }

    @Test
    void timesOneReplayReturnsConsumedValueWithoutASecondMutation() {
        assertEquals(EffectPolicy.LOCAL_TRANSACTIONAL, provider.getEffectPolicy("get"));
        assertEquals(EffectPolicy.MANUAL_RECOVERY, provider.getEffectPolicy("remove"));
        FunctionContext create = effect(10, "generation-10", "create-times");
        FileUtil.createFileOnce(Constants.SYSTEM_SWAP_PATH, "times-pool.json",
                "create-times", "local");
        assertEquals("", FileUtil.read(poolPath("times-pool")));
        assertEquals("Swap pool created: times-pool",
                provider.call("create", List.of("times-pool"), create));
        assertEquals("Swap pool created: times-pool",
                provider.call("create", List.of("times-pool"), create));
        assertEquals("Variable added: once (type=times(1))", provider.call("add",
                List.of("once:payload", "times-pool", "type:times(1)"),
                effect(10, "generation-10", "add-times")));

        FunctionContext consume = effect(10, "generation-10", "consume-once");
        assertEquals("payload", provider.call("get", List.of("once", "times-pool"), consume));
        String committed = FileUtil.read(poolPath("times-pool"));

        assertEquals("payload", provider.call("get", List.of("once", "times-pool"), consume));
        assertEquals(committed, FileUtil.read(poolPath("times-pool")));
        assertFalse(content("times-pool").containsKey("once"));
        assertTrue(appliedEffects("times-pool").containsKey("consume-once"));
    }

    @Test
    void syncConsumeAndUpdateCommitInALinearizableOrder() throws Exception {
        create("sync-pool", effect(20, "generation-20", "create-sync"));
        provider.call("add", List.of("message:old", "sync-pool", "type:sync"),
                effect(20, "generation-20", "add-sync"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Object> consumed = new AtomicReference<>();
        AtomicReference<Object> updated = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread reader = Thread.ofVirtual().start(() -> runTogether(ready, start, failure,
                () -> consumed.set(provider.call("get", List.of("message", "sync-pool"),
                        effect(21, "generation-21", "consume-sync")))));
        Thread writer = Thread.ofVirtual().start(() -> runTogether(ready, start, failure,
                () -> updated.set(provider.call("update", List.of("message", "sync-pool", "new"),
                        effect(22, "generation-22", "update-sync")))));

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        reader.join();
        writer.join();
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        assertEquals("Variable updated: message", updated.get());

        Map<String, Object> variable = variable("sync-pool", "message");
        assertEquals("new", variable.get("value"));
        if ("old".equals(consumed.get())) {
            assertEquals(Boolean.TRUE, variable.get("changed"));
        } else {
            assertEquals("new", consumed.get());
            assertEquals(Boolean.FALSE, variable.get("changed"));
        }
    }

    @Test
    void duplicateUpdateEffectReturnsItsOriginalResultAndDoesNotRewrite() {
        create("update-pool", effect(30, "generation-30", "create-update"));
        provider.call("add", List.of("item:initial", "update-pool"),
                effect(30, "generation-30", "add-update"));

        FunctionContext duplicate = effect(30, "generation-30", "update-once");
        Object first = provider.call("update", List.of("item", "update-pool", "first"), duplicate);
        String committed = FileUtil.read(poolPath("update-pool"));
        Object replay = provider.call("update", List.of("item", "update-pool", "second"), duplicate);

        assertEquals("Variable updated: item", first);
        assertEquals(first, replay);
        assertEquals(committed, FileUtil.read(poolPath("update-pool")));
        assertEquals("first", variable("update-pool", "item").get("value"));
    }

    @Test
    void generationFencingRejectsStaleOwnersAndExpiredLeaseCanBeTakenOver()
            throws Exception {
        create("lock-pool", effect(40, "generation-a", "create-lock"));
        provider.call("add", List.of("guarded:initial", "lock-pool"),
                effect(40, "generation-a", "add-lock"));

        Map<String, Object> first = resultMap(provider.call("lock",
                List.of("guarded", "lock-pool", 1_000L),
                effect(40, "generation-a", "lock-a")));
        long firstToken = number(first, "fencingToken");
        assertError(provider.call("update", List.of("guarded", "lock-pool", "stale"),
                effect(40, "generation-b", "wrong-generation-update")));
        assertError(provider.call("unlock", List.of("guarded", "lock-pool", firstToken),
                effect(40, "generation-b", "wrong-generation-unlock")));
        assertEquals("initial", variable("lock-pool", "guarded").get("value"));

        awaitExpiration(number(first, "leaseUntilEpochMs"));
        Map<String, Object> second = resultMap(provider.call("lock",
                List.of("guarded", "lock-pool", 5_000L),
                effect(40, "generation-b", "lock-b")));
        long secondToken = number(second, "fencingToken");
        assertTrue(secondToken > firstToken);

        Map<String, Object> locked = variable("lock-pool", "guarded");
        assertEquals("generation-b", locked.get("lockedByGeneration"));
        assertEquals(secondToken, ((Number) locked.get("fencingToken")).longValue());
        assertError(provider.call("update", List.of("guarded", "lock-pool", "former"),
                effect(40, "generation-a", "former-owner-update")));
        assertEquals("Variable updated: guarded", provider.call("update",
                List.of("guarded", "lock-pool", "current", secondToken),
                effect(40, "generation-b", "current-owner-update")));

        Map<String, Object> renewed = resultMap(provider.call("renewLock",
                List.of("guarded", "lock-pool", secondToken, 5_000L),
                effect(40, "generation-b", "renew-b")));
        assertEquals(secondToken, number(renewed, "fencingToken"));
        assertError(provider.call("unlock", List.of("guarded", "lock-pool", secondToken),
                effect(40, "generation-a", "stale-token-unlock")));
        assertEquals("Variable unlocked: guarded", provider.call("unlock",
                List.of("guarded", "lock-pool", secondToken),
                effect(40, "generation-b", "unlock-b")));

        provider.call("add", List.of("legacy:value", "lock-pool"),
                effect(41, "generation-b", "add-legacy"));
        FunctionContext legacy = new FunctionContext(41, 1, "local");
        assertInstanceOf(Map.class, provider.call("lock", List.of("legacy", "lock-pool"), legacy));
        assertEquals("Variable unlocked: legacy",
                provider.call("unlock", List.of("legacy", "lock-pool"), legacy));
    }

    private void create(String pool, FunctionContext context) {
        assertEquals("Swap pool created: " + pool,
                provider.call("create", List.of(pool), context));
    }

    private static FunctionContext effect(int pid, String generation, String effectId) {
        return new FunctionContext(pid, 1, "local", generation, Map.of(),
                null, null, null).forEffect(effectId, false);
    }

    private static void runTogether(CountDownLatch ready, CountDownLatch start,
                                    AtomicReference<Throwable> failure, Runnable operation) {
        ready.countDown();
        try {
            start.await();
            operation.run();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static void awaitExpiration(long deadline) throws InterruptedException {
        long delay = deadline - System.currentTimeMillis() + 2L;
        if (delay > 0) Thread.sleep(delay);
        while (System.currentTimeMillis() <= deadline) Thread.sleep(1L);
    }

    private static String poolPath(String pool) {
        return Constants.SYSTEM_SWAP_PATH + pool + ".json";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(String pool) {
        return (Map<String, Object>) assertInstanceOf(Map.class,
                pool(pool).get("content"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variable(String pool, String variable) {
        return (Map<String, Object>) assertInstanceOf(Map.class,
                content(pool).get(variable));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> appliedEffects(String pool) {
        return (Map<String, Object>) assertInstanceOf(Map.class,
                pool(pool).get("AppliedEffects"));
    }

    private static Map<String, Object> pool(String pool) {
        return JsonUtil.parseToMapStrict(FileUtil.read(poolPath(pool)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultMap(Object result) {
        return (Map<String, Object>) assertInstanceOf(Map.class, result);
    }

    private static long number(Map<String, Object> map, String field) {
        return ((Number) map.get(field)).longValue();
    }

    private static void assertError(Object result) {
        assertInstanceOf(String.class, result);
        assertTrue(((String) result).startsWith("ERROR:"), () -> String.valueOf(result));
    }
}
