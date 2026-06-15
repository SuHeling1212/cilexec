package com.follarce.script;

/**
 * AST node types for the FCL expression language.
 */
public enum NodeType {
    NUMBER_LITERAL, STRING_LITERAL, BOOLEAN_LITERAL,
    IDENTIFIER, UNARY_OP, BINARY_OP,
    INDEX_ACCESS, FUNCTION_CALL, ARRAY_LITERAL, MAP_LITERAL
}
