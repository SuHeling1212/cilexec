package com.follarce.market.client;

import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclRuntimeException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketRuntimeFunctionsTest {
    @Test
    void configuresUpdatesSearchesAndInstallsWithoutAnFclPackage() throws Exception {
        byte[] database = "sqlite-package".getBytes(StandardCharsets.UTF_8);
        String sha256 = hex(MessageDigest.getInstance("SHA-256").digest(database));
        FakeHost host = new FakeHost(database, sha256);
        FclFunctionRegistry registry = new FclFunctionRegistry();
        new MarketRuntimeFunctions(host).register(registry);
        FclFunctionRegistry.Invocation invocation = new FclFunctionRegistry.Invocation(7,
                new FclContinuation());

        registry.invoke("market.configure", List.of("http://market.example:8787"), invocation);
        assertEquals("http://market.example:8787",
                registry.invoke("market.origin", List.of(), invocation));
        Object updated = registry.invoke("market.update", List.of(), invocation);
        assertEquals(1L, ((Map<?, ?>) updated).get("packages"));

        Object found = registry.invoke("market.search", List.of("text editor"), invocation);
        assertEquals(1, ((List<?>) found).size());
        assertEquals(1, ((List<?>) registry.invoke("market.search", List.of("ed"),
                invocation)).size());
        assertTrue(((List<?>) registry.invoke("market.search", List.of("or"),
                invocation)).isEmpty());
        assertTrue(((List<?>) registry.invoke("market.search", List.of("1"),
                invocation)).isEmpty());
        assertTrue(((List<?>) registry.invoke("market.search", List.of("1.0.0"),
                invocation)).isEmpty());
        assertTrue(((List<?>) registry.invoke("market.search",
                List.of("cilexec/editor/1.0.0"), invocation)).isEmpty());
        assertEquals(1, ((List<?>) registry.invoke("market.search",
                List.of(sha256.substring(0, 8)), invocation)).size());
        assertEquals(sha256, ((Map<?, ?>) registry.invoke("market.info", List.of(sha256),
                invocation)).get("sha256"));
        Object downloaded = registry.invoke("market.download", List.of(sha256), invocation);
        assertEquals(true, ((Map<?, ?>) downloaded).get("ok"));
        Object installed = registry.invoke("market.install", List.of(sha256), invocation);
        assertEquals(true, ((Map<?, ?>) installed).get("ok"));
        assertEquals(1, ((List<?>) registry.invoke("market.list", List.of(), invocation)).size());
        assertEquals(1, host.downloads);
        assertEquals(1, host.installs);

        Object installedAgain = registry.invoke("market.install", List.of(sha256), invocation);
        assertEquals(true, ((Map<?, ?>) installedAgain).get("alreadyInstalled"));
        assertEquals(1, host.installs);
        assertEquals(List.of(), ((Map<?, ?>) registry.invoke("market.upgrade", List.of(),
                invocation)).get("upgraded"));
        assertTrue(((String) registry.invoke("market.help", List.of(), invocation))
                .contains("market.install"));
        assertEquals(MarketRuntimeFunctions.CLIENT_VERSION,
                ((Map<?, ?>) registry.invoke("market.run", List.of(), invocation)).get("version"));
        assertEquals(true, registry.invoke("market.uninstall", List.of(sha256), invocation));
        assertTrue(((List<?>) registry.invoke("market.list", List.of(), invocation)).isEmpty());
        assertEquals(false, registry.invoke("market.uninstall", List.of(sha256), invocation));
    }

    @Test
    void rejectsMalformedIndexAndIncompletePackageIds() {
        FakeHost host = new FakeHost(new byte[]{1}, "0".repeat(64));
        host.index = "{\"apiVersion\":\"wrong\",\"packages\":[]}";
        FclFunctionRegistry registry = new FclFunctionRegistry();
        new MarketRuntimeFunctions(host).register(registry);
        FclFunctionRegistry.Invocation invocation = new FclFunctionRegistry.Invocation(1,
                new FclContinuation());
        registry.invoke("market.configure", List.of("https://market.example"), invocation);

        assertThrows(FclRuntimeException.class,
                () -> registry.invoke("market.update", List.of(), invocation));
        assertThrows(FclRuntimeException.class,
                () -> registry.invoke("market.info", List.of("abc"), invocation));
        assertThrows(FclRuntimeException.class,
                () -> registry.invoke("market.configure", List.of("file:///tmp/market"),
                        invocation));
        assertThrows(FclRuntimeException.class,
                () -> registry.invoke("market.search", List.of(), invocation));
    }

    @Test
    void rejectsCyclicDependenciesBeforeDownloadingAnything() {
        String first = "1".repeat(64);
        String second = "2".repeat(64);
        FakeHost host = new FakeHost(new byte[]{1}, first);
        host.index = "{\"apiVersion\":\"cilexec.market/v1\",\"packages\":["
                + record("one", first, second) + "," + record("two", second, first) + "]}";
        FclFunctionRegistry registry = new FclFunctionRegistry();
        new MarketRuntimeFunctions(host).register(registry);
        FclFunctionRegistry.Invocation invocation = new FclFunctionRegistry.Invocation(2,
                new FclContinuation());
        registry.invoke("market.configure", List.of("https://market.example"), invocation);
        registry.invoke("market.update", List.of(), invocation);

        assertThrows(FclRuntimeException.class,
                () -> registry.invoke("market.install", List.of(first), invocation));
        assertEquals(0, host.downloads);
    }

    private static String record(String name, String sha256, String dependency) {
        return "{\"namespace\":\"test\",\"name\":\"" + name
                + "\",\"version\":\"1.0.0\",\"kind\":\"application\","
                + "\"coordinate\":\"test/" + name + "/1.0.0\","
                + "\"download\":\"/market/v1/" + sha256 + "\","
                + "\"sha256\":\"" + sha256 + "\",\"bytes\":1,"
                + "\"dependencies\":[{\"sha256\":\"" + dependency
                + "\",\"optional\":false}],\"latest\":true}";
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static final class FakeHost implements MarketRuntimeFunctions.Host {
        private final Map<String, String> environment = new LinkedHashMap<>();
        private final Map<String, String> files = new LinkedHashMap<>();
        private final byte[] packageBytes;
        private final String packageId;
        private String index;
        private int downloads;
        private int installs;

        private FakeHost(byte[] packageBytes, String packageId) {
            this.packageBytes = packageBytes.clone();
            this.packageId = packageId;
            this.index = "{\"apiVersion\":\"cilexec.market/v1\",\"packages\":[{"
                    + "\"namespace\":\"cilexec\",\"name\":\"editor\","
                    + "\"version\":\"1.0.0\",\"kind\":\"application\","
                    + "\"coordinate\":\"cilexec/editor/1.0.0\","
                    + "\"download\":\"/market/v1/" + packageId + "\","
                    + "\"sha256\":\"" + packageId + "\",\"bytes\":"
                    + packageBytes.length + ",\"dependencies\":[],\"latest\":true,"
                    + "\"summary\":\"Text editor\"}]}";
        }

        @Override public String environment(String name) { return environment.get(name); }
        @Override public void setEnvironment(String name, String value) {
            environment.put(name, value);
        }
        @Override public boolean exists(String path) { return files.containsKey(path); }
        @Override public void ensureDirectory(String path) { }
        @Override public String readText(String path) { return files.get(path); }
        @Override public void writeText(String path, String content) { files.put(path, content); }
        @Override public boolean removeFile(String path) { return files.remove(path) != null; }
        @Override public boolean fileMatches(String path, String sha256, long bytes) {
            return files.get(path) != null && sha256.equals(packageId)
                    && bytes == packageBytes.length;
        }
        @Override public Object httpGet(String url, FclFunctionRegistry.Invocation invocation) {
            return Map.of("status", 200L, "body", index, "headers", Map.of());
        }
        @Override public Object download(String url, String path,
                                         FclFunctionRegistry.Invocation invocation) {
            downloads++;
            files.put(path, "downloaded");
            return Map.of("path", path);
        }
        @Override public Map<String, Object> install(String path, String binding) {
            installs++;
            return Map.of("sha256", packageId, "coordinate", "cilexec/editor/1.0.0",
                    "binding", binding, "environmentId",
                    "00000000-0000-4000-8000-000000000001", "hash", "1".repeat(64));
        }
        @Override public boolean removeBinding(String environmentId, String binding) {
            return true;
        }
        @Override public void pin(String environmentId, String binding, String packageHash) { }
    }
}
