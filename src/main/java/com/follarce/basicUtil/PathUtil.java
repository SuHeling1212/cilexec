package com.follarce.basicUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class PathUtil {

    private static final String ENV_FILE = "/system/config/env.json";
    private static volatile Map<String, String> pathAliases = null;
    private static volatile Map<String, String> envVars = null;
    private static volatile long lastLoadTime = 0;
    private static final long CACHE_TTL = 5000;
    private static volatile boolean initialized = false;
    private static final Object initLock = new Object();

    private static void ensureInitialized() {
        if (!initialized) {
            synchronized (initLock) {
                if (!initialized) {
                    initDefaults();
                    initialized = true;
                }
            }
        }
    }

    private static void loadEnvFile() {
        ensureInitialized();
        
        long now = System.currentTimeMillis();
        if (pathAliases != null && (now - lastLoadTime) < CACHE_TTL) {
            return;
        }

        try {
            String vfsRoot = FileUtil.getVfsRoot();
            String realPath = vfsRoot + ENV_FILE.replace('/', File.separatorChar);
            File file = new File(realPath);

            if (!file.exists()) {
                return;
            }

            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String jsonContent = content;
            
            String[] metaResult = extractMetaContent(content);
            if (metaResult[0].equals("SUCCESS")) {
                jsonContent = metaResult[1];
            }

            Map<String, Object> env = (Map<String, Object>) JsonUtil.readJson(jsonContent);
            if (env == null) {
                return;
            }

            Map<String, Object> aliasesObj = (Map<String, Object>) env.get("pathAliases");
            Map<String, Object> varsObj = (Map<String, Object>) env.get("envVars");

            if (aliasesObj != null) {
                for (Map.Entry<String, Object> entry : aliasesObj.entrySet()) {
                    pathAliases.put(entry.getKey(), entry.getValue().toString());
                }
            }

            if (varsObj != null) {
                for (Map.Entry<String, Object> entry : varsObj.entrySet()) {
                    envVars.put(entry.getKey(), entry.getValue().toString());
                }
            }

            lastLoadTime = now;
        } catch (IOException e) {
            Logger.error("Failed to load env.json: " + e.getMessage());
        }
    }

    private static void initDefaults() {
        if (pathAliases == null) {
            pathAliases = new HashMap<>();
        }
        if (envVars == null) {
            envVars = new HashMap<>();
        }
        pathAliases.put("~", "/user/local");
        pathAliases.put("$HOME", "/user/local");
        envVars.put("HOME", "/user/local");
    }

    private static String[] extractMetaContent(String content) {
        if (content == null || content.isEmpty()) {
            return new String[] { "ERROR", "EMPTY_CONTENT" };
        }

        String startMarker = "#<META>\n";
        String endMarker = "\n<META>#";

        int startIndex = content.indexOf(startMarker);
        if (startIndex == -1) {
            return new String[] { "SUCCESS", content };
        }

        int endIndex = content.indexOf(endMarker, startIndex + startMarker.length());
        if (endIndex == -1) {
            return new String[] { "SUCCESS", content };
        }

        String remaining = content.substring(endIndex + endMarker.length());
        return new String[] { "SUCCESS", remaining.trim() };
    }

    public static String resolvePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        loadEnvFile();

        String resolved = path;
        boolean changed = true;
        
        while (changed) {
            changed = false;
            for (Map.Entry<String, String> entry : pathAliases.entrySet()) {
                String alias = entry.getKey();
                String target = entry.getValue();
                
                if (resolved.startsWith(alias)) {
                    int aliasLen = alias.length();
                    boolean shouldReplace = false;
                    
                    if (resolved.length() == aliasLen) {
                        shouldReplace = true;
                    } else {
                        char nextChar = resolved.charAt(aliasLen);
                        if (nextChar == '/' || nextChar == '\\') {
                            shouldReplace = true;
                        }
                    }
                    
                    if (shouldReplace) {
                        if (resolved.length() == aliasLen) {
                            resolved = target;
                        } else {
                            resolved = target + resolved.substring(aliasLen);
                        }
                        changed = true;
                        break;
                    }
                }
            }
        }

        return resolved;
    }

    public static String getEnvVar(String name) {
        loadEnvFile();
        return envVars.get(name);
    }

    public static Map<String, String> getAllPathAliases() {
        loadEnvFile();
        return new HashMap<>(pathAliases);
    }

    public static Map<String, String> getAllEnvVars() {
        loadEnvFile();
        return new HashMap<>(envVars);
    }

    public static String[] setPathAlias(String alias, String target) {
        if (alias == null || alias.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_ALIAS" };
        }
        if (target == null || target.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_TARGET" };
        }

        loadEnvFile();
        pathAliases.put(alias, target);

        return saveEnvFile();
    }

    public static String[] setEnvVar(String name, String value) {
        if (name == null || name.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_NAME" };
        }

        loadEnvFile();
        envVars.put(name, value);

        return saveEnvFile();
    }

    private static String[] saveEnvFile() {
        try {
            Map<String, Object> env = new HashMap<>();
            env.put("pathAliases", pathAliases);
            env.put("envVars", envVars);

            String jsonContent = JsonUtil.toJson(env);

            String vfsRoot = FileUtil.getVfsRoot();
            String realPath = vfsRoot + ENV_FILE.replace('/', File.separatorChar);
            File file = new File(realPath);

            String metaHeader = "#<META>\n{}\n<META>#\n";
            String fullContent = metaHeader + jsonContent;

            Files.write(file.toPath(), fullContent.getBytes(StandardCharsets.UTF_8));

            lastLoadTime = 0;
            return new String[] { "SUCCESS", null };
        } catch (IOException e) {
            Logger.error("Failed to save env.json: " + e.getMessage());
            return new String[] { "ERROR", "SAVE_FAILED" };
        }
    }
}
