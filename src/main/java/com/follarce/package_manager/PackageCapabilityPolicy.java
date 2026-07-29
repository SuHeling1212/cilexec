package com.follarce.package_manager;

import com.follarce.domain.auth.Capability;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclExpression;
import com.follarce.fcl.FclInstruction;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;

import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies that package source cannot silently use more authority than its manifest declares. */
public final class PackageCapabilityPolicy {
    private static final Set<String> KNOWN = Set.of(
            "vfs.read", "vfs.write", "terminal.raw_input", "network.http",
            "network.socket", "process.create", "package.manage", "system.admin");
    private static final Map<String, Set<Capability>> APPLICATION = Map.of(
            "vfs.read", Set.of(Capability.VFS_READ),
            "vfs.write", Set.of(Capability.VFS_WRITE),
            "terminal.raw_input", Set.of(Capability.TERMINAL_ATTACH),
            "network.http", Set.of(Capability.EFFECT_REQUEST),
            "network.socket", Set.of(Capability.EFFECT_REQUEST),
            "process.create", Set.of(Capability.PROCESS_CREATE),
            "package.manage", Set.of(Capability.PACKAGE_IMPORT, Capability.PACKAGE_BIND),
            "system.admin", Set.of(Capability.SYSTEM_ADMIN));

    private final Set<String> required;

    private PackageCapabilityPolicy(Set<String> required) {
        this.required = Set.copyOf(required);
    }

    public static PackageCapabilityPolicy inspect(byte[] database,
                                                  PackageDescriptor descriptor) {
        SqlitePackageReader reader = new SqlitePackageReader();
        Set<String> declared = new LinkedHashSet<>();
        for (PackageIndex.CapabilityRequirement capability : descriptor.capabilityIndex()) {
            if (capability.required()) {
                String key = normalize(capability.key());
                if (!KNOWN.contains(key)) {
                    throw new SecurityException(
                            "Unknown required package capability: " + capability.key());
                }
                declared.add(key);
            }
        }
        Set<String> used = new LinkedHashSet<>();
        Map<String, byte[]> sources = reader.readResources(database,
                descriptor.moduleIndex().stream().map(PackageIndex.Module::objectPath).toList());
        for (PackageIndex.Module module : descriptor.moduleIndex()) {
            String source = strictUtf8(sources.get(module.objectPath()), module.objectPath());
            for (FclInstruction instruction : new FclCompiler().compile(source).instructions()) {
                expressions(instruction).forEach(expression -> calls(expression, used));
            }
        }
        if (!declared.containsAll(used)) {
            Set<String> missing = new LinkedHashSet<>(used);
            missing.removeAll(declared);
            throw new SecurityException(
                    "Package source uses undeclared capabilities: " + missing);
        }
        return new PackageCapabilityPolicy(declared);
    }

    public void requireUserCapabilities(Set<Capability> available) {
        if (available.contains(Capability.SYSTEM_ADMIN)) return;
        Set<Capability> missing = new LinkedHashSet<>();
        for (String key : required) {
            for (Capability capability : APPLICATION.getOrDefault(key, Set.of())) {
                if (!available.contains(capability)) missing.add(capability);
            }
        }
        if (!missing.isEmpty()) {
            throw new SecurityException("User has not granted package capabilities: " + missing);
        }
    }

    private static String strictUtf8(byte[] bytes, String resource) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new SecurityException("Package module is not valid UTF-8: " + resource,
                    invalid);
        }
    }

    private static List<FclExpression> expressions(FclInstruction instruction) {
        if (instruction instanceof FclInstruction.Assignment value) {
            java.util.ArrayList<FclExpression> result = new java.util.ArrayList<>(value.indices());
            result.add(value.value());
            return result;
        }
        if (instruction instanceof FclInstruction.Evaluation value) return List.of(value.expression());
        if (instruction instanceof FclInstruction.Conditional value) return List.of(value.condition());
        if (instruction instanceof FclInstruction.Loop value) return List.of(value.condition());
        if (instruction instanceof FclInstruction.Return value && value.value() != null) {
            return List.of(value.value());
        }
        return List.of();
    }

    private static void calls(FclExpression expression, Set<String> required) {
        if (expression instanceof FclExpression.Call call) {
            capability(call.name()).ifPresent(required::add);
            call.arguments().forEach(argument -> calls(argument, required));
        } else if (expression instanceof FclExpression.ArrayLiteral value) {
            value.elements().forEach(item -> calls(item, required));
        } else if (expression instanceof FclExpression.MapLiteral value) {
            value.entries().forEach(entry -> {
                calls(entry.key(), required);
                calls(entry.value(), required);
            });
        } else if (expression instanceof FclExpression.Unary value) {
            calls(value.operand(), required);
        } else if (expression instanceof FclExpression.Binary value) {
            calls(value.left(), required);
            calls(value.right(), required);
        } else if (expression instanceof FclExpression.Index value) {
            calls(value.target(), required);
            calls(value.index(), required);
        }
    }

    private static java.util.Optional<String> capability(String call) {
        if (call.equals("socket.bind") || call.equals("socket.accept")
                || call.startsWith("system.") || call.equals("user.removeUser")) {
            return java.util.Optional.of("system.admin");
        }
        if (call.startsWith("network.")) return java.util.Optional.of("network.http");
        if (call.startsWith("socket.")) return java.util.Optional.of("network.socket");
        if (Set.of("webget", "webpost", "download").contains(call)) {
            return java.util.Optional.of("network.http");
        }
        if (Set.of("connect", "send", "receive", "bind", "accept").contains(call)) {
            return java.util.Optional.of(Set.of("bind", "accept").contains(call)
                    ? "system.admin" : "network.socket");
        }
        if (call.equals("io.input") || call.equals("io.readKey")
                || call.equals("io.readChar") || call.equals("input")
                || call.equals("readKey") || call.equals("readChar")) {
            return java.util.Optional.of("terminal.raw_input");
        }
        if (call.equals("process.fork") || call.equals("fork")) {
            return java.util.Optional.of("process.create");
        }
        if (call.startsWith("package.") && !call.equals("package.info")
                && !call.equals("package.list") && !call.equals("package.verify")
                && !call.equals("package.resource")) {
            return java.util.Optional.of("package.manage");
        }
        if (call.startsWith("file.")) {
            String name = call.substring("file.".length());
            return java.util.Optional.of(Set.of("read", "readChunk", "readMetaData",
                    "listdir", "exists", "size").contains(name) ? "vfs.read" : "vfs.write");
        }
        if (Set.of("readFile", "readChunk", "readMetaData", "listdir", "exists",
                "fileSize").contains(call)) return java.util.Optional.of("vfs.read");
        if (Set.of("writeFile", "appendFile", "createFile", "createDir", "removeFile",
                "removeDir", "rename").contains(call)) return java.util.Optional.of("vfs.write");
        return java.util.Optional.empty();
    }

    private static String normalize(String key) {
        return switch (key) {
            case "vfs_read" -> "vfs.read";
            case "vfs_write" -> "vfs.write";
            case "terminal_raw_input" -> "terminal.raw_input";
            case "network_http" -> "network.http";
            case "network_socket" -> "network.socket";
            case "process_create" -> "process.create";
            case "package_manage" -> "package.manage";
            case "system_admin" -> "system.admin";
            default -> key;
        };
    }
}
