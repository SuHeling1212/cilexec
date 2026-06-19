package com.follarce.process;

import com.follarce.Constants;
import com.follarce.exception.ExceptionContext;
import com.follarce.exception.ProcessException;
import com.follarce.exception.RecoverableException;
import com.follarce.exception.UnrecoverableException;
import com.follarce.function.FunctionContext;
import com.follarce.function.FunctionRegistry;
import com.follarce.log.Logger;
import com.follarce.script.*;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.follarce.util.PathUtil;
import com.follarce.util.UserUtil;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 进程执行引擎 —— 由调度器驱动，每次 step() 执行一行 FCL 代码。
 * <p>
 * 不再继承 Thread，改为状态机模型：
 * NEW → READY → RUNNING → (COMPLETED → READY | BLOCKED | TERMINATED)
 * <p>
 * 每行执行流程：loadFromFile() → executeLine() → saveToFile()
 */
public class ProcessRunner {

    /**
     * step() 返回结果，供调度器决策。
     */
    public enum StepResult {
        COMPLETED,   // 正常执行了一行，还有更多代码
        BLOCKED,     // 进程阻塞（wait/waitPid）
        TERMINATED   // 进程终止
    }

    /**
     * 进程运行时状态。
     */
    public enum ProcessState {
        NEW, READY, RUNNING, BLOCKED, TERMINATED
    }

    /**
     * 阻塞原因。
     */
    public enum BlockReason {
        NONE, WAIT_ANY, WAIT_PID
    }

