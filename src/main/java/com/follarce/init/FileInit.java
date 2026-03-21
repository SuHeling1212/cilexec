package com.follarce.init;

import java.io.*;
import java.nio.charset.StandardCharsets;

import com.follarce.basicUtil.Logger;
import com.follarce.basicUtil.TimeUtil;

public class FileInit {

    private static final String VFS_ROOT = "cilexec_root";

    public static void init() {
        String workDir = getWorkDirectory();
        String vfsPath = workDir + File.separator + VFS_ROOT;

        // Check if file structure already exists and is valid
        if (isValidFileStructure(vfsPath)) {
            Logger.debug("File structure already exists and is valid, skipping initialization");
            return;
        }

        createDirectories(vfsPath);
        createFiles(vfsPath);
        copyInitFile(vfsPath);
    }

    /**
     * Check if file structure exists and is valid
     */
    private static boolean isValidFileStructure(String vfsPath) {
        // Check if all required directories exist
        String[][] requiredDirs = {
            {"system", "app"},
            {"system", "config"},
            {"system", "process"},
            {"system", "swap"},
            {"user", "local", "app"}
        };

        for (String[] dirParts : requiredDirs) {
            String dirPath = vfsPath;
            for (String part : dirParts) {
                dirPath += File.separator + part;
            }
            if (!new File(dirPath).isDirectory()) {
                return false;
            }
        }

        // Check if required files exist
        String initJsonPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "init.json";
        String localJsonPath = vfsPath + File.separator + "user" + File.separator + "local" + File.separator + "local.json";
        String usersJsonPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "users.json";
        String initFclPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "INIT.fcl";

        return new File(initJsonPath).exists() &&
               new File(localJsonPath).exists() &&
               new File(usersJsonPath).exists() &&
               new File(initFclPath).exists();
    }

    private static String getWorkDirectory() {
        try {
            String path = FileInit.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();
            File jarFile = new File(path);
            return jarFile.getParent();
        } catch (Exception e) {
            return System.getProperty("user.dir");
        }
    }

    private static void createDirectories(String vfsPath) {
        String[][] dirs = {
            {"system", "app"},
            {"system", "config"},
            {"system", "process"},
            {"system", "swap"},
            {"user", "local", "app"}
        };

        new File(vfsPath).mkdirs();

        for (String[] dirParts : dirs) {
            String dirPath = vfsPath;
            for (String part : dirParts) {
                dirPath += File.separator + part;
            }
            new File(dirPath).mkdirs();
        }
    }

    private static void createFiles(String vfsPath) {
        int[] time = TimeUtil.getTime();
        String timeStr = String.format("[%d,%d,%d,%d,%d,%d,%d]",
            time[0], time[1], time[2], time[3], time[4], time[5], time[6]);

        // init.json - VFS root configuration (only if not exists)
        String initJsonPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "init.json";
        String initContent = "{\n    \"root\": \"" + vfsPath.replace("\\", "\\\\") + "\"\n}";
        writeFileIfNotExists(initJsonPath, initContent);

        // local.json - local user configuration (only if not exists)
        String localJsonPath = vfsPath + File.separator + "user" + File.separator + "local" + File.separator + "local.json";
        String localContent = "{\n" +
            "    \"name\": \"local\",\n" +
            "    \"id\": 1,\n" +
            "    \"permission\": \"local\",\n" +
            "    \"boot\": \"~/app/\",\n" +
            "    \"createTime\": " + timeStr + ",\n" +
            "    \"process\": []\n" +
            "}";
        writeFileIfNotExists(localJsonPath, localContent);

        // users.json - user database (only if not exists)
        String usersJsonPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "users.json";
        String usersContent = "{\n" +
            "    \"currentUser\": \"local\",\n" +
            "    \"users\": {\n" +
            "        \"local\": {\n" +
            "            \"password\": \"local\",\n" +
            "            \"isLocal\": true,\n" +
            "            \"home\": \"/user/local\",\n" +
            "            \"created\": " + timeStr + "\n" +
            "        }\n" +
            "    }\n" +
            "}";
        writeFileIfNotExists(usersJsonPath, usersContent);
    }

    /**
     * Copy INIT.fcl from resources to VFS (if not exists)
     */
    private static void copyInitFile(String vfsPath) {
        String initFclPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "INIT.fcl";
        
        // Only create if not exists
        if (new File(initFclPath).exists()) {
            return;
        }

        try {
            // Read INIT.fcl from resources
            InputStream is = FileInit.class.getResourceAsStream("/INIT.fcl");
            if (is == null) {
                Logger.warn("INIT.fcl not found in resources");
                return;
            }

            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            is.close();

            // Write to VFS
            writeFile(initFclPath, content);
        } catch (IOException e) {
            Logger.error("Error copying INIT.fcl: " + e.getMessage());
        }
    }

    private static void writeFile(String path, String content) {
        try {
            File file = new File(path);
            file.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(content);
            }
        } catch (IOException e) {
            Logger.error("Error writing file " + path + ": " + e.getMessage());
        }
    }

    private static void writeFileIfNotExists(String path, String content) {
        File file = new File(path);
        if (!file.exists()) {
            writeFile(path, content);
        }
    }
}
