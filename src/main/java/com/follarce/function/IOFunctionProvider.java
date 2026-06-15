package com.follarce.function;

import com.follarce.Constants;
import com.follarce.util.FileUtil;

import java.util.List;
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
                        System.out.println(args.get(0));
                    } else {
                        System.out.println();
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

                case "readFile":
                    return FileUtil.read(getStringArg(args, 0));

                case "writeFile":
                    FileUtil.write(getStringArg(args, 0), getStringArg(args, 1));
                    return null;

                default:
                    return null;
            }
        } catch (Exception e) {
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

    private static String getStringArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) {
            return null;
        }
        Object val = args.get(index);
        return val != null ? val.toString() : null;
    }
}