    // ── 语句模式匹配 ──
    private static final Pattern FUNC_PATTERN =
            Pattern.compile("^func\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)\\s*\\{?\\s*$");
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^import\\s+\"([^\"]+)\"\\s*$");
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^include\\s+\"([^\"]+)\"\\s*$");
    private static final Pattern IF_PATTERN =
            Pattern.compile("^if\\s*\\(?([^{)]+)\\)?\\s*\\{?.*");
    private static final Pattern WHILE_PATTERN =
            Pattern.compile("^while\\s*\\(?([^{)]+)\\)?\\s*\\{?.*");
    private static final Pattern ASSIGN_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(.+)$");
    private static final Pattern INDEX_ASSIGN_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\[([^\\]]+)\\]\\s*=\\s*(.+)$");
    private static final Pattern FORK_PATTERN =
            Pattern.compile("^\\s*fork\\s*\\(\\s*\\)\\s*$");
    private static final Pattern RETURN_PATTERN =
            Pattern.compile("^return\\b\\s*(.*)$");
    private static final Pattern BREAK_PATTERN =
            Pattern.compile("^break\\s*$");

    // ── 运行时状态 ──
    private volatile boolean running = true;
    private final int pid;
    private ProcessState state = ProcessState.NEW;
    private int priority = Constants.DEFAULT_PRIORITY;
    private BlockReason blockReason = BlockReason.NONE;
    private Map<String, Object> processData;
    private Map<String, Object> data;
    private List<String> codeLines;
    private int currentLine;
    private List<Map<String, Object>> blockStack;
    private Map<String, Object> returnValue;
    // 进程启动时间戳（毫秒），用于计算 RunningTime
    private long processStartMs;

    // ── 用户函数缓存 ──
    private final Map<String, FunctionDef> functions = new LinkedHashMap<>();

    // ── 调用栈（用于用户函数调用） ──
    private final Deque<CallFrame> callStack = new ArrayDeque<>();

    // ── 用户函数调用参数（由 NodeEvaluator 回调传入） ──
    private String pendingFuncName;
    private List<Object> pendingFuncArgs;

    // ── 赋值语境中的用户函数返回处理 ──
    private String pendingAssignVarName = null;

    // ── 已导入的文件列表（用于断电恢复后重新导入） ──
    private List<String> importedFiles = new ArrayList<>();

    public ProcessRunner(int pid, Map<String, Object> processData) {
        this.pid = pid;
        this.processData = processData;
        this.processStartMs = System.currentTimeMillis();
        this.callStack.clear();
        loadFromProcessData();
    }

    // ════════════════════════════════════════════
    // 公共 API（供调度器调用）
    // ════════════════════════════════════════════

    /**
     * 初始化进程：设置用户上下文、解析函数定义、状态置为 READY。
     * 由调度器在首次调度前调用一次。
     */
    public void init() {
        String initialOwner = (String) processData.get("Owner");
        UserUtil.setCurrentUser(initialOwner != null ? initialOwner : Constants.DEFAULT_USER_LOCAL);
        // 从进程数据读取优先级
        Object priorityObj = processData.get("Priority");
        if (priorityObj instanceof Number) {
            priority = ((Number) priorityObj).intValue();
            if (priority != Constants.PRIORITY_HIGH && priority != Constants.PRIORITY_LOW) {
                priority = Constants.PRIORITY_NORMAL;
            }
        }
        parseFunctionDefinitions();
        state = ProcessState.READY;
        Logger.info("Process " + pid + " (" + getProcessName() + ") initialized, priority=" + priority);
    }

    /**
     * 执行一步（一行 FCL 代码）。
     * <p>
     * 由调度器反复调用，直到返回 TERMINATED 或 BLOCKED。
     * BLOCKED 的进程需要由调度器调用 checkWakeup() 确认可唤醒后重新调度。
     *
     * @return COMPLETED / BLOCKED / TERMINATED
     */
    public StepResult step() {
        if (state == ProcessState.TERMINATED) {
            return StepResult.TERMINATED;
        }
        if (state == ProcessState.BLOCKED) {
            return StepResult.BLOCKED;
        }

        state = ProcessState.RUNNING;
        try {
            executeLine();
            clearTransientState();

            if (state == ProcessState.BLOCKED) {
                return StepResult.BLOCKED;
            }
            if (!running) {
                state = ProcessState.TERMINATED;
                cleanup();
                return StepResult.TERMINATED;
            }
            state = ProcessState.READY;
            return StepResult.COMPLETED;
        } catch (Exception e) {
            handleException(e, "step");
            clearTransientState();
            state = ProcessState.TERMINATED;
            return StepResult.TERMINATED;
        }
    }

    /**
     * 检查阻塞进程是否可唤醒。
     * 调度器定期调用此方法检查 BLOCKED 进程的 wait/waitPid 条件。
     *
     * @return true = 条件满足，可移回就绪队列
     */
    public boolean checkWakeup() {
        if (state != ProcessState.BLOCKED) return true;
        if (blockReason == BlockReason.NONE) {
            state = ProcessState.READY;
            return true;
        }

        // 从文件重新加载以获取最新状态
        loadFromFile();
        if (!running) {
            state = ProcessState.TERMINATED;
            return false;
        }

        Map<String, Object> children = (Map<String, Object>) processData.get("Child");
        // 没有子进程 → 不阻塞
        if (children == null || children.isEmpty()) {
            state = ProcessState.READY;
            Logger.info("Process " + pid + " woken from wait (no children)");
            return true;
        }

        // 检查子进程文件是否存在
        for (String pidStr : children.keySet()) {
            try {
                int childPid = Integer.parseInt(pidStr);
                if (!FileUtil.exists(PathUtil.findProcessFilePathByPid(childPid))) {
                    state = ProcessState.READY;
                    Logger.info("Process " + pid + " woken from wait (child " + childPid + " terminated)");
                    return true;
                }
            } catch (NumberFormatException ignored) {}
        }

        return false; // 仍在等待
    }

    // ── 访问器 ──

    public int getPid() { return pid; }
    public int getPriority() { return priority; }
    public ProcessState getState() { return state; }

    public String getProcessName() {
        Object name = processData.get("Name");
        return name != null ? name.toString() : "PID-" + pid;
    }

    public boolean isRunning() { return running && state != ProcessState.TERMINATED; }

    /**
     * 停止进程（由调度器调用，如进程文件被删除）。
     */
    public void stopProcess() {
        running = false;
        state = ProcessState.TERMINATED;
    }

    // ════════════════════════════════════════════
    // 主执行逻辑
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void executeLine() {
        try {
            // 1. 从文件加载最新状态
            loadFromFile();

            // 2. 检查是否执行完毕
            if (currentLine >= codeLines.size()) {
                // 如果在函数调用中，自动返回到调用者
                if (!callStack.isEmpty()) {
                    CallFrame frame = callStack.pop();
                    this.data = frame.savedData;
                    this.codeLines = frame.savedCodeLines;
                    this.currentLine = frame.savedCurrentLine + 1; // 跳过函数调用行
                    // 设置返回值（如果有）
                    if (returnValue != null && returnValue.get("value") != null) {
                        data.put("__return_value", returnValue.get("value"));
                    }
                    this.returnValue = null;
                    this.blockStack = new ArrayList<>();
                    saveToFile();
                    return;
                }
                running = false;
                saveToFile();
                // 正常终止：保留/删除进程文件
                handleProcessTermination();
                return;
            }

            String line = codeLines.get(currentLine).trim();

            // 3. 跳过空行、注释
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                currentLine++;
                saveToFile();
                return;
            }

            // 4. 处理花括号行
            if (line.startsWith("}")) {
                int[] counts = countBracesInLine(line);
                handleClosingBraces(counts[1]);  // 支持 }}} 同时关闭多层嵌套
                if (counts[0] > 0) {
                    // } else { 这种平衡情况，跳过该行 body
                    currentLine++;
                }
                saveToFile();
                return;
            }
            if (line.equals("{")) {
                currentLine++;
                saveToFile();
                return;
            }

            try {
                // 当前用户由 init() 初始化，switchUser 动态切换，此处不再重置

                // 5. 识别语句类型
                if (line.startsWith("func ")) {
                    skipFunctionBody();
                    saveToFile();
                    return;
                }

                if (line.startsWith("import ")) {
                    currentLine++;
                    saveToFile();
                    handleImport(line);
                    return;
                }

                if (line.startsWith("include ")) {
                    currentLine++;
                    saveToFile();
                    handleInclude(line);
                    return;
                }

                if (line.startsWith("if ") || line.startsWith("if(")) {
                    handleIf(line);
                    saveToFile();
                    return;
                }

                if (line.startsWith("while ") || line.startsWith("while(")) {
                    handleWhile(line);
                    saveToFile();
                    return;
                }

                if (line.startsWith("return")) {
                    handleReturn(line);
                    saveToFile();
                    return;
                }

                if (line.equals("break")) {
                    handleBreak();
                    saveToFile();
                    return;
                }

                if (line.equals("continue")) {
                    handleContinue();
                    saveToFile();
                    return;
                }

                // 6. fork() 特殊处理
                if (line.matches("^\\s*fork\\s*\\(\\s*\\)\\s*$")) {
                    currentLine++;
                    saveToFile();
                    handleFork();
                    return;
                }

                // 7. exec() 特殊处理
                if (line.startsWith("exec(") || line.startsWith("exec (")) {
                    handleExec(line);
                    loadFromFile();
                    return;
                }

                // 8. 索引赋值 arr[0] = expr
                Matcher indexAssignMatcher = INDEX_ASSIGN_PATTERN.matcher(line);
                if (indexAssignMatcher.matches()) {
                    currentLine++;
                    saveToFile();
                    handleIndexAssignment(indexAssignMatcher);
                    saveToFile();
                    return;
                }

                // 9. 普通赋值 x = expr
                Matcher assignMatcher = ASSIGN_PATTERN.matcher(line);
                if (assignMatcher.matches()) {
                    currentLine++;
                    saveToFile();
                    handleAssignment(assignMatcher);
                    saveToFile();
                    return;
                }

                // 10. 通用表达式（函数调用、字面量等）
                currentLine++;
                saveToFile();
                Object exprResult = evaluateExpression(line);
                if (exprResult == null) {
                    Logger.warn("Unknown statement or expression evaluation returned null in PID " + pid
                            + ", line=" + (currentLine - 1) + ": " + line);
                } else if (exprResult instanceof String) {
                    handleSpecialMarker((String) exprResult);
                    // 如果标记处理导致阻塞，回退行号并返回（state 已在 handleWait 中设置）
                    if (state == ProcessState.BLOCKED) {
                        return;
                    }
                }
                saveToFile();

            } catch (Exception e) {
                handleException(e, "line: " + line);
            }
        } finally {
            // 不在这里清理瞬态状态——由 step() 统一调用 clearTransientState()
        }
    }

    // ════════════════════════════════════════════
    // 控制流处理
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void handleIf(String line) {
        Matcher matcher = IF_PATTERN.matcher(line);
        if (!matcher.matches()) {
            Logger.error("Invalid if syntax in PID " + pid + ", line=" + currentLine + ": " + line);
            currentLine++;
            return;
        }
        String condition = matcher.group(1).trim();
        boolean result = evaluateToBoolean(condition);
        if (result) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "IF");
            block.put("startLine", currentLine);
            block.put("condition", condition);
            blockStack.add(block);
            currentLine++;
        } else {
            currentLine = skipToMatchingBrace(currentLine + 1);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleWhile(String line) {
        Matcher matcher = WHILE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            Logger.error("Invalid while syntax in PID " + pid + ", line=" + currentLine + ": " + line);
            currentLine++;
            return;
        }
        String condition = matcher.group(1).trim();
        boolean result = evaluateToBoolean(condition);

        boolean alreadyInLoop = false;
        for (int i = blockStack.size() - 1; i >= 0; i--) {
            Map<String, Object> block = blockStack.get(i);
            if ("WHILE".equals(block.get("type"))
                    && ((Number) block.get("startLine")).intValue() == currentLine) {
                alreadyInLoop = true;
                break;
            }
        }

        if (result) {
            if (!alreadyInLoop) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "WHILE");
                block.put("startLine", currentLine);
                block.put("condition", condition);
                blockStack.add(block);
            }
            currentLine++;
        } else {
            if (alreadyInLoop) {
                blockStack.remove(blockStack.size() - 1);
            }
            currentLine = skipToMatchingBrace(currentLine + 1);
        }
    }

    private void handleClosingBraces(int count) {
        for (int i = 0; i < count; i++) {
            if (blockStack.isEmpty()) {
                currentLine++;
                return;
            }
            Map<String, Object> block = blockStack.get(blockStack.size() - 1);
            String type = (String) block.get("type");
            if ("WHILE".equals(type)) {
                String condition = (String) block.get("condition");
                if (evaluateToBoolean(condition)) {
                    int startLine = ((Number) block.get("startLine")).intValue();
                    currentLine = startLine;
                    return;
                }
                blockStack.remove(blockStack.size() - 1);
            } else if ("IF".equals(type)) {
                blockStack.remove(blockStack.size() - 1);
            } else {
                currentLine++;
                return;
            }
        }
        currentLine++;
    }

    private void handleBreak() {
        boolean foundWhile = false;
        for (int i = blockStack.size() - 1; i >= 0; i--) {
            Map<String, Object> block = blockStack.get(i);
            if ("WHILE".equals(block.get("type"))) {
                int removeCount = blockStack.size() - i;
                for (int j = 0; j < removeCount; j++) {
                    blockStack.remove(blockStack.size() - 1);
                }
                foundWhile = true;
                break;
            }
        }
        if (!foundWhile) {
            Logger.warn("Break outside while loop in PID " + pid + ", line=" + currentLine);
        }
        currentLine = skipToMatchingBrace(currentLine + 1);
    }

    private void handleContinue() {
        for (int i = blockStack.size() - 1; i >= 0; i--) {
            Map<String, Object> block = blockStack.get(i);
            if ("WHILE".equals(block.get("type"))) {
                currentLine = ((Number) block.get("startLine")).intValue();
                return;
            }
        }
        Logger.warn("Continue outside while loop in PID " + pid + ", line=" + currentLine);
    }

    private void handleReturn(String line) {
        Matcher matcher = RETURN_PATTERN.matcher(line);
        if (matcher.matches()) {
            String expr = matcher.group(1).trim();
            if (!expr.isEmpty()) {
                returnValue = new LinkedHashMap<>();
                returnValue.put("value", evaluateExpression(expr));
            } else {
                returnValue = new LinkedHashMap<>();
            }
        }

        if (callStack.isEmpty()) {
            running = false;
            currentLine = codeLines.size();
        } else {
            CallFrame frame = callStack.pop();
            this.data = frame.savedData;
            this.codeLines = frame.savedCodeLines;
            this.currentLine = frame.savedCurrentLine;

            Object retVal = null;
            if (returnValue != null) {
                retVal = returnValue.get("value");
                data.put("__return_value", retVal);
            }
            if (pendingAssignVarName != null) {
                data.put(pendingAssignVarName, retVal);
                pendingAssignVarName = null;
            }
            currentLine++;
        }
    }

    // ════════════════════════════════════════════
    // 进程操作：fork / exec
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private int handleFork() {
        return handleFork(null);
    }

    @SuppressWarnings("unchecked")
    private int handleFork(String varName) {
        try {
            saveToFile();

            int childPid = allocatePid();

            Map<String, Object> childData = JsonUtil.deepCopy(processData);

            childData.put("PID", childPid);
            childData.put("Name", getProcessName() + "_fork");
            childData.put("Owner", UserUtil.getCurrentUser());
            childData.put("Priority", priority); // 继承父进程优先级
            Map<String, Object> parentInfo = new LinkedHashMap<>();
            parentInfo.put("Name", processData.get("Name"));
            parentInfo.put("PID", pid);
            parentInfo.put("Path", processData.get("Path"));
            childData.put("Parent", parentInfo);
            childData.put("Child", new LinkedHashMap<>());

            Map<String, Object> program = (Map<String, Object>) childData.get("Program");
            if (program != null) {
                Map<String, Object> code = (Map<String, Object>) program.get("Code");
                if (code != null) {
                    code.put("runningCodeLine", currentLine);
                    code.put("BlockStack", new ArrayList<>());
                }
                program.put("returnValue", null);
            }

            Map<String, Object> childProgram = (Map<String, Object>) childData.get("Program");
            if (childProgram != null) {
                Map<String, Object> childInnerData = (Map<String, Object>) childProgram.get("Data");
                if (childInnerData == null) {
                    childInnerData = new LinkedHashMap<>();
                    childProgram.put("Data", childInnerData);
                }
                if (varName != null && !varName.isEmpty()) {
                    childInnerData.put(varName, 0L);
                }
                childData.put("__isForked", true);
            }

            String childFileName = childPid + ".pres";
            String childJson = JsonUtil.toMetaJson(childData);
            FileUtil.createFile(Constants.SYSTEM_PROCESS_PATH, childFileName);
            FileUtil.write(Constants.SYSTEM_PROCESS_PATH + childFileName, childJson);

            Map<String, Object> children = (Map<String, Object>) processData.get("Child");
            if (children == null) {
                children = new LinkedHashMap<>();
                processData.put("Child", children);
            }
            Map<String, Object> childInfo = new LinkedHashMap<>();
            childInfo.put("Name", childData.get("Name"));
            childInfo.put("PID", childPid);
            childInfo.put("Path", childData.get("Path"));
            children.put(String.valueOf(childPid), childInfo);
            saveToFile();

            Logger.info("Fork: PID " + pid + " created child PID " + childPid);
            return childPid;
        } catch (Exception e) {
            Logger.error("Fork failed for PID " + pid + ": " + e.getMessage());
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private void handleExec(String line) {
        String inner = line.substring(line.indexOf('(') + 1, line.lastIndexOf(')')).trim();
        String[] parts = parseExecArgs(inner);
        if (parts.length == 0) return;

        String path = parts[0];
        String scriptPath = PathUtil.resolvePath(path);
        if (!FileUtil.exists(scriptPath)) {
            Logger.error("Exec: script not found: " + scriptPath);
            return;
        }

        String currentUser = UserUtil.getCurrentUser();
        if (!FileUtil.checkFilePermission(scriptPath, Constants.PERM_READ, currentUser)) {
            Logger.warn("Exec denied: " + currentUser + " cannot read " + scriptPath);
            return;
        }

        String scriptContent = FileUtil.read(scriptPath);
        if (scriptContent == null || scriptContent.trim().isEmpty()) {
            Logger.error("Exec: empty script: " + scriptPath);
            return;
        }

        List<String> newCodeLines = new ArrayList<>();
        for (String l : scriptContent.split("\n")) {
            newCodeLines.add(l);
        }

        processData.put("Path", scriptPath);
        processData.put("startTime", FileUtil.getCurrentTimeArray());
        processData.put("RunningTime", 0);

        Map<String, Object> program = (Map<String, Object>) processData.get("Program");
        if (program == null) {
            program = new LinkedHashMap<>();
            processData.put("Program", program);
        }

        Map<String, Object> newData = new LinkedHashMap<>();
        newData.put("__current_script", scriptPath);
        if (parts.length > 1) {
            newData.put("argc", parts.length - 1);
            List<String> argv = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                argv.add(parts[i]);
            }
            newData.put("argv", argv);
        }
        program.put("Data", newData);

        Map<String, Object> code = new LinkedHashMap<>();
        code.put("Code", newCodeLines);
        code.put("runningCodeLine", 0);
        code.put("BlockStack", new ArrayList<>());
        program.put("Code", code);
        program.put("returnValue", null);

        this.codeLines = newCodeLines;
        expandInlineBraces();
        this.data = newData;
        this.currentLine = 0;
        this.blockStack = new ArrayList<>();
        this.functions.clear();
        parseFunctionDefinitions();

        String execUser = detectExecUser(parts);
        if (execUser != null) {
            processData.put("Owner", execUser);
        }

        saveToFile();
        Logger.info("Exec: PID " + pid + " loaded script " + scriptPath);
    }

    @SuppressWarnings("unchecked")
    private synchronized int allocatePid() {
        Map<Integer, String> pidMap = PathUtil.scanProcessFileNames();
        int maxPid = Constants.PID_INIT;
        for (int p : pidMap.keySet()) {
            if (p > maxPid) maxPid = p;
        }
        return maxPid + 1;
    }

    // ════════════════════════════════════════════
    // 赋值处理
    // ════════════════════════════════════════════

    private void handleAssignment(Matcher matcher) {
        String varName = matcher.group(1).trim();
        String expr = matcher.group(2).trim();

        if (expr.trim().matches("^fork\\s*\\(\\s*\\)\\s*$")) {
            int childPid = handleFork(varName);
            data.put(varName, childPid);
            return;
        }

        Object value = evaluateExpression(expr);

        if (value instanceof String && ((String) value).startsWith("USER:")) {
            pendingAssignVarName = varName;
            String funcName = ((String) value).substring(5);
            handleUserFunction(funcName);
            return;
        }

        data.put(varName, value);
    }

    @SuppressWarnings("unchecked")
    private void handleIndexAssignment(Matcher matcher) {
        String varName = matcher.group(1).trim();
        String indexExpr = matcher.group(2).trim();
        String valueExpr = matcher.group(3).trim();

        Object target = data.get(varName);
        Object index = evaluateExpression(indexExpr);
        Object value = evaluateExpression(valueExpr);

        value = resolveMarkerValue(value, varName);
        if (target instanceof List) {
            int i = toIntIndex(index);
            ((List<Object>) target).set(i, value);
        } else if (target instanceof Map) {
            ((Map<Object, Object>) target).put(index, value);
        } else if (target instanceof Object[]) {
            int i = toIntIndex(index);
            ((Object[]) target)[i] = value;
        }
    }

    // ════════════════════════════════════════════
    // 表达式求值
    // ════════════════════════════════════════════

    private Object evaluateExpression(String expr) {
        Lexer lexer = new Lexer(expr);
        List<Token> tokens = lexer.tokenize();
        if (tokens.isEmpty()) return null;

        Parser parser = new Parser(tokens);
        AstNode ast = parser.parse();
        if (ast == null) return null;

        String user = (String) processData.get("Owner");
        if (user == null) user = Constants.DEFAULT_USER_LOCAL;
        int ppid = 0;
        Map<String, Object> parent = (Map<String, Object>) processData.get("Parent");
        if (parent != null && parent.get("PID") instanceof Number) {
            ppid = ((Number) parent.get("PID")).intValue();
        }
        NodeEvaluator evaluator = new NodeEvaluator(data, pid, ppid, user);
        evaluator.setFunctionArgCallback((name, args) -> {
            pendingFuncName = name;
            pendingFuncArgs = args;
        });
        Object result = evaluator.evaluate(ast);

        if (!(result instanceof String && ((String) result).startsWith("USER:"))) {
            pendingFuncName = null;
            pendingFuncArgs = null;
        }
        return result;
    }

    private boolean evaluateToBoolean(String condition) {
        Object result = evaluateExpression(condition);
        if (result == null) return false;
        if (result instanceof Boolean) return (Boolean) result;
        if (result instanceof Number) return ((Number) result).doubleValue() != 0;
        if (result instanceof String) return !((String) result).isEmpty();
        return true;
    }

    // ════════════════════════════════════════════
    // 特殊标记处理
    // ════════════════════════════════════════════

    private void handleSpecialMarker(String marker) {
        if (marker == null) return;

        if (marker.equals("FORK")) {
            handleFork();
        } else if (marker.startsWith("KILL:")) {
            handleKill(marker.substring(5));
        } else if (marker.equals("WAIT")) {
            handleWait();
        } else if (marker.startsWith("WAITPID:")) {
            handleWaitPid(marker.substring(8));
        } else if (marker.startsWith("PAUSE:")) {
            handlePause(marker.substring(6));
        } else if (marker.startsWith("CONTINUE:")) {
            handleContinue(marker.substring(9));
        } else if (marker.startsWith("EXEC:")) {
            // 由外层 handleExec 处理
        } else if (marker.startsWith("USER:")) {
            handleUserFunction(marker.substring(5));
        }
    }

    private Object resolveMarkerValue(Object value) {
        return resolveMarkerValue(value, null);
    }

    private Object resolveMarkerValue(Object value, String varName) {
        if (!(value instanceof String)) return value;
        String marker = (String) value;
        if (marker.equals("FORK")) {
            int childPid = handleFork(varName);
            return childPid >= 0 ? childPid : value;
        } else if (marker.startsWith("KILL:")) {
            handleKill(marker.substring(5));
            return true;
        } else if (marker.equals("WAIT")) {
            handleWait();
            // WAIT 标记仅在赋值语境中出现时，不阻塞（存储 true 作为结果）
            return true;
        } else if (marker.startsWith("WAITPID:")) {
            handleWaitPid(marker.substring(8));
            return true;
        } else if (marker.startsWith("PAUSE:")) {
            handlePause(marker.substring(6));
            return true;
        } else if (marker.startsWith("CONTINUE:")) {
            handleContinue(marker.substring(9));
            return true;
        } else if (marker.startsWith("USER:")) {
            String funcName = marker.substring(5);
            handleUserFunction(funcName);
            Object retVal = data.get("__return_value");
            if (retVal != null) {
                data.remove("__return_value");
            }
            return retVal != null ? retVal : true;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private void handleKill(String pidStr) {
        try {
            int targetPid = Integer.parseInt(pidStr.trim());

            if (!checkProcessOwner(targetPid)) {
                Logger.warn("Kill denied: PID " + pid + " cannot kill PID " + targetPid);
                return;
            }

            String processPath = PathUtil.findProcessFilePathByPid(targetPid);
            if (processPath == null || !FileUtil.exists(processPath)) return;

            String content = FileUtil.read(processPath);
            Map<String, Object> targetData = JsonUtil.parseToMap(content);

            Map<String, Object> children = (Map<String, Object>) targetData.get("Child");
            if (children != null && !children.isEmpty()) {
                String initPath = PathUtil.getProcessFilePath(Constants.PID_INIT);
                String initContent = FileUtil.read(initPath);
                Map<String, Object> initData = JsonUtil.parseToMap(initContent);
                Map<String, Object> initChildren = (Map<String, Object>) initData.get("Child");
                if (initChildren == null) {
                    initChildren = new LinkedHashMap<>();
                    initData.put("Child", initChildren);
                }
                initChildren.putAll(children);
                FileUtil.write(initPath, JsonUtil.toMetaJson(initData));
            }

            Map<String, Object> parent = (Map<String, Object>) targetData.get("Parent");
            if (parent != null && parent.get("PID") != null) {
                int parentPid = ((Number) parent.get("PID")).intValue();
                String parentPath = PathUtil.findProcessFilePathByPid(parentPid);
                if (parentPath != null && FileUtil.exists(parentPath)) {
                    String parentContent = FileUtil.read(parentPath);
                    Map<String, Object> parentData = JsonUtil.parseToMap(parentContent);
                    Map<String, Object> parentChildren = (Map<String, Object>) parentData.get("Child");
                    if (parentChildren != null) {
                        parentChildren.remove(pidStr);
                    }
                    FileUtil.write(parentPath, JsonUtil.toMetaJson(parentData));
                }
            }

            FileUtil.removeFile(processPath);
            Logger.info("Kill: PID " + targetPid + " terminated by PID " + pid);

        } catch (Exception e) {
            Logger.error("Kill failed: " + e.getMessage());
        }
    }

    /**
     * 非阻塞版 wait()。
     * 检查子进程是否已终止；如果所有子进程仍在运行，设置 state=BLOCKED。
     */
    @SuppressWarnings("unchecked")
    private void handleWait() {
        // 从文件重新加载以确保看到最新的子进程状态
        loadFromFile();

        Map<String, Object> children = (Map<String, Object>) processData.get("Child");
        // 没有子进程 → 不阻塞
        if (children == null || children.isEmpty()) return;

        // 检查是否有子进程已终止（.pres 文件已被删除）
        for (Iterator<Map.Entry<String, Object>> it = children.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Object> entry = it.next();
            int childPid = Integer.parseInt(entry.getKey());
            if (!FileUtil.exists(PathUtil.findProcessFilePathByPid(childPid))) {
                // 子进程已终止，从列表中移除
                it.remove();
                processData.put("Child", children);
                saveToFile();
                Logger.info("Wait: child PID " + childPid + " finished (parent PID " + pid + ")");
                return; // 不阻塞
            }
        }

        // 所有子进程仍在运行 → 阻塞
        this.state = ProcessState.BLOCKED;
        this.blockReason = BlockReason.WAIT_ANY;
        // 回退行号：下次被唤醒时重新执行 wait() 行
        currentLine--;
        saveToFile();
        Logger.info("Process " + pid + " blocked on wait()");
    }

    @SuppressWarnings("unchecked")
    private void handleWaitPid(String pidStr) {
        int targetPid = Integer.parseInt(pidStr.trim());

        Map<String, Object> children = (Map<String, Object>) processData.get("Child");
        if (children == null || !children.containsKey(pidStr)) {
            Logger.warn("WaitPID denied: PID " + pidStr + " is not a child of PID " + pid);
            return;
        }

        // 检查目标子进程是否已终止
        String childPath = PathUtil.findProcessFilePathByPid(targetPid);
        if (childPath == null || !FileUtil.exists(childPath)) {
            // 子进程已终止
            children.remove(pidStr);
            processData.put("Child", children);
            saveToFile();
            return;
        }

        // 子进程仍在运行 → 阻塞
        this.state = ProcessState.BLOCKED;
        this.blockReason = BlockReason.WAIT_PID;
        currentLine--;
        saveToFile();
        Logger.info("Process " + pid + " blocked on waitPID(" + targetPid + ")");
    }

    @SuppressWarnings("unchecked")
    private void handlePause(String pidStr) {
        int targetPid = Integer.parseInt(pidStr.trim());

        if (!checkProcessOwner(targetPid)) {
            Logger.warn("Pause denied: PID " + pid + " cannot pause PID " + targetPid);
            return;
        }

        String targetPath = PathUtil.findProcessFilePathByPid(targetPid);
        if (targetPath != null && FileUtil.exists(targetPath)) {
            String content = FileUtil.read(targetPath);
            Map<String, Object> targetData = JsonUtil.parseToMap(content);
            targetData.put("Status", false);
            FileUtil.write(targetPath, JsonUtil.toMetaJson(targetData));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleContinue(String pidStr) {
        int targetPid = Integer.parseInt(pidStr.trim());

        if (!checkProcessOwner(targetPid)) {
            Logger.warn("Continue denied: PID " + pid + " cannot continue PID " + targetPid);
            return;
        }

        String targetPath = PathUtil.findProcessFilePathByPid(targetPid);
        if (targetPath != null && FileUtil.exists(targetPath)) {
            String content = FileUtil.read(targetPath);
            Map<String, Object> targetData = JsonUtil.parseToMap(content);
            targetData.put("Status", true);
            FileUtil.write(targetPath, JsonUtil.toMetaJson(targetData));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleUserFunction(String funcName) {
        FunctionDef def = FunctionRegistry.getUserFunction(funcName);
        if (def == null) {
            Logger.warn("User function not found: " + funcName);
            return;
        }
        CallFrame frame = new CallFrame(new LinkedHashMap<>(data),
                new ArrayList<>(codeLines), currentLine);
        callStack.push(frame);
        this.data = new LinkedHashMap<>();
        this.codeLines = new ArrayList<>(def.bodyLines);
        this.currentLine = 0;
        this.blockStack = new ArrayList<>();
        this.returnValue = null;

        List<Object> args = pendingFuncArgs;
        pendingFuncArgs = null;
        if (args == null) {
            args = new ArrayList<>();
        }
        Logger.debug("handleUserFunction " + funcName + " args: " + args + " params: " + def.params);
        if (def.params != null && args != null) {
            for (int i = 0; i < def.params.size() && i < args.size(); i++) {
                data.put(def.params.get(i), args.get(i));
            }
        }
        saveToFile();
    }

    // ════════════════════════════════════════════
    // Import 处理
    // ════════════════════════════════════════════

    private void handleInclude(String line) {
        Matcher matcher = INCLUDE_PATTERN.matcher(line);
        if (!matcher.matches()) return;

        String includePath = matcher.group(1);
        String resolvedPath = PathUtil.resolvePath(includePath);

        if (!FileUtil.exists(resolvedPath)) {
            Logger.warn("Include file not found: " + includePath);
            return;
        }

        String currentUser = UserUtil.getCurrentUser();
        if (!FileUtil.checkFilePermission(resolvedPath, Constants.PERM_READ, currentUser)) {
            Logger.warn("Include denied: " + currentUser + " cannot read " + includePath);
            return;
        }

        String includeContent = FileUtil.read(resolvedPath);
        if (includeContent == null || includeContent.trim().isEmpty()) return;

        List<String> includeLines = new ArrayList<>();
        for (String l : includeContent.split("\n")) {
            includeLines.add(l);
        }
        codeLines.addAll(currentLine + 1, includeLines);
        parseFunctionDefinitions();
    }

    private void handleImport(String line) {
        Matcher matcher = IMPORT_PATTERN.matcher(line);
        if (!matcher.matches()) return;

        String importPath = matcher.group(1);
        String resolvedPath = PathUtil.resolvePath(importPath);

        if (!FileUtil.exists(resolvedPath)) {
            Logger.warn("Import file not found: " + importPath);
            return;
        }

        String currentUser = UserUtil.getCurrentUser();
        if (!FileUtil.checkFilePermission(resolvedPath, Constants.PERM_READ, currentUser)) {
            Logger.warn("Import denied: " + currentUser + " cannot read " + importPath);
            return;
        }

        String importContent = FileUtil.read(resolvedPath);
        if (importContent == null || importContent.trim().isEmpty()) return;

        String namespace = PathUtil.getFileName(importPath);
        if (namespace.endsWith(".fcl")) {
            namespace = namespace.substring(0, namespace.length() - 4);
        }
        Logger.debug("Import: using namespace '" + namespace + "' from " + importPath);

        List<String> importLines = new ArrayList<>();
        for (String l : importContent.split("\n")) {
            importLines.add(l);
        }

        int i = 0;
        while (i < importLines.size()) {
            String l = importLines.get(i).trim();
            Matcher funcMatcher = FUNC_PATTERN.matcher(l);
            if (funcMatcher.matches()) {
                String funcName = funcMatcher.group(1);
                String paramsStr = funcMatcher.group(2).trim();
                List<String> params = new ArrayList<>();
                if (!paramsStr.isEmpty()) {
                    for (String p : paramsStr.split(",")) {
                        params.add(p.trim());
                    }
                }
                int bodyStart = i + 1;
                int braceDepth = l.contains("{") ? 1 : 0;
                int bodyEnd = bodyStart;
                for (int j = bodyStart; j < importLines.size(); j++) {
                    int[] counts = countBracesInLine(importLines.get(j));
                    braceDepth += counts[0] - counts[1];
                    if (braceDepth <= 0) {
                        bodyEnd = j;
                        break;
                    }
                }
                List<String> bodyLines = new ArrayList<>();
                for (int j = bodyStart; j < bodyEnd; j++) {
                    bodyLines.add(importLines.get(j));
                }
                FunctionDef def = new FunctionDef(funcName, params, bodyLines, -1);

                String namespacedName = namespace + "." + funcName;
                FunctionRegistry.registerUserFunction(namespacedName, def);
                Logger.debug("Import: registered '" + namespacedName + "' from " + importPath);

                if (!FunctionRegistry.hasUserFunction(funcName)) {
                    FunctionRegistry.registerUserFunction(funcName, def);
                    Logger.debug("Import: also registered short name '" + funcName + "'");
                } else {
                    Logger.debug("Import: short name '" + funcName + "' conflicts, namespace required");
                }

                i = bodyEnd;
            }
            i++;
        }
        importedFiles.add(importPath);
    }

    // ════════════════════════════════════════════
    // 函数定义解析
    // ════════════════════════════════════════════

    private void parseFunctionDefinitions() {
        functions.clear();
        int i = 0;
        while (i < codeLines.size()) {
            String line = codeLines.get(i).trim();
            Matcher matcher = FUNC_PATTERN.matcher(line);
            if (matcher.matches()) {
                String funcName = matcher.group(1);
                String paramsStr = matcher.group(2).trim();
                List<String> params = new ArrayList<>();
                if (!paramsStr.isEmpty()) {
                    for (String p : paramsStr.split(",")) {
                        params.add(p.trim());
                    }
                }
                int bodyStart = i + 1;
                int bodyEnd = bodyStart;
                int braceDepth = line.contains("{") ? 1 : 0;
                for (int j = bodyStart; j < codeLines.size(); j++) {
                    int[] counts = countBracesInLine(codeLines.get(j));
                    braceDepth += counts[0] - counts[1];
                    if (braceDepth <= 0) {
                        bodyEnd = j;
                        break;
                    }
                }
                List<String> bodyLines = new ArrayList<>();
                for (int j = bodyStart; j < bodyEnd; j++) {
                    bodyLines.add(codeLines.get(j));
                }
                FunctionDef def = new FunctionDef(funcName, params, bodyLines, i);
                functions.put(funcName, def);
                FunctionRegistry.registerUserFunction(funcName, def);
            }
            i++;
        }
    }

    private void skipFunctionBody() {
        int braceDepth = 0;
        String current = codeLines.get(currentLine).trim();
        if (current.contains("{")) braceDepth = 1;

        while (currentLine < codeLines.size()) {
            currentLine++;
            if (currentLine >= codeLines.size()) break;
            int[] counts = countBracesInLine(codeLines.get(currentLine));
            braceDepth += counts[0] - counts[1];
            if (braceDepth <= 0) {
                currentLine++;
                return;
            }
        }
    }

    // ════════════════════════════════════════════
    // 花括号匹配
    // ════════════════════════════════════════════

    private int skipToMatchingBrace(int startLine) {
        int depth = 1;
        for (int i = startLine; i < codeLines.size(); i++) {
            int[] counts = countBracesInLine(codeLines.get(i));
            depth += counts[0] - counts[1];
            if (depth <= 0) return i + 1;
        }
        return codeLines.size();
    }

    private static int[] countBracesInLine(String line) {
        int open = 0, close = 0;
        boolean inString = false;
        char stringChar = '"';
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == stringChar) { inString = false; }
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            if (c == '{') open++;
            if (c == '}') close++;
        }
        return new int[]{open, close};
    }

    private void expandInlineBraces() {
        if (codeLines == null || codeLines.isEmpty()) return;

        List<String> expanded = new ArrayList<>();
        for (String line : codeLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals("{") || trimmed.equals("}") ||
                trimmed.startsWith("//") || trimmed.startsWith("#")) {
                expanded.add(line);
                continue;
            }
            if (!trimmed.contains("{") && !trimmed.contains("}")) {
                expanded.add(line);
                continue;
            }

            StringBuilder current = new StringBuilder();
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '{' || c == '}') {
                    if (current.length() > 0) {
                        String seg = current.toString().trim();
                        if (!seg.isEmpty()) {
                            expanded.add(seg);
                        }
                        current.setLength(0);
                    }
                    expanded.add(String.valueOf(c));
                } else {
                    current.append(c);
                }
            }
            if (current.length() > 0) {
                String seg = current.toString().trim();
                if (!seg.isEmpty()) {
                    expanded.add(seg);
                }
            }
        }

        if (expanded.size() != codeLines.size()) {
            codeLines.clear();
            codeLines.addAll(expanded);
            Logger.debug("expandInlineBraces: expanded " + codeLines.size() + " lines");
        }
    }

    // ════════════════════════════════════════════
    // 文件状态管理
    // ════════════════════════════════════════════

    private String getProcessFilePath() {
        return PathUtil.getProcessFilePath(pid);
    }

    @SuppressWarnings("unchecked")
    private void loadFromFile() {
        String processPath = getProcessFilePath();
        if (!FileUtil.exists(processPath)) {
            running = false;
            return;
        }
        try {
            String content = FileUtil.read(processPath);
            if (content == null || content.trim().isEmpty()) {
                running = false;
                return;
            }
            processData = JsonUtil.parseToMap(content);
            Object filePidObj = processData.get("PID");
            if (filePidObj instanceof Number) {
                int filePid = ((Number) filePidObj).intValue();
                if (filePid != pid) {
                    Logger.warn("PID mismatch in loadFromFile: local=" + pid + " file=" + filePid + ", terminating");
                    running = false;
                    return;
                }
            }
            loadFromProcessData();
        } catch (Exception e) {
            Logger.error("Failed to load process " + pid + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromProcessData() {
        Object status = processData.get("Status");
        if (status instanceof Boolean) {
            running = (Boolean) status;
        }

        // 读取优先级
        Object priorityObj = processData.get("Priority");
        if (priorityObj instanceof Number) {
            priority = ((Number) priorityObj).intValue();
        }

        Map<String, Object> program = (Map<String, Object>) processData.get("Program");
        if (program != null) {
            data = (Map<String, Object>) program.get("Data");
            if (data == null) {
                data = new LinkedHashMap<>();
                program.put("Data", data);
            }

            Map<String, Object> code = (Map<String, Object>) program.get("Code");
            if (code != null) {
                codeLines = (List<String>) code.get("Code");
                if (codeLines == null) codeLines = new ArrayList<>();
                expandInlineBraces();
                Object lineObj = code.get("runningCodeLine");
                currentLine = lineObj instanceof Number ? ((Number) lineObj).intValue() : 0;
                blockStack = (List<Map<String, Object>>) code.get("BlockStack");
                if (blockStack == null) {
                    blockStack = new ArrayList<>();
                    code.put("BlockStack", blockStack);
                }
            } else {
                codeLines = new ArrayList<>();
                currentLine = 0;
                blockStack = new ArrayList<>();
            }

            returnValue = (Map<String, Object>) program.get("returnValue");
        }

        callStack.clear();
        List<Map<String, Object>> callStackData = (List<Map<String, Object>>) processData.get("CallStack");
        if (callStackData != null) {
            for (Map<String, Object> frameData : callStackData) {
                @SuppressWarnings("unchecked")
                Map<String, Object> savedData = (Map<String, Object>) frameData.get("Data");
                @SuppressWarnings("unchecked")
                List<String> savedCode = (List<String>) frameData.get("Code");
                Object lineObj = frameData.get("CodeLine");
                int savedLine = lineObj instanceof Number ? ((Number) lineObj).intValue() : 0;
                if (savedData != null && savedCode != null) {
                    callStack.push(new CallFrame(savedData, savedCode, savedLine));
                }
            }
        }

        if (program != null) {
            pendingAssignVarName = (String) program.get("pendingAssignVarName");
        }

        if (program != null) {
            List<String> savedImports = (List<String>) program.get("imports");
            if (savedImports != null && !savedImports.isEmpty()) {
                if (importedFiles == null) {
                    importedFiles = new ArrayList<>();
                }
                importedFiles.clear();
                for (String importPath : savedImports) {
                    importedFiles.add(importPath);
                    handleImport("import \"" + importPath + "\"");
                }
            }
        }

        parseFunctionDefinitions();
    }

    @SuppressWarnings("unchecked")
    private void saveToFile() {
        try {
            processData.put("Status", running);
            processData.put("RunningTime", (System.currentTimeMillis() - processStartMs) / 1000);
            processData.put("Priority", priority);

            Map<String, Object> program = (Map<String, Object>) processData.get("Program");
            if (program == null) {
                program = new LinkedHashMap<>();
                processData.put("Program", program);
            }
            program.put("Data", data);
            program.put("returnValue", returnValue);

            List<Map<String, Object>> callStackData = new ArrayList<>();
            for (CallFrame frame : callStack) {
                Map<String, Object> frameData = new LinkedHashMap<>();
                frameData.put("Data", new LinkedHashMap<>(frame.savedData));
                frameData.put("Code", new ArrayList<>(frame.savedCodeLines));
                frameData.put("CodeLine", frame.savedCurrentLine);
                callStackData.add(frameData);
            }
            processData.put("CallStack", callStackData);
            if (pendingAssignVarName != null) {
                program.put("pendingAssignVarName", pendingAssignVarName);
            } else {
                program.remove("pendingAssignVarName");
            }

            if (importedFiles != null && !importedFiles.isEmpty()) {
                program.put("imports", new ArrayList<>(importedFiles));
            } else {
                program.remove("imports");
            }

            Map<String, Object> code = new LinkedHashMap<>();
            code.put("Code", codeLines);
            code.put("runningCodeLine", currentLine < codeLines.size() ? currentLine : codeLines.size());
            code.put("BlockStack", blockStack);
            program.put("Code", code);

            String json = JsonUtil.toMetaJson(processData);
            String processPath = getProcessFilePath();
            FileUtil.write(processPath, json);
        } catch (Exception e) {
            Logger.error("Failed to save process " + pid + ": " + e.getMessage());
        }
    }

    private void clearTransientState() {
        this.data = null;
        this.codeLines = null;
        this.currentLine = 0;
        this.blockStack = null;
        this.returnValue = null;
        this.pendingFuncName = null;
        this.pendingFuncArgs = null;
        this.functions.clear();
        this.callStack.clear();
        this.pendingAssignVarName = null;
        this.importedFiles = null;
    }

    // ════════════════════════════════════════════
    // 进程终止与清理
    // ════════════════════════════════════════════

    /**
     * 进程终止时清理：更新父进程的 Child 列表（以便父进程被唤醒）。
     */
    private void cleanup() {
        try {
            cleanParentChildList();
            handleProcessTermination();
        } catch (Exception e) {
            Logger.warn("Cleanup failed for PID " + pid + ": " + e.getMessage());
        }
    }

    /**
     * 清理父进程的 Child 列表：告知父进程本子进程已退出。
     */
    private void cleanParentChildList() {
        try {
            Map<String, Object> parent = (Map<String, Object>) processData.get("Parent");
            if (parent == null) return;
            Object ppidObj = parent.get("PID");
            if (!(ppidObj instanceof Number)) return;
            int ppid = ((Number) ppidObj).intValue();

            String parentPath = Constants.SYSTEM_PROCESS_PATH + ppid + ".pres";
            String parentContent = FileUtil.read(parentPath);
            if (parentContent == null || parentContent.trim().isEmpty()) return;

            Map<String, Object> parentData = JsonUtil.parseToMap(parentContent);
            Map<String, Object> children = (Map<String, Object>) parentData.get("Child");
            if (children == null || !children.containsKey(String.valueOf(pid))) return;

            children.remove(String.valueOf(pid));
            parentData.put("Child", children);
            String updatedJson = JsonUtil.toMetaJson(parentData);
            FileUtil.write(parentPath, updatedJson);
            Logger.info("Child " + pid + " removed from parent " + ppid + "'s Child list");
        } catch (Exception e) {
            Logger.warn("Failed to clean parent Child list for PID " + pid + ": " + e.getMessage());
        }
    }

    private void handleProcessTermination() {
        try {
            if (pid == Constants.PID_INIT) {
                if (Constants.DELETE_PROCESS_FILE_ON_EXIT) {
                    clearAllProcessFiles();
                    Logger.info("INIT process completed normally, all process files cleaned up");
                } else {
                    Logger.info("INIT process completed normally (files retained per config)");
                }
            } else {
                if (Constants.DELETE_PROCESS_FILE_ON_EXIT) {
                    String processPath = getProcessFilePath();
                    if (FileUtil.exists(processPath)) {
                        FileUtil.removeFile(processPath);
                        Logger.info("Process " + pid + " terminated normally, file removed");
                    }
                } else {
                    Logger.info("Process " + pid + " terminated normally (file retained per config)");
                }
            }
        } catch (Exception e) {
            Logger.warn("Process termination cleanup failed for PID " + pid + ": " + e.getMessage());
        }
    }

    private void clearAllProcessFiles() {
        String realDir = PathUtil.toRealPath(Constants.SYSTEM_PROCESS_PATH);
        java.io.File dir = new java.io.File(realDir);
        java.io.File[] files = dir.listFiles((d, name) -> name.endsWith(".pres"));
        if (files != null) {
            for (java.io.File f : files) {
                if (f.exists()) {
                    f.delete();
                }
            }
        }
    }

    // ════════════════════════════════════════════
    // 权限检查
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private boolean checkProcessOwner(int targetPid) {
        if (UserUtil.isLocal()) return true;
        String currentUser = UserUtil.getCurrentUser();

        String targetPath = PathUtil.findProcessFilePathByPid(targetPid);
        if (targetPath == null || !FileUtil.exists(targetPath)) return false;

        String content = FileUtil.read(targetPath);
        Map<String, Object> targetData = JsonUtil.parseToMap(content);
        Object owner = targetData.get("Owner");
        return owner != null && owner.toString().equals(currentUser);
    }

    // ════════════════════════════════════════════
    // 异常处理
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void handleException(Exception e, String operation) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

        if (data == null) {
            data = new LinkedHashMap<>();
            Map<String, Object> program = (Map<String, Object>) processData.get("Program");
            if (program == null) {
                program = new LinkedHashMap<>();
                processData.put("Program", program);
            }
            program.put("Data", data);
        }

        String currentLineText = (currentLine >= 0 && codeLines != null && currentLine < codeLines.size())
                ? codeLines.get(currentLine) : null;
        ExceptionContext ctx = new ExceptionContext(pid, currentLine, null, currentLineText, operation);

        if (e instanceof ProcessException) {
            ProcessException pe = (ProcessException) e;
            ExceptionContext existingCtx = pe.getContext();
            if (existingCtx.getProcessId() <= 0) existingCtx.setProcessId(pid);
            if (existingCtx.getLineNumber() <= 0) existingCtx.setLineNumber(currentLine);
            if (existingCtx.getCurrentLine() == null) existingCtx.setCurrentLine(currentLineText);
            if (existingCtx.getOperation() == null) existingCtx.setOperation(operation);
        }

        Logger.error("Process " + pid + " error at line " + currentLine + " (" + operation + "): " + msg
                + " | context=" + ctx.toDetailedString());

        if (e instanceof RecoverableException) {
            data.put("_warning", msg);
        } else if (e instanceof UnrecoverableException) {
            data.put("_error", msg);
            running = false;
        } else if (e instanceof RuntimeException) {
            data.put("_error", msg);
            running = false;
        } else {
            data.put("_warning", msg);
        }
        saveToFile();
    }

    // ════════════════════════════════════════════
    // 辅助方法
    // ════════════════════════════════════════════

    private int toIntIndex(Object index) {
        if (index instanceof Number) return ((Number) index).intValue();
        if (index instanceof String) {
            try { return Integer.parseInt((String) index); } catch (NumberFormatException e) {
                throw new RuntimeException("Invalid index: " + index);
            }
        }
        throw new RuntimeException("Invalid index type: " + index.getClass());
    }

    private String[] parseExecArgs(String inner) {
        List<String> result = new ArrayList<>();
        inner = inner.trim();
        if (inner.startsWith("\"")) {
            int endQuote = inner.indexOf('"', 1);
            if (endQuote > 0) {
                result.add(inner.substring(1, endQuote));
                String rest = inner.substring(endQuote + 1).trim();
                if (!rest.isEmpty()) {
                    for (String p : rest.split("\\s+")) {
                        p = p.trim();
                        if (!p.isEmpty()) result.add(p);
                    }
                }
            }
        } else {
            for (String p : inner.split("\\s+")) {
                p = p.trim();
                if (!p.isEmpty()) result.add(p);
            }
        }
        return result.toArray(new String[0]);
    }

    private String detectExecUser(String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            if ("-user".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }

    // ── 调用帧 ──

    private static class CallFrame {
        final Map<String, Object> savedData;
        final List<String> savedCodeLines;
        final int savedCurrentLine;

        CallFrame(Map<String, Object> savedData, List<String> savedCodeLines, int savedCurrentLine) {
            this.savedData = savedData;
            this.savedCodeLines = savedCodeLines;
            this.savedCurrentLine = savedCurrentLine;
        }
    }
}
