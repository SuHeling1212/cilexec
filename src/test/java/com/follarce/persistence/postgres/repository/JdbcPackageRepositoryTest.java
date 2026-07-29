package com.follarce.persistence.postgres.repository;

import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.port.PackageRepository;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcPackageRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-07-22T06:00:00Z");

    @Test
    void sendsOneExactSecurityDefinerBundleWithStableJsonShapes() {
        JdbcCapture capture = new JdbcCapture();
        JdbcPackageRepository repository = new JdbcPackageRepository(capture.connection());

        PackageRepository.ReleaseWriteResult result = repository.registerRelease(
                packageIndex());

        assertEquals(PackageRepository.ReleaseWriteResult.REGISTERED, result);
        assertTrue(capture.sql.startsWith("SELECT package.register_release_bundle("));
        assertEquals(13, capture.sql.chars().filter(character -> character == '?').count());
        assertTrue(capture.parameters.get(8).toString().contains("\"moduleName\""));
        assertTrue(capture.parameters.get(8).toString().contains("\"moduleObjectPath\""));
        assertTrue(capture.parameters.get(9).toString().contains("\"dependencyNamespace\""));
        assertTrue(capture.parameters.get(10).toString().contains("\"entrypointName\""));
        assertTrue(capture.parameters.get(11).toString().contains("\"exportName\""));
        assertTrue(capture.parameters.get(12).toString().contains("\"capabilityKey\""));
    }

    private static PackageIndex packageIndex() {
        ObjectHash packageHash = hash("logical package");
        ObjectHash databaseHash = hash("sqlite bytes");
        PackageRelease release = new PackageRelease(
                new PackageRelease.Coordinate("std", "example", "1.0.0"),
                new PackageRelease.Hash(packageHash), databaseHash, databaseHash,
                NOW);
        return new PackageIndex(release,
                List.of(new PackageIndex.Module("main", "modules/main.fcl", hash("main"))),
                List.of(new PackageIndex.Dependency("std", "base", "1.0.0", false)),
                List.of(new PackageIndex.Entrypoint("run", "main", "main")),
                List.of(new PackageIndex.Export("api", "main", "api")),
                List.of(new PackageIndex.CapabilityRequirement("vfs_read", true, "read")));
    }

    private static ObjectHash hash(String value) {
        return ObjectHash.sha256(new BinaryContent(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class JdbcCapture {
        private final Map<Integer, Object> parameters = new LinkedHashMap<>();
        private String sql;

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement")) {
                            sql = (String) arguments[0];
                            return preparedStatement();
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private PreparedStatement preparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if (method.getName().startsWith("set") && arguments != null
                                && arguments.length >= 2 && arguments[0] instanceof Integer index) {
                            parameters.put(index, arguments[1]);
                            return null;
                        }
                        if (method.getName().equals("executeQuery")) return resultSet();
                        return defaultValue(method.getReturnType());
                    });
        }

        private ResultSet resultSet() {
            boolean[] available = {true};
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("next")) {
                            boolean current = available[0];
                            available[0] = false;
                            return current;
                        }
                        if (method.getName().equals("getBoolean")) return true;
                        return defaultValue(method.getReturnType());
                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0F;
            if (type == double.class) return 0D;
            if (type == char.class) return '\0';
            return null;
        }
    }
}
