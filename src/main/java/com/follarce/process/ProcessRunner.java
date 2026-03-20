package com.follarce.process;

import com.follarce.util.*;
import java.util.*;
import java.util.regex.*;

public class ProcessRunner implements Runnable {

    private int pid;
    private boolean running;
    private Map<String, Object> data;
    private List<String> codeLines;
    private int currentLine;
    private Map<String, FunctionDef> functions;
    private Object returnValue;
    private long startTimeMs;
    private String owner;

    public ProcessRunner(int pid) {
        this.pid = pid;
        this.running = true;
        this.functions = new HashMap<>();
        ProcessFunc.setCurrentPid(pid);
        loadFromFile();

        if (owner != null) {
            UserUtil.setCurrentUser(owner);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromFile() {
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        if (!readResult[0].equals("SUCCESS")) {
            running = false;
            return;
        }

        Map<String, Object> process;
        try {
            process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
        } catch (Exception e) {
            running = false;
            return;
        }

        this.owner = (String) process.get("Owner");
        if (this.owner == null) {
            this.owner = "local";
        }

        Boolean status = (Boolean) process.get("Status");
        if (status == null || !status) {
            running = false;
            return;
        }

        Object startTimeObj = process.get("startTime");
        if (startTimeObj instanceof List) {
            List<?> list = (List<?>) startTimeObj;
            int[] startTime = new int[7];
            for (int i = 0; i < list.size() && i < 7; i++) {
                Object val = list.get(i);
                if (val instanceof Number) {
                    startTime[i] = ((Number) val).intValue();
                }
            }
            Calendar cal = Calendar.getInstance();
            cal.set(startTime[0], startTime[1] - 1, startTime[2],
                    startTime[3], startTime[4], startTime[5]);
            this.startTimeMs = cal.getTimeInMillis() + startTime[6];
        } else {
            this.startTimeMs = System.currentTimeMillis();
        }

        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        this.data = (Map<String, Object>) program.get("Data");
        if (this.data == null) {
            this.data = new HashMap<>();
        }

        Map<String, Object> code = (Map<String, Object>) program.get("Code");
        this.codeLines = (List<String>) code.get("Code");

        Object runningLine = code.get("runningCodeLine");
        if (runningLine instanceof Number) {
            this.currentLine = ((Number) runningLine).intValue();
        } else if (runningLine instanceof List) {
            this.currentLine = ((Number) ((List<?>) runningLine).get(0)).intValue();
        } else {
            this.currentLine = 0;
        }

        if (this.currentLine >= this.codeLines.size()) {
            this.currentLine = 0;
        }
    }

    @SuppressWarnings("unchecked")
    private void saveToFile() {
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        if (!readResult[0].equals("SUCCESS")) {
            return;
        }

        Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);

        long currentTime = System.currentTimeMillis();
        int runningSeconds = (int) ((currentTime - startTimeMs) / 1000);
        process.put("RunningTime", runningSeconds);

        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        program.put("Data", this.data);

        Map<String, Object> code = (Map<String, Object>) program.get("Code");
        code.put("runningCodeLine", this.currentLine);

        process.put("Status", this.running);

        FileUtil.write("/system/process/" + pid + ".json", JsonUtil.toJson(process));
    }

    @Override
    public void run() {
        if (owner != null) {
            UserUtil.setCurrentUser(owner);
        }

        while (running) {
            executeLine();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    public void executeLine() {
        if (owner != null) {
            UserUtil.setCurrentUser(owner);
        }

        loadFromFile();

        if (!running)
            return;

        if (currentLine >= codeLines.size()) {
            running = false;
            saveToFile();
            return;
        }

        String line = codeLines.get(currentLine).trim();

        if (line.isEmpty() || line.startsWith("//")) {
            currentLine++;
            saveToFile();
            return;
        }

        if (line.equals("{") || line.equals("}")) {
            currentLine++;
            saveToFile();
            return;
        }

        try {
            executeStatement(line);
        } catch (Exception e) {
            running = false;
            data.put("_error", e.getMessage());
        }

        currentLine++;
        saveToFile();
    }

    private void executeStatement(String line) {
        if (line.startsWith("import ")) {
            handleImport(line);
            return;
        }

        if (line.startsWith("if ")) {
            handleIf(line);
            return;
        }

        if (line.startsWith("while ")) {
            handleWhile(line);
            return;
        }

        if (line.startsWith("return ")) {
            handleReturn(line);
            return;
        }

        if (line.contains("=")) {
            handleAssignment(line);
            return;
        }

        evaluate(line);
    }

    private void handleImport(String line) {
        Pattern p = Pattern.compile("import\\s+\"([^\"]+)\"");
        Matcher m = p.matcher(line);
        if (!m.find()) {
            throw new RuntimeException("Invalid import syntax");
        }

        String fileName = m.group(1);
        String importPath;

        String currentScript = (String) data.get("__current_script");
        if (currentScript != null) {
            int lastSlash = currentScript.lastIndexOf('/');
            if (lastSlash >= 0) {
                importPath = currentScript.substring(0, lastSlash + 1) + fileName;
            } else {
                importPath = fileName;
            }
        } else {
            importPath = "/user/local/app/" + fileName;
        }

        String[] readResult = FileUtil.read(importPath);
        if (!readResult[0].equals("SUCCESS")) {
            throw new RuntimeException("Failed to import: " + fileName);
        }

        parseFunctionsFromScript(readResult[1]);
    }

    private void parseFunctionsFromScript(String content) {
        String[] lines = content.split("\n");
        int i = 0;

        while (i < lines.length) {
            String line = lines[i].trim();

            if (line.startsWith("func ")) {
                Pattern p = Pattern.compile("func\\s+(\\w+)\\((.*)\\)\\s*\\{");
                Matcher m = p.matcher(line);
                if (m.find()) {
                    String name = m.group(1);
                    String paramsStr = m.group(2).trim();
                    List<String> params = paramsStr.isEmpty() ? new ArrayList<>()
                            : Arrays.asList(paramsStr.split("\\s*,\\s*"));

                    List<String> body = new ArrayList<>();
                    i++;
                    int braceCount = 1;

                    while (i < lines.length && braceCount > 0) {
                        String bodyLine = lines[i];
                        i++;
                        if (bodyLine.contains("{"))
                            braceCount++;
                        if (bodyLine.contains("}"))
                            braceCount--;
                        if (braceCount > 0) {
                            body.add(bodyLine);
                        }
                    }

                    functions.put(name, new FunctionDef(params, body));
                    continue;
                }
            }
            i++;
        }
    }

    private void handleIf(String line) {
        String condition = line.substring(2, line.length() - 1).trim();
        boolean result = isTrue(evaluate(condition));

        if (!result) {
            int braceCount = 1;
            int i = currentLine + 1;
            while (i < codeLines.size() && braceCount > 0) {
                String l = codeLines.get(i).trim();
                if (l.equals("{"))
                    braceCount++;
                if (l.equals("}"))
                    braceCount--;
                i++;
            }
            currentLine = i - 1;
        }
    }

    private void handleWhile(String line) {
        String condition = line.substring(5, line.length() - 1).trim();

        if (!isTrue(evaluate(condition))) {
            int braceCount = 1;
            int i = currentLine + 1;
            while (i < codeLines.size() && braceCount > 0) {
                String l = codeLines.get(i).trim();
                if (l.equals("{"))
                    braceCount++;
                if (l.equals("}"))
                    braceCount--;
                i++;
            }
            currentLine = i - 1;
        }
    }

    private void handleReturn(String line) {
        String expr = line.substring(7).trim();
        if (expr.isEmpty()) {
            returnValue = null;
        } else {
            returnValue = evaluate(expr);
        }
        int braceCount = 1;
        int i = currentLine + 1;
        while (i < codeLines.size() && braceCount > 0) {
            String l = codeLines.get(i).trim();
            if (l.equals("{"))
                braceCount++;
            if (l.equals("}"))
                braceCount--;
            i++;
        }
        currentLine = i - 1;
    }

    private void handleAssignment(String line) {
        String assignLine = line;
        String[] typeKeywords = { "int ", "string ", "array ", "map " };
        for (String type : typeKeywords) {
            if (line.startsWith(type)) {
                assignLine = line.substring(type.length());
                break;
            }
        }

        String[] parts = assignLine.split("=", 2);
        if (parts.length < 2) {
            throw new RuntimeException("Invalid assignment syntax");
        }

        String left = parts[0].trim();
        String right = parts[1].trim();

        if (left.contains("[")) {
            handleIndexAssignment(left, right);
            return;
        }

        Object value = evaluate(right);
        data.put(left, value);
    }

    @SuppressWarnings("unchecked")
    private void handleIndexAssignment(String left, String right) {
        Pattern p = Pattern.compile("(\\w+)\\[(.*)\\]");
        Matcher m = p.matcher(left);
        if (!m.find()) {
            throw new RuntimeException("Invalid index syntax");
        }

        String containerName = m.group(1);
        String indexExpr = m.group(2).trim();

        Object container = data.get(containerName);
        if (container == null) {
            throw new RuntimeException("Variable not defined: " + containerName);
        }

        Object index = evaluate(indexExpr);
        Object value = evaluate(right);

        if (container instanceof List && index instanceof Number) {
            List<Object> list = (List<Object>) container;
            int idx = ((Number) index).intValue();
            if (idx < 0)
                idx = list.size() + idx;
            if (idx < 0 || idx >= list.size()) {
                throw new RuntimeException("Array index out of bounds");
            }
            list.set(idx, value);
        } else if (container instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) container;
            map.put(index, value);
        } else {
            throw new RuntimeException("Index operation not supported");
        }
    }

    private Object evaluate(String expr) {
        expr = expr.trim();

        if (expr.matches("-?\\d+")) {
            return Integer.parseInt(expr);
        }
        if (expr.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(expr);
        }
        if (expr.startsWith("\"") && expr.endsWith("\"")) {
            return expr.substring(1, expr.length() - 1);
        }
        if (expr.equals("true"))
            return true;
        if (expr.equals("false"))
            return false;

        if (expr.startsWith("[") && expr.endsWith("]")) {
            return parseArray(expr);
        }

        if (expr.startsWith("{") && expr.endsWith("}")) {
            return parseMap(expr);
        }

        if (expr.startsWith("#")) {
            String varName = expr.substring(1);
            Object val = data.get(varName);
            if (val instanceof List)
                return ((List<?>) val).size();
            if (val instanceof Map)
                return ((Map<?, ?>) val).size();
            if (val instanceof String)
                return ((String) val).length();
            throw new RuntimeException("Cannot get length");
        }

        if (expr.contains("(") && !expr.startsWith("\"") && !expr.startsWith("'")) {
            return handleFunctionCall(expr);
        }

        if (data.containsKey(expr)) {
            return data.get(expr);
        }

        if (expr.contains("[")) {
            return handleIndexAccess(expr);
        }

        return evaluateOperator(expr);
    }

    private Object handleFunctionCall(String expr) {
        int parenIndex = expr.indexOf('(');
        String funcName = expr.substring(0, parenIndex).trim();
        String argsStr = expr.substring(parenIndex + 1, expr.length() - 1).trim();

        List<Object> args = new ArrayList<>();
        if (!argsStr.isEmpty()) {
            int depth = 0;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < argsStr.length(); i++) {
                char c = argsStr.charAt(i);
                if (c == '(')
                    depth++;
                if (c == ')')
                    depth--;
                if (c == ',' && depth == 0) {
                    args.add(evaluate(current.toString().trim()));
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
            if (current.length() > 0) {
                args.add(evaluate(current.toString().trim()));
            }
        }

        Object[] argArray = args.toArray();

        // 1. Try FileUtil
        Object result = FileUtil.call(funcName, argArray);
        if (result != null) {
            return result;
        }

        // 2. Try ProcessFunc
        result = ProcessFunc.call(funcName, argArray);
        if (result != null) {
            return result;
        }

        // 3. Try TimeUtil
        if ("now".equals(funcName)) {
            return TimeUtil.getTime();
        }

        // 4. Try JsonUtil
        if ("parseJson".equals(funcName)) {
            if (argArray.length < 1)
                return new String[] { "ERROR", "INVALID_ARGUMENTS" };
            return JsonUtil.readJson((String) argArray[0]);
        }
        if ("toJson".equals(funcName)) {
            if (argArray.length < 1)
                return new String[] { "ERROR", "INVALID_ARGUMENTS" };
            return JsonUtil.toJson(argArray[0]);
        }

        // 5. Type conversion
        if ("int".equals(funcName)) {
            if (argArray.length < 1)
                return 0;
            if (argArray[0] instanceof String)
                return Integer.parseInt((String) argArray[0]);
            if (argArray[0] instanceof Number)
                return ((Number) argArray[0]).intValue();
            return 0;
        }
        if ("str".equals(funcName)) {
            if (argArray.length < 1)
                return "";
            return argArray[0].toString();
        }
        if ("len".equals(funcName)) {
            if (argArray.length < 1)
                return 0;
            Object obj = argArray[0];
            if (obj instanceof List)
                return ((List<?>) obj).size();
            if (obj instanceof Map)
                return ((Map<?, ?>) obj).size();
            if (obj instanceof String)
                return ((String) obj).length();
            if (obj instanceof Object[])
                return ((Object[]) obj).length;
            return 1;
        }

        // 6. User defined functions
        FunctionDef func = functions.get(funcName);
        if (func != null) {
            if (args.size() != func.params.size()) {
                throw new RuntimeException("Function " + funcName + " expects " +
                        func.params.size() + " arguments, got " + args.size());
            }

            Map<String, Object> oldData = new HashMap<>(this.data);
            int oldLine = this.currentLine;
            List<String> oldCodeLines = this.codeLines;
            Object oldReturnValue = this.returnValue;
            this.returnValue = null;

            for (int i = 0; i < func.params.size(); i++) {
                this.data.put(func.params.get(i), args.get(i));
            }

            this.codeLines = func.body;
            this.currentLine = 0;

            while (this.currentLine < this.codeLines.size() && this.returnValue == null) {
                String line = this.codeLines.get(this.currentLine).trim();
                this.currentLine++;
                if (!line.isEmpty() && !line.startsWith("//") && !line.equals("{") && !line.equals("}")) {
                    executeStatement(line);
                }
            }

            Object ret = this.returnValue;

            this.data = oldData;
            this.codeLines = oldCodeLines;
            this.currentLine = oldLine;
            this.returnValue = oldReturnValue;

            return ret;
        }

        throw new RuntimeException("Unknown function: " + funcName);
    }

    private Object handleIndexAccess(String expr) {
        Pattern p = Pattern.compile("(\\w+)\\[(.*)\\]");
        Matcher m = p.matcher(expr);
        if (!m.find()) {
            throw new RuntimeException("Invalid index syntax");
        }

        String containerName = m.group(1);
        String indexExpr = m.group(2).trim();

        Object container = data.get(containerName);
        if (container == null) {
            throw new RuntimeException("Variable not defined: " + containerName);
        }

        Object index = evaluate(indexExpr);

        if (container instanceof List && index instanceof Number) {
            List<?> list = (List<?>) container;
            int idx = ((Number) index).intValue();
            if (idx < 0)
                idx = list.size() + idx;
            if (idx < 0 || idx >= list.size()) {
                throw new RuntimeException("Array index out of bounds");
            }
            return list.get(idx);
        } else if (container instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) container;
            return map.get(index);
        } else {
            throw new RuntimeException("Index operation not supported");
        }
    }

    private List<Object> parseArray(String expr) {
        String content = expr.substring(1, expr.length() - 1).trim();
        List<Object> result = new ArrayList<>();

        if (!content.isEmpty()) {
            int depth = 0;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '[')
                    depth++;
                if (c == ']')
                    depth--;
                if (c == ',' && depth == 0) {
                    result.add(evaluate(current.toString().trim()));
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
            if (current.length() > 0) {
                result.add(evaluate(current.toString().trim()));
            }
        }

        return result;
    }

    private Map<Object, Object> parseMap(String expr) {
        String content = expr.substring(1, expr.length() - 1).trim();
        Map<Object, Object> result = new HashMap<>();

        if (!content.isEmpty()) {
            int depth = 0;
            boolean inKey = true;
            StringBuilder currentKey = new StringBuilder();
            StringBuilder currentValue = new StringBuilder();

            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{' || c == '[')
                    depth++;
                if (c == '}' || c == ']')
                    depth--;

                if (c == ':' && depth == 0 && inKey) {
                    inKey = false;
                    continue;
                }

                if (c == ',' && depth == 0) {
                    if (!inKey && currentValue.length() > 0) {
                        Object key = evaluate(currentKey.toString().trim());
                        Object value = evaluate(currentValue.toString().trim());
                        result.put(key, value);
                    }
                    currentKey = new StringBuilder();
                    currentValue = new StringBuilder();
                    inKey = true;
                } else if (inKey) {
                    currentKey.append(c);
                } else {
                    currentValue.append(c);
                }
            }

            if (!inKey && currentValue.length() > 0) {
                Object key = evaluate(currentKey.toString().trim());
                Object value = evaluate(currentValue.toString().trim());
                result.put(key, value);
            }
        }

        return result;
    }

    private Object evaluateOperator(String expr) {
        if (expr.contains(" and ")) {
            String[] parts = expr.split(" and ", 2);
            boolean left = isTrue(evaluate(parts[0].trim()));
            boolean right = isTrue(evaluate(parts[1].trim()));
            return left && right;
        }

        if (expr.contains(" or ")) {
            String[] parts = expr.split(" or ", 2);
            boolean left = isTrue(evaluate(parts[0].trim()));
            boolean right = isTrue(evaluate(parts[1].trim()));
            return left || right;
        }

        if (expr.startsWith("not ")) {
            return !isTrue(evaluate(expr.substring(4).trim()));
        }

        if (expr.contains("==")) {
            String[] parts = expr.split("==", 2);
            Object left = evaluate(parts[0].trim());
            Object right = evaluate(parts[1].trim());
            return Objects.equals(left, right);
        }

        if (expr.contains("!=")) {
            String[] parts = expr.split("!=", 2);
            Object left = evaluate(parts[0].trim());
            Object right = evaluate(parts[1].trim());
            return !Objects.equals(left, right);
        }

        if (expr.contains("+")) {
            String[] parts = expr.split("\\+", 2);
            Object left = evaluate(parts[0].trim());
            Object right = evaluate(parts[1].trim());

            if (left instanceof Number && right instanceof Number) {
                return ((Number) left).doubleValue() + ((Number) right).doubleValue();
            }
            return left.toString() + right.toString();
        }

        if (expr.contains("-") && !expr.startsWith("-")) {
            String[] parts = expr.split("-", 2);
            Object left = evaluate(parts[0].trim());
            Object right = evaluate(parts[1].trim());
            return ((Number) left).doubleValue() - ((Number) right).doubleValue();
        }

        if (expr.contains("*")) {
            String[] parts = expr.split("\\*", 2);
            Object left = evaluate(parts[0].trim());
            Object right = evaluate(parts[1].trim());
            return ((Number) left).doubleValue() * ((Number) right).doubleValue();
        }

        if (expr.contains("/")) {
            String[] parts = expr.split("/", 2);
            Object left = evaluate(parts[0].trim());
            Object right = evaluate(parts[1].trim());
            if (((Number) right).doubleValue() == 0) {
                throw new RuntimeException("Division by zero");
            }
            return ((Number) left).doubleValue() / ((Number) right).doubleValue();
        }

        if (expr.contains("%")) {
            String[] parts = expr.split("%", 2);
            Object left = evaluate(parts[0].trim());
            Object right = evaluate(parts[1].trim());
            return ((Number) left).intValue() % ((Number) right).intValue();
        }

        throw new RuntimeException("Cannot parse expression: " + expr);
    }

    private boolean isTrue(Object obj) {
        if (obj == null)
            return false;
        if (obj instanceof Boolean)
            return (Boolean) obj;
        if (obj instanceof Number)
            return ((Number) obj).doubleValue() != 0;
        if (obj instanceof String)
            return !((String) obj).isEmpty();
        if (obj instanceof List)
            return !((List<?>) obj).isEmpty();
        if (obj instanceof Map)
            return !((Map<?, ?>) obj).isEmpty();
        return true;
    }

    public int getPid() {
        return pid;
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        saveToFile();
    }

    private static class FunctionDef {
        List<String> params;
        List<String> body;

        FunctionDef(List<String> params, List<String> body) {
            this.params = params;
            this.body = body;
        }
    }
}