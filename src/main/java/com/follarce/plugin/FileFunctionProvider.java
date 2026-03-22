package com.follarce.plugin;

import com.follarce.basicUtil.FileUtil;

/**
 * File operation function provider
 * Encapsulates script calling interface for FileUtil
 */
public class FileFunctionProvider implements FunctionProvider {
    
    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        // File operations don't need PID context, call FileUtil directly
        return call(name, args);
    }
    
    /**
     * Compatible with legacy calling method (no context)
     */
    public Object call(String name, Object[] args) {
        switch (name) {
            case "read":
                if (args.length < 1) return error("INVALID_ARGUMENTS");
                return FileUtil.read((String) args[0]);
                
            case "write":
                if (args.length < 2) return error("INVALID_ARGUMENTS");
                String writeContent = args[1] instanceof String ? (String) args[1] : String.valueOf(args[1]);
                return FileUtil.write((String) args[0], writeContent);
                
            case "listDir":
                if (args.length < 1) return error("INVALID_ARGUMENTS");
                return FileUtil.getListOfFileAndDirectory((String) args[0]);
                
            case "readMeta":
                if (args.length < 1) return error("INVALID_ARGUMENTS");
                return FileUtil.readFileMetaData((String) args[0]);
                
            case "writeMeta":
                if (args.length < 2) return error("INVALID_ARGUMENTS");
                return FileUtil.writeFileMetaData((String) args[0], (String) args[1]);

            case "append":
                if (args.length < 2) return error("INVALID_ARGUMENTS");
                String appendContent = args[1] instanceof String ? (String) args[1] : String.valueOf(args[1]);
                return FileUtil.append((String) args[0], appendContent);

            case "createFile":
                if (args.length < 2) return error("INVALID_ARGUMENTS");
                return FileUtil.createFile((String) args[0], (String) args[1]);
                
            case "removeFile":
                if (args.length < 1) return error("INVALID_ARGUMENTS");
                return FileUtil.removeFile((String) args[0]);
                
            case "createDir":
                if (args.length < 2) return error("INVALID_ARGUMENTS");
                return FileUtil.createDirectory((String) args[0], (String) args[1]);
                
            case "removeDir":
                if (args.length < 1) return error("INVALID_ARGUMENTS");
                return FileUtil.removeDirectory((String) args[0]);
                
            case "rename":
                if (args.length < 2) return error("INVALID_ARGUMENTS");
                return FileUtil.Rename((String) args[0], (String) args[1]);
                
            case "link":
                if (args.length < 2) return error("INVALID_ARGUMENTS");
                return FileUtil.Link((String) args[0], (String) args[1]);
                
            case "lock":
                if (args.length < 1) return error("INVALID_ARGUMENTS");
                return FileUtil.lock((String) args[0]);
                
            case "unlock":
                if (args.length < 1) return error("INVALID_ARGUMENTS");
                return FileUtil.unlock((String) args[0]);
                
            default:
                return null;
        }
    }
    
    private String[] error(String code) {
        return new String[]{"ERROR", code};
    }
    
    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[]{
            new FunctionInfo("read", "Read file content",
                new String[]{"path: string"}, "String[]", "File"),
            new FunctionInfo("write", "Write file content",
                new String[]{"path: string", "content: string"}, "String[]", "File"),
            new FunctionInfo("listdir", "List directory contents",
                new String[]{"path: string"}, "String[]", "File"),
            new FunctionInfo("createFile", "Create new file",
                new String[]{"path: string", "name: string"}, "String[]", "File"),
            new FunctionInfo("removeFile", "Delete file",
                new String[]{"path: string"}, "String[]", "File"),
            new FunctionInfo("createDir", "Create directory",
                new String[]{"path: string", "name: string"}, "String[]", "File"),
            new FunctionInfo("removeDir", "Delete empty directory",
                new String[]{"path: string"}, "String[]", "File"),
            new FunctionInfo("rename", "Rename file or directory",
                new String[]{"path: string", "newName: string"}, "String[]", "File"),
            new FunctionInfo("link", "Create symbolic link",
                new String[]{"path: string", "sourcePath: string"}, "String[]", "File"),
            new FunctionInfo("lock", "Lock file",
                new String[]{"path: string"}, "String[]", "File"),
            new FunctionInfo("unlock", "Unlock file",
                new String[]{"path: string"}, "String[]", "File"),
            new FunctionInfo("readMeta", "Read file metadata",
                new String[]{"path: string"}, "String[]", "File"),
            new FunctionInfo("writeMeta", "Write file metadata",
                new String[]{"path: string", "content: string"}, "String[]", "File"),
            new FunctionInfo("append", "Append content to file",
                new String[]{"path: string", "content: string"}, "String[]", "File")
        };
    }
    
    @Override
    public String getProviderName() {
        return "FileFunctionProvider";
    }
}
