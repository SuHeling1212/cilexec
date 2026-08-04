package com.follarce.fcl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministically links immutable package library modules behind public import names. */
public final class FclProgramLinker {
    public record Export(String symbol, List<String> publicNames) {
        public Export {
            Objects.requireNonNull(symbol, "symbol");
            publicNames = List.copyOf(publicNames);
        }
    }

    public record Module(String packageIdentity, String moduleName, String source,
                         List<Export> exports) {
        public Module {
            Objects.requireNonNull(packageIdentity, "packageIdentity");
            Objects.requireNonNull(moduleName, "moduleName");
            Objects.requireNonNull(source, "source");
            exports = List.copyOf(exports);
        }
    }

    private final FclCompiler compiler = new FclCompiler();
    private final Map<String, FclProgram> compiledModules = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, FclProgram> eldest) {
                    return size() > 128;
                }
            });

    public FclProgram link(FclProgram base, List<Module> requestedModules) {
        Objects.requireNonNull(base, "base");
        List<Module> modules = List.copyOf(requestedModules);
        if (modules.isEmpty()) return base;

        List<Context> contexts = new ArrayList<>();
        long expressionOffset = maximumExpressionId(base.instructions());
        for (int index = 0; index < modules.size(); index++) {
            Module module = modules.get(index);
            String moduleKey = module.packageIdentity() + "\u0000" + module.moduleName();
            FclProgram compiled;
            synchronized (compiledModules) {
                compiled = compiledModules.get(moduleKey);
                if (compiled == null) {
                    compiled = compiler.compile(module.source());
                    compiledModules.put(moduleKey, compiled);
                } else if (!compiled.source().equals(module.source())) {
                    throw new FclRuntimeException(
                            "Package module identity resolved to different source");
                }
            }
            requireLibraryModule(module, compiled);
            long maximum = maximumExpressionId(compiled.instructions());
            contexts.add(new Context(module, compiled, expressionOffset,
                    internalPrefix(module, index)));
            try {
                expressionOffset = Math.addExact(expressionOffset, maximum);
            } catch (ArithmeticException overflow) {
                throw new FclRuntimeException(
                        "Linked package expression identifiers exceed the supported range",
                        overflow);
            }
        }

        Map<String, Map<String, String>> internalNames = internalNames(contexts);
        List<FclInstruction> instructions = new ArrayList<>(base.instructions());
        int exitJump = instructions.size();
        instructions.add(new FclInstruction.Jump(-1, -1));
        Map<String, FclProgram.Function> functions = new LinkedHashMap<>(base.functions());

        for (Context context : contexts) {
            int offset = instructions.size();
            Map<String, String> localNames = internalNames.get(context.identityKey());
            for (FclInstruction instruction : context.program().instructions()) {
                instructions.add(copyInstruction(instruction, offset, context.expressionOffset(),
                        context, internalNames, localNames));
            }
            for (Map.Entry<String, FclProgram.Function> entry
                    : context.program().functions().entrySet()) {
                FclProgram.Function source = entry.getValue();
                String internal = localNames.get(entry.getKey());
                FclProgram.Function linked = new FclProgram.Function(internal,
                        source.parameters(), source.entryPoint() + offset,
                        source.endPoint() + offset);
                bind(functions, internal, linked);
            }
            for (Export export : context.module().exports()) {
                String internal = localNames.get(export.symbol());
                if (internal == null) throw new FclRuntimeException("Package export is missing: "
                        + context.module().moduleName() + "." + export.symbol());
                FclProgram.Function linked = functions.get(internal);
                for (String publicName : export.publicNames()) {
                    validatePublicName(publicName);
                    bind(functions, publicName, linked);
                }
            }
        }
        instructions.set(exitJump, new FclInstruction.Jump(-1, instructions.size()));
        StringBuilder linkedSource = new StringBuilder(base.source());
        for (Context context : contexts) {
            linkedSource.append("\n// linked ").append(context.module().packageIdentity())
                    .append('/').append(context.module().moduleName()).append('\n')
                    .append(context.module().source());
        }
        return new FclProgram(instructions, functions, linkedSource.toString());
    }

    private static Map<String, Map<String, String>> internalNames(List<Context> contexts) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Context context : contexts) {
            Map<String, String> names = new LinkedHashMap<>();
            context.program().functions().keySet().forEach(name -> names.put(name,
                    context.prefix() + safe(name)));
            result.put(context.identityKey(), Map.copyOf(names));
        }
        return Map.copyOf(result);
    }

    private static FclInstruction copyInstruction(FclInstruction instruction, int offset,
                                                  long expressionOffset, Context context,
                                                  Map<String, Map<String, String>> allNames,
                                                  Map<String, String> localNames) {
        java.util.function.Function<FclExpression, FclExpression> expression = value ->
                copyExpression(value, expressionOffset, context, allNames, localNames);
        if (instruction instanceof FclInstruction.Assignment value) {
            return new FclInstruction.Assignment(value.line(), value.variable(),
                    value.indices().stream().map(expression).toList(),
                    expression.apply(value.value()));
        }
        if (instruction instanceof FclInstruction.Evaluation value) {
            return new FclInstruction.Evaluation(value.line(), expression.apply(value.expression()));
        }
        if (instruction instanceof FclInstruction.Conditional value) {
            return new FclInstruction.Conditional(value.line(), expression.apply(value.condition()),
                    value.falseTarget() + offset, value.endTarget() + offset);
        }
        if (instruction instanceof FclInstruction.Loop value) {
            return new FclInstruction.Loop(value.line(), expression.apply(value.condition()),
                    value.bodyTarget() + offset, value.endTarget() + offset);
        }
        if (instruction instanceof FclInstruction.Break value) {
            return new FclInstruction.Break(value.line());
        }
        if (instruction instanceof FclInstruction.Continue value) {
            return new FclInstruction.Continue(value.line());
        }
        if (instruction instanceof FclInstruction.Return value) {
            return new FclInstruction.Return(value.line(), expression.apply(value.value()),
                    value.implicit());
        }
        if (instruction instanceof FclInstruction.FunctionDeclaration value) {
            return new FclInstruction.FunctionDeclaration(value.line(),
                    localNames.get(value.name()), value.parameters(), value.bodyTarget() + offset,
                    value.endTarget() + offset);
        }
        if (instruction instanceof FclInstruction.Jump value) {
            return new FclInstruction.Jump(value.line(), value.target() + offset);
        }
        throw new FclRuntimeException("Package modules cannot contain import/include directives");
    }

    private static FclExpression copyExpression(FclExpression expression, long offset,
                                                Context context,
                                                Map<String, Map<String, String>> allNames,
                                                Map<String, String> localNames) {
        final long id;
        try {
            id = Math.addExact(expression.id(), offset);
        } catch (ArithmeticException overflow) {
            throw new FclRuntimeException(
                    "Linked package expression identifiers exceed the supported range", overflow);
        }
        if (expression instanceof FclExpression.Literal value) {
            return new FclExpression.Literal(id, value.value());
        }
        if (expression instanceof FclExpression.Variable value) {
            return new FclExpression.Variable(id, value.name());
        }
        if (expression instanceof FclExpression.ArrayLiteral value) {
            return new FclExpression.ArrayLiteral(id, value.elements().stream()
                    .map(item -> copyExpression(item, offset, context, allNames, localNames)).toList());
        }
        if (expression instanceof FclExpression.MapLiteral value) {
            return new FclExpression.MapLiteral(id, value.entries().stream().map(entry ->
                    new FclExpression.MapEntry(
                            copyExpression(entry.key(), offset, context, allNames, localNames),
                            copyExpression(entry.value(), offset, context, allNames, localNames)))
                    .toList());
        }
        if (expression instanceof FclExpression.Unary value) {
            return new FclExpression.Unary(id, value.operator(), copyExpression(value.operand(),
                    offset, context, allNames, localNames));
        }
        if (expression instanceof FclExpression.Binary value) {
            return new FclExpression.Binary(id, value.operator(),
                    copyExpression(value.left(), offset, context, allNames, localNames),
                    copyExpression(value.right(), offset, context, allNames, localNames));
        }
        if (expression instanceof FclExpression.Index value) {
            return new FclExpression.Index(id,
                    copyExpression(value.target(), offset, context, allNames, localNames),
                    copyExpression(value.index(), offset, context, allNames, localNames));
        }
        FclExpression.Call call = (FclExpression.Call) expression;
        String name = resolveCallName(call.name(), context, allNames, localNames);
        return new FclExpression.Call(id, name, call.arguments().stream()
                .map(item -> copyExpression(item, offset, context, allNames, localNames)).toList());
    }

    private static String resolveCallName(String name, Context context,
                                          Map<String, Map<String, String>> allNames,
                                          Map<String, String> localNames) {
        String local = localNames.get(name);
        if (local != null) return local;
        int separator = name.indexOf('.');
        if (separator > 0 && separator < name.length() - 1) {
            String moduleName = name.substring(0, separator);
            String symbol = name.substring(separator + 1);
            Map<String, String> target = allNames.get(context.module().packageIdentity()
                    + "\u0000" + moduleName);
            if (target != null && target.containsKey(symbol)) return target.get(symbol);
        }
        return name;
    }

    private static void requireLibraryModule(Module module, FclProgram program) {
        BitSet bodies = new BitSet(program.instructions().size());
        for (FclProgram.Function function : program.functions().values()) {
            bodies.set(function.entryPoint(), function.endPoint());
        }
        for (int index = 0; index < program.instructions().size(); index++) {
            if (bodies.get(index)) continue;
            if (!(program.instructions().get(index) instanceof FclInstruction.FunctionDeclaration)) {
                throw new FclRuntimeException("Package module must contain only top-level function "
                        + "declarations: " + module.moduleName());
            }
        }
    }

    private static long maximumExpressionId(List<FclInstruction> instructions) {
        long maximum = 0;
        for (FclInstruction instruction : instructions) {
            if (instruction instanceof FclInstruction.Assignment value) {
                for (FclExpression index : value.indices()) maximum = Math.max(maximum, max(index));
                maximum = Math.max(maximum, max(value.value()));
            } else if (instruction instanceof FclInstruction.Evaluation value) {
                maximum = Math.max(maximum, max(value.expression()));
            } else if (instruction instanceof FclInstruction.Conditional value) {
                maximum = Math.max(maximum, max(value.condition()));
            } else if (instruction instanceof FclInstruction.Loop value) {
                maximum = Math.max(maximum, max(value.condition()));
            } else if (instruction instanceof FclInstruction.Return value) {
                maximum = Math.max(maximum, max(value.value()));
            }
        }
        return maximum;
    }

    private static long max(FclExpression expression) {
        if (expression == null) return 0;
        long maximum = expression.id();
        if (expression instanceof FclExpression.ArrayLiteral value) {
            for (FclExpression child : value.elements()) maximum = Math.max(maximum, max(child));
        } else if (expression instanceof FclExpression.MapLiteral value) {
            for (FclExpression.MapEntry entry : value.entries()) {
                maximum = Math.max(maximum, max(entry.key()));
                maximum = Math.max(maximum, max(entry.value()));
            }
        } else if (expression instanceof FclExpression.Unary value) {
            maximum = Math.max(maximum, max(value.operand()));
        } else if (expression instanceof FclExpression.Binary value) {
            maximum = Math.max(maximum, max(value.left()));
            maximum = Math.max(maximum, max(value.right()));
        } else if (expression instanceof FclExpression.Index value) {
            maximum = Math.max(maximum, max(value.target()));
            maximum = Math.max(maximum, max(value.index()));
        } else if (expression instanceof FclExpression.Call value) {
            for (FclExpression child : value.arguments()) maximum = Math.max(maximum, max(child));
        }
        return maximum;
    }

    private static String internalPrefix(Module module, int index) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (module.packageIdentity() + "\u0000" + module.moduleName())
                            .getBytes(StandardCharsets.UTF_8));
            return "__pkg_" + java.util.HexFormat.of().formatHex(digest, 0, 8)
                    + "_" + index + "_";
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static void validatePublicName(String name) {
        String identifier = "[A-Za-z_][A-Za-z0-9_]*";
        String namespace = "(?:" + identifier + "|[0-9A-Fa-f]{64})";
        if (name == null || !(name.matches(identifier)
                || name.matches(namespace + "\\." + identifier))) {
            throw new FclRuntimeException("Invalid imported function name: " + name);
        }
    }

    private static void bind(Map<String, FclProgram.Function> functions, String name,
                             FclProgram.Function function) {
        if (functions.putIfAbsent(name, function) != null) {
            throw new FclRuntimeException("Imported function conflicts with existing function: "
                    + name);
        }
    }

    private record Context(Module module, FclProgram program, long expressionOffset,
                           String prefix) {
        private String identityKey() {
            return module.packageIdentity() + "\u0000" + module.moduleName();
        }
    }
}
