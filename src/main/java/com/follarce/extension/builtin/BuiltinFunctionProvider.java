package com.follarce.extension.builtin;

import com.follarce.kernel.api.function.EffectPolicy;
import com.follarce.kernel.api.function.FunctionProvider;

/** Base contract for function providers compiled into the Cilexec binary. */
public abstract class BuiltinFunctionProvider implements FunctionProvider {
    @Override
    public EffectPolicy getEffectPolicy(String functionName) {
        return BuiltinFunctionCatalog.policy(getNamespace(), functionName);
    }
}
