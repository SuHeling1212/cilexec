package com.follarce.extension.api;

/** A Java implementation of one FCL function. */
@FunctionalInterface
public interface ExtensionFunction {
    Object invoke(ExtensionFunctionContext context);
}
