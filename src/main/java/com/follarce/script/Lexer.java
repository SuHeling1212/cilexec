package com.follarce.script;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexer – converts an FCL expression string into a list of Tokens.
 */
public class Lexer {
    private final String input;
    private int pos;

    public Lexer(String input) {
        this.input = input;
        this.pos = 0;
    }

    /**
     * Tokenize the entire input string.
     */
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (pos < input.length()) {
            skipWhitespace();
            if (pos >= input.length()) break;

            char c = input.charAt(pos);

            // Comments: // or #
            if (c == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '/') {
                skipToEndOfLine();
                continue;
            }
            // Note: '#' is NOT treated as a comment in the expression lexer.
            // It is the length operator and is folded into identifiers by readIdentifier()
            // (e.g. "#myArray" becomes an IDENTIFIER token). The evaluator handles the rest.

            // Single-character tokens
            switch (c) {
                case '+': tokens.add(new Token(TokenType.PLUS, "+", pos)); pos++; continue;
                case '-': {
                    // Could be a negative number – readNumber handles the leading '-' case.
                    // But the caller expects readNumber for negative literals.
                    // We try to read a number if this '-' is followed by a digit.
                    if (pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1))) {
                        tokens.add(readNumber());
                    } else {
                        tokens.add(new Token(TokenType.MINUS, "-", pos));
                        pos++;
                    }
                    continue;
                }
                case '*': tokens.add(new Token(TokenType.STAR, "*", pos)); pos++; continue;
                case '/': tokens.add(new Token(TokenType.SLASH, "/", pos)); pos++; continue;
                case '%': tokens.add(new Token(TokenType.PERCENT, "%", pos)); pos++; continue;
                case '(': tokens.add(new Token(TokenType.LPAREN, "(", pos)); pos++; continue;
                case ')': tokens.add(new Token(TokenType.RPAREN, ")", pos)); pos++; continue;
                case '[': tokens.add(new Token(TokenType.LBRACKET, "[", pos)); pos++; continue;
                case ']': tokens.add(new Token(TokenType.RBRACKET, "]", pos)); pos++; continue;
                case '{': tokens.add(new Token(TokenType.LBRACE, "{", pos)); pos++; continue;
                case '}': tokens.add(new Token(TokenType.RBRACE, "}", pos)); pos++; continue;
                case ',': tokens.add(new Token(TokenType.COMMA, ",", pos)); pos++; continue;
                case ':': tokens.add(new Token(TokenType.COLON, ":", pos)); pos++; continue;
                case '=': {
                    if (pos + 1 < input.length() && input.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.EQ, "==", pos));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.ASSIGN, "=", pos));
                        pos++;
                    }
                    continue;
                }
                case '!': {
                    if (pos + 1 < input.length() && input.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.NEQ, "!=", pos));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.NOT, "!", pos));
                        pos++;
                    }
                    continue;
                }
                case '<': {
                    if (pos + 1 < input.length() && input.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.LE, "<=", pos));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.LT, "<", pos));
                        pos++;
                    }
                    continue;
                }
                case '>': {
                    if (pos + 1 < input.length() && input.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.GE, ">=", pos));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.GT, ">", pos));
                        pos++;
                    }
                    continue;
                }
                case '"': {
                    tokens.add(readString());
                    continue;
                }
            }

            // Numbers
            if (Character.isDigit(c)) {
                tokens.add(readNumber());
                continue;
            }

            // Identifiers / keywords (including 'and', 'or', 'true', 'false')
            // Also handle '#name' for the length operator.
            if (Character.isLetter(c) || c == '_' || c == '#') {
                tokens.add(readIdentifier());
                continue;
            }

            // Unknown character – advance to avoid infinite loop
            pos++;
        }

        tokens.add(new Token(TokenType.EOF, "", input.length()));
        return tokens;
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }

    private void skipToEndOfLine() {
        while (pos < input.length() && input.charAt(pos) != '\n') {
            pos++;
        }
    }

    private Token readNumber() {
        int start = pos;
        // optional leading minus for negative number literals
        if (pos < input.length() && input.charAt(pos) == '-') {
            pos++;
        }
        // integer part
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
            pos++;
        }
        // fractional part
        if (pos < input.length() && input.charAt(pos) == '.') {
            pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
        }
        String lexeme = input.substring(start, pos);
        return new Token(TokenType.NUMBER, lexeme, start);
    }

    private Token readString() {
        int start = pos;
        pos++; // skip opening "
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '"') {
                pos++; // skip closing "
                break;
            }
            if (c == '\\' && pos + 1 < input.length()) {
                char next = input.charAt(pos + 1);
                switch (next) {
                    case 'n':  sb.append('\n'); pos += 2; continue;
                    case 't':  sb.append('\t'); pos += 2; continue;
                    case 'r':  sb.append('\r'); pos += 2; continue;
                    case '"':  sb.append('"');  pos += 2; continue;
                    case '\\': sb.append('\\'); pos += 2; continue;
                    default:   sb.append(c);    pos++;    continue;
                }
            }
            sb.append(c);
            pos++;
        }
        // The lexeme stored is the original raw string including quotes
        String rawLexeme = input.substring(start, Math.min(pos, input.length()));
        return new Token(TokenType.STRING, rawLexeme, start);
    }

    private Token readIdentifier() {
        int start = pos;
        // Allow '#' prefix for length operator: #identifier
        // Identifiers: [#][a-zA-Z_][a-zA-Z0-9_.]*
        if (pos < input.length() && input.charAt(pos) == '#') {
            pos++;
        }
        if (pos < input.length() && (Character.isLetter(input.charAt(pos)) || input.charAt(pos) == '_')) {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                    pos++;
                } else {
                    break;
                }
            }
        }
        String lexeme = input.substring(start, pos);

        // Keywords (only for non-#-prefixed identifiers)
        if (start < input.length() && input.charAt(start) != '#') {
            if (lexeme.equals("and")) {
                return new Token(TokenType.AND, lexeme, start);
            }
            if (lexeme.equals("or")) {
                return new Token(TokenType.OR, lexeme, start);
            }
            if (lexeme.equals("true") || lexeme.equals("false")) {
                return new Token(TokenType.BOOLEAN, lexeme, start);
            }
        }
        return new Token(TokenType.IDENTIFIER, lexeme, start);
    }
}