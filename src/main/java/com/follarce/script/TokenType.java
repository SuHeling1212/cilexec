package com.follarce.script;

/**
 * Token types for the FCL expression language.
 */
public enum TokenType {
    NUMBER, STRING, BOOLEAN, IDENTIFIER,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    ASSIGN, EQ, NEQ, LT, GT, LE, GE,
    NOT, AND, OR,
    LPAREN, RPAREN, LBRACKET, RBRACKET,
    LBRACE, RBRACE, COMMA, COLON,
    EOF
}
