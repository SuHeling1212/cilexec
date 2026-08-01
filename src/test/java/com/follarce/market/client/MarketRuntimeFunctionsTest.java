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
        Object updated = registry.invoke("market.update", List.of(), invocation);
        assertEquals(1L, ((Map<?, ?>) updated).get("packages"));

        Object found = registry.invoke("market.search", List.of("text editor"), invocation);
        assertEquals(1, ((List<?>) found).size());
        Object installed = registry.invoke("market.install", List.of(sha256), invocation);
        assertEquals(true, ((Map<?, ?>) installed).get("ok"));
        assertEquals(1, ((List<?>) registry.invoke("market.list", List.of(), invocation)).size());
        assertEquals(1, host.downloads);
        assertEquals(1, host.installs);
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
