package com.follarce.pack;

import com.follarce.util.JsonUtil;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host-side package builder and inspector for development and publishing. */
public final class PackageCli {
    private PackageCli() {}

    public static void main(String[] args) {
        try {
            if (args.length == 0) usage();
            switch (args[0]) {
                case "build" -> build(args);
                case "inspect", "verify" -> inspect(args);
                default -> usage();
            }
        } catch (PackageException e) {
            System.err.println("Package error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void build(String[] args) {
        if (args.length != 3) usage();
        PackageBuilder.BuildResult result = PackageBuilder.build(Path.of(args[1]), Path.of(args[2]));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "built");
        output.put("coordinate", result.coordinate().key());
        output.put("integrity", result.integrity());
        output.put("path", result.path().toString());
        output.put("size", result.size());
        System.out.println(JsonUtil.toJson(output));
    }

    private static void inspect(String[] args) {
        if (args.length != 2) usage();
        PackageArchive archive = PackageArchive.read(Path.of(args[1]));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "valid");
        output.put("coordinate", archive.manifest().coordinate().key());
        output.put("integrity", archive.integrity());
        output.put("entries", archive.entryNames());
        output.put("manifest", archive.manifest().source());
        System.out.println(JsonUtil.toJson(output));
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  PackageCli build <source-directory> <output.pack>");
        System.err.println("  PackageCli inspect <package.pack>");
        System.exit(1);
    }
}
