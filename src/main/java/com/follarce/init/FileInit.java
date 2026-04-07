package com.follarce.init;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import com.follarce.basicUtil.Logger;
import com.follarce.basicUtil.TimeUtil;
import com.follarce.basicUtil.JsonUtil;

public class FileInit {

    private static final String VFS_ROOT = "cilexec_root";

    public static void init() {
        String workDir = getWorkDirectory();
        String vfsPath = workDir + File.separator + VFS_ROOT;

        if (isValidFileStructure(vfsPath)) {
            Logger.debug("File structure already exists and is valid, skipping initialization");
            return;
        }

        createDirectories(vfsPath);
        createFiles(vfsPath);
        copyInitFile(vfsPath);
    }

    private static boolean isValidFileStructure(String vfsPath) {
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

        String initJsonPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "init.json";
        String localJsonPath = vfsPath + File.separator + "user" + File.separator + "local" + File.separator + "local.json";
        String usersJsonPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "users.json";
        String initFclPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "INIT.fcl";
        String envJsonPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "env.json";

        return new File(initJsonPath).exists() &&
               new File(localJsonPath).exists() &&
               new File(usersJsonPath).exists() &&
               new File(initFclPath).exists() &&
               new File(envJsonPath).exists();
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
            {"system"},
            {"system", "app"},
            {"system", "config"},
            {"system", "process"},
            {"system", "swap"},
            {"user"},
            {"user", "local"},
            {"user", "local", "app"}
        };

        new File(vfsPath).mkdirs();

        int[] time = TimeUtil.getTime();

        for (String[] dirParts : dirs) {
            String dirPath = vfsPath;
            for (String part : dirParts) {
                dirPath += File.separator + part;
            }
            File dir = new File(dirPath);
            dir.mkdirs();
            createDirectoryMeta(dir, time);
        }
    }

    private static void createDirectoryMeta(File dir, int[] time) {
        File metaFile = new File(dir, ".META");
        if (metaFile.exists()) {
            return;
        }

        try {
            Map<String, Object> metaMap = new HashMap<>();

            Map<String, Object> timeMap = new HashMap<>();
            timeMap.put("createTime", new int[] { time[0], time[1], time[2], time[3], time[4], time[5], time[6] });
            timeMap.put("lastEditTime", new int[] { time[0], time[1], time[2], time[3], time[4], time[5], time[6] });
            timeMap.put("lastOpenTime", new int[] { time[0], time[1], time[2], time[3], time[4], time[5], time[6] });
            metaMap.put("Time", timeMap);

            metaMap.put("Owner", "local");

            Map<String, String> permMap = new HashMap<>();
            permMap.put("Owner", "read, write");
            permMap.put("Others", "read");
            metaMap.put("Permission", permMap);

            Map<String, Object> lockMap = new HashMap<>();
            lockMap.put("isLocked", false);
            lockMap.put("lockedBy", null);
            metaMap.put("locked", lockMap);

            String metaJson = JsonUtil.toJson(metaMap);
            String fileContent = "#<META>\n" + metaJson + "\n<META>#\n";

            Files.write(metaFile.toPath(), fileContent.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.error("Error creating directory metadata: " + e.getMessage());
        }
    }

    private static void createFiles(String vfsPath) {
        int[] time = TimeUtil.getTime();
        String timeStr = String.format("[%d,%d,%d,%d,%d,%d,%d]",
            time[0], time[1], time[2], time[3], time[4], time[5], time[6]);

        String initJsonPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "init.json";
        String initContent = "{\n    \"root\": \"" + vfsPath.replace("\\", "\\\\") + "\"\n}";
        writeFileWithMeta(initJsonPath, initContent, time);

        String localJsonPath = vfsPath + File.separator + "user" + File.separator + "local" + File.separator + "local.json";
        String localContent = "{\n" +
            "    \"name\": \"local\",\n" +
            "    \"id\": 1,\n" +
            "    \"permission\": \"local\",\n" +
            "    \"boot\": \"~/app/\",\n" +
            "    \"createTime\": " + timeStr + ",\n" +
            "    \"process\": []\n" +
            "}";
        writeFileWithMeta(localJsonPath, localContent, time);

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
        writeFileWithMeta(usersJsonPath, usersContent, time);

        String envJsonPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "env.json";
        String envContent = "{\n" +
            "    \"pathAliases\": {\n" +
            "        \"~\": \"/user/local\",\n" +
            "        \"$HOME\": \"/user/local\",\n" +
            "        \"$SYSTEM\": \"/system\"\n" +
            "    },\n" +
            "    \"envVars\": {\n" +
            "        \"PATH\": \"/system/app:~/app\",\n" +
            "        \"HOME\": \"/user/local\"\n" +
            "    }\n" +
            "}";
        writeFileWithMeta(envJsonPath, envContent, time);
    }

    private static void copyInitFile(String vfsPath) {
        String initFclPath = vfsPath + File.separator + "system" + File.separator + "config" + File.separator + "INIT.fcl";
        
        if (new File(initFclPath).exists()) {
            return;
        }

        try (InputStream is = FileInit.class.getResourceAsStream("/INIT.fcl")) {
            if (is == null) {
                Logger.warn("INIT.fcl not found in resources");
                return;
            }

            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            int[] time = TimeUtil.getTime();
            writeFileWithMeta(initFclPath, content, time);
        } catch (IOException e) {
            Logger.error("Error copying INIT.fcl: " + e.getMessage());
        }
    }

    private static void writeFileWithMeta(String path, String content, int[] time) {
        File file = new File(path);
        if (file.exists()) {
            return;
        }

        try {
            file.getParentFile().mkdirs();

            Map<String, Object> metaMap = new HashMap<>();

            Map<String, Object> timeMap = new HashMap<>();
            timeMap.put("createTime", new int[] { time[0], time[1], time[2], time[3], time[4], time[5], time[6] });
            timeMap.put("lastEditTime", new int[] { time[0], time[1], time[2], time[3], time[4], time[5], time[6] });
            timeMap.put("lastOpenTime", new int[] { time[0], time[1], time[2], time[3], time[4], time[5], time[6] });
            metaMap.put("Time", timeMap);

            metaMap.put("Owner", "local");

            Map<String, String> permMap = new HashMap<>();
            permMap.put("Owner", "read, write");
            permMap.put("Others", "read");
            metaMap.put("Permission", permMap);

            Map<String, Object> lockMap = new HashMap<>();
            lockMap.put("isLocked", false);
            lockMap.put("lockedBy", null);
            metaMap.put("locked", lockMap);

            metaMap.put("Size", new Object[] { content.getBytes(StandardCharsets.UTF_8).length, "B" });

            String metaJson = JsonUtil.toJson(metaMap);
            String fullContent = "#<META>\n" + metaJson + "\n<META>#\n" + content;

            Files.write(file.toPath(), fullContent.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.error("Error writing file " + path + ": " + e.getMessage());
        }
    }
}
