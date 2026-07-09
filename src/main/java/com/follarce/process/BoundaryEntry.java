package com.follarce.process;

/**
 * 边界表条目 —— 记录 if/while 控制流的起止范围。
 * <p>
 * 由 {@link BoundaryTable#scan} 在代码加载时预扫描生成。
 * 执行过程中通过 {@link #conditionLine} 和 {@link #bodyEnd} 实现 O(1) 跳转，
 * 不再需要运行时逐字符括号匹配。
 */
public class BoundaryEntry {

    public enum Type {
        IF, WHILE
    }

    private final Type type;
    private final String condition;
    private final int conditionLine;
    private final int bodyStart;
    private final int bodyEnd;
    private final boolean hasElse;
    private final int elseBodyStart;

    public BoundaryEntry(Type type, String condition, int conditionLine, int bodyStart, int bodyEnd) {
        this(type, condition, conditionLine, bodyStart, bodyEnd, false, -1);
    }

    public BoundaryEntry(Type type, String condition, int conditionLine, int bodyStart, int bodyEnd,
                         boolean hasElse, int elseBodyStart) {
        this.type = type;
        this.condition = condition != null ? condition.trim() : "";
        this.conditionLine = conditionLine;
        this.bodyStart = bodyStart;
        this.bodyEnd = bodyEnd;
        this.hasElse = hasElse;
        this.elseBodyStart = elseBodyStart;
    }

    public Type getType() { return type; }
    public String getCondition() { return condition; }
    public int getConditionLine() { return conditionLine; }
    public int getBodyStart() { return bodyStart; }
    public int getBodyEnd() { return bodyEnd; }
    public boolean hasElse() { return hasElse; }
    public int getElseBodyStart() { return elseBodyStart; }

    @Override
    public String toString() {
        String s = type + "{cond=" + conditionLine + ",body=" + bodyStart + "-" + bodyEnd;
        if (hasElse) s += ",else=" + elseBodyStart;
        return s + "}";
    }
}
