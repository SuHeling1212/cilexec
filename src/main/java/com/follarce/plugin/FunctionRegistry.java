package com.follarce.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Function registry center
 * Unified management of all function providers
 */
public class FunctionRegistry {
    
    private static final List<FunctionProvider> providers = new ArrayList<>();
    
    /**
     * Register function provider
     * 
     * @param provider Function provider
     */
    public static void register(FunctionProvider provider) {
        if (provider != null) {
            providers.add(provider);
        }
    }
    
    /**
     * Call function
     * 
     * @param name    Function name
     * @param args    Argument array
     * @param context Call context
     * @return Function return value, returns null if function does not exist
     */
    public static Object call(String name, Object[] args, FunctionContext context) {
        Object lastError = null;
        for (FunctionProvider provider : providers) {
            Object result = provider.call(name, args, context);
            if (result != null) {
                if (isErrorResult(result)) {
                    lastError = result;
                    continue;
                }
                return result;
            }
        }
        return lastError;
    }
    
    private static boolean isErrorResult(Object result) {
        if (result instanceof Object[]) {
            Object[] arr = (Object[]) result;
            return arr.length >= 1 && "ERROR".equals(arr[0]);
        }
        if (result instanceof String[]) {
            String[] arr = (String[]) result;
            return arr.length >= 1 && "ERROR".equals(arr[0]);
        }
        return false;
    }
    
    /**
     * Call function (simplified version, no context)
     * 
     * @param name Function name
     * @param args Argument array
     * @return Function return value
     */
    public static Object call(String name, Object[] args) {
        return call(name, args, new FunctionContext(0, 0, "local"));
    }
    
    /**
     * Get all registered function information
     * 
     * @return All function information
     */
    public static List<FunctionInfo> getAllFunctions() {
        List<FunctionInfo> allFunctions = new ArrayList<>();
        for (FunctionProvider provider : providers) {
            FunctionInfo[] functions = provider.getFunctions();
            if (functions != null) {
                for (FunctionInfo info : functions) {
                    allFunctions.add(info);
                }
            }
        }
        return allFunctions;
    }
    
    /**
     * Generate Markdown format API documentation
     * 
     * @return Markdown document
     */
    public static String generateDocumentation() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Script API Documentation\n\n");
        sb.append("| Function Name | Parameters | Return Type | Description |\n");
        sb.append("|---------------|------------|-------------|-------------|\n");
        
        for (FunctionInfo info : getAllFunctions()) {
            sb.append(info.toMarkdown()).append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Clear all registered providers (mainly for testing)
     */
    public static void clear() {
        providers.clear();
    }
    
    /**
     * Get the number of registered providers
     */
    public static int getProviderCount() {
        return providers.size();
    }
}
