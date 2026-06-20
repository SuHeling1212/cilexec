package com.follarce.script;

import java.util.List;
import java.util.Map;

/**
 * 指令接口 —— Token 流架构的核心抽象。
 * <p>
 * FCL 源码经 Lexer → Parser 后产生扁平的 Instruction 列表，
 * ProcessRunner 按 ip 取 Instruction 并 switch 执行。
 * <p>
 * 默认方法提供跨包类型安全的数据访问。
 */
public interface Instruction {
    InstructionType getType();

    // ── 跨包数据访问（默认实现返回 null/0，由具体子类覆盖） ──

    default String condition() { return null; }
    default int head() { return 0; }
    default int tail() { return 0; }
    default String varName() { return null; }
    default String expression() { return null; }
    default String indexExpr() { return null; }
    default String valueExpr() { return null; }
    default String pathWithArgs() { return null; }
    default String pidExpr() { return null; }
    default String path() { return null; }
    default String name() { return null; }
    default List<String> params() { return null; }
}

// ════════════════════════════════════════════
// 控制流指令
// ════════════════════════════════════════════

/** if 指令：判条件，真则推进 BlockStack 并进入 body，假则跳到 tail。 */
class IfInstruction implements Instruction {
    /* package-private, modified by StatementParser after construction */
    String condition;
    int head;
    int tail;

    IfInstruction(String condition, int head, int tail) {
        this.condition = condition;
        this.head = head;
        this.tail = tail;
    }

    @Override
    public InstructionType getType() { return InstructionType.IF; }
    @Override public String condition() { return condition; }
    @Override public int head() { return head; }
    @Override public int tail() { return tail; }
}

/** while 指令：判条件，真则推进 BlockStack 并进入 body，假则跳到 tail。 */
class WhileInstruction implements Instruction {
    String condition;
    int head;
    int tail;

    WhileInstruction(String condition, int head, int tail) {
        this.condition = condition;
        this.head = head;
        this.tail = tail;
    }

    @Override
    public InstructionType getType() { return InstructionType.WHILE; }
    @Override public String condition() { return condition; }
    @Override public int head() { return head; }
    @Override public int tail() { return tail; }
}

// ════════════════════════════════════════════
// 单值指令
// ════════════════════════════════════════════

/** 简单变量的赋值指令。 */
class AssignmentInstruction implements Instruction {
    String varName;
    String expression;

    AssignmentInstruction(String varName, String expression) {
        this.varName = varName;
        this.expression = expression;
    }

    @Override
    public InstructionType getType() { return InstructionType.ASSIGN; }
    @Override public String varName() { return varName; }
    @Override public String expression() { return expression; }
}

/** 索引赋值指令（如 arr[0] = 1）。 */
class IndexAssignmentInstruction implements Instruction {
    String varName;
    String indexExpr;
    String valueExpr;

    IndexAssignmentInstruction(String varName, String indexExpr, String valueExpr) {
        this.varName = varName;
        this.indexExpr = indexExpr;
        this.valueExpr = valueExpr;
    }

    @Override
    public InstructionType getType() { return InstructionType.INDEX_ASSIGN; }
    @Override public String varName() { return varName; }
    @Override public String indexExpr() { return indexExpr; }
    @Override public String valueExpr() { return valueExpr; }
}

/** 表达式求值指令（函数调用、字面量、变量引用等）。 */
class ExpressionInstruction implements Instruction {
    String expression;

    ExpressionInstruction(String expression) {
        this.expression = expression;
    }

    @Override
    public InstructionType getType() { return InstructionType.EXPR; }
    @Override public String expression() { return expression; }
}

/** return 指令。 */
class ReturnInstruction implements Instruction {
    String expression;

    ReturnInstruction(String expression) {
        this.expression = expression;
    }

    @Override
    public InstructionType getType() { return InstructionType.RETURN; }
    @Override public String expression() { return expression; }
}

/** break 指令。 */
class BreakInstruction implements Instruction {
    @Override
    public InstructionType getType() { return InstructionType.BREAK; }
}

