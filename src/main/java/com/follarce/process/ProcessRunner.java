package com.follarce.process;

import com.follarce.Constants;
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
 * 进程执行引擎 —— 每个进程一个线程，逐行执行 FCL 代码。
 * <p>
 * 执行循环：每 10ms 执行一行，完整执行流程为：
 * loadFromFile() → executeLine() → saveToFile()
 */
public class ProcessRunner extends Thread {

    private static final Pattern FUNC_PATTERN =
            Pattern.compile("^func\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)\\s*\\{?\\s*$");
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^import\\s+\"([^\"]+)\"\\s*$");
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^include\\s+\"([^\"]+)\"\\s*$");
    private static final Pattern IF_PATTERN =
            Pattern.compile("^if\\s*\\(?([^{)]+)\\)?\\s*\\{?\\s*$");
    private static final Pattern WHILE_PATTERN =
            Pattern.compile("^while\\s*\\(?([^{)]+)\\)?\\s*\\{?\\s*$");
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

    // ── fork 子进程 PID（由 handleFork 设置，供 handleAssignment 读取） ──
    private int lastForkChildPid = -1;

    // ── 赋值语境中的用户函数返回处理 ──
    // 当在当前变量赋值中调用用户函数时，记录变量名，函数返回后自动赋值
    private String pendingAssignVarName = null;

    public ProcessRunner(int pid, Map<String, Object> processData) {
        super("Process-" + pid);
        this.pid = pid;
        this.processData = processData;
        this.processStartMs = System.currentTimeMillis();
        this.callStack.clear();
        loadFromProcessData();
    }

    /**
     * 获取当前进程的 .pres 文件路径（基于 Name 字段）。
     */
    private String getProcessFilePath() {
        return PathUtil.getProcessFilePath(pid);
    }

    public int getPid() { return pid; }

    public String getProcessName() {
        Object name = processData.get("Name");
        return name != null ? name.toString() : "PID-" + pid;
    }

    public boolean isRunning() { return running; }

    public void stopProcess() {
        running = false;
        interrupt();
    }

    // ════════════════════════════════════════════
    // 主执行循环
    // ════════════════════════════════════════════

    @Override
    public void run() {
        Logger.info("Process " + pid + " (" + getProcessName() + ") started");

        // 初始化用户上下文（从进程 Owner 读取，但后续由 switchUser 接管）
        // 函数定义由每 tick 的 loadFromProcessData() 自动解析
        String initialOwner = (String) processData.get("Owner");
        UserUtil.setCurrentUser(initialOwner != null ? initialOwner : Constants.DEFAULT_USER_LOCAL);

        while (running) {
            try {
                executeLine();
                Thread.sleep(Constants.PROCESS_TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            } catch (Exception e) {
                handleException(e, "executeLine");
            }
        }

        Logger.info("Process " + pid + " (" + getProcessName() + ") terminated");
    }

    // ════════════════════════════════════════════
    // 单行执行
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void executeLine() {
        try {
            // 1. 从文件加载最新状态（含 callStack、pendingAssignVarName）
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
            if (line.equals("{") || line.startsWith("}")) {
                // } 需要处理 while 循环回跳 handleClosingBrace 已递增 currentLine
                if (line.startsWith("}")) {
                    handleClosingBrace();
                } else {
                    // 对于 { 行：跳过 { 行进入 body
                    currentLine++;
                }
                saveToFile();
                return;
            }

            try {
                // 当前用户由 run() 初始化，switchUser 动态切换，此处不再重置

                // 5. 识别语句类型
                if (line.startsWith("func ")) {
                    // 函数定义 → 跳过
                    skipFunctionBody();
                    saveToFile();
                    return;
                }

                if (line.startsWith("import ")) {
                    handleImport(line);
                    currentLine++;
                    saveToFile();
                    return;
                }

                if (line.startsWith("include ")) {
                    handleInclude(line);
                    currentLine++;
                    saveToFile();
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

                // 6. fork() 特殊处理
                if (line.matches("^\\s*fork\\s*\\(\\s*\\)\\s*$")) {
                    handleFork();
                    saveToFile();
                    return;
                }

                // 7. exec() 特殊处理
                if (line.startsWith("exec(") || line.startsWith("exec (")) {
                    handleExec(line);
                    // exec 后重新加载文件
                    loadFromFile();
                    return;
                }

                // 8. 索引赋值 arr[0] = expr
                Matcher indexAssignMatcher = INDEX_ASSIGN_PATTERN.matcher(line);
                if (indexAssignMatcher.matches()) {
                    handleIndexAssignment(indexAssignMatcher);
                    currentLine++;
                    saveToFile();
                    return;
                }

                // 9. 普通赋值 x = expr
                Matcher assignMatcher = ASSIGN_PATTERN.matcher(line);
                if (assignMatcher.matches()) {
                    handleAssignment(assignMatcher);
                    currentLine++;
                    saveToFile();
                    return;
                }

                // 10. 通用表达式（函数调用、字面量等）
                handleExpression(line);

            } catch (Exception e) {
                handleException(e, "line: " + line);
            }

            currentLine++;
            saveToFile();
        } finally {
            // 每个 tick 结束后清除所有瞬态字段
            // 下个 tick 从文件完整恢复
            clearTransientState();
        }
    }

    // ════════════════════════════════════════════
    // 控制流处理
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void handleIf(String line) {
        Matcher matcher = IF_PATTERN.matcher(line);
        if (!matcher.matches()) {
            currentLine++;
            return;
        }
        String condition = matcher.group(1).trim();
        boolean result = evaluateToBoolean(condition);
        if (result) {
            // 条件成立：push IF block，进入 body
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "IF");
            block.put("startLine", currentLine);
            block.put("condition", condition);
            blockStack.add(block);
            currentLine++;
        } else {
            // 条件不成立：跳过 body 到匹配的 }
            currentLine = skipToMatchingBrace(currentLine + 1);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleWhile(String line) {
        Matcher matcher = WHILE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            currentLine++;
            return;
        }
        String condition = matcher.group(1).trim();
        boolean result = evaluateToBoolean(condition);
        if (result) {
            // 条件成立：push WHILE block，进入 body
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "WHILE");
            block.put("startLine", currentLine);
            block.put("condition", condition);
            blockStack.add(block);
            currentLine++;
        } else {
            // 条件不成立：跳过 body
            currentLine = skipToMatchingBrace(currentLine + 1);
        }
    }

    private void handleClosingBrace() {
        if (blockStack.isEmpty()) {
            currentLine++;
            return;
        }
        Map<String, Object> block = blockStack.get(blockStack.size() - 1);
        String type = (String) block.get("type");
        if ("WHILE".equals(type)) {
            // 重新检查 while 条件
            String condition = (String) block.get("condition");
            boolean result = evaluateToBoolean(condition);
            if (result) {
                // 条件仍成立：跳回 while 开头
                int startLine = ((Number) block.get("startLine")).intValue();
                currentLine = startLine;
            } else {
                // 条件不成立：pop block，继续
                blockStack.remove(blockStack.size() - 1);
                currentLine++;
            }
        } else if ("IF".equals(type)) {
            // if body 结束，pop block
            blockStack.remove(blockStack.size() - 1);
            currentLine++;
        } else {
            currentLine++;
        }
    }

    private void handleBreak() {
        // 从当前行向前扫描 }，计数
        int depth = 0;
        int start = currentLine;
        // 先看当前 blockStack
        if (!blockStack.isEmpty()) {
            Map<String, Object> top = blockStack.get(blockStack.size() - 1);
            if ("WHILE".equals(top.get("type"))) {
                blockStack.remove(blockStack.size() - 1);
            }
        }
        // 找到匹配的 }
        currentLine = skipToMatchingBrace(currentLine + 1);
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

        // 如果不在函数调用中，终止进程
        if (callStack.isEmpty()) {
            running = false;
            currentLine = codeLines.size();
        } else {
            // 恢复调用帧
            CallFrame frame = callStack.pop();
            this.data = frame.savedData;
            this.codeLines = frame.savedCodeLines;
            this.currentLine = frame.savedCurrentLine;

            // 如果在赋值语境中调用了用户函数，自动完成赋值
            Object retVal = null;
            if (returnValue != null) {
                retVal = returnValue.get("value");
                data.put("__return_value", retVal);
            }
            if (pendingAssignVarName != null) {
                data.put(pendingAssignVarName, retVal);
                pendingAssignVarName = null;
            }

            // 清空函数的代码
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
            // 1. 保存当前状态
            saveToFile();

            // 2. 分配新 PID
            int childPid = allocatePid();

            // 3. 深拷贝父进程数据
            Map<String, Object> childData = JsonUtil.deepCopy(processData);

            // 4. 修改子进程属性：PID、Name、Owner（#2 继承当前 ThreadLocal 用户）
            childData.put("PID", childPid);
            childData.put("Name", getProcessName() + "_fork");
            childData.put("Owner", UserUtil.getCurrentUser());
            // 设置父进程信息
            Map<String, Object> parentInfo = new LinkedHashMap<>();
            parentInfo.put("Name", processData.get("Name"));
            parentInfo.put("PID", pid);
            parentInfo.put("Path", processData.get("Path"));
            childData.put("Parent", parentInfo);
            childData.put("Child", new LinkedHashMap<>());

            // 5. 重置子进程状态
            Map<String, Object> program = (Map<String, Object>) childData.get("Program");
            if (program != null) {
                Map<String, Object> code = (Map<String, Object>) program.get("Code");
                if (code != null) {
                    code.put("runningCodeLine", currentLine + 1);
                    code.put("BlockStack", new ArrayList<>());
                }
                program.put("returnValue", null);
            }

            // 6. 更新父进程的 Child 列表
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

            // 7. 保存父进程（更新 Child 列表）
            saveToFile();

            // 8. 注入 fork 返回值 + fork 特殊变量
            //    - 子进程：varName=0（如果存在）、fork=1
            //    - 父进程：fork=0
            //    先深拷贝了全部变量，再添加 fork 相关变量
            Map<String, Object> childProgram = (Map<String, Object>) childData.get("Program");
            if (childProgram != null) {
                Map<String, Object> childInnerData = (Map<String, Object>) childProgram.get("Data");
                if (childInnerData == null) {
                    childInnerData = new LinkedHashMap<>();
                    childProgram.put("Data", childInnerData);
                }
                // 子进程 fork 返回值：var = 0
                if (varName != null && !varName.isEmpty()) {
                    childInnerData.put(varName, 0L);
                }
                // 子进程 fork 特殊变量：fork = 1（标志此进程是 fork 产物）
                childInnerData.put("fork", 1L);
            }
            // 父进程 fork 特殊变量：fork = 0
            this.data.put("fork", 0L);

            // 9. 现在创建子进程文件（变量已注入，Scheduler 读到完整版）
            String childFileName = childPid + ".pres";
            String childJson = JsonUtil.toMetaJson(childData);
            FileUtil.createFile(Constants.SYSTEM_PROCESS_PATH, childFileName);
            FileUtil.write(Constants.SYSTEM_PROCESS_PATH + childFileName, childJson);

            this.lastForkChildPid = childPid;

            Logger.info("Fork: PID " + pid + " created child PID " + childPid);
            return childPid;
        } catch (Exception e) {
            Logger.error("Fork failed for PID " + pid + ": " + e.getMessage());
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private void handleExec(String line) {
        // 解析 exec("path", params...)
        String inner = line.substring(line.indexOf('(') + 1, line.lastIndexOf(')')).trim();
        // 简单解析：第一个参数是路径，剩余是参数
        String[] parts = parseExecArgs(inner);
        if (parts.length == 0) return;

        String path = parts[0];
        // 读取目标脚本
        String scriptPath = PathUtil.resolvePath(path);
        if (!FileUtil.exists(scriptPath)) {
            Logger.error("Exec: script not found: " + scriptPath);
            return;
        }

        // 权限检查：只能 exec 自己有读取权限的脚本
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

        // 重置当前进程
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

        // 设置参数
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

        // 重新解析函数定义
        this.codeLines = newCodeLines;
        this.data = newData;
        this.currentLine = 0;
        this.blockStack = new ArrayList<>();
        this.functions.clear();
        parseFunctionDefinitions();

        // 设置当前进程的用户
        String execUser = detectExecUser(parts);
        if (execUser != null) {
            processData.put("Owner", execUser);
        }

        saveToFile();
        Logger.info("Exec: PID " + pid + " loaded script " + scriptPath);
    }

    /**
     * 分配新的 PID。
     * synchronized 防止并发 fork 时分配相同的 PID。
     */
    @SuppressWarnings("unchecked")
    private synchronized int allocatePid() {
        Map<Integer, String> pidMap = PathUtil.scanProcessFileNames();
        int maxPid = Constants.PID_INIT;
        for (int p : pidMap.keySet()) {
            if (p > maxPid) maxPid = p;
        }
        return maxPid + 1;
    }

    /**
     * fork 变量传播（已废弃）：变量注入已由 handleFork(varName) 在创建子进程文件时完成。
     * 保留此方法仅用于 handleSpecialMarker 等无变量名的路径。
     */
    private void propagateForkVariable(String varName) {
        // 变量已在 handleFork 中注入，无需额外操作
    }

    // ════════════════════════════════════════════
    // 赋值处理
    // ════════════════════════════════════════════

    private void handleAssignment(Matcher matcher) {
        String varName = matcher.group(1).trim();
        String expr = matcher.group(2).trim();

        // 特殊处理 var = fork() — 直接调 handleFork(varName) 使子进程得到 var=0
        if (expr.trim().matches("^fork\\s*\\(\\s*\\)\\s*$")) {
            int childPid = handleFork(varName);
            data.put(varName, childPid);
            return;
        }

        Object value = evaluateExpression(expr);

        // 如果结果是 USER: 标记（调用用户函数），设置 pendingAssignVarName
        // handleReturn 会在函数返回时自动完成赋值
        if (value instanceof String && ((String) value).startsWith("USER:")) {
            pendingAssignVarName = varName;
            // 触发用户函数调用（切换上下文到函数体）
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

    /**
     * 处理表达式语句（函数调用）。
     */
    private void handleExpression(String line) {
        Object result = evaluateExpression(line);
        // 检查是否是特殊标记
        if (result instanceof String) {
            String marker = (String) result;
            handleSpecialMarker(marker);
        }
    }

    /**
     * 对表达式字符串进行全面求值。
     */
    private Object evaluateExpression(String expr) {
        // 预处理：处理行首函数调用
        try {
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
            // 设置函数回调，捕获函数名和参数
            evaluator.setFunctionArgCallback((name, args) -> {
                pendingFuncName = name;
                pendingFuncArgs = args;
            });
            if (expr.contains("expected") || expr.contains("actual")) {
                Logger.debug("DATA CHECK: expr=" + expr + " data=" + data + " dataKeys=" + data.keySet());
            }
            Object result = evaluator.evaluate(ast);

            // 注意：特殊标记由 handleExpression 统一处理，
            // 这里只返回结果，不做标记处理

            return result;
        } catch (Exception e) {
            Logger.warn("Expression evaluation error in PID " + pid + ": " + e.getMessage()
                    + " expr=" + expr);
            return null;
        }
    }

    /**
     * 求值布尔表达式。
     */
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

    /**
     * 解析表达式求值结果中的特殊标记：如果结果是标记字符串，
     * 执行对应操作并返回真实值（如 fork 返回子 PID，kill 返回 true）。
     * 使赋值语境（如 pid = fork()）也能正确触发操作。
     */
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
            // 用户函数执行后，获取返回值
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

            // 权限检查：只能 kill 自己的进程（或者 local 用户）
            if (!checkProcessOwner(targetPid)) {
                Logger.warn("Kill denied: PID " + pid + " cannot kill PID " + targetPid);
                return;
            }

            String processPath = PathUtil.findProcessFilePathByPid(targetPid);
            if (processPath == null || !FileUtil.exists(processPath)) return;

            // 读取目标进程
            String content = FileUtil.read(processPath);
            Map<String, Object> targetData = JsonUtil.parseToMap(content);

            // 孤儿进程收养：将子进程过继给 INIT
            Map<String, Object> children = (Map<String, Object>) targetData.get("Child");
            if (children != null && !children.isEmpty()) {
                // 读取 INIT 进程
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

            // 从父进程的 Child 列表中移除
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

            // 删除进程文件
            FileUtil.removeFile(processPath);
            Logger.info("Kill: PID " + targetPid + " terminated by PID " + pid);

        } catch (Exception e) {
            Logger.error("Kill failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleWait() {
        // 等待任意子进程结束（最多 1000 次 ≈ 100s，防止永久挂起 #4）
        int maxWaits = 1000;
        int waitCount = 0;
        while (running && waitCount < maxWaits) {
            Map<String, Object> children = (Map<String, Object>) processData.get("Child");
            if (children == null || children.isEmpty()) break;

            for (Iterator<Map.Entry<String, Object>> it = children.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, Object> entry = it.next();
                int childPid = Integer.parseInt(entry.getKey());
                String childPath = PathUtil.findProcessFilePathByPid(childPid);

                if (!FileUtil.exists(childPath)) {
                    it.remove();
                    saveToFile();
                    Logger.info("Wait: child PID " + childPid + " finished (parent PID " + pid + ")");
                    return;
                }

                String childContent = FileUtil.read(childPath);
                Map<String, Object> childData = JsonUtil.parseToMap(childContent);
                Object status = childData.get("Status");
                if (status instanceof Boolean && !(Boolean) status) {
                    it.remove();
                    saveToFile();
                    return;
                }
            }

            waitCount++;
            // 重新加载进程文件（子进程列表可能已变更）
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            loadFromFile();
        }
        if (waitCount >= maxWaits) {
            Logger.warn("Wait timeout for all children (PID " + pid + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleWaitPid(String pidStr) {
        int targetPid = Integer.parseInt(pidStr.trim());

        // 权限检查：只能等待自己的子进程
        Map<String, Object> children = (Map<String, Object>) processData.get("Child");
        if (children == null || !children.containsKey(pidStr)) {
            Logger.warn("WaitPID denied: PID " + pidStr + " is not a child of PID " + pid);
            return;
        }

        // 最多等待 1000 次 ≈ 100s（#4）
        int maxWaits = 1000;
        int waitCount = 0;
        while (running && waitCount < maxWaits) {
            String childPath = PathUtil.findProcessFilePathByPid(targetPid);
            if (childPath == null || !FileUtil.exists(childPath)) {
                // 子进程已删除
                children = (Map<String, Object>) processData.get("Child");
                if (children != null) children.remove(pidStr);
                saveToFile();
                return;
            }
            waitCount++;
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (waitCount >= maxWaits) {
            Logger.warn("WaitPID timeout for PID " + targetPid + " (caller PID " + pid + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePause(String pidStr) {
        int targetPid = Integer.parseInt(pidStr.trim());

        // 权限检查：只能暂停自己的进程（或者 local 用户）
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

        // 权限检查：只能恢复自己的进程（或者 local 用户）
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
        // 保存当前上下文
        CallFrame frame = new CallFrame(new LinkedHashMap<>(data),
                new ArrayList<>(codeLines), currentLine);
        callStack.push(frame);
        // 切换到函数体
        this.data = new LinkedHashMap<>();
        this.codeLines = new ArrayList<>(def.bodyLines);
        this.currentLine = 0;
        this.blockStack = new ArrayList<>();
        this.returnValue = null;

        // 获取函数参数（从回调捕获或 FunctionRegistry）
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
            Logger.debug("Function " + funcName + " params: " + def.params + " args: " + args + " data: " + data);
        }
        // 保存到文件
        saveToFile();
        Logger.debug("After saveToFile in handleUserFunction, data keys: " + data.keySet());
        // 设置 currentLine = -1，因为外层 executeLine 会 +1 变为 0，使函数体从第一行开始执行
        this.currentLine = -1;
    }

    // ════════════════════════════════════════════
    // Import 处理
    // ════════════════════════════════════════════

    /**
     * include：将文件内容直接插入当前代码中执行（类似 C 的 #include）。
     */
    private void handleInclude(String line) {
        Matcher matcher = INCLUDE_PATTERN.matcher(line);
        if (!matcher.matches()) return;

        String includePath = matcher.group(1);
        String resolvedPath = PathUtil.resolvePath(includePath);

        if (!FileUtil.exists(resolvedPath)) {
            Logger.warn("Include file not found: " + includePath);
            return;
        }

        // 权限检查：只有有读取权限才能 include
        String currentUser = UserUtil.getCurrentUser();
        if (!FileUtil.checkFilePermission(resolvedPath, Constants.PERM_READ, currentUser)) {
            Logger.warn("Include denied: " + currentUser + " cannot read " + includePath);
            return;
        }

        String includeContent = FileUtil.read(resolvedPath);
        if (includeContent == null || includeContent.trim().isEmpty()) return;

        // 在当前位置插入代码行
        List<String> includeLines = new ArrayList<>();
        for (String l : includeContent.split("\n")) {
            includeLines.add(l);
        }
        codeLines.addAll(currentLine + 1, includeLines);
        parseFunctionDefinitions();
    }

    /**
     * import：仅解析文件中的函数定义并注册，不执行非函数代码。
     * 以 "文件名.函数名" 的格式注册，支持命名空间调用。
     * 同名函数无冲突时也注册短名，允许直接调用。
     */
    private void handleImport(String line) {
        Matcher matcher = IMPORT_PATTERN.matcher(line);
        if (!matcher.matches()) return;

        String importPath = matcher.group(1);
        String resolvedPath = PathUtil.resolvePath(importPath);

        if (!FileUtil.exists(resolvedPath)) {
            Logger.warn("Import file not found: " + importPath);
            return;
        }

        // 权限检查：只有有读取权限才能 import
        String currentUser = UserUtil.getCurrentUser();
        if (!FileUtil.checkFilePermission(resolvedPath, Constants.PERM_READ, currentUser)) {
            Logger.warn("Import denied: " + currentUser + " cannot read " + importPath);
            return;
        }

        String importContent = FileUtil.read(resolvedPath);
        if (importContent == null || importContent.trim().isEmpty()) return;

        // 提取文件名作为命名空间（去掉路径和后缀）
        String namespace = PathUtil.getFileName(importPath);
        if (namespace.endsWith(".fcl")) {
            namespace = namespace.substring(0, namespace.length() - 4);
        }
        Logger.debug("Import: using namespace '" + namespace + "' from " + importPath);

        // 仅解析函数定义并注册到 FunctionRegistry，不执行非函数代码
        List<String> importLines = new ArrayList<>();
        for (String l : importContent.split("\n")) {
            importLines.add(l);
        }

        // 用 parseFunctionDefinitions 的逻辑只注册函数
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
                // 提取函数体（#3 逐字符匹配花括号）
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

                // 以 "命名空间.函数名" 注册
                String namespacedName = namespace + "." + funcName;
                FunctionRegistry.registerUserFunction(namespacedName, def);
                Logger.debug("Import: registered '" + namespacedName + "' from " + importPath);

                // 无冲突时也注册短名（允许直接调用）
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
                // 提取函数体（#3 逐字符匹配花括号）
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

    /**
     * 跳过函数体（遇到 func 行时）。
     */
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

    /**
     * 逐字符统计一行中 { 和 } 的数量，跳过字符串字面量和注释（#3）。
     * @return int[2] = {openCount, closeCount}
     */
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

    // ════════════════════════════════════════════
    // 文件状态管理
    // ════════════════════════════════════════════

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
            // #15: 从文件同步 PID（防止 processData 中的 PID 被外部修改后与 this.pid 不一致）
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
                Object lineObj = code.get("runningCodeLine");
                currentLine = lineObj instanceof Number ? ((Number) lineObj).intValue() : 0;
                blockStack = (List<Map<String, Object>>) code.get("BlockStack");
                if (blockStack == null) {
                    blockStack = new ArrayList<>();
                    code.put("BlockStack", blockStack);
                }
            }

            returnValue = (Map<String, Object>) program.get("returnValue");
        }

        // 反序列化调用栈
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

        // 恢复 pendingAssignVarName（跨函数调用 tick）
        if (program != null) {
            pendingAssignVarName = (String) program.get("pendingAssignVarName");
        }

        // 每 tick 重新解析函数定义，确保与当前 codeLines 一致
        parseFunctionDefinitions();
    }

    @SuppressWarnings("unchecked")
    private void saveToFile() {
        try {
            // 更新运行时间（自进程启动以来的秒数）
            processData.put("Status", running);
            processData.put("RunningTime", (System.currentTimeMillis() - processStartMs) / 1000);

            Map<String, Object> program = (Map<String, Object>) processData.get("Program");
            if (program == null) {
                program = new LinkedHashMap<>();
                processData.put("Program", program);
            }
            program.put("Data", data);
            program.put("returnValue", returnValue);

            // 序列化调用栈（callStack）到 processData
            List<Map<String, Object>> callStackData = new ArrayList<>();
            for (CallFrame frame : callStack) {
                Map<String, Object> frameData = new LinkedHashMap<>();
                frameData.put("Data", new LinkedHashMap<>(frame.savedData));
                frameData.put("Code", new ArrayList<>(frame.savedCodeLines));
                frameData.put("CodeLine", frame.savedCurrentLine);
                callStackData.add(frameData);
            }
            processData.put("CallStack", callStackData);
            // 持久化 pendingAssignVarName（跨函数调用 tick）
            if (pendingAssignVarName != null) {
                program.put("pendingAssignVarName", pendingAssignVarName);
            } else {
                program.remove("pendingAssignVarName");
            }

            Map<String, Object> code = new LinkedHashMap<>();
            code.put("Code", codeLines);
            code.put("runningCodeLine", currentLine < codeLines.size() ? currentLine : codeLines.size());
            code.put("BlockStack", blockStack);
            program.put("Code", code);

            String json = JsonUtil.toMetaJson(processData);
            String processPath = getProcessFilePath();
            FileUtil.writeAtomic(processPath, json);
        } catch (Exception e) {
            Logger.error("Failed to save process " + pid + ": " + e.getMessage());
        }
    }

    /**
     * 清空所有瞬态字段：在每个 tick 结束后调用，确保内存中不残留任何状态。
     * 下个 tick 的 loadFromFile() 会从文件中完整恢复。
     */
    private void clearTransientState() {
        this.data = null;
        this.codeLines = null;
        this.currentLine = 0;
        this.blockStack = null;
        this.returnValue = null;
        // 以下字段在同一 tick 内使用，不需要跨 tick
        this.pendingFuncName = null;
        this.pendingFuncArgs = null;
        // functions 在下个 tick 会被 loadFromProcessData 中的 parseFunctionDefinitions 重建
        this.functions.clear();
        // callStack 和 pendingAssignVarName 已持久化到 processData 并写入文件
        this.callStack.clear();
        this.pendingAssignVarName = null;
    }

    // ════════════════════════════════════════════
    // 进程终止与文件清理
    // ════════════════════════════════════════════

    /**
     * 处理进程正常终止时的文件清理：
     * - INIT (PID=1) 正常退出 → 清除所有进程文件
     * - 普通进程正常退出 → 删除自身进程文件
     * - 任何异常终止（错误、Ctrl+C）→ 文件保留（由其他路径处理）
     */
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

    /**
     * 清除 /system/process/ 下所有 .pres 文件。
     * 在 INIT 正常退出时调用。
     */
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

    /**
     * 检查当前用户是否有权限操作目标进程。
     * 规则：目标进程的 Owner 等于当前用户，或者当前用户是 local（管理员）。
     */
    @SuppressWarnings("unchecked")
    private boolean checkProcessOwner(int targetPid) {
        // local 用户拥有一切权限
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
        Logger.error("Process " + pid + " error at line " + currentLine + " (" + operation + "): " + msg);

        // 区分异常类型：RecoverableException → 警告不终止，UnrecoverableException → 终止进程
        if (e instanceof RecoverableException) {
            // 可恢复异常：仅记录警告，进程继续运行
            data.put("_warning", msg);
        } else if (e instanceof UnrecoverableException) {
            // 不可恢复异常：记录错误，终止进程
            data.put("_error", msg);
            running = false;
        } else if (e instanceof RuntimeException) {
            // 普通运行时异常：默认终止进程
            data.put("_error", msg);
            running = false;
        } else {
            // 其余异常：记录警告
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
        // 简单解析：第一个引号包围的是路径，剩余以空格分隔
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
            // 无引号路径
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

    // ── 调用帧（用于函数调用恢复上下文） ──

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
