package com.follarce.fcl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compiles structured FCL source into statement-addressable instructions. */
public final class FclCompiler {
    private static final int MAX_SYNTACTIC_NESTING = 256;
    private static final int MAX_OPERATOR_CHAIN = 256;
    private static final java.util.regex.Pattern SIMPLE_IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final java.util.Set<String> RESERVED_WORDS = java.util.Set.of(
            "func", "if", "else", "while", "break", "continue", "return",
            "import", "include", "as", "and", "or", "not", "public", "private",
            "true", "false", "null");
    /** Qualified names treated as the language-level memory.destroy operation. */
    private static final java.util.Set<String> DESTROY_FUNCTIONS = java.util.Set.of(
            "memory.destroy", "memory.unset", "memory.release");
    public FclProgram compile(String source) {
        Objects.requireNonNull(source, "source");
        return new Parser(source, new Lexer(source).scan()).compile();
    }

    private enum Type {
        IDENTIFIER, NUMBER, STRING,
        LEFT_PAREN, RIGHT_PAREN, LEFT_BRACKET, RIGHT_BRACKET,
        LEFT_BRACE, RIGHT_BRACE, COMMA, COLON, DOT,
        PLUS, MINUS, STAR, SLASH, PERCENT, HASH,
        BANG, BANG_EQUAL, EQUAL, EQUAL_EQUAL,
        LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
        AND, OR, SEMICOLON, NEWLINE, EOF
    }

    private record Token(Type type, String text, Object literal, int line, int column) {}

    private static final class Lexer {
        private final String source;
        private final List<Token> tokens = new ArrayList<>();
        private int start;
        private int current;
        private int line = 1;
        private int column = 1;
        private int startColumn = 1;
        private int delimiterDepth;

        private Lexer(String source) {
            this.source = source;
        }

        private List<Token> scan() {
            while (!atEnd()) {
                start = current;
                startColumn = column;
                scanToken();
            }
            tokens.add(new Token(Type.EOF, "", null, line, column));
            return List.copyOf(tokens);
        }

        private void scanToken() {
            char character = advance();
            switch (character) {
                case ' ', '\t', '\f' -> { }
                case '\r' -> {
                    if (peek() == '\n') advance();
                    newline();
                }
                case '\n' -> newline();
                case '(' -> add(Type.LEFT_PAREN);
                case ')' -> add(Type.RIGHT_PAREN);
                case '[' -> add(Type.LEFT_BRACKET);
                case ']' -> add(Type.RIGHT_BRACKET);
                case '{' -> add(Type.LEFT_BRACE);
                case '}' -> add(Type.RIGHT_BRACE);
                case ',' -> add(Type.COMMA);
                case ':' -> add(Type.COLON);
                case '.' -> add(Type.DOT);
                case '+' -> add(Type.PLUS);
                case '-' -> add(Type.MINUS);
                case '*' -> add(Type.STAR);
                case '%' -> add(Type.PERCENT);
                case ';' -> add(Type.SEMICOLON);
                case '!' -> add(match('=') ? Type.BANG_EQUAL : Type.BANG);
                case '=' -> add(match('=') ? Type.EQUAL_EQUAL : Type.EQUAL);
                case '<' -> add(match('=') ? Type.LESS_EQUAL : Type.LESS);
                case '>' -> add(match('=') ? Type.GREATER_EQUAL : Type.GREATER);
                case '&' -> {
                    if (!match('&')) error("Expected '&' after '&'");
                    add(Type.AND);
                }
                case '|' -> {
                    if (!match('|')) error("Expected '|' after '|'");
                    add(Type.OR);
                }
                case '/' -> {
                    if (match('/')) skipComment();
                    else add(Type.SLASH);
                }
                case '#' -> add(Type.HASH);
                case '"' -> string();
                default -> {
                    if (digit(character)) number();
                    else if (identifierStart(character)) identifier();
                    else error("Unexpected character '" + character + "'");
                }
            }
        }

