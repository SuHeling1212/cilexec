package com.follarce.function;

import com.follarce.Constants;
import com.follarce.pack.PackageException;
import com.follarce.pack.PackageManager;

import java.util.List;

/** FCL package.* API for the effective process user. */
public final class PackageFunctionProvider implements FunctionProvider {
    private final PackageManager manager;

    public PackageFunctionProvider() {
        this(PackageManager.getInstance());
    }

    PackageFunctionProvider(PackageManager manager) {
        this.manager = manager;
    }

    @Override
    public String getNamespace() {
        return "package";
    }

    @Override
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            String user = context.getCurrentUser();
            return switch (functionName) {
                case "build" -> manager.build(user,
                        context.resolvePath(stringArg(args, 0, "source")),
                        context.resolvePath(stringArg(args, 1, "output")));
                case "install" -> manager.install(user,
                        context.resolvePath(stringArg(args, 0, "source")),
                        optionalStringArg(args, 1),
                        resolveOptionalPath(args, 2, context),
                        context.getEffectId(), context.getPid(), context.getProcessGeneration());
                case "remove" -> manager.remove(user, stringArg(args, 0, "binding"),
                        context.getEffectId(), context.getPid(), context.getProcessGeneration());
                case "list" -> manager.list(user);
                case "info" -> manager.info(user, stringArg(args, 0, "binding"));
                case "verify" -> manager.verify(user, stringArg(args, 0, "binding"));
                case "resource" -> manager.readResource(user,
                        stringArg(args, 0, "binding"), stringArg(args, 1, "resource"));
                case "pin" -> manager.pin(user, stringArg(args, 0, "binding or integrity"));
                case "unpin" -> manager.unpin(user, stringArg(args, 0, "binding or integrity"));
                case "gc" -> {
                    requireLocal(user, "garbage collection");
                    yield manager.garbageCollect();
                }
                case "recover" -> {
                    requireLocal(user, "package recovery");
                    manager.recoverTransactions();
                    yield "Package recovery completed";
                }
                default -> null;
            };
        } catch (PackageException | IllegalArgumentException e) {
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
        }
    }

    private static String resolveOptionalPath(List<Object> args, int index, FunctionContext context) {
        String value = optionalStringArg(args, index);
        return value == null || value.isBlank() ? null : context.resolvePath(value);
    }

    private static String stringArg(List<Object> args, int index, String label) {
        String value = optionalStringArg(args, index);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("package." + label + " argument is required");
        }
        return value;
    }

    private static String optionalStringArg(List<Object> args, int index) {
        if (args == null || index >= args.size() || args.get(index) == null) return null;
        return args.get(index).toString();
    }

    private static void requireLocal(String user, String operation) {
        if (!Constants.DEFAULT_USER_LOCAL.equals(user)) {
            throw new PackageException("Only local may run global package " + operation);
        }
    }
}
