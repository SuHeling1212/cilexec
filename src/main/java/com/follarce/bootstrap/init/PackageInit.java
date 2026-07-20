package com.follarce.bootstrap.init;

import com.follarce.extension.pack.PackageManager;
import com.follarce.kernel.log.Logger;

/** Initializes package storage and reconciles interrupted package transactions. */
public final class PackageInit {
    private PackageInit() {}

    public static void init() {
        Logger.info("PackageInit: initializing package storage");
        PackageManager manager = PackageManager.getInstance();
        manager.initialize();
        manager.recoverTransactions();
        Logger.info("PackageInit: package storage ready");
    }
}