        private void identifier() {
            while (identifierPart(peek())) advance();
            while (peek() == '.' && identifierStart(peekNext())) {
                advance();
                while (identifierPart(peek())) advance();
            }
            add(Type.IDENTIFIER);
        }

        private void number() {
            if (qualifiedHashIdentifier()) {
                add(Type.IDENTIFIER);
                return;
            }
            while (digit(peek())) advance();
            boolean decimal = false;
            if (peek() == '.' && digit(peekNext())) {
                decimal = true;
                advance();
                while (digit(peek())) advance();
            }
            String text = source.substring(start, current);
            try {
                if (decimal) {
                    double value = Double.parseDouble(text);
                    // parseDouble saturates overflowing literals to Infinity, which later
                    // fails when the continuation is serialized to JSON. Reject the literal
                    // at compile time instead of accepting a value that cannot round-trip.
                    if (!Double.isFinite(value)) error("Numeric literal is not finite: '" + text + "'");
                    add(Type.NUMBER, value);
                } else {
                    add(Type.NUMBER, Long.parseLong(text));
                }
            } catch (NumberFormatException failure) {
                error("Invalid number '" + text + "'");
            }
        }

        private boolean qualifiedHashIdentifier() {
            int hashEnd = start + 64;
            if (hashEnd + 1 >= source.length() || source.charAt(hashEnd) != '.'
                    || !identifierStart(source.charAt(hashEnd + 1))) return false;
            for (int index = start; index < hashEnd; index++) {
                char value = source.charAt(index);
                if (!(digit(value) || value >= 'a' && value <= 'f'
                        || value >= 'A' && value <= 'F')) return false;
            }
            while (current < hashEnd) advance();
            advance();
            while (identifierPart(peek())) advance();
            while (peek() == '.' && identifierStart(peekNext())) {
                advance();
                while (identifierPart(peek())) advance();
            }
            return true;
        }

        private void string() {
            StringBuilder value = new StringBuilder();
            while (!atEnd() && peek() != '"') {
                char character = advance();
                if (character == '\r') {
                    if (peek() == '\n') advance();
                    line++;
                    column = 1;
                    value.append('\n');
                    continue;
                }
                if (character == '\n') {
                    line++;
                    column = 1;
                    value.append(character);
                    continue;
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (atEnd()) error("Unterminated string escape");
                char escaped = advance();
                value.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> {
                        error("Unknown escape sequence '\\" + escaped + "'");
                        throw new AssertionError("unreachable");
                    }
                });
            }
            if (atEnd()) error("Unterminated string literal");
            advance();
            add(Type.STRING, value.toString());
        }

        private void skipComment() {
            while (!atEnd() && peek() != '\n' && peek() != '\r') advance();
        }

        private void newline() {
            tokens.add(new Token(Type.NEWLINE, "\n", null, line, startColumn));
            line++;
            column = 1;
        }

        private char advance() {
            char value = source.charAt(current++);
            column++;
            return value;
        }

        private boolean match(char expected) {
            if (atEnd() || source.charAt(current) != expected) return false;
            current++;
            column++;
            return true;
        }

        private char peek() {
            return atEnd() ? '\0' : source.charAt(current);
        }

        private char peekNext() {
            return current + 1 >= source.length() ? '\0' : source.charAt(current + 1);
        }

        private boolean atEnd() {
            return current >= source.length();
        }

        private void add(Type type) {
            add(type, null);
        }

        private void add(Type type, Object literal) {
            if (type == Type.LEFT_PAREN || type == Type.LEFT_BRACKET
                    || type == Type.LEFT_BRACE) {
                if (++delimiterDepth > MAX_SYNTACTIC_NESTING) {
                    error("FCL nesting exceeds " + MAX_SYNTACTIC_NESTING + " levels");
                }
            } else if ((type == Type.RIGHT_PAREN || type == Type.RIGHT_BRACKET
                    || type == Type.RIGHT_BRACE) && delimiterDepth > 0) {
                delimiterDepth--;
            }
            tokens.add(new Token(type, source.substring(start, current), literal,
                    line, startColumn));
        }

