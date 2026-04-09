package com.follarce.plugin;

import com.follarce.basicUtil.JsonUtil;
import com.follarce.basicUtil.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

public class IOFunctionProvider implements FunctionProvider {

    private final BufferedReader reader;

    public IOFunctionProvider() {
        this.reader = new BufferedReader(new InputStreamReader(System.in));
    }

    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            case "print":
                return handlePrint(args, false);
            case "println":
                return handlePrint(args, true);
            case "printf":
                return handlePrintf(args);

            case "input":
                return handleInput(args);
            case "inputLine":
                return handleInputLine(args);

            case "printErr":
                return handlePrintErr(args);

            default:
                return null;
        }
    }

    private Object handlePrint(Object[] args, boolean newline) {
        if (args.length < 1) {
            if (newline) {
                System.out.println("");
            }
            return new String[]{"SUCCESS"};
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(" ");
            }
            sb.append(formatValue(args[i]));
        }

        if (newline) {
            System.out.println(sb.toString());
        } else {
            System.out.print(sb.toString());
        }

        return new String[]{"SUCCESS"};
    }

    private String formatValue(Object obj) {
        if (obj == null) {
            return "null";
        }
        if (obj instanceof Object[] || obj instanceof String[]) {
            return JsonUtil.toJson(obj);
        }
        if (obj instanceof List || obj instanceof Map) {
            return JsonUtil.toJson(obj);
        }
        return obj.toString();
    }

    private Object handlePrintf(Object[] args) {
        if (args.length < 1) {
            return error("INVALID_ARGUMENTS");
        }

        if (!(args[0] instanceof String)) {
            return error("ARGUMENT_MUST_BE_STRING");
        }

        String format = (String) args[0];
        Object[] formatArgs = new Object[args.length - 1];
        System.arraycopy(args, 1, formatArgs, 0, formatArgs.length);

        try {
            String result = String.format(format, formatArgs);
            System.out.print(result);
            return new String[]{"SUCCESS"};
        } catch (Exception e) {
            return error("FORMAT_ERROR");
        }
    }

    private Object handleInput(Object[] args) {
        String prompt = "";
        if (args.length >= 1 && args[0] != null) {
            prompt = args[0].toString();
        }

        if (!prompt.isEmpty()) {
            System.out.print(prompt);
        }

        try {
            String input = reader.readLine();
            return input != null ? input : "";
        } catch (IOException e) {
            Logger.error("IO error reading input: " + e.getMessage());
            return error("IO_ERROR");
        }
    }

    private Object handleInputLine(Object[] args) {
        String prompt = "";
        if (args.length >= 1 && args[0] != null) {
            prompt = args[0].toString();
        }

        if (!prompt.isEmpty()) {
            System.out.println(prompt);
        }

        try {
            String input = reader.readLine();
            return input != null ? input : "";
        } catch (IOException e) {
            Logger.error("IO error reading input: " + e.getMessage());
            return error("IO_ERROR");
        }
    }

    private Object handlePrintErr(Object[] args) {
        if (args.length < 1) {
            return new String[]{"SUCCESS"};
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(" ");
            }
            sb.append(args[i] != null ? args[i].toString() : "null");
        }

        System.err.println(sb.toString());

        return new String[]{"SUCCESS"};
    }

    private String[] error(String code) {
        return new String[]{"ERROR", code};
    }

    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[]{
            new FunctionInfo(
                "print",
                "Print to console without newline",
                new String[]{"...values: any"},
                "String[]",
                "IO"
            ),
            new FunctionInfo(
                "println",
                "Print to console with newline",
                new String[]{"...values: any"},
                "String[]",
                "IO"
            ),
            new FunctionInfo(
                "printf",
                "Formatted print to console",
                new String[]{"format: string", "...args: any"},
                "String[]",
                "IO"
            ),
            new FunctionInfo(
                "input",
                "Read a line from console (prompt on same line)",
                new String[]{"prompt: string (optional)"},
                "String",
                "IO"
            ),
            new FunctionInfo(
                "inputLine",
                "Read a line from console (prompt on new line)",
                new String[]{"prompt: string (optional)"},
                "String",
                "IO"
            ),
            new FunctionInfo(
                "printErr",
                "Print to error stream",
                new String[]{"...values: any"},
                "String[]",
                "IO"
            )
        };
    }

    @Override
    public String getProviderName() {
        return "IOFunctionProvider";
    }
}
