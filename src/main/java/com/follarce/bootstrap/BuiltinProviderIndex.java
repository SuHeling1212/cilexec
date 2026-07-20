package com.follarce.bootstrap;

import com.follarce.extension.builtin.FileFunctionProvider;
import com.follarce.extension.builtin.IOFunctionProvider;
import com.follarce.extension.builtin.MathFunctionProvider;
import com.follarce.extension.builtin.NetworkFunctionProvider;
import com.follarce.extension.builtin.PackageFunctionProvider;
import com.follarce.extension.builtin.PathFunctionProvider;
import com.follarce.extension.builtin.PrivilegedFunctionProvider;
import com.follarce.extension.builtin.ProcessFunctionProvider;
import com.follarce.extension.builtin.SocketFunctionProvider;
import com.follarce.extension.builtin.SwapFunctionProvider;
import com.follarce.extension.builtin.TermFunctionProvider;
import com.follarce.extension.builtin.UserFunctionProvider;
import com.follarce.extension.builtin.UtilFunctionProvider;
import com.follarce.kernel.api.function.FunctionProvider;
import com.follarce.kernel.function.FunctionRegistry;

import java.util.List;

/** Compile-time index of every extension built into the single Cilexec binary. */
public final class BuiltinProviderIndex {
    private BuiltinProviderIndex() {}

    public static List<FunctionProvider> createAll() {
        return List.of(
                new FileFunctionProvider(),
                new IOFunctionProvider(),
                new MathFunctionProvider(),
                new NetworkFunctionProvider(),
                new PackageFunctionProvider(),
                new PathFunctionProvider(),
                new PrivilegedFunctionProvider(),
                new ProcessFunctionProvider(),
                new SocketFunctionProvider(),
                new SwapFunctionProvider(),
                new TermFunctionProvider(),
                new UserFunctionProvider(),
                new UtilFunctionProvider()
        );
    }

    public static int install() {
        return FunctionRegistry.registerProviders(createAll());
    }
}
