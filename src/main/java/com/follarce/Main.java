package com.follarce;

import com.follarce.init.*;
import com.follarce.plugin.*;
import com.follarce.util.*;

import java.util.*;

public class Main {

    public static void main(String[] args) {
        // Initialize system components
        FileInit.init();
        
        // Register function providers for script engine
        registerFunctionProviders();
        
        ProcessInit.init();
    }
    
    /**
     * Register all function providers to FunctionRegistry
     * This is the core of the plugin system where all script-callable functions are registered
     */
    private static void registerFunctionProviders() {
        // File operations
        FunctionRegistry.register(new FileFunctionProvider());
        
        // Process management
        FunctionRegistry.register(new ProcessFunctionProvider());
        
        // User management
        FunctionRegistry.register(new UserFunctionProvider());
        
        // Utility functions (time, JSON, type conversion, etc.)
        FunctionRegistry.register(new UtilFunctionProvider());
        
        // Swap pool functions (if exists)
        // FunctionRegistry.register(new SwapFunctionProvider());
        
        Logger.info("Registered " + FunctionRegistry.getProviderCount() + " function providers");
    }
}
