package com.follarce.script;

/**
 * 指令类型枚举。
 */
public enum InstructionType {
    IF, WHILE, FORK, EXEC, KILL,
    WAIT, WAITPID, PAUSE, CONTINUE_PID,
    ASSIGN, INDEX_ASSIGN, EXPR,
    RETURN, BREAK, CONTINUE,
    FUNC_DEF, IMPORT, INCLUDE,
    BLOCK_START, BLOCK_END, NOP
}