        private void error(String message) {
            throw new FclCompileException(message, line, startColumn);
        }

        private static boolean digit(char value) {
            return value >= '0' && value <= '9';
        }

        private static boolean identifierStart(char value) {
            return value == '_' || value >= 'a' && value <= 'z'
                    || value >= 'A' && value <= 'Z';
        }

        private static boolean identifierPart(char value) {
            return identifierStart(value) || digit(value);
        }
    }

    private static final class Parser {
        private final String source;
        private final List<Token> tokens;
        private final List<FclInstruction> instructions = new ArrayList<>();
        private final Map<String, FclProgram.Function> functions = new LinkedHashMap<>();
        private int current;
        private long expressionId = 1;

        private Parser(String source, List<Token> tokens) {
            this.source = source;
            this.tokens = tokens;
        }

        private FclProgram compile() {
            skipSeparators();
            while (!check(Type.EOF)) {
                statement(true, 0, 0);
                skipSeparators();
            }
            return new FclProgram(instructions, functions, source);
        }

        private void statement(boolean topLevel, int loopDepth, int functionDepth) {
            Token start = peek();
            if (word("func")) {
                if (!topLevel || functionDepth != 0) {
                    fail(start, "Functions may only be declared at top level");
                }
                function(start, true);
                return;
            }
            if (word("public") || word("private")) {
                boolean publicBinding = previous().text().equals("public");
                if (!topLevel || functionDepth != 0) {
                    fail(start, "Functions may only be declared at top level");
                }
                Token function = consume(Type.IDENTIFIER, "Expected 'func' after visibility modifier");
                if (!function.text().equals("func")) fail(function,
                        "Expected 'func' after visibility modifier");
                function(start, publicBinding);
                return;
            }
            if (word("if")) {
                conditional(start, loopDepth, functionDepth);
                return;
            }
            if (word("while")) {
                loop(start, loopDepth, functionDepth);
                return;
            }
            if (word("break")) {
                if (loopDepth == 0) fail(start, "break is only valid inside while");
                instructions.add(new FclInstruction.Break(start.line()));
                finishSimple();
                return;
            }
            if (word("continue")) {
                if (loopDepth == 0) fail(start, "continue is only valid inside while");
                instructions.add(new FclInstruction.Continue(start.line()));
                finishSimple();
                return;
            }
            if (word("return")) {
                FclExpression value = atStatementEnd()
                        ? literal(null) : expression();
                instructions.add(new FclInstruction.Return(start.line(), value, false));
                finishSimple();
                return;
            }
            if (word("import")) {
                if (!topLevel) fail(start, "import/include must be at the top level");
                importInstruction(start);
                return;
            }
            if (word("include")) {
                if (!topLevel) fail(start, "import/include must be at the top level");
                includeInstruction(start);
                return;
            }
            assignmentOrExpression(start);
        }

        private void function(Token start, boolean publicBinding) {
            Token name = consume(Type.IDENTIFIER, "Expected function name");
            requireBindableIdentifier(name, "Function name");
            if (functions.containsKey(name.text())) {
                fail(name, "Function is already declared: " + name.text());
            }
            consume(Type.LEFT_PAREN, "Expected '(' after function name");
            List<String> parameters = new ArrayList<>();
            if (!check(Type.RIGHT_PAREN)) {
                do {
                    Token parameterToken = consume(Type.IDENTIFIER,
                            "Expected parameter name");
                    requireBindableIdentifier(parameterToken, "Parameter name");
                    String parameter = parameterToken.text();
                    if (parameters.contains(parameter)) {
                        fail(previous(), "Duplicate parameter: " + parameter);
                    }
                    parameters.add(parameter);
                } while (match(Type.COMMA));
            }
            consume(Type.RIGHT_PAREN, "Expected ')' after parameters");
            int declarationPointer = instructions.size();
            instructions.add(new FclInstruction.FunctionDeclaration(start.line(), name.text(),
                    parameters, -1, -1, publicBinding));
            openBlock("Expected '{' before function body");
            int bodyTarget = instructions.size();
            block(0, 1);
            instructions.add(new FclInstruction.Return(start.line(), literal(null), true));
            int endTarget = instructions.size();
            instructions.set(declarationPointer, new FclInstruction.FunctionDeclaration(
                    start.line(), name.text(), parameters, bodyTarget, endTarget, publicBinding));
            functions.put(name.text(), new FclProgram.Function(name.text(), parameters,
                    bodyTarget, endTarget, null, publicBinding, null));
        }

        private void conditional(Token start, int loopDepth, int functionDepth) {
            FclExpression condition = conditionExpression("if");
            int conditionalPointer = instructions.size();
            instructions.add(new FclInstruction.Conditional(start.line(), condition, -1, -1));
            openBlock("Expected '{' after if condition");
            block(loopDepth, functionDepth);
            skipSeparators();
            if (word("else")) {
                int jumpPointer = instructions.size();
                instructions.add(new FclInstruction.Jump(start.line(), -1));
                int falseTarget = instructions.size();
                skipSeparators();
                if (word("if")) {
                    conditional(start, loopDepth, functionDepth);
                } else {
                    openBlock("Expected '{' after else");
                    block(loopDepth, functionDepth);
                }
                int endTarget = instructions.size();
                instructions.set(conditionalPointer, new FclInstruction.Conditional(
                        start.line(), condition, falseTarget, endTarget));
                instructions.set(jumpPointer, new FclInstruction.Jump(start.line(), endTarget));
            } else {
                int endTarget = instructions.size();
                instructions.set(conditionalPointer, new FclInstruction.Conditional(
                        start.line(), condition, endTarget, endTarget));
            }
        }

        private void loop(Token start, int loopDepth, int functionDepth) {
            FclExpression condition = conditionExpression("while");
            int header = instructions.size();
            instructions.add(new FclInstruction.Loop(start.line(), condition, -1, -1));
            openBlock("Expected '{' after while condition");
            int bodyTarget = instructions.size();
            block(loopDepth + 1, functionDepth);
            instructions.add(new FclInstruction.Jump(start.line(), header));
            int endTarget = instructions.size();
            instructions.set(header, new FclInstruction.Loop(start.line(), condition,
                    bodyTarget, endTarget));
        }

        private FclExpression conditionExpression(String keyword) {
            if (match(Type.LEFT_PAREN)) {
                FclExpression condition = expression();
                consume(Type.RIGHT_PAREN, "Expected ')' after " + keyword + " condition");
                return condition;
            }
            if (atStatementEnd() || check(Type.LEFT_BRACE)) {
                fail(peek(), "Expected " + keyword + " condition");
            }
            return expression();
        }

        private void block(int loopDepth, int functionDepth) {
            skipSeparators();
            while (!check(Type.RIGHT_BRACE) && !check(Type.EOF)) {
                statement(false, loopDepth, functionDepth);
                skipSeparators();
            }
            consume(Type.RIGHT_BRACE, "Expected '}' after block");
        }

        private void openBlock(String message) {
            skipSeparators();
            consume(Type.LEFT_BRACE, message);
        }

        private void importInstruction(Token start) {
            String target = String.valueOf(consume(Type.STRING,
                    "Expected quoted import target, for example: "
                            + "import \"<64-hex-sha256>\"").literal());
            String identity = target.endsWith(".*")
                    ? target.substring(0, target.length() - 2) : target;
            boolean packageHash = identity.matches("(?i)[0-9a-f]{64}");
            boolean sourceFile = !target.endsWith(".*") && target.toLowerCase(
                    java.util.Locale.ROOT).endsWith(".fcl");
            if (!packageHash && !sourceFile) {
                fail(previous(), "Import target must be a 64-character package database SHA-256 "
                        + "or a .fcl source path");
            }
            String alias = null;
            if (word("as")) {
                alias = String.valueOf(consume(Type.STRING,
                        "Expected quoted import alias, for example: as \"editor\"").literal());
                if (!simpleBindableIdentifier(alias)) {
                    fail(previous(), "Import alias must be a simple identifier");
                }
            }
            instructions.add(new FclInstruction.Import(start.line(), target, alias,
                    target.endsWith(".*")));
            finishSimple();
        }

        private void includeInstruction(Token start) {
            String target = directiveTarget("include target");
            instructions.add(new FclInstruction.Include(start.line(), target));
            finishSimple();
        }

        private String directiveTarget(String description) {
            if (match(Type.STRING)) return String.valueOf(previous().literal());
            Token target = consume(Type.IDENTIFIER, "Expected " + description);
            String value = target.text();
            if (match(Type.DOT)) {
                consume(Type.STAR, "Only .* is valid after a directive target");
                value += ".*";
            }
            return value;
        }

        private void assignmentOrExpression(Token start) {
            int marker = current;
            if (match(Type.IDENTIFIER)) {
                Token variable = previous();
                List<FclExpression> indices = new ArrayList<>();
                while (match(Type.LEFT_BRACKET)) {
                    indices.add(expression());
                    consume(Type.RIGHT_BRACKET, "Expected ']' after assignment index");
                }
                if (match(Type.EQUAL)) {
                    requireBindableIdentifier(variable, "Assignment target");
                    FclExpression value = expression();
                    instructions.add(new FclInstruction.Assignment(start.line(), variable.text(),
                            indices, value));
                    finishSimple();
                    return;
                }
            }
            current = marker;
            FclExpression expression = expression();
            instructions.add(new FclInstruction.Evaluation(start.line(), expression));
            finishSimple();
        }

        private FclExpression expression() {
            return or();
        }

        private FclExpression or() {
            FclExpression expression = and();
            int operators = 0;
            while (match(Type.OR) || word("or")) {
                requireOperatorDepth(++operators);
                expression = new FclExpression.Binary(nextId(), "or", expression, and());
            }
            return expression;
        }

        private FclExpression and() {
            FclExpression expression = equality();
            int operators = 0;
            while (match(Type.AND) || word("and")) {
                requireOperatorDepth(++operators);
                expression = new FclExpression.Binary(nextId(), "and", expression, equality());
            }
            return expression;
        }

        private FclExpression equality() {
            FclExpression expression = comparison();
            int operators = 0;
            while (match(Type.EQUAL_EQUAL, Type.BANG_EQUAL)) {
                requireOperatorDepth(++operators);
                String operator = previous().text();
                expression = new FclExpression.Binary(nextId(), operator, expression,
                        comparison());
            }
            return expression;
        }

        private FclExpression comparison() {
            FclExpression expression = term();
            int operators = 0;
            while (match(Type.LESS, Type.LESS_EQUAL, Type.GREATER, Type.GREATER_EQUAL)) {
                requireOperatorDepth(++operators);
                String operator = previous().text();
                expression = new FclExpression.Binary(nextId(), operator, expression, term());
            }
            return expression;
        }

        private FclExpression term() {
            FclExpression expression = factor();
            int operators = 0;
            while (match(Type.PLUS, Type.MINUS)) {
                requireOperatorDepth(++operators);
                String operator = previous().text();
                expression = new FclExpression.Binary(nextId(), operator, expression, factor());
            }
            return expression;
        }

        private FclExpression factor() {
            FclExpression expression = unary();
            int operators = 0;
            while (match(Type.STAR, Type.SLASH, Type.PERCENT)) {
                requireOperatorDepth(++operators);
                String operator = previous().text();
                expression = new FclExpression.Binary(nextId(), operator, expression, unary());
            }
            return expression;
        }

        private FclExpression unary() {
            List<String> operators = new ArrayList<>();
            while (true) {
                if (match(Type.BANG, Type.MINUS, Type.HASH)) {
                    operators.add(previous().text());
                } else if (word("not")) {
                    operators.add("!");
                } else {
                    break;
                }
                requireOperatorDepth(operators.size());
            }
            FclExpression expression = postfix();
            for (int index = operators.size() - 1; index >= 0; index--) {
                expression = new FclExpression.Unary(nextId(), operators.get(index), expression);
            }
            return expression;
        }

        private FclExpression postfix() {
            FclExpression expression = primary();
            while (true) {
                // Hash-qualified package calls: "<64-hex-sha256>".function(...). The
                // qualifier is the immutable package identity; the dot form keeps the
                // hash out of the identifier grammar.
                if (expression instanceof FclExpression.Literal literal
                        && literal.value() instanceof String text
                        && text.matches("(?i)[0-9a-f]{64}")
                        && match(Type.DOT)) {
                    Token member = consume(Type.IDENTIFIER,
                            "Expected a function name after '\".\"'");
                    expression = new FclExpression.Variable(nextId(),
                            text.toLowerCase(java.util.Locale.ROOT) + "." + member.text());
                    continue;
                }
                if (match(Type.LEFT_PAREN)) {
                    if (!(expression instanceof FclExpression.Variable)) {
                        fail(previous(), "Only named functions can be called");
                    }
                    FclExpression.Variable variable = (FclExpression.Variable) expression;
                    if (DESTROY_FUNCTIONS.contains(variable.name())) {
                        expression = destroyTarget(variable.name());
                        continue;
                    }
                    List<FclExpression> arguments = new ArrayList<>();
                    if (!check(Type.RIGHT_PAREN)) {
                        do {
                            arguments.add(expression());
                        } while (match(Type.COMMA));
                    }
                    consume(Type.RIGHT_PAREN, "Expected ')' after arguments");
                    expression = new FclExpression.Call(nextId(), variable.name(), arguments);
                    continue;
                }
                if (match(Type.LEFT_BRACKET)) {
                    FclExpression index = expression();
                    consume(Type.RIGHT_BRACKET, "Expected ']' after index");
                    expression = new FclExpression.Index(nextId(), expression, index);
                    continue;
                }
                return expression;
            }
        }

        /**
         * Parses a {@code memory.destroy} delete target: a symbol name optionally followed
         * by index brackets. Anything else (literals, strings, binary expressions, array or
         * map literals, function calls) is rejected here so the runtime always receives the
         * real root name plus the index path instead of a deep-copied value.
         */
        private FclExpression destroyTarget(String qualifiedName) {
            if (check(Type.RIGHT_PAREN)) {
                fail(peek(), qualifiedName + " expects a symbol target");
            }
            Token root = consume(Type.IDENTIFIER, qualifiedName
                    + " target must be a symbol name such as " + qualifiedName + "(a)");
            if (RESERVED_WORDS.contains(root.text())) {
                fail(root, root.text() + " is a reserved word");
            }
            List<FclExpression> indices = new ArrayList<>();
            while (match(Type.LEFT_BRACKET)) {
                indices.add(expression());
                consume(Type.RIGHT_BRACKET, "Expected ']' after destroy index");
            }
            if (check(Type.COMMA)) {
                fail(peek(), qualifiedName + " accepts exactly one target");
            }
            consume(Type.RIGHT_PAREN, "Expected ')' after destroy target");
            return new FclExpression.DestroyTarget(nextId(), root.text(), indices);
        }

        private FclExpression primary() {
            if (match(Type.NUMBER, Type.STRING)) {
                return literal(previous().literal());
            }
            if (word("true")) return literal(true);
            if (word("false")) return literal(false);
            if (word("null")) return literal(null);
            if (match(Type.IDENTIFIER)) {
                Token identifier = previous();
                if (RESERVED_WORDS.contains(identifier.text())) {
                    fail(identifier, identifier.text() + " is a reserved word");
                }
                return new FclExpression.Variable(nextId(), identifier.text());
            }
            if (match(Type.LEFT_PAREN)) {
                FclExpression expression = expression();
                consume(Type.RIGHT_PAREN, "Expected ')' after expression");
                return expression;
            }
            if (match(Type.LEFT_BRACKET)) {
                List<FclExpression> elements = new ArrayList<>();
                skipExpressionNewlines();
                if (!check(Type.RIGHT_BRACKET)) {
                    do {
                        skipExpressionNewlines();
                        elements.add(expression());
                        skipExpressionNewlines();
                    } while (match(Type.COMMA));
                }
                consume(Type.RIGHT_BRACKET, "Expected ']' after array literal");
                return new FclExpression.ArrayLiteral(nextId(), elements);
            }
            if (match(Type.LEFT_BRACE)) {
                List<FclExpression.MapEntry> entries = new ArrayList<>();
                skipExpressionNewlines();
                if (!check(Type.RIGHT_BRACE)) {
                    do {
                        skipExpressionNewlines();
                        FclExpression key;
                        if (check(Type.IDENTIFIER) && checkNext(Type.COLON)) {
                            key = literal(advance().text());
                        } else {
                            key = expression();
                        }
                        consume(Type.COLON, "Expected ':' after map key");
                        skipExpressionNewlines();
                        FclExpression value = expression();
                        entries.add(new FclExpression.MapEntry(key, value));
                        skipExpressionNewlines();
                    } while (match(Type.COMMA));
                }
                consume(Type.RIGHT_BRACE, "Expected '}' after map literal");
                return new FclExpression.MapLiteral(nextId(), entries);
            }
            fail(peek(), "Expected expression");
            throw new AssertionError("unreachable");
        }

        private FclExpression literal(Object value) {
            return new FclExpression.Literal(nextId(), value);
        }

        private long nextId() {
            return expressionId++;
        }

        private void requireOperatorDepth(int depth) {
            if (depth > MAX_OPERATOR_CHAIN) {
                fail(previous(), "Expression operator chain exceeds "
                        + MAX_OPERATOR_CHAIN + " operations");
            }
        }

        private void requireBindableIdentifier(Token token, String description) {
            if (RESERVED_WORDS.contains(token.text())) {
                fail(token, token.text() + " is a reserved word");
            }
            if (!simpleBindableIdentifier(token.text())) {
                fail(token, description + " must be a simple non-reserved identifier");
            }
        }

        private static boolean simpleBindableIdentifier(String value) {
            return SIMPLE_IDENTIFIER.matcher(value).matches()
                    && !RESERVED_WORDS.contains(value);
        }

        private void finishSimple() {
            if (match(Type.SEMICOLON, Type.NEWLINE)) return;
            if (check(Type.RIGHT_BRACE) || check(Type.EOF)) return;
            fail(peek(), "Expected end of statement");
        }

        private boolean atStatementEnd() {
            return check(Type.SEMICOLON) || check(Type.NEWLINE)
                    || check(Type.RIGHT_BRACE) || check(Type.EOF);
        }

        private void skipSeparators() {
            while (match(Type.SEMICOLON, Type.NEWLINE)) { }
        }

        private void skipExpressionNewlines() {
            while (match(Type.NEWLINE)) { }
        }

        private boolean word(String value) {
            if (check(Type.IDENTIFIER) && peek().text().equals(value)) {
                current++;
                return true;
            }
            return false;
        }

        private boolean match(Type... types) {
            for (Type type : types) {
                if (check(type)) {
                    current++;
                    return true;
                }
            }
            return false;
        }

        private Token consume(Type type, String message) {
            if (check(type)) return advance();
            fail(peek(), message);
            throw new AssertionError("unreachable");
        }

        private boolean check(Type type) {
            return peek().type() == type;
        }

        private boolean checkNext(Type type) {
            return current + 1 < tokens.size() && tokens.get(current + 1).type() == type;
        }

        private Token advance() {
            if (!check(Type.EOF)) current++;
            return previous();
        }

        private Token peek() {
            return tokens.get(current);
        }

        private Token previous() {
            return tokens.get(current - 1);
        }

        private void fail(Token token, String message) {
            throw new FclCompileException(message, token.line(), token.column());
        }
    }
}
