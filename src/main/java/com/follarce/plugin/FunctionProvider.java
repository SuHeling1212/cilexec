package com.follarce.plugin;

/**
 * Script function provider interface
 * All modules providing script-callable functions should implement this interface
 */
public interface FunctionProvider {
    
    /**
     * Call function
     *
     * @param name Function name
     * @param args Argument array
     * @param context Call context (contains current PID, etc.)
     * @return Function return value, returns null if function does not exist
     */
    Object call(String name, Object[] args, FunctionContext context);
    
    /**
     * Get list of functions supported by this provider (for documentation generation)
     *
     * @return Function info array
     */
    default FunctionInfo[] getFunctions() {
        return new FunctionInfo[0];
    }
    
    /**
     * Get provider name
     */
    default String getProviderName() {
        return this.getClass().getSimpleName();
    }
}
