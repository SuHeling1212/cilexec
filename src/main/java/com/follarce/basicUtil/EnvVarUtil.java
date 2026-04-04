package com.follarce.basicUtil;

import java.util.HashMap;
import java.util.Map;

public class EnvVarUtil {

    private static final String ENV_VAR_PREFIX = "__ENV_";

    public static String[] setEnv(String name, String value) {
        if (name == null || name.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_ENV_NAME" };
        }

        if (value == null) {
            return new String[] { "ERROR", "INVALID_ENV_VALUE" };
        }

        int currentPid = com.follarce.process.ProcessFunc.getPID();
        String[] readResult = FileUtil.read("/system/process/" + currentPid + ".json");

        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
        }

        try {
            Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
            Map<String, Object> program = (Map<String, Object>) process.get("Program");
            Map<String, Object> data = (Map<String, Object>) program.get("Data");

            if (data == null) {
                data = new HashMap<>();
                program.put("Data", data);
            }

            Map<String, Object> envVars = (Map<String, Object>) data.get(ENV_VAR_PREFIX);
            if (envVars == null) {
                envVars = new HashMap<>();
                data.put(ENV_VAR_PREFIX, envVars);
            }

            envVars.put(name, value);

            FileUtil.write("/system/process/" + currentPid + ".json", JsonUtil.toJson(process));

            Logger.info("Environment variable set: " + name + "=" + value + " for PID " + currentPid);

            return new String[] { "SUCCESS", null };

        } catch (Exception e) {
            Logger.error("Failed to set environment variable: " + e.getMessage());
            return new String[] { "ERROR", "SET_ENV_FAILED" };
        }
    }

    public static String[] getEnv(String name) {
        if (name == null || name.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_ENV_NAME" };
        }

        int currentPid = com.follarce.process.ProcessFunc.getPID();
        String[] readResult = FileUtil.read("/system/process/" + currentPid + ".json");

        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
        }

        try {
            Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
            Map<String, Object> program = (Map<String, Object>) process.get("Program");
            Map<String, Object> data = (Map<String, Object>) program.get("Data");

            if (data == null) {
                String systemValue = getSystemEnv(name);
                if (systemValue != null) {
                    return new String[] { "SUCCESS", systemValue };
                }
                return new String[] { "ERROR", "ENV_VAR_NOT_FOUND" };
            }

            Map<String, Object> envVars = (Map<String, Object>) data.get(ENV_VAR_PREFIX);
            if (envVars == null) {
                String systemValue = getSystemEnv(name);
                if (systemValue != null) {
                    return new String[] { "SUCCESS", systemValue };
                }
                return new String[] { "ERROR", "ENV_VAR_NOT_FOUND" };
            }

            Object value = envVars.get(name);
            if (value == null) {
                String systemValue = getSystemEnv(name);
                if (systemValue != null) {
                    return new String[] { "SUCCESS", systemValue };
                }
                return new String[] { "ERROR", "ENV_VAR_NOT_FOUND" };
            }

            return new String[] { "SUCCESS", value.toString() };

        } catch (Exception e) {
            Logger.error("Failed to get environment variable: " + e.getMessage());
            return new String[] { "ERROR", "GET_ENV_FAILED" };
        }
    }

    public static String[] listEnv() {
        int currentPid = com.follarce.process.ProcessFunc.getPID();
        String[] readResult = FileUtil.read("/system/process/" + currentPid + ".json");

        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
        }

        try {
            Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
            Map<String, Object> program = (Map<String, Object>) process.get("Program");
            Map<String, Object> data = (Map<String, Object>) program.get("Data");

            Map<String, String> allEnvVars = new HashMap<>();

            if (data != null) {
                Map<String, Object> envVars = (Map<String, Object>) data.get(ENV_VAR_PREFIX);
                if (envVars != null) {
                    for (Map.Entry<String, Object> entry : envVars.entrySet()) {
                        allEnvVars.put(entry.getKey(), entry.getValue().toString());
                    }
                }
            }

            allEnvVars.putAll(getAllSystemEnv());

            return new String[] { "SUCCESS", JsonUtil.toJson(allEnvVars) };

        } catch (Exception e) {
            Logger.error("Failed to list environment variables: " + e.getMessage());
            return new String[] { "ERROR", "LIST_ENV_FAILED" };
        }
    }

    public static String[] deleteEnv(String name) {
        if (name == null || name.trim().isEmpty()) {
            return new String[] { "ERROR", "INVALID_ENV_NAME" };
        }

        int currentPid = com.follarce.process.ProcessFunc.getPID();
        String[] readResult = FileUtil.read("/system/process/" + currentPid + ".json");

        if (!readResult[0].equals("SUCCESS")) {
            return new String[] { "ERROR", "PROCESS_NOT_FOUND" };
        }

        try {
            Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
            Map<String, Object> program = (Map<String, Object>) process.get("Program");
            Map<String, Object> data = (Map<String, Object>) program.get("Data");

            if (data == null) {
                return new String[] { "ERROR", "ENV_VAR_NOT_FOUND" };
            }

            Map<String, Object> envVars = (Map<String, Object>) data.get(ENV_VAR_PREFIX);
            if (envVars == null) {
                return new String[] { "ERROR", "ENV_VAR_NOT_FOUND" };
            }

            if (!envVars.containsKey(name)) {
                return new String[] { "ERROR", "ENV_VAR_NOT_FOUND" };
            }

            envVars.remove(name);

            FileUtil.write("/system/process/" + currentPid + ".json", JsonUtil.toJson(process));

            Logger.info("Environment variable deleted: " + name + " for PID " + currentPid);

            return new String[] { "SUCCESS", null };

        } catch (Exception e) {
            Logger.error("Failed to delete environment variable: " + e.getMessage());
            return new String[] { "ERROR", "DELETE_ENV_FAILED" };
        }
    }

    public static Map<String, String> getEnvVarsForProcess(int pid) {
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");

        if (!readResult[0].equals("SUCCESS")) {
            return new HashMap<>();
        }

        try {
            Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
            Map<String, Object> program = (Map<String, Object>) process.get("Program");
            Map<String, Object> data = (Map<String, Object>) program.get("Data");

            Map<String, String> envVars = new HashMap<>(getAllSystemEnv());

            if (data != null) {
                Map<String, Object> processEnvVars = (Map<String, Object>) data.get(ENV_VAR_PREFIX);
                if (processEnvVars != null) {
                    for (Map.Entry<String, Object> entry : processEnvVars.entrySet()) {
                        envVars.put(entry.getKey(), entry.getValue().toString());
                    }
                }
            }

            return envVars;

        } catch (Exception e) {
            Logger.error("Failed to get environment variables for process " + pid + ": " + e.getMessage());
            return new HashMap<>();
        }
    }

    public static void setEnvVarsForProcess(int pid, Map<String, String> envVars) {
        if (envVars == null || envVars.isEmpty()) {
            return;
        }

        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");

        if (!readResult[0].equals("SUCCESS")) {
            return;
        }

        try {
            Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
            Map<String, Object> program = (Map<String, Object>) process.get("Program");
            Map<String, Object> data = (Map<String, Object>) program.get("Data");

            if (data == null) {
                data = new HashMap<>();
                program.put("Data", data);
            }

            Map<String, Object> processEnvVars = (Map<String, Object>) data.get(ENV_VAR_PREFIX);
            if (processEnvVars == null) {
                processEnvVars = new HashMap<>();
                data.put(ENV_VAR_PREFIX, processEnvVars);
            }

            for (Map.Entry<String, String> entry : envVars.entrySet()) {
                processEnvVars.put(entry.getKey(), entry.getValue());
            }

            FileUtil.write("/system/process/" + pid + ".json", JsonUtil.toJson(process));

        } catch (Exception e) {
            Logger.error("Failed to set environment variables for process " + pid + ": " + e.getMessage());
        }
    }

    private static String getSystemEnv(String name) {
        String value = System.getenv(name);
        return value;
    }

    private static Map<String, String> getAllSystemEnv() {
        Map<String, String> systemEnv = System.getenv();
        return new HashMap<>(systemEnv);
    }
}
