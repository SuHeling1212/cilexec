package com.follarce.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * 语句解析器 —— 将 FCL 代码行列表编译为扁平的 Instruction 列表。
 * <p>
 * 职责链：代码行 → 按 ; 拆分 → 逐段 Lexer.tokenize() → parseStatement() → Instruction[]
 * <p>
 * 控制流处理：{@code if}/{@code while}/{@code {} 行产生 IfInstruction/WhileInstruction/BlockStart，
 * 后者在 {@link #build()} 阶段与 BlockEnd 配对，填入正确的 bodyStart/bodyEnd 索引。
 */
public class StatementParser {

    private final List<Instruction> instructions = new ArrayList<>();
    private final List<IfWhileEntry> ifWhileStack = new ArrayList<>();

    // ── 临时缓存：等待 } 闭合时填充控制流偏移 ──
    private static class IfWhileEntry {
        final Instruction instruction;
        final int instructionIndex;
        final int sourceBodyStart; // FuncDef 专用：body 起始指令索引

        IfWhileEntry(Instruction inst, int idx) {
            this.instruction = inst;
            this.instructionIndex = idx;
            this.sourceBodyStart = 0;
        }

        IfWhileEntry(Instruction inst, int idx, int bodyStart) {
            this.instruction = inst;
            this.instructionIndex = idx;
            this.sourceBodyStart = bodyStart;
        }
    }

    // ════════════════════════════════════════════
    // 公开 API
    // ════════════════════════════════════════════

    /**
     * 解析一行代码，生成零到多条指令。
     * <p>
     * 支持 {@code ;} 分隔多条语句。
     */
    public void parseLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            instructions.add(new NopInstruction());
            return;
        }

        // 按 ; 拆分（需跳过字符串内的 ;）
        List<String> segments = splitBySemicolon(trimmed);
        for (String seg : segments) {
            seg = seg.trim();
            if (seg.isEmpty()) continue;
            parseStatement(seg);
        }
    }

    /**
     * 完成所有行的解析，填充 If/While 指令的 tail 偏移。
     *
     * @return 完整的扁平指令数组
     */
    public Instruction[] build() {
        if (!ifWhileStack.isEmpty()) {
            throw new IllegalStateException(ifWhileStack.size()
                    + " unclosed if/while blocks (missing matching '}')");
        }
        return instructions.toArray(new Instruction[0]);
    }

    // ════════════════════════════════════════════
    // 按 ; 拆分（跳过字符串和括号上下文）
    // ════════════════════════════════════════════

    public static List<String> splitBySemicolon(String line) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        char stringChar = '"';
        int start = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == stringChar) { inString = false; }
                continue;
            }
            if (c == '"') {
                inString = true;
                stringChar = '"';
                continue;
            }
            if (c == '(' || c == '{' || c == '[') { depth++; continue; }
            if (c == ')' || c == '}' || c == ']') { depth--; continue; }
            if (c == ';' && depth == 0) {
                result.add(line.substring(start, i));
                start = i + 1;
            }
        }
        if (start < line.length()) {
            result.add(line.substring(start));
        }
        return result;
    }

    // ════════════════════════════════════════════
    // 语句级解析（按第一个 token 分发）
    // ════════════════════════════════════════════

    private void parseStatement(String code) {
        Lexer lexer = new Lexer(code);
        List<Token> tokens = lexer.tokenize();
        if (tokens.isEmpty()) {
            instructions.add(new NopInstruction());
            return;
        }

        Token first = tokens.get(0);
        if (first.type == TokenType.EOF) {
            instructions.add(new NopInstruction());
            return;
        }

        switch (first.type) {
            case IF:        parseIf(tokens);      break;
            case WHILE:     parseWhile(tokens);   break;
            case FUNC:      parseFuncDef(tokens); break;
            case RETURN:    parseReturn(tokens);  break;
            case BREAK:     instructions.add(new BreakInstruction()); break;
            case CONTINUE:  instructions.add(new ContinueInstruction()); break;
            case IMPORT:    parseImport(tokens);  break;
            case INCLUDE:   parseInclude(tokens); break;
            case LBRACE:    instructions.add(new BlockStartInstruction()); break;
            case RBRACE:    handleClosingBrace(); break;
            default:        parseAssignmentOrExpr(tokens, code); break;
        }
    }

    // ════════════════════════════════════════════
    // 各语句类型解析
    // ════════════════════════════════════════════

    private void parseIf(List<Token> tokens) {
        String condition = extractCondition(tokens);
        int instIdx = instructions.size();
        instructions.add(new IfInstruction(condition, instIdx, 0)); // tail 在 } 闭合时填充
        ifWhileStack.add(new IfWhileEntry(instructions.get(instIdx), instIdx));
    }

    private void parseWhile(List<Token> tokens) {
        String condition = extractCondition(tokens);
        int instIdx = instructions.size();
        instructions.add(new WhileInstruction(condition, instIdx, 0)); // tail 在 } 闭合时填充
        ifWhileStack.add(new IfWhileEntry(instructions.get(instIdx), instIdx));
    }

    private void parseFuncDef(List<Token> tokens) {
        // func name(params) { ... }
        if (tokens.size() < 2) {
            instructions.add(new NopInstruction());
            return;
        }
        String name = tokens.get(1).lexeme;
        List<String> params = new ArrayList<>();
        int i = 2;
        if (i < tokens.size() && tokens.get(i).type == TokenType.LPAREN) {
            i++;
            while (i < tokens.size() && tokens.get(i).type != TokenType.RPAREN) {
                if (tokens.get(i).type == TokenType.IDENTIFIER) {
                    params.add(tokens.get(i).lexeme);
                }
                i++;
            }
        }

        int instIdx = instructions.size();
        instructions.add(new FuncDefInstruction(name, params, 0, 0));
        ifWhileStack.add(new IfWhileEntry(instructions.get(instIdx), instIdx, instructions.size()));
    }

    private void parseReturn(List<Token> tokens) {
        StringBuilder expr = new StringBuilder();
        for (int i = 1; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type == TokenType.EOF) break;
            if (expr.length() > 0) expr.append(' ');
            expr.append(t.lexeme);
        }
        instructions.add(new ReturnInstruction(expr.toString().trim()));
    }

    private void parseImport(List<Token> tokens) {
        String path = extractStringArg(tokens, 1);
        instructions.add(new ImportInstruction(path));
    }

    private void parseInclude(List<Token> tokens) {
        String path = extractStringArg(tokens, 1);
        instructions.add(new IncludeInstruction(path));
    }

    // ════════════════════════════════════════════
    // 闭合花括号处理
    // ════════════════════════════════════════════

    private void handleClosingBrace() {
        if (ifWhileStack.isEmpty()) {
            instructions.add(new BlockEndInstruction());
            return;
        }

        IfWhileEntry entry = ifWhileStack.remove(ifWhileStack.size() - 1);
        int bodyEndIdx = instructions.size();

        if (entry.instruction instanceof IfInstruction) {
            IfInstruction ifInst = (IfInstruction) entry.instruction;
            ifInst.tail = bodyEndIdx + 1;
        } else if (entry.instruction instanceof WhileInstruction) {
            WhileInstruction w = (WhileInstruction) entry.instruction;
            w.tail = bodyEndIdx + 1;
        } else if (entry.instruction instanceof FuncDefInstruction) {
            FuncDefInstruction f = (FuncDefInstruction) entry.instruction;
            f.bodyStart = entry.sourceBodyStart;
            f.bodyEnd = bodyEndIdx;
        }

        instructions.add(new BlockEndInstruction());
    }

    // ════════════════════════════════════════════
    // 赋值 / 表达式 / IPC 语句
    // ════════════════════════════════════════════

    private void parseAssignmentOrExpr(List<Token> tokens, String rawCode) {
        String firstId = getFirstIdentifier(tokens);

        if ("fork".equals(firstId)) {
            instructions.add(new ForkInstruction());
            return;
        }
        if (firstId != null && firstId.startsWith("exec")) {
            instructions.add(new ExecInstruction(rawCode));
            return;
        }
        if ("kill".equals(firstId)) {
            String arg = extractParenArg(tokens);
            instructions.add(new KillInstruction(arg != null ? arg : "0"));
            return;
        }
        if ("wait".equals(firstId) && isBareCall(tokens, "wait")) {
            instructions.add(new WaitInstruction());
            return;
        }
        if (firstId != null && firstId.startsWith("waitPid")) {
            String arg = extractParenArg(tokens);
            instructions.add(new WaitPidInstruction(arg != null ? arg : "0"));
            return;
        }
        if (firstId != null && firstId.startsWith("pause")) {
            String arg = extractParenArg(tokens);
            instructions.add(new PauseInstruction(arg != null ? arg : "0"));
            return;
        }
        if (firstId != null && firstId.startsWith("continue")) {
            String arg = extractParenArg(tokens);
            instructions.add(new ContinuePidInstruction(arg != null ? arg : "0"));
            return;
        }

        // 索引赋值 arr[0] = expr
        java.util.regex.Matcher indexAssignMatcher =
                com.follarce.process.ExpressionEvaluator.INDEX_ASSIGN_PATTERN.matcher(rawCode.trim());
        if (indexAssignMatcher.matches()) {
            instructions.add(new IndexAssignmentInstruction(
                    indexAssignMatcher.group(1).trim(),
                    indexAssignMatcher.group(2).trim(),
                    indexAssignMatcher.group(3).trim()
            ));
            return;
        }

        // 普通赋值 x = expr
        java.util.regex.Matcher assignMatcher =
                com.follarce.process.ExpressionEvaluator.ASSIGN_PATTERN.matcher(rawCode.trim());
        if (assignMatcher.matches()) {
            instructions.add(new AssignmentInstruction(
                    assignMatcher.group(1).trim(),
                    assignMatcher.group(2).trim()
            ));
            return;
        }

        // fallback：通用表达式
        instructions.add(new ExpressionInstruction(rawCode.trim()));
    }

    // ════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════

    private static String extractCondition(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        boolean inParen = false;
        for (Token t : tokens) {
            if (t.type == TokenType.LPAREN) { inParen = true; continue; }
            if (t.type == TokenType.RPAREN && inParen) { inParen = false; continue; }
            if (t.type == TokenType.EOF) break;
            if (inParen) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t.lexeme);
            }
        }
        return sb.toString().trim();
    }

    private static String getFirstIdentifier(List<Token> tokens) {
        for (Token t : tokens) {
            if (t.type == TokenType.IDENTIFIER) return t.lexeme;
            if (t.type == TokenType.LPAREN) break;
        }
        return null;
    }

    private static boolean isBareCall(List<Token> tokens, String name) {
        if (tokens.size() < 2) return false;
        return tokens.get(0).type == TokenType.IDENTIFIER
                && name.equals(tokens.get(0).lexeme)
                && tokens.get(1).type == TokenType.LPAREN
                && tokens.stream().anyMatch(t -> t.type == TokenType.RPAREN);
    }

    private static String extractParenArg(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        boolean inParen = false;
        for (Token t : tokens) {
            if (t.type == TokenType.LPAREN) { inParen = true; continue; }
            if (t.type == TokenType.RPAREN && inParen) break;
            if (inParen) {
                sb.append(t.lexeme);
            }
        }
        return sb.toString().trim();
    }

    private static String extractStringArg(List<Token> tokens, int startIdx) {
        for (int i = startIdx; i < tokens.size(); i++) {
            if (tokens.get(i).type == TokenType.STRING) {
                String raw = tokens.get(i).lexeme;
                return raw.substring(1, raw.length() - 1);
            }
        }
        return "";
    }

    // ════════════════════════════════════════════
    // 指令序列化/反序列化（跨包使用）
    // ════════════════════════════════════════════

    /**
     * 将 Instruction[] 序列化为 JSON 兼容的 List&lt;Map&gt;。
     */
    public static List<Map<String, Object>> serializeInstructions(Instruction[] instructions) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Instruction inst : instructions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", inst.getType().name());
            putIf(m, "condition", inst.condition());
            if (inst.head() > 0) m.put("head", inst.head());
            if (inst.tail() > 0) m.put("tail", inst.tail());
            putIf(m, "varName", inst.varName());
            putIf(m, "expression", inst.expression());
            putIf(m, "indexExpr", inst.indexExpr());
            putIf(m, "valueExpr", inst.valueExpr());
            putIf(m, "pathWithArgs", inst.pathWithArgs());
            putIf(m, "pidExpr", inst.pidExpr());
            putIf(m, "path", inst.path());
            result.add(m);
        }
        return result;
    }

    /**
     * 从 List&lt;Map&gt; 反序列化为 Instruction[]。
     */
    @SuppressWarnings("unchecked")
    public static Instruction[] deserializeInstructions(List<Map<String, Object>> data) {
        List<Instruction> result = new ArrayList<>();
        for (Map<String, Object> m : data) {
            String type = (String) m.get("type");
            InstructionType it = InstructionType.valueOf(type);
            switch (it) {
                case IF:       result.add(new IfInstruction(s(m,"condition"), i(m,"head"), i(m,"tail"))); break;
                case WHILE:    result.add(new WhileInstruction(s(m,"condition"), i(m,"head"), i(m,"tail"))); break;
                case ASSIGN:   result.add(new AssignmentInstruction(s(m,"varName"), s(m,"expression"))); break;
                case INDEX_ASSIGN: result.add(new IndexAssignmentInstruction(s(m,"varName"), s(m,"indexExpr"), s(m,"valueExpr"))); break;
                case EXPR:     result.add(new ExpressionInstruction(s(m,"expression"))); break;
                case RETURN:   result.add(new ReturnInstruction(s(m,"expression"))); break;
                case BREAK:    result.add(new BreakInstruction()); break;
                case CONTINUE: result.add(new ContinueInstruction()); break;
                case FORK:     result.add(new ForkInstruction()); break;
                case EXEC:     result.add(new ExecInstruction(s(m,"pathWithArgs"))); break;
                case KILL:     result.add(new KillInstruction(s(m,"pidExpr"))); break;
                case WAIT:     result.add(new WaitInstruction()); break;
                case WAITPID:  result.add(new WaitPidInstruction(s(m,"pidExpr"))); break;
                case PAUSE:    result.add(new PauseInstruction(s(m,"pidExpr"))); break;
                case CONTINUE_PID: result.add(new ContinuePidInstruction(s(m,"pidExpr"))); break;
                case FUNC_DEF: result.add(new FuncDefInstruction(s(m,"name"), null, i(m,"bodyStart"), i(m,"bodyEnd"))); break;
                case IMPORT:   result.add(new ImportInstruction(s(m,"path"))); break;
                case INCLUDE:  result.add(new IncludeInstruction(s(m,"path"))); break;
                case BLOCK_START: result.add(new BlockStartInstruction()); break;
                case BLOCK_END: result.add(new BlockEndInstruction()); break;
                case NOP:      result.add(new NopInstruction()); break;
            }
        }
        return result.toArray(new Instruction[0]);
    }

    private static void putIf(Map<String, Object> m, String key, String val) {
        if (val != null) m.put(key, val);
    }

    private static String s(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static int i(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }
}
