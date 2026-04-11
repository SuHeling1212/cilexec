package com.follarce.process.interpreter;

import java.util.*;

/**
 * 词法分析器：将源代码字符串转换为 Token 流
 */
public class Lexer {
    
    public enum TokenType {
        NUMBER, STRING, BOOLEAN, IDENTIFIER, 
        OPERATOR, LEFT_PAREN, RIGHT_PAREN, 
        LEFT_BRACKET, RIGHT_BRACKET, LEFT_BRACE, RIGHT_BRACE,
        COMMA, COLON, END
    }
    
    public static class Token {
        public final TokenType type;
        public final String value;
        
        public Token(TokenType type, String value) {
            this.type = type;
            this.value = value;
        }
        
        @Override
        public String toString() {
            return String.format("Token(%s, '%s')", type, value);
        }
    }
    
    // 运算符优先级
    public static final Map<String, Integer> PRECEDENCE = new HashMap<>();
    static {
        PRECEDENCE.put("or", 1);
        PRECEDENCE.put("and", 2);
        PRECEDENCE.put("not", 3);
        PRECEDENCE.put("==", 4);
        PRECEDENCE.put("!=", 4);
        PRECEDENCE.put("<", 5);
        PRECEDENCE.put(">", 5);
        PRECEDENCE.put("<=", 5);
        PRECEDENCE.put(">=", 5);
        PRECEDENCE.put("+", 6);
        PRECEDENCE.put("-", 6);
        PRECEDENCE.put("*", 7);
        PRECEDENCE.put("/", 7);
        PRECEDENCE.put("%", 7);
    }
    
    private final String source;
    private int pos;
    private final int length;
    
    public Lexer(String source) {
        this.source = source;
        this.pos = 0;
        this.length = source.length();
    }
    
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        
        while (pos < length) {
            char c = source.charAt(pos);
            
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (Character.isDigit(c) || 
                      (c == '.' && pos + 1 < length && Character.isDigit(source.charAt(pos + 1)))) {
                tokens.add(readNumber());
            } else if (c == '"') {
                tokens.add(readString());
            } else if (c == '(') {
                tokens.add(new Token(TokenType.LEFT_PAREN, "("));
                pos++;
            } else if (c == ')') {
                tokens.add(new Token(TokenType.RIGHT_PAREN, ")"));
                pos++;
            } else if (c == '[') {
                tokens.add(new Token(TokenType.LEFT_BRACKET, "["));
                pos++;
            } else if (c == ']') {
                tokens.add(new Token(TokenType.RIGHT_BRACKET, "]"));
                pos++;
            } else if (c == '{') {
                tokens.add(new Token(TokenType.LEFT_BRACE, "{"));
                pos++;
            } else if (c == '}') {
                tokens.add(new Token(TokenType.RIGHT_BRACE, "}"));
                pos++;
            } else if (c == ',') {
                tokens.add(new Token(TokenType.COMMA, ","));
                pos++;
            } else if (c == ':') {
                tokens.add(new Token(TokenType.COLON, ":"));
                pos++;
            } else if (isOperatorChar(c)) {
                tokens.add(readOperator());
            } else if (Character.isLetter(c) || c == '_') {
                tokens.add(readIdentifier());
            } else {
                throw new RuntimeException("Unexpected character: " + c + " at position " + pos);
            }
        }
        
        tokens.add(new Token(TokenType.END, ""));
        return tokens;
    }
    
    private Token readNumber() {
        int start = pos;
        while (pos < length && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '.')) {
            pos++;
        }
        String value = source.substring(start, pos);
        return new Token(TokenType.NUMBER, value);
    }
    
    private Token readString() {
        pos++; // skip opening "
        StringBuilder sb = new StringBuilder();
        
        while (pos < length) {
            char c = source.charAt(pos);
            if (c == '\\') {
                pos++;
                if (pos < length) {
                    char next = source.charAt(pos);
                    switch (next) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        default: sb.append(next);
                    }
                }
            } else if (c == '"') {
                pos++;
                break;
            } else {
                sb.append(c);
            }
            pos++;
        }
        
        return new Token(TokenType.STRING, sb.toString());
    }
    
    private boolean isOperatorChar(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '%' ||
               c == '=' || c == '!' || c == '<' || c == '>';
    }
    
    private Token readOperator() {
        int start = pos;
        while (pos < length && isOperatorChar(source.charAt(pos))) {
            pos++;
        }
        String value = source.substring(start, pos);
        return new Token(TokenType.OPERATOR, value);
    }
    
    private Token readIdentifier() {
        int start = pos;
        while (pos < length && (Character.isLetterOrDigit(source.charAt(pos)) || source.charAt(pos) == '_' || source.charAt(pos) == '.')) {
            pos++;
        }
        String value = source.substring(start, pos);
        
        // 关键字
        if (value.equals("true") || value.equals("false")) {
            return new Token(TokenType.BOOLEAN, value);
        }
        if (value.equals("and") || value.equals("or") || value.equals("not")) {
            return new Token(TokenType.OPERATOR, value);
        }
        
        return new Token(TokenType.IDENTIFIER, value);
    }
}