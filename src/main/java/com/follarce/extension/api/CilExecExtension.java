package com.follarce.extension.api;

/**
 * Source-only Java extension entry point.
 *
 * <p>Implementations are instantiated explicitly by the source extension index while the
 * application is assembled. CilExec deliberately provides no runtime discovery mechanism.</p>
 */
public interface CilExecExtension {
    ExtensionDescriptor descriptor();

    /** Declares all functions and effects. Registration must be deterministic and side-effect free. */
    void register(ExtensionRegistrar registrar);
}
