package com.follarce.script;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for FCL expressions.
 *
 * Operator precedence (lowest to highest):
 *   or(1) < and(2) < not(3, unary) < comparison(4) == != < > <= >=
 *   < add/sub(5) + - < mul/div(6) * / %
 */
public class Parser {
    private final List<Token> tokens;
    private int pos;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    /**
     * Parse an entire expression.
     */
    public AstNode parse() {
        AstNode result = parseOr();
        if (current().type != TokenType.EOF) {
            throw new RuntimeException("Unexpected token '" + current().lexeme + "' at position " + current().position);
        }
        return result;
    }

    // ---- Precedence levels ----

    private AstNode parseOr() {
        AstNode left = parseAnd();
        while (current().type == TokenType.OR) {
            consume(TokenType.OR);
            AstNode right = parseAnd();
            left = AstNode.binaryOp("or", left, right);
        }
        return left;
    }

    private AstNode parseAnd() {
        AstNode left = parseComparison();
        while (current().type == TokenType.AND) {
            consume(TokenType.AND);
            AstNode right = parseComparison();
            left = AstNode.binaryOp("and", left, right);
        }
        return left;
    }

    private AstNode parseComparison() {
        AstNode left = parseAddSub();
        while (isComparisonOp(current().type)) {
            Token op = current();
            String opStr = op.lexeme;
            pos++;
            AstNode right = parseAddSub();
            left = AstNode.binaryOp(opStr, left, right);
        }
        return left;
    }

    private AstNode parseAddSub() {
        AstNode left = parseMulDiv();
        while (current().type == TokenType.PLUS || current().type == TokenType.MINUS) {
            Token op = current();
            String opStr = op.lexeme;
            pos++;
            AstNode right = parseMulDiv();
            left = AstNode.binaryOp(opStr, left, right);
        }
        return left;
    }

    private AstNode parseMulDiv() {
        AstNode left = parseUnary();
        while (current().type == TokenType.STAR || current().type == TokenType.SLASH || current().type == TokenType.PERCENT) {
            Token op = current();
            String opStr = op.lexeme;
            pos++;
            AstNode right = parseUnary();
            left = AstNode.binaryOp(opStr, left, right);
        }
        return left;
    }

    private AstNode parseUnary() {
        if (current().type == TokenType.NOT) {
            consume(TokenType.NOT);
            AstNode operand = parseUnary();
            return AstNode.unaryOp("!", operand);
        }
        if (current().type == TokenType.MINUS) {
            // Negative number literal is handled in the lexer; here a unary minus.
            // But the lexer already folded -digit into NUMBER tokens.
            // A standalone '-' followed by something that is not a digit at lex time
            // is a MINUS token; we need to handle unary minus when it appears as operator.
            // This case occurs when the '-' is not part of a number (e.g. -(x+1)).
            consume(TokenType.MINUS);
            AstNode operand = parseUnary();
            return AstNode.unaryOp("-", operand);
        }
        return parsePostfix();
    }

    /**
     * Parse postfix operations: primary followed by index / call chains.
     */
    private AstNode parsePostfix() {
        AstNode node = parsePrimary();

        while (true) {
            if (current().type == TokenType.LBRACKET) {
                // Index access: expr[expr]
                consume(TokenType.LBRACKET);
                AstNode index = parseOr();
                consume(TokenType.RBRACKET);
                node = AstNode.indexAccess(node, index);
            } else if (current().type == TokenType.LPAREN) {
                // Function call on an expression (only if node is an identifier)
                // We treat this as a function call where the name is the identifier's name.
                if (node.type == NodeType.IDENTIFIER) {
                    String funcName = node.name;
                    consume(TokenType.LPAREN);
                    List<AstNode> args = parseArgumentList();
                    consume(TokenType.RPAREN);
                    node = AstNode.functionCall(funcName, args);
                } else {
                    // Allow calls on expressions? For now, treat as illegal.
                    throw new RuntimeException("Cannot call non-identifier expression as function at position " + current().position);
                }
            } else {
                break;
            }
        }

        return node;
    }

    /**
     * Parse a primary expression: literal, identifier, parenthesized, array, map.
     */
    private AstNode parsePrimary() {
        Token tok = current();

        switch (tok.type) {
            case NUMBER: {
                pos++;
                String lex = tok.lexeme;
                if (lex.contains(".")) {
                    return AstNode.numberLiteral(Double.parseDouble(lex));
                } else {
                    return AstNode.numberLiteral(Long.parseLong(lex));
                }
            }
            case STRING: {
                pos++;
                // Strip surrounding quotes and unescape
                String raw = tok.lexeme;
                String content = raw.substring(1, raw.length() - 1);
                content = StringEscape.unescape(content);
                return AstNode.stringLiteral(content);
            }
            case BOOLEAN: {
                pos++;
                return AstNode.booleanLiteral(Boolean.parseBoolean(tok.lexeme));
            }
            case IDENTIFIER: {
                pos++;
                return AstNode.identifier(tok.lexeme);
            }
            case LPAREN: {
                consume(TokenType.LPAREN);
                AstNode expr = parseOr();
                consume(TokenType.RPAREN);
                return expr;
            }
            case LBRACKET: {
                // Array literal: [element, ...]
                consume(TokenType.LBRACKET);
                List<AstNode> elements = new ArrayList<>();
                if (current().type != TokenType.RBRACKET) {
                    elements.add(parseOr());
                    while (current().type == TokenType.COMMA) {
                        consume(TokenType.COMMA);
                        elements.add(parseOr());
                    }
                }
                consume(TokenType.RBRACKET);
                return AstNode.arrayLiteral(elements);
            }
            case LBRACE: {
                // Map literal: { key: value, ... }
                consume(TokenType.LBRACE);
                List<AstNode> keys = new ArrayList<>();
                List<AstNode> vals = new ArrayList<>();
                if (current().type != TokenType.RBRACE) {
                    // key: value
                    AstNode key = parseOr();
                    consume(TokenType.COLON);
                    AstNode val = parseOr();
                    keys.add(key);
                    vals.add(val);
                    while (current().type == TokenType.COMMA) {
                        consume(TokenType.COMMA);
                        AstNode k = parseOr();
                        consume(TokenType.COLON);
                        AstNode v = parseOr();
                        keys.add(k);
                        vals.add(v);
                    }
                }
                consume(TokenType.RBRACE);
                return AstNode.mapLiteral(keys, vals);
            }
            default:
                throw new RuntimeException("Unexpected token '" + tok.lexeme + "' at position " + tok.position);
        }
    }

    /**
     * Parse function-call argument list (already past the opening paren).
     */
    private List<AstNode> parseArgumentList() {
        List<AstNode> args = new ArrayList<>();
        if (current().type != TokenType.RPAREN) {
            args.add(parseOr());
            while (current().type == TokenType.COMMA) {
                consume(TokenType.COMMA);
                args.add(parseOr());
            }
        }
        return args;
    }

    // ---- Helpers ----

    private Token current() {
        return tokens.get(pos);
    }

    private void consume(TokenType expected) {
        if (current().type != expected) {
            throw new RuntimeException("Expected " + expected + " but got '" + current().lexeme + "' at position " + current().position);
        }
        pos++;
    }

    private boolean isComparisonOp(TokenType type) {
        return type == TokenType.EQ || type == TokenType.NEQ
            || type == TokenType.LT || type == TokenType.GT
            || type == TokenType.LE || type == TokenType.GE;
    }

    // unescapeString moved to shared utility: StringEscape.unescape()
}