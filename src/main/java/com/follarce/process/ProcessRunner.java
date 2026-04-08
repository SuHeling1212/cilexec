package com.follarce.process;

import com.follarce.init.UserInit;
import com.follarce.plugin.FunctionContext;
import com.follarce.plugin.FunctionRegistry;
import com.follarce.basicUtil.*;
import com.follarce.process.exception.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProcessRunner implements Runnable {

    private enum BlockType {
        WHILE, IF, FUNCTION
    }

    private static class BlockInfo {
        BlockType type;
        int startLine;
        String condition; // Only for WHILE blocks

        BlockInfo(BlockType type, int startLine) {
            this.type = type;
            this.startLine = startLine;
        }

        BlockInfo(BlockType type, int startLine, String condition) {
            this.type = type;
            this.startLine = startLine;
            this.condition = condition;
        }
    }

    private int pid;
    private boolean running;
    private Map<String, Object> data;
    private List<String> codeLines;
    private int currentLine;
    private Map<String, FunctionDef> functions;
    private Object returnValue;
    private long startTimeMs;
    private String owner;
    private Deque<BlockInfo> blockStack; // Track block types and positions
    private FunctionContext functionContext; // Function call context

    public ProcessRunner(int pid) {
        this.pid = pid;
        this.running = true;
        this.functions = new HashMap<>();
        this.blockStack = new ArrayDeque<>();
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

        // Set thread-local PID for process isolation
        ProcessFunc.setCurrentPid(pid);
    }
    
    private int getParentPid() {
        String[] readResult = FileUtil.read("/system/process/" + pid + ".json");
        if (!readResult[0].equals("SUCCESS")) return 0;
        
        try {
            Object processObj = JsonUtil.readJson(readResult[1]);
            if (!(processObj instanceof Map)) return 0;
            Map<String, Object> process = (Map<String, Object>) processObj;
            Map<String, Object> parent = (Map<String, Object>) process.get("Parent");
            if (parent != null && parent.containsKey("PID")) {
                Object parentPidObj = parent.get("PID");
                if (parentPidObj instanceof Number) {
                    return ((Number) parentPidObj).intValue();
                }
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
            Object processObj = JsonUtil.readJson(readResult[1]);
            if (!(processObj instanceof Map)) {
                running = false;
                return;
            }
            process = (Map<String, Object>) processObj;
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
        
        // Restore blockStack from file
        this.blockStack.clear();
        Object blockStackObj = code.get("BlockStack");
        if (blockStackObj instanceof List) {
            List<?> blockStackList = (List<?>) blockStackObj;
            for (Object item : blockStackList) {
                if (item instanceof Map) {
                    Map<?, ?> blockMap = (Map<?, ?>) item;
                    String typeStr = (String) blockMap.get("type");
                    Number startLineNum = (Number) blockMap.get("startLine");
                    String condition = (String) blockMap.get("condition");
                    if (typeStr != null && startLineNum != null) {
                        BlockType type = BlockType.valueOf(typeStr);
                        BlockInfo blockInfo = new BlockInfo(type, startLineNum.intValue(), condition);
                        this.blockStack.push(blockInfo);
                    }
                }
            }
        }
        
        // Parse function definitions (don't remove them, will skip during execution)
        parseFunctionDefinitions();

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

        Object processObj = JsonUtil.readJson(readResult[1]);
        if (!(processObj instanceof Map)) {
            return;
        }
        Map<String, Object> process = (Map<String, Object>) processObj;

        long currentTime = System.currentTimeMillis();
        int runningSeconds = (int) ((currentTime - startTimeMs) / Constants.TIME_DIVISOR);
        process.put("RunningTime", runningSeconds);

        Map<String, Object> program = (Map<String, Object>) process.get("Program");
        program.put("Data", this.data);

        Map<String, Object> code = (Map<String, Object>) program.get("Code");
        code.put("runningCodeLine", this.currentLine);
        code.put("Code", this.codeLines);
        
        // Save blockStack to persist block state
        List<Map<String, Object>> blockStackList = new ArrayList<>();
        for (BlockInfo block : this.blockStack) {
            Map<String, Object> blockMap = new HashMap<>();
            blockMap.put("type", block.type.name());
            blockMap.put("startLine", block.startLine);
            if (block.condition != null) {
                blockMap.put("condition", block.condition);
            }
            blockStackList.add(blockMap);
        }
        code.put("BlockStack", blockStackList);

        // Only update Status if explicitly requested (to avoid overwriting during shutdown)
        if (updateStatus) {
            process.put("Status", this.running);
        }

        FileUtil.write("/system/process/" + pid + ".json", JsonUtil.toJsonPretty(process));
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
                handleException(e, "process_execution");
            }
            try {
                Thread.sleep(Constants.PROCESS_TICK_MS);
            } catch (InterruptedException e) {
                Logger.info("Process " + pid + " interrupted");
                break;
            }
        }
    }

    public void executeLine() {
        if (owner != null) {
            UserUtil.setCurrentUser(owner);
        }

        loadFromFile();

        if (currentLine >= codeLines.size()) {
            running = false;
            saveToFile(true);
            return;
        }

        if (!running || codeLines == null) {
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
            if (!blockStack.isEmpty()) {
                BlockInfo block = blockStack.peek();
                if (block.type == BlockType.WHILE) {
                    String condition = block.condition;
                    if (isTrue(evaluate(condition))) {
                        currentLine = block.startLine;
                    } else {
                        blockStack.pop();
                        currentLine++;
                    }
                } else {
                    blockStack.pop();
                    currentLine++;
                }
            } else {
                currentLine++;
            }
            if (currentLine >= codeLines.size()) {
                running = false;
            }
            saveToFile();
            return;
        }

        // For fork() and other statements: increment line first, then execute
        // This ensures child process starts from next line
        if (line.startsWith("fork(")) {
            currentLine++;
            saveToFile();
            try {
                executeStatement(line);
            } catch (Exception e) {
                handleException(e, "fork_operation");
            }
            return;
        }

        if (line.startsWith("exec(")) {
            try {
                executeStatement(line);
                loadFromFile();
                saveToFile();
            } catch (Exception e) {
                handleException(e, "exec_operation");
            }
            return;
        }

        int lineBefore = currentLine;
        try {
            executeStatement(line);
        } catch (Exception e) {
            handleException(e, "statement_execution");
        }

        if (currentLine == lineBefore) {
            currentLine++;
        }
        saveToFile(running == false);
    }

    private void executeStatement(String line) {
        if (line.startsWith("func ")) {
            return;
        }

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

        // Check if this is an assignment (variable = value)
        // Must be: identifier = expression, not inside quotes or parentheses
        if (isAssignment(line)) {
            handleAssignment(line);
            return;
        }

        evaluate(line);
    }
    
    private boolean isAssignment(String line) {
        int eqIndex = line.indexOf('=');
        if (eqIndex == -1) {
            return false;
        }
        
        // Check if there's a second '=' (comparison operator ==)
        if (eqIndex + 1 < line.length() && line.charAt(eqIndex + 1) == '=') {
            return false;
        }
        
        // Check if '=' is inside quotes
        boolean inQuotes = false;
        for (int i = 0; i < eqIndex; i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
            }
        }
        if (inQuotes) {
            return false;
        }
        
        // Check if '=' is inside parentheses
        int parenDepth = 0;
        for (int i = 0; i < eqIndex; i++) {
            char c = line.charAt(i);
            if (c == '(') parenDepth++;
            if (c == ')') parenDepth--;
        }
        if (parenDepth > 0) {
            return false;
        }
        
        // Check if left side is a valid identifier (possibly with index access)
        String left = line.substring(0, eqIndex).trim();
        if (left.isEmpty()) {
            return false;
        }
        
        // Valid identifier: starts with letter or underscore, contains only alphanumeric, underscore, and brackets
        if (left.contains("[") && left.contains("]")) {
            // Index access: identifier[expr]
            int bracketIndex = left.indexOf('[');
            String baseName = left.substring(0, bracketIndex).trim();
            return isValidIdentifier(baseName);
        }
        
        return isValidIdentifier(left);
    }
    
    private boolean isValidIdentifier(String name) {
        if (name.isEmpty()) {
            return false;
        }
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    private boolean isStandaloneFunctionCall(String expr) {
        expr = expr.trim();
        if (expr.isEmpty() || expr.contains(" ")) {
            return false;
        }
        
        int parenIndex = expr.indexOf('(');
        if (parenIndex <= 0) {
            return false;
        }
        
        String funcName = expr.substring(0, parenIndex).trim();
        if (!isValidIdentifier(funcName)) {
            return false;
        }
        
        if (!expr.endsWith(")")) {
            return false;
        }
        
        return true;
    }

    private void handleBreak() {
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
        if (!blockStack.isEmpty() && blockStack.peek().type == BlockType.WHILE) {
            blockStack.pop();
        }
        currentLine = Math.min(i, codeLines.size());
    }

    private void handleImport(String line) {
        Pattern p = Pattern.compile("import\\s+\"([^\"]+)\"");
        Matcher m = p.matcher(line);
        if (!m.find()) {
            throw wrapException("Invalid import syntax", "import_statement");
        }

        String fileName = m.group(1);
        String importPath;

        if (fileName.startsWith("/") || fileName.startsWith("~") || fileName.startsWith("$")) {
            importPath = fileName;
        } else {
            String currentScript = (String) data.get("__current_script");
            if (currentScript != null) {
                int lastSlash = currentScript.lastIndexOf('/');
                if (lastSlash >= 0) {
                    importPath = currentScript.substring(0, lastSlash + 1) + fileName;
                } else {
                    importPath = fileName;
                }
            } else {
                importPath = "~/app/" + fileName;
            }
        }

        String[] readResult = FileUtil.read(importPath);
        if (!readResult[0].equals("SUCCESS")) {
            throw UnrecoverableException.fileNotFound(importPath, pid, currentLine);
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

    private void parseFunctionDefinitions() {
        if (codeLines == null || codeLines.isEmpty()) {
            return;
        }

        // Clear existing functions and re-parse
        functions.clear();

        int i = 0;
        while (i < codeLines.size()) {
            String line = codeLines.get(i).trim();

            if (line.startsWith("func ")) {
                Pattern p = Pattern.compile("func\\s+(\\w+)\\((.*)\\)\\s*\\{");
                Matcher m = p.matcher(line);
                if (m.find()) {
                    String name = m.group(1);
                    String paramsStr = m.group(2).trim();
                    List<String> params = paramsStr.isEmpty() ? new ArrayList<>()
                            : Arrays.asList(paramsStr.split("\\s*,\\s*"));

                    List<String> body = new ArrayList<>();
                    int braceCount = 1;
                    i++;

                    while (i < codeLines.size() && braceCount > 0) {
                        String bodyLine = codeLines.get(i);
                        if (bodyLine.contains("{"))
                            braceCount++;
                        if (bodyLine.contains("}"))
                            braceCount--;
                        if (braceCount > 0) {
                            body.add(bodyLine);
                        }
                        i++;
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

        if (result) {
            blockStack.push(new BlockInfo(BlockType.IF, currentLine, null));
        } else {
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
        String condition;
        String afterWhile = line.substring(5).trim();
        
        if (afterWhile.endsWith("{")) {
            afterWhile = afterWhile.substring(0, afterWhile.length() - 1).trim();
        }
        
        if (afterWhile.startsWith("(") && afterWhile.endsWith(")")) {
            condition = afterWhile.substring(1, afterWhile.length() - 1).trim();
        } else {
            condition = afterWhile;
        }

        if (!blockStack.isEmpty()) {
            BlockInfo top = blockStack.peek();
            if (top.type == BlockType.WHILE && top.startLine == currentLine) {
                if (!isTrue(evaluate(condition))) {
                    blockStack.pop();
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
                return;
            }
        }

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
        } else {
            blockStack.push(new BlockInfo(BlockType.WHILE, currentLine, condition));
        }
    }

    private void handleReturn(String line) {
        String expr = line.substring(7).trim();
        if (expr.endsWith(";")) {
            expr = expr.substring(0, expr.length() - 1).trim();
        }
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
        String[] parts = line.split("=", 2);
        if (parts.length < 2) {
            throw wrapException("Invalid assignment syntax", "assignment");
        }

        String left = parts[0].trim();
        String right = parts[1].trim();

        if (left.startsWith("_")) {
            throw wrapException("Variable names cannot start with underscore (reserved for system use): " + left, "reserved_variable");
        }

        if (left.contains("[")) {
            handleIndexAssignment(left, right);
            return;
        }

        Object value = evaluate(right);
        data.put(left, value);
    }

    @SuppressWarnings("unchecked")
    private void handleIndexAssignment(String left, String right) {
        int firstBracket = left.indexOf('[');
        if (firstBracket <= 0) {
            throw wrapException("Invalid index syntax", "index_assignment");
        }
        
        String containerName = left.substring(0, firstBracket);
        Object container = data.get(containerName);
        if (container == null) {
            throw UnrecoverableException.undefinedVariable(containerName, pid, currentLine);
        }
        
        String indexExpr = left.substring(firstBracket);
        Object value = evaluate(right);
        
        handleIndexAssignmentRecursive(container, indexExpr, value);
    }
    
    @SuppressWarnings("unchecked")
    private void handleIndexAssignmentRecursive(Object container, String indexExpr, Object value) {
        if (!indexExpr.startsWith("[")) {
            return;
        }
        
        int depth = 0;
        int closeBracket = -1;
        for (int i = 0; i < indexExpr.length(); i++) {
            char c = indexExpr.charAt(i);
            if (c == '[' || c == '{')
                depth++;
            else if (c == ']' || c == '}')
                depth--;
            
            if (c == ']' && depth == 0) {
                closeBracket = i;
                break;
            }
        }
        
        if (closeBracket == -1) {
            throw wrapException("Invalid index syntax: missing closing bracket", "index_assignment");
        }
        
        String indexContent = indexExpr.substring(1, closeBracket).trim();
        Object index = evaluate(indexContent);
        
        String remaining = indexExpr.substring(closeBracket + 1).trim();
        
        if (remaining.startsWith("[")) {
            Object nextContainer;
            if (container instanceof List && index instanceof Number) {
                List<?> list = (List<?>) container;
                int idx = ((Number) index).intValue();
                if (idx < 0)
                    idx = list.size() + idx;
                if (idx < 0 || idx >= list.size()) {
                    throw UnrecoverableException.arrayIndexOutOfBounds(idx, list.size(), pid, currentLine);
                }
                nextContainer = list.get(idx);
            } else if (container instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) container;
                nextContainer = map.get(index);
            } else {
                throw UnrecoverableException.typeError("array or map", 
                    container.getClass().getSimpleName(), pid, currentLine);
            }
            
            if (nextContainer == null) {
                throw wrapException("Cannot access index of null value", "index_assignment");
            }
            
            handleIndexAssignmentRecursive(nextContainer, remaining, value);
        } else {
            if (container instanceof List && index instanceof Number) {
                List<Object> list = (List<Object>) container;
                int idx = ((Number) index).intValue();
                if (idx < 0)
                    idx = list.size() + idx;
                if (idx < 0 || idx >= list.size()) {
                    throw UnrecoverableException.arrayIndexOutOfBounds(idx, list.size(), pid, currentLine);
                }
                list.set(idx, value);
            } else if (container instanceof Map) {
                Map<Object, Object> map = (Map<Object, Object>) container;
                map.put(index, value);
            } else {
                throw UnrecoverableException.typeError("array or map", 
                    container.getClass().getSimpleName(), pid, currentLine);
            }
        }
    }

    private String preprocessFunctionCalls(String expr) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            
            if (c == '"') {
                // Copy string literal as-is
                result.append(c);
                i++;
                while (i < expr.length()) {
                    char sc = expr.charAt(i);
                    result.append(sc);
                    if (sc == '\\' && i + 1 < expr.length()) {
                        i++;
                        result.append(expr.charAt(i));
                    } else if (sc == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
            } else if (Character.isLetter(c) || c == '_') {
                // Potential function name
                int start = i;
                while (i < expr.length() && (Character.isLetterOrDigit(expr.charAt(i)) || expr.charAt(i) == '_')) {
                    i++;
                }
                String name = expr.substring(start, i);
                
                // Check if followed by '('
                int savedI = i;
                while (i < expr.length() && Character.isWhitespace(expr.charAt(i))) {
                    i++;
                }
                
                if (i < expr.length() && expr.charAt(i) == '(') {
                    // This is a function call - find the matching closing parenthesis
                    int parenCount = 1;
                    int funcEnd = i + 1;
                    while (funcEnd < expr.length() && parenCount > 0) {
                        char fc = expr.charAt(funcEnd);
                        if (fc == '(') parenCount++;
                        if (fc == ')') parenCount--;
                        funcEnd++;
                    }
                    // Extract the full function call expression
                    String funcCallExpr = expr.substring(start, funcEnd);
                    Object funcResult = handleFunctionCall(funcCallExpr);
                    i = funcEnd;
                    result.append(funcResult != null ? funcResult.toString() : "null");
                } else {
                    i = savedI; // Restore i to position after identifier
                    result.append(name);
                }
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }

    private Object evaluate(String expr) {
        expr = expr.trim();
        
        // Remove trailing semicolon if present
        if (expr.endsWith(";")) {
            expr = expr.substring(0, expr.length() - 1).trim();
        }

        // Check if this is a standalone function call first
        // A standalone function call is: functionName(arguments) with no other operators
        if (isStandaloneFunctionCall(expr)) {
            return handleFunctionCall(expr);
        }

        // Pre-process: find and evaluate function calls in the expression
        // This is for complex expressions like "result: " + read(path)
        expr = preprocessFunctionCalls(expr);

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
            // Check if this is a pure string or a string expression (e.g., "hello"+"world")
            // Count unescaped quotes - if more than 2, it's likely an expression
            int quoteCount = 0;
            for (int i = 0; i < expr.length(); i++) {
                if (expr.charAt(i) == '"' && (i == 0 || expr.charAt(i - 1) != '\\')) {
                    quoteCount++;
                }
            }
            // If there are more than 2 unescaped quotes, it's an expression with multiple strings
            if (quoteCount > 2) {
                return evaluateOperator(expr);
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
                        throw wrapException("Unclosed escape sequence", "string_parsing");
                    }
                } else {
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
            throw wrapException("Cannot get length of " + 
                (val != null ? val.getClass().getSimpleName() : "null"), "length_operation");
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

        // Check if this is an index access: identifier[expr]
        // Must match pattern: word[expr]
        if (expr.contains("[")) {
            Pattern indexPattern = Pattern.compile("(\\w+)\\[(.*)\\]");
            Matcher indexMatcher = indexPattern.matcher(expr);
            if (indexMatcher.matches()) {
                return handleIndexAccess(expr);
            }
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
        
        // Find the matching closing parenthesis
        int depth = 1;
        int closeParenIndex = parenIndex + 1;
        while (closeParenIndex < expr.length() && depth > 0) {
            char c = expr.charAt(closeParenIndex);
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth > 0) closeParenIndex++;
        }
        
        String argsStr = expr.substring(parenIndex + 1, closeParenIndex).trim();

        List<Object> args = new ArrayList<>();
        if (!argsStr.isEmpty()) {
            int argDepth = 0;
            boolean inString = false;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < argsStr.length(); i++) {
                char c = argsStr.charAt(i);
                
                // Track string literals
                if (c == '"' && (i == 0 || argsStr.charAt(i - 1) != '\\')) {
                    inString = !inString;
                }
                
                if (!inString) {
                    if (c == '(')
                        argDepth++;
                    if (c == ')')
                        argDepth--;
                    if (c == ',' && argDepth == 0) {
                        String argStr = current.toString().trim();
                        if (!argStr.isEmpty()) {
                            args.add(evaluate(argStr));
                        }
                        current = new StringBuilder();
                        continue;
                    }
                }
                current.append(c);
            }
            String argStr = current.toString().trim();
            if (!argStr.isEmpty()) {
                args.add(evaluate(argStr));
            }
        }

        Object[] argArray = args.toArray();

        // Use new plugin system to call functions
        Object result = FunctionRegistry.call(funcName, argArray, functionContext);
        if (result != null) {
            return result;
        }

        FunctionDef func = functions.get(funcName);
        if (func != null) {
            if (args.size() != func.params.size()) {
                throw wrapException("Function " + funcName + " expects " +
                        func.params.size() + " arguments, got " + args.size(), "function_call");
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
        throw UnrecoverableException.unknownFunction(funcName, pid, currentLine);
    }

    private Object handleIndexAccess(String expr) {
        int firstBracket = expr.indexOf('[');
        if (firstBracket <= 0) {
            throw wrapException("Invalid index syntax", "index_access");
        }
        
        String containerName = expr.substring(0, firstBracket);
        Object container = data.get(containerName);
        if (container == null) {
            throw UnrecoverableException.undefinedVariable(containerName, pid, currentLine);
        }
        
        return handleIndexAccessRecursive(container, expr.substring(firstBracket));
    }
    
    private Object handleIndexAccessRecursive(Object container, String indexExpr) {
        if (!indexExpr.startsWith("[")) {
            return container;
        }
        
        int depth = 0;
        int closeBracket = -1;
        for (int i = 0; i < indexExpr.length(); i++) {
            char c = indexExpr.charAt(i);
            if (c == '[' || c == '{')
                depth++;
            else if (c == ']' || c == '}')
                depth--;
            
            if (c == ']' && depth == 0) {
                closeBracket = i;
                break;
            }
        }
        
        if (closeBracket == -1) {
            throw wrapException("Invalid index syntax: missing closing bracket", "index_access");
        }
        
        String indexContent = indexExpr.substring(1, closeBracket).trim();
        Object index = evaluate(indexContent);
        
        Object result;
        if (container instanceof List && index instanceof Number) {
            List<?> list = (List<?>) container;
            int idx = ((Number) index).intValue();
            if (idx < 0)
                idx = list.size() + idx;
            if (idx < 0 || idx >= list.size()) {
                throw UnrecoverableException.arrayIndexOutOfBounds(idx, list.size(), pid, currentLine);
            }
            result = list.get(idx);
        } else if (container instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) container;
            result = map.get(index);
        } else {
            throw UnrecoverableException.typeError("array or map", 
                container.getClass().getSimpleName(), pid, currentLine);
        }
        
        String remaining = indexExpr.substring(closeBracket + 1).trim();
        if (remaining.startsWith("[")) {
            return handleIndexAccessRecursive(result, remaining);
        }
        
        return result;
    }

    private List<Object> parseArray(String expr) {
        String content = expr.substring(1, expr.length() - 1).trim();
        List<Object> result = new ArrayList<>();

        if (!content.isEmpty()) {
            int depth = 0;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '[' || c == '{')
                    depth++;
                if (c == ']' || c == '}')
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
        PRECEDENCE.put("+", 6);
        PRECEDENCE.put("-", 6);
        PRECEDENCE.put("*", 7);
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
                            throw wrapException("Unclosed escape sequence", "string_parsing");
                        }
                    } else if (current == '"') {
                        break;
                    } else {
                        sb.append(current);
                        pos++;
                    }
                }
                if (pos >= expr.length()) {
                    throw wrapException("Unclosed string", "string_parsing");
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
            tokens.add(new Token(TokenType.OPERATOR, String.valueOf(c)));
            pos++;
            } else if (c == '=') {
                if (pos + 1 < expr.length() && expr.charAt(pos + 1) == '=') {
                    tokens.add(new Token(TokenType.OPERATOR, "=="));
                    pos += 2;
                } else {
                    throw wrapException("Invalid operator: " + c, "tokenize");
                }
            } else if (c == '!') {
                if (pos + 1 < expr.length() && expr.charAt(pos + 1) == '=') {
                    tokens.add(new Token(TokenType.OPERATOR, "!="));
                    pos += 2;
                } else {
                    throw wrapException("Invalid operator: " + c, "tokenize");
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
                throw wrapException("Unexpected character: " + c, "tokenize");
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
            // Check for unary operators at the beginning
            Token token = peek();
            if (token.type == TokenType.OPERATOR && token.value.equals("not")) {
                consume(); // Consume the "not" operator
                String op = "not";
                ASTNode right = parseExpression(PRECEDENCE.get(op));
                return new ASTNode("unary", op, null, right);
            }
            
            ASTNode left = parsePrimary();
            
            while (true) {
                token = peek();
                if (token.type != TokenType.OPERATOR) break;
                
                int opPrecedence = PRECEDENCE.getOrDefault(token.value, 0);
                if (opPrecedence <= precedence) break;
                
                consume(); // Consume the operator
                String op = token.value;
                
                // For binary operators only (unary "not" is handled above)
                ASTNode right = parseExpression(opPrecedence);
                left = new ASTNode("binary", op, left, right);
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
                    throw wrapException("Expected closing parenthesis", "expression_parsing");
                }
                return expr;
            }
            
            throw wrapException("Expected expression", "expression_parsing");
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
                    throw UnrecoverableException.undefinedVariable(varName, pid, currentLine);
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
                        throw UnrecoverableException.typeError("number", 
                            right != null ? right.getClass().getSimpleName() : "null", pid, currentLine);
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
                            throw UnrecoverableException.divisionByZero(pid, currentLine, op);
                        }
                        return ((Number) leftVal).doubleValue() / ((Number) rightVal).doubleValue();
                    case "%":
                        ensureNumbers(leftVal, rightVal);
                        if (((Number) rightVal).intValue() == 0) {
                            throw UnrecoverableException.divisionByZero(pid, currentLine, op);
                        }
                        return ((Number) leftVal).intValue() % ((Number) rightVal).intValue();
                }
                break;
        }
        
        throw wrapException("Cannot evaluate AST node: " + node.type, "ast_evaluation");
    }

    private void ensureNumbers(Object left, Object right) {
        if (!(left instanceof Number) || !(right instanceof Number)) {
            String leftType = left != null ? left.getClass().getSimpleName() : "null";
            String rightType = right != null ? right.getClass().getSimpleName() : "null";
            throw UnrecoverableException.typeError("numbers", leftType + " and " + rightType, pid, currentLine);
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
    
    private String getCurrentFilePath() {
        if (data != null && data.containsKey("__current_script")) {
            return (String) data.get("__current_script");
        }
        return "/system/process/" + pid + ".json";
    }
    
    private ExceptionContext createExceptionContext(String operation) {
        String currentLineStr = null;
        if (codeLines != null && currentLine >= 0 && currentLine < codeLines.size()) {
            currentLineStr = codeLines.get(currentLine);
        }
        return new ExceptionContext(pid, currentLine, getCurrentFilePath(), currentLineStr, operation);
    }
    
    private void handleException(Exception e, String operation) {
        ExceptionContext context = createExceptionContext(operation);
        
        if (e instanceof ProcessException) {
            ProcessException pe = (ProcessException) e;
            logProcessException(pe, context);
            
            if (pe.isRecoverable()) {
                Logger.warn("Recoverable exception in process " + pid + ", attempting to continue: " + pe.getMessage());
                data.put("_warning", pe.getMessage());
            } else {
                Logger.error("Unrecoverable exception in process " + pid, pe);
                data.put("_error", pe.getMessage());
                running = false;
            }
        } else {
            ProcessException wrappedException = new UnrecoverableException(
                e.getMessage() != null ? e.getMessage() : "Unknown error",
                e,
                context
            );
            logProcessException(wrappedException, context);
            Logger.error("Exception in process " + pid, wrappedException);
            data.put("_error", e.getMessage());
            running = false;
        }
        
        saveToFile(!running);
    }
    
    private void logProcessException(ProcessException e, ExceptionContext context) {
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("Process Exception Details:\n");
        logMessage.append(e.toDetailedString());
        Logger.error(logMessage.toString(), e);
    }
    
    private RuntimeException wrapException(String message, String operation) {
        ExceptionContext context = createExceptionContext(operation);
        return new UnrecoverableException(message, context);
    }
    
    private RuntimeException wrapException(String message, Throwable cause, String operation) {
        ExceptionContext context = createExceptionContext(operation);
        return new UnrecoverableException(message, cause, context);
    }
}