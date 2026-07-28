package com.follarce.extension.api;

/** Build-time declaration surface supplied to one source extension. */
public interface ExtensionRegistrar {
    void function(String namespace, String name, ExtensionFunction function, String... aliases);

    void effect(ExtensionEffectHandler handler);
}
