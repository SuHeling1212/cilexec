package com.follarce.extension;

import com.follarce.extension.api.CilExecExtension;

import java.util.List;

/**
 * The only production source extension index.
 *
 * <p>Add extension constructors to {@link #sourceExtensions()} before building CilExec. The
 * resulting catalog is immutable, and no runtime path can add, remove, or replace entries.</p>
 */
public final class SourceExtensionIndex {
    private static final JavaExtensionCatalog CATALOG =
            JavaExtensionCatalog.compile(sourceExtensions());

    private SourceExtensionIndex() {
    }

    public static JavaExtensionCatalog catalog() {
        return CATALOG;
    }

    private static List<CilExecExtension> sourceExtensions() {
        return List.of(
                // new YourExtension()
        );
    }
}
