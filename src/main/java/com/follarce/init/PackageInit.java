package com.follarce.init;

import com.follarce.log.Logger;
import com.follarce.pack.PackageManager;

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
