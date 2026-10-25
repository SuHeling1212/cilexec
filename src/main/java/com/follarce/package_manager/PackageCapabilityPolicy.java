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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies that package source cannot silently use more authority than its manifest declares.
 *
 * <p>0.0.5 dispatches entity operations as methods on receiver objects. The policy therefore
 * tracks simple variable kinds through the module (a variable bound by {@code file.open} is a
 * file, one bound by {@code pkg.data()} is a private data space, and so on), traverses both
 * named-receiver calls and chained {@link FclExpression.MethodCall} expressions, and maps the
 * receiver kind plus method name to the package capability that the operation exercises.
 */
public final class PackageCapabilityPolicy {
    private static final Set<String> KNOWN = Set.of(
            "vfs.read", "vfs.write", "terminal.raw_input", "network.http",
            "network.socket", "process.create", "process.control", "package.manage",
            "package.data", "system.admin");
    private static final Map<String, Set<Capability>> APPLICATION = Map.of(
            "vfs.read", Set.of(Capability.VFS_READ),
            "vfs.write", Set.of(Capability.VFS_WRITE),
            "terminal.raw_input", Set.of(Capability.TERMINAL_ATTACH),
            "network.http", Set.of(Capability.EFFECT_REQUEST),
            "network.socket", Set.of(Capability.EFFECT_REQUEST),
            "process.create", Set.of(Capability.PROCESS_CREATE),
            "process.control", Set.of(Capability.PROCESS_CONTROL_OWN),
            "package.manage", Set.of(Capability.PACKAGE_IMPORT, Capability.PACKAGE_BIND),
            "package.data", Set.of(Capability.PACKAGE_BIND),
            "system.admin", Set.of(Capability.SYSTEM_ADMIN));

    private static final String FILE = "file";
    private static final String DIRECTORY = "directory";
    private static final String PATH = "path";
    private static final String PROCESS = "process";
    private static final String SWAP_POOL = "swapPool";
    private static final String SWAP_VARIABLE = "swapVariable";
    private static final String CHANNEL = "channel";
    private static final String TOPIC = "topic";
    private static final String DELIVERY = "delivery";
    private static final String PACKAGE = "package";
    private static final String PACKAGE_SPACE = "packageSpace";
    private static final String PACKAGE_QUOTA = "packageQuota";
    private static final String USER = "user";
    private static final String PROGRAM = "program";
    private static final String TERMINAL = "terminal";
    private static final String LEASE = "lease";

