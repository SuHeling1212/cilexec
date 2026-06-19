package com.follarce.process.func;

import com.follarce.log.Logger;
import com.follarce.process.expr.ExpressionEvaluator;
import com.follarce.script.FunctionDef;
import com.follarce.script.Lexer;
import com.follarce.script.Parser;
import com.follarce.script.AstNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户函数管理器 —— 解析函数定义、管理调用栈、处理函数调用。
 * <p>
 * 职责：
 * <ul>
 *   <li>解析 {@code func name(params) { ... }} 定义</li>
 *   <li>维护 {@link CallFrame} 调用栈</li>
 *   <li>处理函数调用时的参数传递和返回</li>
 * </ul>
 */
public class FunctionManager {

    private static final Pattern FUNC_DEF_PATTERN =
            Pattern.compile("^func\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)\\s*\\{?\\s*$");

    private final int pid;
    private final ExpressionEvaluator evaluator;

    // 用户函数定义缓存
    private final Map<String, FunctionDef> functions = new LinkedHashMap<>();

    // 调用栈
    private final Deque<CallFrame> callStack = new ArrayDeque<>();

    // 函数调用参数（由 NodeEvaluator 回调传入）
    private String pendingFuncName;
    private List<Object> pendingFuncArgs;

    public FunctionManager(int pid, ExpressionEvaluator evaluator) {
        this.pid = pid;
        this.evaluator = evaluator;
    }

    public Map<String, FunctionDef> getFunctions() { return functions; }
    public Deque<CallFrame> getCallStack() { return callStack; }
    public String getPendingFuncName() { return pendingFuncName; }
    public void setPendingFuncName(String name) { this.pendingFuncName = name; }
    public List<Object> getPendingFuncArgs() { return pendingFuncArgs; }
    public void setPendingFuncArgs(List<Object> args) { this.pendingFuncArgs = args; }

    /**
     * 从代码行中解析所有函数定义。
     * 在代码加载时调用一次。
     *
     * @param codeLines 已剔除注释的代码行
     */
    public void parseFunctions(List<String> codeLines) {
        functions.clear();
        if (codeLines == null) return;

        for (int i = 0; i < codeLines.size(); i++) {
            String line = codeLines.get(i).trim();
            Matcher matcher = FUNC_DEF_PATTERN.matcher(line);
            if (matcher.matches()) {
                String funcName = matcher.group(1);
                String paramsStr = matcher.group(2).trim();
                List<String> params = paramsStr.isEmpty()
                        ? new ArrayList<>()
                        : Arrays.asList(paramsStr.split("\\s*,\\s*"));

                // 查找函数体
                int bodyStart = i + 1;
                int bodyEnd = findFunctionBodyEnd(codeLines, bodyStart);
                List<String> bodyLines = codeLines.subList(bodyStart, bodyEnd);

                functions.put(funcName, new FunctionDef(funcName, params, new ArrayList<>(bodyLines), bodyStart));
                Logger.debug("Parsed function: " + funcName + "(" + params + ") at lines " + bodyStart + "-" + bodyEnd);
            }
        }
    }

    /**
     * 设置函数调用参数（由 NodeEvaluator 回调调用）。
     */
    public void setFunctionArgs(String funcName, List<Object> args) {
        this.pendingFuncName = funcName;
        this.pendingFuncArgs = args;
    }

    /**
     * 清除待处理的函数调用状态。
     */
    public void clearPending() {
        this.pendingFuncName = null;
        this.pendingFuncArgs = null;
    }

    /**
     * 保存当前数据到调用帧（准备进入函数体）。
     */
    public CallFrame saveFrame(Map<String, Object> currentData, List<String> currentCodeLines, int currentLine) {
        CallFrame frame = new CallFrame(new LinkedHashMap<>(currentData),
                new ArrayList<>(currentCodeLines), currentLine);
        callStack.push(frame);
        return frame;
    }

    /**
     * 从调用栈弹出，返回上一个帧。
     */
    public CallFrame popFrame() {
        return callStack.isEmpty() ? null : callStack.pop();
    }

    public boolean isInCall() {
        return !callStack.isEmpty();
    }

    /**
     * 查找函数定义。
     */
    public FunctionDef getFunction(String name) {
        return functions.get(name);
    }

    /**
     * 清除所有函数定义和调用栈。
     */
    public void clear() {
        functions.clear();
        callStack.clear();
        pendingFuncName = null;
        pendingFuncArgs = null;
    }

    // ════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════

    /**
     * 找到函数体的结束行号（匹配的 }）。
     */
    private int findFunctionBodyEnd(List<String> codeLines, int startLine) {
        int depth = 0;
        for (int i = startLine; i < codeLines.size(); i++) {
            String line = codeLines.get(i).trim();
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') {
                    if (depth == 0) return i; // 函数体结束
                    depth--;
                }
            }
            // 如果 } 是这一行最后一个字符且 depth 为 0
            if (depth == 0 && line.endsWith("}")) {
                return i;
            }
        }
        return codeLines.size();
    }

    /**
     * 调用帧 —— 保存函数调用前的执行上下文。
     */
    public static class CallFrame {
        public final Map<String, Object> savedData;
        public final List<String> savedCodeLines;
        public final int savedCurrentLine;

        public CallFrame(Map<String, Object> savedData, List<String> savedCodeLines, int savedCurrentLine) {
            this.savedData = savedData;
            this.savedCodeLines = savedCodeLines;
            this.savedCurrentLine = savedCurrentLine;
        }
    }
}
