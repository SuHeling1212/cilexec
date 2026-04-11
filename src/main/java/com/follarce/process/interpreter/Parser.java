package com.follarce.process.interpreter;

import java.util.*;
import com.follarce.process.interpreter.Lexer.*;

/**
 * 语法分析器：将 Token 流转换为 AST
 */
public class Parser {
    
    // AST 节点类型
    public enum ASTType {
        NUMBER, STRING, BOOLEAN, IDENTIFIER,
        UNARY, BINARY, INDEX, FUNCTION_CALL,
        ARRAY, MAP
    }
    
    public static class ASTNode {
        public final ASTType type;
        public final Object value;
        public ASTNode left;
        public ASTNode right;
        public List<ASTNode> children;
        
        public ASTNode(ASTType type, Object value) {
            this.type = type;
            this.value = value;
        }
        
        public ASTNode(ASTType type, Object value, ASTNode left, ASTNode right) {
            this.type = type;
            this.value = value;
            this.left = left;
            this.right = right;
        }
        
        @Override
        public String toString() {
            return String.format("AST(%s, %s)", type, value);
        }
    }
    
    private final List<Token> tokens;
    private int pos;
    
    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }
    
    private Token peek() {
        return tokens.get(pos);
    }
    
    private Token consume() {
        return tokens.get(pos++);
    }
    
    private boolean match(TokenType type) {
        if (peek().type == type) {
            consume();
            return true;
        }
        return false;
    }
    
    public ASTNode parse() {
        return parseExpression(0);
    }
    
    private ASTNode parseExpression(int precedence) {
        // 处理一元运算符
        Token token = peek();
        if (token.type == TokenType.OPERATOR && token.value.equals("not")) {
            consume();
            ASTNode right = parseExpression(Lexer.PRECEDENCE.get("not"));
            return new ASTNode(ASTType.UNARY, "not", null, right);
        }
        if (token.type == TokenType.OPERATOR && token.value.equals("-")) {
            consume();
            ASTNode right = parseExpression(Lexer.PRECEDENCE.get("-"));
            return new ASTNode(ASTType.UNARY, "-", null, right);
        }
        
        ASTNode left = parsePrimary();
        
        while (true) {
            token = peek();
            
            // 处理索引访问: identifier [ expression ]
            if (token.type == TokenType.LEFT_BRACKET) {
                consume(); // [
                ASTNode index = parseExpression(0);
                if (!match(TokenType.RIGHT_BRACKET)) {
                    throw new RuntimeException("Expected ']'");
                }
                left = new ASTNode(ASTType.INDEX, null, left, index);
                continue;
            }
            
            if (token.type != TokenType.OPERATOR) break;
            
            int opPrecedence = Lexer.PRECEDENCE.getOrDefault(token.value, 0);
            if (opPrecedence <= precedence) break;
            
            consume();
            String op = token.value;
            ASTNode right = parseExpression(opPrecedence);
            left = new ASTNode(ASTType.BINARY, op, left, right);
        }
        
        return left;
    }
    
    private ASTNode parsePrimary() {
        Token token = peek();
        
        // 数字
        if (match(TokenType.NUMBER)) {
            if (token.value.contains(".")) {
                return new ASTNode(ASTType.NUMBER, Double.parseDouble(token.value));
            } else {
                return new ASTNode(ASTType.NUMBER, Integer.parseInt(token.value));
            }
        }
        
        // 字符串
        if (match(TokenType.STRING)) {
            return new ASTNode(ASTType.STRING, token.value);
        }
        
        // 布尔值
        if (match(TokenType.BOOLEAN)) {
            return new ASTNode(ASTType.BOOLEAN, Boolean.parseBoolean(token.value));
        }
        
        // 标识符
        if (match(TokenType.IDENTIFIER)) {
            ASTNode identifier = new ASTNode(ASTType.IDENTIFIER, token.value);
            
            // 检查是否是函数调用
            if (peek().type == TokenType.LEFT_PAREN) {
                consume(); // (
                List<ASTNode> args = new ArrayList<>();
                if (peek().type != TokenType.RIGHT_PAREN) {
                    do {
                        args.add(parseExpression(0));
                    } while (match(TokenType.COMMA));
                }
                if (!match(TokenType.RIGHT_PAREN)) {
                    throw new RuntimeException("Expected ')'");
                }
                ASTNode funcCall = new ASTNode(ASTType.FUNCTION_CALL, token.value);
                funcCall.children = args;
                return funcCall;
            }
            
            return identifier;
        }
        
        // 数组
        if (match(TokenType.LEFT_BRACKET)) {
            List<ASTNode> elements = new ArrayList<>();
            if (peek().type != TokenType.RIGHT_BRACKET) {
                do {
                    elements.add(parseExpression(0));
                } while (match(TokenType.COMMA));
            }
            if (!match(TokenType.RIGHT_BRACKET)) {
                throw new RuntimeException("Expected ']'");
            }
            ASTNode array = new ASTNode(ASTType.ARRAY, null);
            array.children = elements;
            return array;
        }
        
        // Map
        if (match(TokenType.LEFT_BRACE)) {
            List<ASTNode> keys = new ArrayList<>();
            List<ASTNode> values = new ArrayList<>();
            if (peek().type != TokenType.RIGHT_BRACE) {
                do {
                    keys.add(parseExpression(0));
                    if (!match(TokenType.COLON)) {
                        throw new RuntimeException("Expected ':'");
                    }
                    values.add(parseExpression(0));
                } while (match(TokenType.COMMA));
            }
            if (!match(TokenType.RIGHT_BRACE)) {
                throw new RuntimeException("Expected '}'");
            }
            ASTNode map = new ASTNode(ASTType.MAP, null);
            map.children = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                map.children.add(keys.get(i));
                map.children.add(values.get(i));
            }
            return map;
        }
        
        // 括号表达式
        if (match(TokenType.LEFT_PAREN)) {
            ASTNode expr = parseExpression(0);
            if (!match(TokenType.RIGHT_PAREN)) {
                throw new RuntimeException("Expected ')'");
            }
            return expr;
        }
        
        throw new RuntimeException("Expected expression, got " + token);
    }
}