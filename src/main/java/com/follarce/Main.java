package com.follarce;

import com.follarce.init.*;
import com.follarce.network.NetworkFunctionProvider;
import com.follarce.network.SocketFunctionProvider;
import com.follarce.network.SocketUtil;
import com.follarce.plugin.*;
import com.follarce.basicUtil.*;

import java.util.*;

public class Main {

    public static void main(String[] args) {
        // Add shutdown hook to log when application ends (including Ctrl+C)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Logger.logShutdown();
            Logger.close();
        }));

        // Log application startup
        Logger.logStartup();

        // Initialize system components
        FileInit.init();
        
        // Register function providers for script engine
        registerFunctionProviders();
        
        ProcessInit.init();
        SocketUtil.init();
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
        
        // Network functions (HTTP download, etc.)
        FunctionRegistry.register(new NetworkFunctionProvider());
        
        // Socket functions (TCP/UDP)
        FunctionRegistry.register(new SocketFunctionProvider());
        
        // Math functions (comprehensive mathematical operations)
        FunctionRegistry.register(new MathFunctionProvider());
        
        // Swap pool functions (if exists)
        // FunctionRegistry.register(new SwapFunctionProvider());
        
        Logger.info("Registered " + FunctionRegistry.getProviderCount() + " function providers");
    }
}
