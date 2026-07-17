package com.follarce.function;

import com.follarce.Constants;
import com.follarce.util.FileUtil;
import com.follarce.util.PathUtil;

import java.util.List;
import java.io.*;
import java.util.Scanner;

/**
 * IO 操作函数提供者。
 * 命名空间: "io"
 */
public class IOFunctionProvider implements FunctionProvider {

    @Override
    public String getNamespace() {
        return "io";
    }

    @Override
    public Object call(String functionName, List<Object> args, FunctionContext context) {
        try {
            switch (functionName) {
                case "print":
                    if (args != null && !args.isEmpty()) {
                        System.out.print(args.get(0));
                    } else {
                        System.out.print("");
                    }
                    return "";

                case "println":
                    if (args != null && !args.isEmpty()) {
                        System.out.println(args.get(0));
                    } else {
                        System.out.println();
                    }
                    return "";

                case "input":
                    if (args != null && !args.isEmpty()) {
                        System.out.print(args.get(0));
                    }
                    return readInput();

                case "readFile": {
                    String rfPath = context.resolvePath(getStringArg(args, 0));
                    if (!FileUtil.checkFilePermission(rfPath, Constants.PERM_READ, context.getCurrentUser())) {
                        return new String[]{Constants.ERROR_MARKER, "Permission denied: read " + rfPath};
                    }
                    return FileUtil.read(rfPath);
                }

                case "writeFile": {
                    String wfPath = context.resolvePath(getStringArg(args, 0));
                    if (!FileUtil.exists(wfPath)) {
                        String parentPath = PathUtil.getParentPath(wfPath);
                        String fileName = PathUtil.getFileName(wfPath);
                        if (!FileUtil.checkFilePermission(parentPath, Constants.PERM_WRITE,
                                context.getCurrentUser())) {
                            return new String[]{Constants.ERROR_MARKER,
                                    "Permission denied: create in " + parentPath};
                        }
                        if (wfPath.startsWith(Constants.SYSTEM_PROCESS_PATH)
                                && !Constants.DEFAULT_USER_LOCAL.equals(context.getCurrentUser())) {
                            return new String[]{Constants.ERROR_MARKER,
                                    "Permission denied: process snapshots are system-owned"};
                        }
                        if (parentPath != null && fileName != null) {
                            if (context.getEffectId() != null) {
                                FileUtil.createFileOnce(parentPath, fileName,
                                        context.getEffectId() + "-create", context.getCurrentUser());
                            } else {
                                FileUtil.createFile(parentPath, fileName);
                            }
                        }
                    }
                    if (!FileUtil.checkFilePermission(wfPath, Constants.PERM_WRITE, context.getCurrentUser())) {
                        return new String[]{Constants.ERROR_MARKER, "Permission denied: write " + wfPath};
                    }
                    if (context.getEffectId() != null) {
                        FileUtil.writeOnce(wfPath, getStringArg(args, 1), context.getEffectId(),
                                context.getPid(), context.getProcessGeneration(), null);
                    } else {
                        FileUtil.write(wfPath, getStringArg(args, 1));
                    }
                    return "";
                }

                case "readChar":
                    return readChar();

                default:
                    return null;
            }
        } catch (Exception e) {
            if ("input".equals(functionName) || "readChar".equals(functionName)) {
                throw new UnknownEffectOutcomeException("Input outcome is unknown: " + e.getMessage(), e);
            }
            return new String[]{Constants.ERROR_MARKER, e.getMessage()};
        }
    }

    private static String readInput() {
        try {
            Scanner scanner = new Scanner(System.in);
            if (scanner.hasNextLine()) {
                return scanner.nextLine();
            }
            return "";
        } catch (Exception e) {
            throw new RuntimeException("Input error: " + e.getMessage());
        }
    }

    private static String readChar() {
        try {
            int c = System.in.read();
            if (c < 0) return "";
            return String.valueOf((char) c);
        } catch (IOException e) {
            return "";
        }
    }

    private static String getStringArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) {
            return null;
        }
        Object val = args.get(index);
        return val != null ? val.toString() : null;
    }
}
