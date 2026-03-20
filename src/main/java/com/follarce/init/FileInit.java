package com.follarce.init;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import com.follarce.util.TimeUtil;

public class FileInit {
    
    private static final String VFS_ROOT = "cilexec_root";
    
    public static void init() {
        String workDir = getWorkDirectory();
        String vfsPath = workDir + File.separator + VFS_ROOT;
        
        createDirectories(vfsPath);
        createFiles(vfsPath);
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
            {"system", "swap"},        // 交换池目录
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
        
        String initJsonPath = vfsPath + File.separator + "system" + File.separator + 
                             "config" + File.separator + "init.json";
        String initContent = "{\n    \"root\": \"" + vfsPath.replace("\\", "\\\\") + "\"\n}";
        writeFile(initJsonPath, initContent);
        
        String localJsonPath = vfsPath + File.separator + "user" + File.separator + 
                              "local" + File.separator + "local.json";
        String localContent = "{\n" +
            "    \"name\": \"local\",\n" +
            "    \"id\": 1,\n" +
            "    \"permission\": \"local\",\n" +
            "    \"boot\": \"~/app/\",\n" +
            "    \"createTime\": " + timeStr + ",\n" +
            "    \"process\": []\n" +
            "}";
        writeFile(localJsonPath, localContent);
        
        // 创建默认的 swap 目录（空，不创建文件）
        // 交换池文件会在使用时动态创建
    }
    
    private static void writeFile(String path, String content) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(content);
        } catch (IOException e) {}
    }
}