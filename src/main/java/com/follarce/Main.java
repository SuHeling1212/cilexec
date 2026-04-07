package com.follarce;

import com.follarce.init.*;
import com.follarce.network.SocketUtil;
import com.follarce.plugin.*;
import com.follarce.basicUtil.*;
import com.follarce.process.ProcessRunner;

public class Main {

    private static IOFunctionProvider ioProvider;

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Logger.logShutdown();
            Logger.close();
        }));

        Logger.logStartup();
        FileInit.init();

        ioProvider = new IOFunctionProvider();
        registerFunctionProviders();

        ProcessInit.init();
        SocketUtil.init();

        ProcessRunner initProcess = new ProcessRunner(0);
        initProcess.run();

        Logger.logShutdown();
        Logger.close();
    }

    private static void registerFunctionProviders() {
        FunctionRegistry.register(new FileFunctionProvider());
        FunctionRegistry.register(new ProcessFunctionProvider());

        FunctionRegistry.register(new SwapFunctionProvider());
        FunctionRegistry.register(new UserFunctionProvider());
        FunctionRegistry.register(new UtilFunctionProvider());
        FunctionRegistry.register(new NetworkFunctionProvider());
        FunctionRegistry.register(new SocketFunctionProvider());
        FunctionRegistry.register(new MathFunctionProvider());
        FunctionRegistry.register(ioProvider);
        FunctionRegistry.register(new PathFunctionProvider());

        Logger.info("Registered " + FunctionRegistry.getProviderCount() + " function providers");
    }
}
