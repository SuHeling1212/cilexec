package com.follarce.extension;

import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.process.CilProcess;
import com.follarce.effect.EffectHandler;
import com.follarce.extension.api.CilExecExtension;
import com.follarce.extension.api.ExtensionDescriptor;
import com.follarce.extension.api.ExtensionEffectHandler;
import com.follarce.extension.api.ExtensionFunction;
import com.follarce.extension.api.ExtensionRegistrar;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable runtime catalog compiled from the one explicit source extension index. */
public final class JavaExtensionCatalog {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern EFFECT_TYPE = Pattern.compile(
            "[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9_-]*)+");
    // Compile-time guard; keep in sync with the namespaces actually registered by
    // FclRuntimeFunctions.register() (and FclBuiltins.pureRegistry()), because
    // installFunctions additionally rejects any namespace already live in the registry.
    private static final Set<String> RESERVED_NAMESPACES = Set.of(
            "math", "util", "text", "path", "term", "file", "io", "process", "user",
            "swapPool", "network", "socket", "package", "system");
    private static final Set<String> RESERVED_EFFECT_TYPES = Set.of(
            "io.output", "network.http-get", "network.http-post", "network.download",
            "system.exec", "socket.connect", "socket.send", "socket.receive", "socket.close",
            "socket.bind", "socket.accept");

    private final List<ExtensionDescriptor> descriptors;
    private final List<RegisteredFunction> functions;
    private final List<EffectHandler> effects;
    private final Set<String> namespaces;

    private JavaExtensionCatalog(List<ExtensionDescriptor> descriptors,
                                 List<RegisteredFunction> functions,
                                 List<EffectHandler> effects,
                                 Set<String> namespaces) {
        this.descriptors = List.copyOf(descriptors);
        this.functions = List.copyOf(functions);
        this.effects = List.copyOf(effects);
        this.namespaces = Collections.unmodifiableSet(new LinkedHashSet<>(namespaces));
    }

    public static JavaExtensionCatalog empty() {
        return compile(List.of());
    }

    /** Executes deterministic source declarations once and seals their resulting catalog. */
    public static JavaExtensionCatalog compile(List<? extends CilExecExtension> extensions) {
        Objects.requireNonNull(extensions, "extensions");
        Builder builder = new Builder();
        for (CilExecExtension extension : List.copyOf(extensions)) {
            Objects.requireNonNull(extension, "extension");
            ExtensionDescriptor descriptor = Objects.requireNonNull(extension.descriptor(),
                    "extension descriptor");
            builder.begin(descriptor);
            try {
                extension.register(builder);
            } finally {
                builder.end();
            }
        }
        return builder.build();
    }

    public List<ExtensionDescriptor> descriptors() {
        return descriptors;
    }

    public List<EffectHandler> effectHandlers() {
        return effects;
    }

    public Set<String> namespaces() {
        return namespaces;
    }