/** continue 指令。 */
class ContinueInstruction implements Instruction {
    @Override
    public InstructionType getType() { return InstructionType.CONTINUE; }
}

// ════════════════════════════════════════════
// IPC 指令
// ════════════════════════════════════════════

/** fork() 指令。 */
class ForkInstruction implements Instruction {
    @Override
    public InstructionType getType() { return InstructionType.FORK; }
}

/** exec(path) 指令。 */
class ExecInstruction implements Instruction {
    String pathWithArgs;

    ExecInstruction(String pathWithArgs) { this.pathWithArgs = pathWithArgs; }

    @Override
    public InstructionType getType() { return InstructionType.EXEC; }
    @Override public String pathWithArgs() { return pathWithArgs; }
}

/** kill(pid) 指令。 */
class KillInstruction implements Instruction {
    String pidExpr;

    KillInstruction(String pidExpr) { this.pidExpr = pidExpr; }

    @Override
    public InstructionType getType() { return InstructionType.KILL; }
    @Override public String pidExpr() { return pidExpr; }
}

/** wait() 指令。 */
class WaitInstruction implements Instruction {
    @Override
    public InstructionType getType() { return InstructionType.WAIT; }
}

/** waitPid(pid) 指令。 */
class WaitPidInstruction implements Instruction {
    String pidExpr;

    WaitPidInstruction(String pidExpr) { this.pidExpr = pidExpr; }

    @Override
    public InstructionType getType() { return InstructionType.WAITPID; }
    @Override public String pidExpr() { return pidExpr; }
}

/** pause(pid) 指令。 */
class PauseInstruction implements Instruction {
    String pidExpr;

    PauseInstruction(String pidExpr) { this.pidExpr = pidExpr; }

    @Override
    public InstructionType getType() { return InstructionType.PAUSE; }
    @Override public String pidExpr() { return pidExpr; }
}

/** continue(pid) 指令。 */
class ContinuePidInstruction implements Instruction {
    String pidExpr;

    ContinuePidInstruction(String pidExpr) { this.pidExpr = pidExpr; }

    @Override
    public InstructionType getType() { return InstructionType.CONTINUE_PID; }
    @Override public String pidExpr() { return pidExpr; }
}

// ════════════════════════════════════════════
// 模块指令
// ════════════════════════════════════════════

/** func 定义指令。 */
class FuncDefInstruction implements Instruction {
    String name;
    List<String> params;
    int bodyStart;
    int bodyEnd;

    FuncDefInstruction(String name, List<String> params, int bodyStart, int bodyEnd) {
        this.name = name;
        this.params = params;
        this.bodyStart = bodyStart;
        this.bodyEnd = bodyEnd;
    }

    @Override
    public InstructionType getType() { return InstructionType.FUNC_DEF; }
    @Override public String name() { return name; }
    @Override public List<String> params() { return params; }
    public int bodyStart() { return bodyStart; }
    public int bodyEnd() { return bodyEnd; }
}

/** import 指令。 */
class ImportInstruction implements Instruction {
    String path;

    ImportInstruction(String path) { this.path = path; }

    @Override
    public InstructionType getType() { return InstructionType.IMPORT; }
    @Override public String path() { return path; }
}

/** include 指令。 */
class IncludeInstruction implements Instruction {
    String path;

    IncludeInstruction(String path) { this.path = path; }

    @Override
    public InstructionType getType() { return InstructionType.INCLUDE; }
    @Override public String path() { return path; }
}

// ════════════════════════════════════════════
// 辅助指令
// ════════════════════════════════════════════

/** 块开始标记（{ 独占一行）。 */
class BlockStartInstruction implements Instruction {
    @Override
    public InstructionType getType() { return InstructionType.BLOCK_START; }
}

/** 块结束标记（} 独占一行）。 */
class BlockEndInstruction implements Instruction {
    @Override
    public InstructionType getType() { return InstructionType.BLOCK_END; }
}

/** 空指令（注释行、空行等）。 */
class NopInstruction implements Instruction {
    @Override
    public InstructionType getType() { return InstructionType.NOP; }
}