    private static final Set<String> FILE_READ_METHODS = Set.of(
            "read", "readChunk", "size", "metadata", "list", "exists");
    private static final Set<String> FILE_WRITE_METHODS = Set.of(
            "write", "append", "rename", "remove", "clear", "lock");
    private static final Set<String> PROCESS_METHODS = Set.of(
            "status", "wait", "pause", "continue", "kill", "send", "removeFinished");
    private static final Set<String> PACKAGE_DATA_METHODS = Set.of(
            "data", "info", "usage", "list", "read", "write", "export", "import", "clear",
            "quota", "root", "exists", "readChunk", "seedResource", "append", "mkdir",
            "remove", "rename", "size");
    private static final Set<String> PACKAGE_MANAGE_METHODS = Set.of("run", "pin", "uninstall");
    private static final Set<String> PACKAGE_READ_METHODS = Set.of("verify", "resource");
    private static final Set<String> QUOTA_METHODS = Set.of("set", "remove");

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
            List<FclInstruction> instructions = new FclCompiler().compile(source).instructions();
            Map<String, String> kinds = new LinkedHashMap<>();
            for (FclInstruction instruction : instructions) {
                for (FclExpression expression : expressions(instruction)) {
                    calls(expression, used, kinds);
                }
                if (instruction instanceof FclInstruction.Assignment assignment
                        && !assignment.variable().contains(".")) {
                    String kind = kindOf(assignment.value(), kinds);
                    if (kind == null) kinds.remove(assignment.variable());
                    else kinds.put(assignment.variable(), kind);
                }
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
            ArrayList<FclExpression> result = new ArrayList<>(value.indices());
            result.add(value.value());
            return result;
        }
        if (instruction instanceof FclInstruction.Evaluation value) return List.of(value.expression());
        if (instruction instanceof FclInstruction.Conditional value) return List.of(value.condition());
        if (instruction instanceof FclInstruction.Loop value) return List.of(value.condition());
        if (instruction instanceof FclInstruction.Return value && value.value() != null) {
            return List.of(value.value());
        }
        if (instruction instanceof FclInstruction.Update value) return value.indices();
        return List.of();
    }

    private static void calls(FclExpression expression, Set<String> required,
                              Map<String, String> kinds) {
        if (expression instanceof FclExpression.Call call) {
            namedCallCapability(call.name(), kinds).ifPresent(required::add);
            call.arguments().forEach(argument -> calls(argument, required, kinds));
            return;
        }
        if (expression instanceof FclExpression.MethodCall call) {
            String targetKind = kindOf(call.target(), kinds);
            methodCapability(targetKind, call.name()).ifPresent(required::add);
            calls(call.target(), required, kinds);
            call.arguments().forEach(argument -> calls(argument, required, kinds));
            return;
        }
        if (expression instanceof FclExpression.Member value) {
            calls(value.target(), required, kinds);
            return;
        }
        if (expression instanceof FclExpression.NewObject value) {
            value.arguments().forEach(argument -> calls(argument, required, kinds));
            return;
        }
        if (expression instanceof FclExpression.SuperConstructor value) {
            value.arguments().forEach(argument -> calls(argument, required, kinds));
            return;
        }
        if (expression instanceof FclExpression.DestroyTarget value) {
            value.indices().forEach(index -> calls(index, required, kinds));
            return;
        }
        if (expression instanceof FclExpression.ArrayLiteral value) {
            value.elements().forEach(item -> calls(item, required, kinds));
            return;
        }
        if (expression instanceof FclExpression.MapLiteral value) {
            value.entries().forEach(entry -> {
                calls(entry.key(), required, kinds);
                calls(entry.value(), required, kinds);
            });
            return;
        }
        if (expression instanceof FclExpression.Unary value) {
            calls(value.operand(), required, kinds);
            return;
        }
        if (expression instanceof FclExpression.Binary value) {
            calls(value.left(), required, kinds);
            calls(value.right(), required, kinds);
            return;
        }
        if (expression instanceof FclExpression.Index value) {
            calls(value.target(), required, kinds);
            calls(value.index(), required, kinds);
        }
    }

    /** Resolves the capability of a dotted call name, using tracked local variable kinds. */
    private static java.util.Optional<String> namedCallCapability(String name,
                                                                  Map<String, String> kinds) {
        int separator = name.indexOf('.');
        if (separator > 0) {
            String receiver = name.substring(0, separator);
            String method = name.substring(separator + 1);
            String kind = kinds.get(receiver);
            if (kind != null && !method.contains(".")) {
                return methodCapability(kind, method);
            }
        }
        return namespaceCapability(name);
    }

    /** Records which object kind a simple assignment result represents. */
    private static String kindOf(FclExpression expression, Map<String, String> kinds) {
        if (expression instanceof FclExpression.Variable variable) {
            return kinds.get(variable.name());
        }
        if (expression instanceof FclExpression.Call call) {
            return callKind(call.name(), kinds);
        }
        if (expression instanceof FclExpression.MethodCall call) {
            return methodResultKind(kindOf(call.target(), kinds), call.name());
        }
        return null;
    }

    private static String callKind(String name, Map<String, String> kinds) {
        return switch (name) {
            case "file.open" -> FILE;
            case "file.create" -> FILE;
            case "file.mkdir" -> DIRECTORY;
            case "file.path" -> PATH;
            case "process.find" -> PROCESS;
            case "swapPool.open", "swapPool.create" -> SWAP_POOL;
            case "ipc.channel", "ipc.createChannel" -> CHANNEL;
            case "ipc.topic", "ipc.createTopic" -> TOPIC;
            case "ipc.delivery" -> DELIVERY;
            case "package.find", "package.install" -> PACKAGE;
            case "package.quotaFor" -> PACKAGE_QUOTA;
            case "packageData.current" -> PACKAGE_SPACE;
            case "user.find", "user.create" -> USER;
            case "program.find" -> PROGRAM;
            case "terminal.find" -> TERMINAL;
            default -> {
                int separator = name.indexOf('.');
                if (separator > 0) {
                    String receiver = name.substring(0, separator);
                    String method = name.substring(separator + 1);
                    String kind = kinds.get(receiver);
                    if (kind != null && !method.contains(".")) {
                        yield methodResultKind(kind, method);
                    }
                }
                yield null;
            }
        };
    }

    private static String methodResultKind(String kind, String method) {
        if (kind == null) return null;
        if (kind.equals(PACKAGE) && method.equals("data")) return PACKAGE_SPACE;
        if (kind.equals(SWAP_POOL) && method.equals("variable")) return SWAP_VARIABLE;
        if ((kind.equals(FILE) || kind.equals(DIRECTORY) || kind.equals(SWAP_VARIABLE))
                && method.equals("lock")) {
            return LEASE;
        }
        return null;
    }

    private static java.util.Optional<String> methodCapability(String kind, String method) {
        if (kind == null) return java.util.Optional.empty();
        return switch (kind) {
            case FILE, DIRECTORY -> FILE_READ_METHODS.contains(method)
                    ? java.util.Optional.of("vfs.read")
                    : FILE_WRITE_METHODS.contains(method)
                    ? java.util.Optional.of("vfs.write") : java.util.Optional.empty();
            case PATH -> method.equals("exists")
                    ? java.util.Optional.of("vfs.read") : java.util.Optional.empty();
            case PROCESS -> PROCESS_METHODS.contains(method)
                    ? java.util.Optional.of("process.control") : java.util.Optional.empty();
            case PACKAGE -> PACKAGE_MANAGE_METHODS.contains(method)
                    ? java.util.Optional.of("package.manage")
                    : PACKAGE_DATA_METHODS.contains(method)
                    ? java.util.Optional.of("package.data") : java.util.Optional.empty();
            case PACKAGE_SPACE -> PACKAGE_DATA_METHODS.contains(method)
                    ? java.util.Optional.of("package.data") : java.util.Optional.empty();
            case PACKAGE_QUOTA -> QUOTA_METHODS.contains(method)
                    ? java.util.Optional.of("package.manage") : java.util.Optional.empty();
            case USER, PROGRAM, TERMINAL -> java.util.Optional.of("system.admin");
            default -> java.util.Optional.empty();
        };
    }

    /** Capabilities of namespace service calls (no tracked receiver object). */
    private static java.util.Optional<String> namespaceCapability(String call) {
        if (call.startsWith("system.") && !call.equals("system.list")
                && !call.equals("system.extensions")) {
            return java.util.Optional.of("system.admin");
        }
        if (call.equals("socket.bind") || call.equals("socket.accept")
                || call.equals("timer.purge") || call.equals("audit.purge")
                || call.equals("storage.purgeUnreferenced") || call.equals("user.list")) {
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
        if (Set.of("io.input", "util.input", "io.readKey", "io.readChar",
                "input", "readKey", "readChar").contains(call)) {
            return java.util.Optional.of("terminal.raw_input");
        }
        if (call.equals("process.fork") || call.equals("fork")) {
            return java.util.Optional.of("process.create");
        }
        if (Set.of("process.exec", "process.kill", "process.pause", "process.continue",
                "process.list", "process.listChildren", "process.getPPID",
                "process.find", "process.clearFinished").contains(call)) {
            return java.util.Optional.of("process.control");
        }
        if (call.startsWith("market.") && !Set.of("market.origin", "market.search",
                "market.info", "market.list", "market.help", "market.run").contains(call)) {
            return java.util.Optional.of("package.manage");
        }
        if (call.startsWith("packageData.")) {
            return java.util.Optional.of("package.data");
        }
        if (call.equals("package.install") || call.equals("package.build")
                || call.equals("package.recover") || call.equals("package.quotaFor")) {
            return java.util.Optional.of("package.manage");
        }
        if (call.equals("file.open") || call.equals("file.path")) {
            return java.util.Optional.of("vfs.read");
        }
        if (call.equals("file.create") || call.equals("file.mkdir")
                || call.equals("file.link")) {
            return java.util.Optional.of("vfs.write");
        }
        if (Set.of("readFile", "readChunk", "readMetaData", "list",
                "exists", "fileSize").contains(call)) return java.util.Optional.of("vfs.read");
        if (Set.of("writeFile", "appendFile", "createFile", "createDir", "remove",
                "rename", "clear").contains(call)) return java.util.Optional.of("vfs.write");
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
            case "process_control" -> "process.control";
            case "package_manage" -> "package.manage";
            case "package_data" -> "package.data";
            case "system_admin" -> "system.admin";
            default -> key;
        };
    }
}