    /** Binds the sealed declarations to one process statement's durable execution context. */
    public void installFunctions(FclFunctionRegistry registry, TransactionContext transaction,
                                 CilProcess process, FclContinuation continuation, Instant now) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(continuation, "continuation");
        Objects.requireNonNull(now, "now");
        // Snapshot the pre-existing (built-in) registrations once so sibling extensions in this
        // catalog may still share namespaces and bare spellings already proven non-conflicting.
        Set<String> existingQualified = registry.qualifiedNames();
        for (RegisteredFunction definition : functions) {
            if (existingQualified.stream().anyMatch(
                    qualified -> namespaceOf(qualified).equals(definition.namespace()))) {
                throw new IllegalStateException("Java extension namespace conflicts with an "
                        + "existing FCL namespace: " + definition.namespace());
            }
            for (String qualified : definition.qualifiedSpellings()) {
                if (existingQualified.contains(qualified)) {
                    throw new IllegalStateException("Java extension function conflicts with an "
                            + "existing FCL function: " + qualified);
                }
                String bare = bareName(qualified);
                if (existingQualified.stream().anyMatch(
                        existing -> bareName(existing).equals(bare))) {
                    throw new IllegalStateException("Java extension function conflicts with an "
                            + "existing FCL bare name: " + bare + " (from " + qualified + ")");
                }
            }
        }
        for (RegisteredFunction definition : functions) {
            registry.registerContextual(definition.namespace(), definition.name(),
                    (arguments, invocation) -> definition.function().invoke(
                            new ExtensionFunctionInvocation(definition.extension().id(),
                                    definition.qualifiedName(), arguments, invocation,
                                    transaction, process, now)),
                    definition.aliases().toArray(String[]::new));
        }
    }

    private static String namespaceOf(String qualified) {
        return qualified.substring(0, qualified.indexOf('.'));
    }

    private static String bareName(String qualified) {
        return qualified.substring(qualified.indexOf('.') + 1);
    }

    private record RegisteredFunction(ExtensionDescriptor extension, String namespace,
                                      String name, Set<String> aliases,
                                      ExtensionFunction function) {
        private RegisteredFunction {
            aliases = Collections.unmodifiableSet(new LinkedHashSet<>(aliases));
        }

        private String qualifiedName() {
            return namespace + "." + name;
        }

        private Set<String> qualifiedSpellings() {
            Set<String> names = new LinkedHashSet<>();
            names.add(qualifiedName());
            aliases.forEach(alias -> names.add(namespace + "." + alias));
            return names;
        }
    }

    private static final class Builder implements ExtensionRegistrar {
        private final List<ExtensionDescriptor> descriptors = new ArrayList<>();
        private final List<RegisteredFunction> functions = new ArrayList<>();
        private final List<EffectHandler> effects = new ArrayList<>();
        private final Set<String> extensionIds = new LinkedHashSet<>();
        private final Set<String> qualifiedFunctions = new LinkedHashSet<>();
        private final Set<String> bareFunctions = new LinkedHashSet<>();
        private final Set<String> effectTypes = new LinkedHashSet<>();
        private final Set<String> namespaces = new LinkedHashSet<>();
        private ExtensionDescriptor current;

        private void begin(ExtensionDescriptor descriptor) {
            if (current != null) throw new IllegalStateException("Nested extension registration");
            if (!extensionIds.add(descriptor.id())) {
                throw new IllegalArgumentException("Duplicate Java extension id: "
                        + descriptor.id());
            }
            descriptors.add(descriptor);
            current = descriptor;
        }

        private void end() {
            current = null;
        }

        @Override
        public void function(String namespace, String name, ExtensionFunction function,
                             String... aliases) {
            ExtensionDescriptor extension = requireCurrent();
            validateName(namespace, "namespace");
            validateName(name, "function");
            if (RESERVED_NAMESPACES.contains(namespace)) {
                throw new IllegalArgumentException("Java extensions cannot modify the built-in "
                        + "FCL namespace: " + namespace);
            }
            Objects.requireNonNull(function, "function");
            Set<String> checkedAliases = new LinkedHashSet<>();
            if (aliases != null) {
                for (String alias : aliases) {
                    validateName(alias, "alias");
                    checkedAliases.add(alias);
                }
            }
            RegisteredFunction definition = new RegisteredFunction(extension, namespace, name,
                    checkedAliases, function);
            for (String qualified : definition.qualifiedSpellings()) {
                if (!qualifiedFunctions.add(qualified)) {
                    throw new IllegalArgumentException("Duplicate Java extension function: "
                            + qualified);
                }
                if (!bareFunctions.add(qualified.substring(qualified.indexOf('.') + 1))) {
                    throw new IllegalArgumentException("Duplicate Java extension bare name: "
                            + qualified.substring(qualified.indexOf('.') + 1));
                }
            }
            namespaces.add(namespace);
            functions.add(definition);
        }

        @Override
        public void effect(ExtensionEffectHandler handler) {
            ExtensionDescriptor extension = requireCurrent();
            Objects.requireNonNull(handler, "handler");
            String effectType = Objects.requireNonNull(handler.effectType(), "effectType");
            if (effectType.length() > 128 || !EFFECT_TYPE.matcher(effectType).matches()) {
                throw new IllegalArgumentException("Invalid Java extension effect type: "
                        + effectType);
            }
            if (RESERVED_EFFECT_TYPES.contains(effectType)) {
                throw new IllegalArgumentException("Java extensions cannot replace a built-in "
                        + "effect handler: " + effectType);
            }
            if (!effectTypes.add(effectType)) {
                throw new IllegalArgumentException("Duplicate Java extension effect: "
                        + effectType);
            }
            effects.add(new ExtensionEffectAdapter(extension, effectType, handler));
        }

        private ExtensionDescriptor requireCurrent() {
            if (current == null) throw new IllegalStateException(
                    "Registration is only valid inside CilExecExtension.register");
            return current;
        }

        private JavaExtensionCatalog build() {
            if (current != null) throw new IllegalStateException("Extension registration open");
            return new JavaExtensionCatalog(descriptors, functions, effects, namespaces);
        }
    }


    private static void validateName(String value, String kind) {
        if (value == null || value.length() > 128 || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + kind + " name: " + value);
        }
    }
}
