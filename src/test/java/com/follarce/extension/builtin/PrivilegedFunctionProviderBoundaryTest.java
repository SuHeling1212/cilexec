package com.follarce.extension.builtin;

import com.follarce.extension.builtin.PrivilegedFunctionProvider;
import com.follarce.kernel.Constants;
import com.follarce.kernel.api.function.FunctionContext;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivilegedFunctionProviderBoundaryTest {
    @Test
    void reflectionAllowsTheJavaRuntimeButRejectsClassesOutsideTheCilexecBinary() {
        PrivilegedFunctionProvider provider = new PrivilegedFunctionProvider();
        FunctionContext context = new FunctionContext(1, 0, "local");

        assertInstanceOf(Number.class, provider.call("invoke",
                List.of("java.lang.System", "currentTimeMillis"), context));

        Object denied = provider.call("invoke",
                List.of(getClass().getName(), "externalProbe"), context);
        Object[] error = assertInstanceOf(Object[].class, denied);
        assertEquals(Constants.ERROR_MARKER, error[0]);
        assertTrue(error[1].toString().contains("outside the Cilexec binary"));
    }

    public static String externalProbe() {
        return "must-not-run";
    }
}
