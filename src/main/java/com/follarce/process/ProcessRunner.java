package com.follarce.process;

import com.follarce.init.UserInit;
import com.follarce.plugin.FunctionContext;
import com.follarce.plugin.FunctionRegistry;
import com.follarce.basicUtil.*;
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
    private Stack<Integer> whileStack; // Track while loop start positions
    private FunctionContext functionContext; // Function call context

    public ProcessRunner(int pid) {
        this.pid = pid;
        this.running = true;
        this.functions = new HashMap<>();
        this.whileStack = new Stack<>();
        loadFromFile();

        // loadFromFile() sets running based on file's Status
        // If Status is false, running will be false and process won't execute

        if (owner != null) {
            UserUtil.setCurrentUser(owner);
        }
        
        // Initialize function call context
        int ppid = getParentPid();
        String currentUser = owner != null ? owner : UserInit.getCurrentUser();
        if (currentUser == null) currentUser = "local";
        this.functionContext = new FunctionContext(pid, ppid, currentUser);
    }
    
    private int getParentPid() {
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        if (!readResult[0].equals("SUCCESS")) return 0;
        
        try {
            Map<String, Object> process = (Map<String, Object>) JsonUtil.readJson(readResult[1]);
            Map<String, Object> parent = (Map<String, Object>) process.get("Parent");
            if (parent != null && parent.containsKey("PID")) {
                return ((Number) parent.get("PID")).intValue();
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
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

        Object statusObj = process.get("Status");
        if (statusObj == null) {
            running = false;
            return;
        }
        
        // Handle status: Boolean (true/false)
        if (statusObj instanceof Boolean) {
            running = (Boolean) statusObj;
        } else {
            // Unknown or null status, treat as stopped
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
        if (program == null) {
            running = false;
            return;
        }
        
        this.data = (Map<String, Object>) program.get("Data");
        if (this.data == null) {
            this.data = new HashMap<>();
        }

        // Set __current_script for import functionality
        String scriptPath = (String) process.get("Path");
        if (scriptPath != null && !scriptPath.isEmpty()) {
            this.data.put("__current_script", scriptPath);
        }

        Map<String, Object> code = (Map<String, Object>) program.get("Code");
        if (code == null) {
            running = false;
            return;
        }
        
        this.codeLines = (List<String>) code.get("Code");
        if (this.codeLines == null) {
            running = false;
            return;
        }
        
        // Remove null elements from codeLines
        this.codeLines.removeIf(Objects::isNull);

        // Load currentLine from file on every load to ensure correct position
        // This is important for fork() - child process needs to start from correct line
        Object runningLine = code.get("runningCodeLine");
        if (runningLine instanceof Number) {
            this.currentLine = ((Number) runningLine).intValue();
        } else if (runningLine instanceof List) {
            List<?> runningLineList = (List<?>) runningLine;
            if (!runningLineList.isEmpty()) {
                this.currentLine = ((Number) runningLineList.get(0)).intValue();
            } else {
                this.currentLine = 0;
            }
        } else {
            this.currentLine = 0;
        }
        
        // Restore whileStack from file
        this.whileStack.clear();
        Object whileStackObj = code.get("WhileStack");
        if (whileStackObj instanceof List) {
            List<?> whileStackList = (List<?>) whileStackObj;
            for (Object item : whileStackList) {
                if (item instanceof Number) {
                    this.whileStack.push(((Number) item).intValue());
                }
            }
        }
        
        // Note: Don't reset currentLine here - let executeLine handle process termination
        // when currentLine >= codeLines.size()
    }

    @SuppressWarnings("unchecked")
    private void saveToFile() {
        saveToFile(false);
    }

    private void saveToFile(boolean updateStatus) {
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
        
        // Save whileStack to persist loop state
        List<Integer> whileStackList = new ArrayList<>(this.whileStack);
        code.put("WhileStack", whileStackList);

        // Only update Status if explicitly requested (to avoid overwriting during shutdown)
        if (updateStatus) {
            process.put("Status", this.running);
        }

        FileUtil.write("/system/process/" + pid + ".json", JsonUtil.toJson(process));
    }

    @Override
    public void run() {
        if (owner != null) {
            UserUtil.setCurrentUser(owner);
        }

        while (running) {
            try {
                executeLine();
            } catch (Exception e) {
                Logger.error("Process " + pid + " crashed: " + e.getMessage());
                data.put("_error", "Process crashed: " + e.getMessage());
                running = false;
                saveToFile(true);
            }
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

        if (!running || codeLines == null)
            return;

        if (currentLine >= codeLines.size()) {
            running = false;
            saveToFile(true);
            return;
        }

        String line = codeLines.get(currentLine).trim();

        if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
            currentLine++;
            saveToFile();
            return;
        }

        if (line.equals("{")) {
            currentLine++;
            saveToFile();
            return;
        }
        
        if (line.equals("}")) {
            // Check if this is the end of a while block
            if (!whileStack.isEmpty()) {
                // Pop the while line and jump back to it
                int whileLine = whileStack.pop();
                currentLine = whileLine;
                saveToFile();
                // Don't increment currentLine here - we want to go back to while line
                return;
            } else {
                currentLine++;
            }
            saveToFile();
            return;
        }

        // For fork() and other statements: increment line first, then execute
        // This ensures child process starts from next line
        if (line.startsWith("fork(")) {
            currentLine++;  // Move to next line first
            saveToFile();   // Save state with updated line
            try {
                executeStatement(line);  // Then execute fork
            } catch (Exception e) {
                running = false;
                data.put("_error", e.getMessage());
                Logger.error("Process " + pid + " error at line " + currentLine + ": " + e.getMessage());
            }
            return;
        }

        // For exec(): reload code after execution
        if (line.startsWith("exec(")) {
            try {
                executeStatement(line);
                // exec replaces the process code, reload from file
                loadFromFile();
            } catch (Exception e) {
                running = false;
                data.put("_error", e.getMessage());
                Logger.error("Process " + pid + " error at line " + currentLine + ": " + e.getMessage());
            }
            return;
        }

        // Normal execution for other statements
        int lineBefore = currentLine;
        try {
            executeStatement(line);
        } catch (Exception e) {
            running = false;
            data.put("_error", e.getMessage());
            Logger.error("Process " + pid + " error at line " + currentLine + ": " + e.getMessage());
        }

        // Only increment if executeStatement didn't change currentLine (e.g., break already set it)
        if (currentLine == lineBefore) {
            currentLine++;
        }
        saveToFile(running == false);
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

        if (line.equals("break")) {
            handleBreak();
            return;
        }

        if (line.contains("=")) {
            handleAssignment(line);
            return;
        }

        evaluate(line);
    }

    private void handleBreak() {
        // Exit the current while loop
        // Find the matching closing brace and jump to the line after it
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
        // Pop the while stack since we're breaking out
        if (!whileStack.isEmpty()) {
            whileStack.pop();
        }
        // Set currentLine to the line after the closing brace
        // Make sure it doesn't exceed code length
        currentLine = Math.min(i, codeLines.size());
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
        // Extract condition from while statement
        // Support both: while condition {  and  while (condition) {
        String condition;
        String afterWhile = line.substring(5).trim(); // Remove "while"
        
        if (afterWhile.endsWith("{")) {
            afterWhile = afterWhile.substring(0, afterWhile.length() - 1).trim(); // Remove "{"
        }
        
        // Remove parentheses if present
        if (afterWhile.startsWith("(") && afterWhile.endsWith(")")) {
            condition = afterWhile.substring(1, afterWhile.length() - 1).trim();
        } else {
            condition = afterWhile;
        }

        if (!isTrue(evaluate(condition))) {
            // Condition is false, skip the entire while block
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
        } else {
            // Condition is true, push while line onto stack and enter block
            whileStack.push(currentLine);
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
            if (expr.length() < 2) {
                return "";
            }
            // Handle escape sequences in strings
            StringBuilder sb = new StringBuilder();
            int pos = 1;
            while (pos < expr.length() - 1) {
                char current = expr.charAt(pos);
                if (current == '\\') {
                    // Handle escape sequences
                    if (pos + 1 < expr.length() - 1) {
                        char next = expr.charAt(pos + 1);
                        switch (next) {
                            case 'n':
                                sb.append('\n');
                                break;
                            case 't':
                                sb.append('\t');
                                break;
                            case 'r':
                                sb.append('\r');
                                break;
                            case '"':
                                sb.append('"');
                                break;
                            case '\\':
                                sb.append('\\');
                                break;
                            default:
                                sb.append(next);
                        }
                        pos += 2;
                    } else {
                        throw new RuntimeException("Unclosed escape sequence");
                    }
                } else {
                    // Regular character
                    sb.append(current);
                    pos++;
                }
            }
            return sb.toString();
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

        // Check if this is a function call (identifier followed by parenthesis)
        // Don't treat expressions like "10 / (2 + 3)" as function calls
        if (expr.contains("(") && !expr.startsWith("\"") && !expr.startsWith("'")) {
            int parenIndex = expr.indexOf('(');
            String potentialFuncName = expr.substring(0, parenIndex).trim();
            // Check if potentialFuncName is a valid identifier (starts with letter or underscore)
            if (!potentialFuncName.isEmpty() && (Character.isLetter(potentialFuncName.charAt(0)) || potentialFuncName.charAt(0) == '_')) {
                return handleFunctionCall(expr);
            }
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
        
        // If funcName is empty, this is likely a parenthesized expression, not a function call
        if (funcName.isEmpty()) {
            return evaluateOperator(expr);
        }
        
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
                    String argStr = current.toString().trim();
                    if (!argStr.isEmpty()) {
                        args.add(evaluate(argStr));
                    } else {
                        args.add("");
                    }
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
            String argStr = current.toString().trim();
            if (!argStr.isEmpty()) {
                args.add(evaluate(argStr));
            } else {
                args.add("");
            }
        }

        Object[] argArray = args.toArray();

        // Use new plugin system to call functions
        Object result = FunctionRegistry.call(funcName, argArray, functionContext);
        if (result != null) {
            return result;
        }

        // 6. User-defined functions
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

        Logger.error("Process " + pid + " unknown function: " + funcName);
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

    // Token types for expression parsing
    private enum TokenType {
        NUMBER, STRING, BOOLEAN, IDENTIFIER, OPERATOR, LEFT_PAREN, RIGHT_PAREN, END
    }

    // Token class
    private static class Token {
        TokenType type;
        String value;
        
        Token(TokenType type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    // AST node types
    private static class ASTNode {
        String type;
        Object value;
        ASTNode left;
        ASTNode right;
        
        ASTNode(String type, Object value) {
            this.type = type;
            this.value = value;
        }
        
        ASTNode(String type, Object value, ASTNode left, ASTNode right) {
            this.type = type;
            this.value = value;
            this.left = left;
            this.right = right;
        }
    }

    // Operator precedence (higher number = higher precedence)
    private static final Map<String, Integer> PRECEDENCE = new HashMap<>();
    static {
        // Logical operators (lowest precedence)
        PRECEDENCE.put("or", 1);
        PRECEDENCE.put("and", 2);
        PRECEDENCE.put("not", 3);
        
        // Comparison operators
        PRECEDENCE.put("==", 4);
        PRECEDENCE.put("!=", 4);
        PRECEDENCE.put("<", 5);
        PRECEDENCE.put(">", 5);
        PRECEDENCE.put("<=", 5);
        PRECEDENCE.put(">=", 5);
        
        // Arithmetic operators
        PRECEDENCE.put("".concat("+"), 6);
        PRECEDENCE.put("-", 6);
        PRECEDENCE.put("*".concat(""), 7);
        PRECEDENCE.put("/", 7);
        PRECEDENCE.put("%", 7);
    }

    // Tokenize an expression
    private List<Token> tokenize(String expr) {
        List<Token> tokens = new ArrayList<>();
        int pos = 0;
        
        while (pos < expr.length()) {
            char c = expr.charAt(pos);
            
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (Character.isDigit(c) || (c == '.' && pos + 1 < expr.length() && Character.isDigit(expr.charAt(pos + 1)))) {
                // Number
                int start = pos;
                while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) {
                    pos++;
                }
                tokens.add(new Token(TokenType.NUMBER, expr.substring(start, pos)));
            } else if (c == '"') {
                // String
                int start = pos + 1;
                StringBuilder sb = new StringBuilder();
                pos = start;
                while (pos < expr.length()) {
                    char current = expr.charAt(pos);
                    if (current == '\\') {
                        // Handle escape sequences
                        if (pos + 1 < expr.length()) {
                            char next = expr.charAt(pos + 1);
                            switch (next) {
                                case 'n':
                                    sb.append('\n');
                                    break;
                                case 't':
                                    sb.append('\t');
                                    break;
                                case 'r':
                                    sb.append('\r');
                                    break;
                                case '"':
                                    sb.append('"');
                                    break;
                                case '\\':
                                    sb.append('\\');
                                    break;
                                default:
                                    sb.append(next);
                            }
                            pos += 2;
                        } else {
                            throw new RuntimeException("Unclosed escape sequence");
                        }
                    } else if (current == '"') {
                        // End of string
                        break;
                    } else {
                        // Regular character
                        sb.append(current);
                        pos++;
                    }
                }
                if (pos >= expr.length()) {
                    throw new RuntimeException("Unclosed string");
                }
                tokens.add(new Token(TokenType.STRING, sb.toString()));
                pos++;
            } else if (c == '(') {
                tokens.add(new Token(TokenType.LEFT_PAREN, "("));
                pos++;
            } else if (c == ')') {
                tokens.add(new Token(TokenType.RIGHT_PAREN, ")"));
                pos++;
            } else if (c == '+' || c == '-' || c == '*' || c == '/' || c == '%') {
                // Arithmetic operator
                tokens.add(new Token(TokenType.OPERATOR, String.valueOf(c)));
                pos++;
            } else if (c == '=') {
                // == operator
                if (pos + 1 < expr.length() && expr.charAt(pos + 1) == '=') {
                    tokens.add(new Token(TokenType.OPERATOR, "=="));
                    pos += 2;
                } else {
                    throw new RuntimeException("Invalid operator: " + c);
                }
            } else if (c == '!') {
                // != operator
                if (pos + 1 < expr.length() && expr.charAt(pos + 1) == '=') {
                    tokens.add(new Token(TokenType.OPERATOR, "!="));
                    pos += 2;
                } else {
                    throw new RuntimeException("Invalid operator: " + c);
                }
            } else if (c == '<') {
                // < or <= operator
                if (pos + 1 < expr.length() && expr.charAt(pos + 1) == '=') {
                    tokens.add(new Token(TokenType.OPERATOR, "<="));
                    pos += 2;
                } else {
                    tokens.add(new Token(TokenType.OPERATOR, "<"));
                    pos++;
                }
            } else if (c == '>') {
                // > or >= operator
                if (pos + 1 < expr.length() && expr.charAt(pos + 1) == '=') {
                    tokens.add(new Token(TokenType.OPERATOR, ">="));
                    pos += 2;
                } else {
                    tokens.add(new Token(TokenType.OPERATOR, ">") );
                    pos++;
                }
            } else if (Character.isLetter(c) || c == '_') {
                // Identifier or boolean
                int start = pos;
                while (pos < expr.length() && (Character.isLetterOrDigit(expr.charAt(pos)) || expr.charAt(pos) == '_')) {
                    pos++;
                }
                String value = expr.substring(start, pos);
                if (value.equals("true") || value.equals("false")) {
                    tokens.add(new Token(TokenType.BOOLEAN, value));
                } else if (value.equals("and") || value.equals("or") || value.equals("not")) {
                    tokens.add(new Token(TokenType.OPERATOR, value));
                } else {
                    tokens.add(new Token(TokenType.IDENTIFIER, value));
                }
            } else {
                throw new RuntimeException("Unexpected character: " + c);
            }
        }
        
        tokens.add(new Token(TokenType.END, ""));
        return tokens;
    }

    // Parser class for building AST
    private class Parser {
        private List<Token> tokens;
        private int pos;
        
        Parser(List<Token> tokens) {
            this.tokens = tokens;
            this.pos = 0;
        }
        
        private Token peek() {
            return tokens.get(pos);
        }
        
        private Token consume() {
            return tokens.get(pos++);
        }
        
        private boolean match(TokenType type) {
            if (peek().type == type) {
                consume();
                return true;
            }
            return false;
        }
        
        // Parse expressions with operator precedence
        public ASTNode parse() {
            return parseExpression(0);
        }
        
        // Recursive descent parser with precedence
        private ASTNode parseExpression(int precedence) {
            ASTNode left = parsePrimary();
            
            while (true) {
                Token token = peek();
                if (token.type != TokenType.OPERATOR) break;
                
                int opPrecedence = PRECEDENCE.getOrDefault(token.value, 0);
                if (opPrecedence <= precedence) break;
                
                consume(); // Consume the operator
                String op = token.value;
                
                // For unary operators like "not"
                if (op.equals("not")) {
                    ASTNode right = parseExpression(opPrecedence);
                    left = new ASTNode("unary", op, null, right);
                } else {
                    // For binary operators
                    ASTNode right = parseExpression(opPrecedence);
                    left = new ASTNode("binary", op, left, right);
                }
            }
            
            return left;
        }
        
        private ASTNode parsePrimary() {
            Token token = peek();
            
            if (match(TokenType.NUMBER)) {
                if (token.value.contains(".")) {
                    return new ASTNode("number", Double.parseDouble(token.value));
                } else {
                    return new ASTNode("number", Integer.parseInt(token.value));
                }
            }
            
            if (match(TokenType.STRING)) {
                return new ASTNode("string", token.value);
            }
            
            if (match(TokenType.BOOLEAN)) {
                return new ASTNode("boolean", Boolean.parseBoolean(token.value));
            }
            
            if (match(TokenType.IDENTIFIER)) {
                return new ASTNode("identifier", token.value);
            }
            
            if (match(TokenType.LEFT_PAREN)) {
                ASTNode expr = parseExpression(0);
                if (!match(TokenType.RIGHT_PAREN)) {
                    throw new RuntimeException("Expected closing parenthesis");
                }
                return expr;
            }
            
            throw new RuntimeException("Expected expression");
        }
    }

    // Evaluate an AST node
    private Object evaluateAST(ASTNode node) {
        switch (node.type) {
            case "number":
            case "string":
            case "boolean":
                return node.value;
            
            case "identifier":
                String varName = (String) node.value;
                if (data.containsKey(varName)) {
                    return data.get(varName);
                } else {
                    throw new RuntimeException("Variable not defined: " + varName);
                }
            
            case "unary":
                String unaryOp = (String) node.value;
                Object right = evaluateAST(node.right);
                if (unaryOp.equals("not")) {
                    return !isTrue(right);
                } else if (unaryOp.equals("-")) {
                    if (right instanceof Number) {
                        return -((Number) right).doubleValue();
                    } else {
                        throw new RuntimeException("Unary minus only applies to numbers");
                    }
                }
                break;
            
            case "binary":
                String op = (String) node.value;
                Object leftVal = evaluateAST(node.left);
                Object rightVal = evaluateAST(node.right);
                
                switch (op) {
                    // Logical operators
                    case "and":
                        return isTrue(leftVal) && isTrue(rightVal);
                    case "or":
                        return isTrue(leftVal) || isTrue(rightVal);
                    
                    // Comparison operators
                    case "==":
                        return Objects.equals(leftVal, rightVal);
                    case "!=":
                        return !Objects.equals(leftVal, rightVal);
                    case "<":
                        ensureNumbers(leftVal, rightVal);
                        return ((Number) leftVal).doubleValue() < ((Number) rightVal).doubleValue();
                    case ">":
                        ensureNumbers(leftVal, rightVal);
                        return ((Number) leftVal).doubleValue() > ((Number) rightVal).doubleValue();
                    case "<=":
                        ensureNumbers(leftVal, rightVal);
                        return ((Number) leftVal).doubleValue() <= ((Number) rightVal).doubleValue();
                    case ">=":
                        ensureNumbers(leftVal, rightVal);
                        return ((Number) leftVal).doubleValue() >= ((Number) rightVal).doubleValue();
                    
                    // Arithmetic operators
                    case "+":
                        if (leftVal instanceof Number && rightVal instanceof Number) {
                            return ((Number) leftVal).doubleValue() + ((Number) rightVal).doubleValue();
                        } else {
                            return leftVal.toString() + rightVal.toString();
                        }
                    case "-":
                        ensureNumbers(leftVal, rightVal);
                        return ((Number) leftVal).doubleValue() - ((Number) rightVal).doubleValue();
                    case "*":
                        ensureNumbers(leftVal, rightVal);
                        return ((Number) leftVal).doubleValue() * ((Number) rightVal).doubleValue();
                    case "/":
                        ensureNumbers(leftVal, rightVal);
                        if (((Number) rightVal).doubleValue() == 0) {
                            throw new RuntimeException("Division by zero");
                        }
                        return ((Number) leftVal).doubleValue() / ((Number) rightVal).doubleValue();
                    case "%":
                        ensureNumbers(leftVal, rightVal);
                        if (((Number) rightVal).intValue() == 0) {
                            throw new RuntimeException("Modulo by zero");
                        }
                        return ((Number) leftVal).intValue() % ((Number) rightVal).intValue();
                }
                break;
        }
        
        throw new RuntimeException("Cannot evaluate AST node: " + node.type);
    }

    // Ensure both values are numbers
    private void ensureNumbers(Object left, Object right) {
        if (!(left instanceof Number) || !(right instanceof Number)) {
            throw new RuntimeException("Operands must be numbers");
        }
    }

    // New evaluateOperator method using AST
    private Object evaluateOperator(String expr) {
        try {
            String trimmedExpr = expr.trim();
            if (trimmedExpr.isEmpty()) {
                return "";
            }
            // Handle cases with only parentheses
            if (trimmedExpr.equals("()")) {
                return "";
            }
            List<Token> tokens = tokenize(trimmedExpr);
            Parser parser = new Parser(tokens);
            ASTNode ast = parser.parse();
            return evaluateAST(ast);
        } catch (Exception e) {
            // If parsing fails, return the original expression as a string
            return expr;
        }
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

    public void shutdown() {
        running = false;
        // Don't save to file - preserve original Status
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