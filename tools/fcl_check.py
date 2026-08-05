#!/usr/bin/env python3
"""Validate FCL source syntax without the Java runtime.

The CilExec FCL grammar is defined authoritatively by FclCompiler.java; this validator
mirrors its lexer and recursive-descent parser closely enough that every module shipped
through the market must pass here before it is packaged. Notably, ``#`` is the length
operator, not a comment: a line such as ``# CilEdit: ...`` is a syntax error here, which
is exactly the failure class that produced un-installable market packages.

Usage:
    python3 fcl_check.py <source.fcl>...
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from typing import Optional

IDENTIFIER_START = re.compile(r"[A-Za-z_]")
IDENTIFIER_PART = re.compile(r"[A-Za-z0-9_]")
DIGIT = re.compile(r"[0-9]")

RESERVED = {
    "func", "if", "else", "while", "break", "continue", "return",
    "import", "include", "as", "and", "or",
    "true", "false", "null",
}

SIMPLE_BINDABLE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
SHA256 = re.compile(r"(?i)[0-9a-f]{64}")


class FclSyntaxError(Exception):
    def __init__(self, message: str, line: int, column: int) -> None:
        super().__init__(f"{message} at {line}:{column}")
        self.line = line
        self.column = column


@dataclass(frozen=True)
class Token:
    kind: str
    text: str
    line: int
    column: int


KIND = {
    "(": "LPAREN", ")": "RPAREN", "[": "LBRACKET", "]": "RBRACKET",
    "{": "LBRACE", "}": "RBRACE", ",": "COMMA", ":": "COLON",
    ".": "DOT", "+": "PLUS", "-": "MINUS", "*": "STAR", "/": "SLASH",
    "%": "PERCENT", ";": "SEMICOLON", "#": "HASH",
    "==": "EQUAL_EQUAL", "=": "EQUAL",
    "<=": "LESS_EQUAL", "<": "LESS",
    ">=": "GREATER_EQUAL", ">": "GREATER",
    "!=": "BANG_EQUAL", "!": "BANG",
    "&&": "AND", "||": "OR",
}


def tokenize(source: str) -> list[Token]:
    tokens: list[Token] = []
    line = 1
    column = 1
    index = 0
    length = len(source)

    def peek(offset: int = 0) -> str:
        position = index + offset
        return source[position] if position < length else ""

    def advance() -> str:
        nonlocal index, line, column
        character = source[index]
        index += 1
        if character == "\n":
            line += 1
            column = 1
        else:
            column += 1
        return character

    def newline() -> None:
        nonlocal line, column
        line += 1
        column = 1

    def error(message: str, at_line: int, at_column: int) -> None:
        raise FclSyntaxError(message, at_line, at_column)

    def add(kind: str, text: str, at_line: int, at_column: int) -> None:
        tokens.append(Token(kind, text, at_line, at_column))

    def dotted_identifier(first: str) -> str:
        # Called with the first character already consumed; consumes the dotted tail.
        text = [first]
        while IDENTIFIER_PART.match(peek()):
            text.append(advance())
        while peek() == "." and IDENTIFIER_START.match(peek(1)):
            advance()
            text.append(".")
            while IDENTIFIER_PART.match(peek()):
                text.append(advance())
        return "".join(text)

    def qualified_hash_identifier(start_index: int, at_line: int, at_column: int) -> bool:
        hash_end = start_index + 64
        if hash_end + 1 >= length or source[hash_end] != ".":
            return False
        if not IDENTIFIER_START.match(source[hash_end + 1]):
            return False
        if not re.fullmatch(r"[0-9a-fA-F]{64}", source[start_index:hash_end]):
            return False
        for _ in range(64):
            advance()
        while peek() == "." and IDENTIFIER_START.match(peek(1)):
            advance()
            while IDENTIFIER_PART.match(peek()):
                advance()
        add("IDENTIFIER", source[start_index:index], at_line, at_column)
        return True

    while index < length:
        start_index = index
        start_line, start_column = line, column
        character = advance()
        if character in " \t\f":
            continue
        if character == "\r":
            if peek() == "\n":
                advance()
            add("NEWLINE", "\n", start_line, start_column)
            newline()
            continue
        if character == "\n":
            add("NEWLINE", "\n", start_line, start_column)
            continue
        if character == "/" and peek() == "/":
            while index < length and peek() not in "\n\r":
                advance()
            continue
        kind = KIND.get(character)
        if kind is not None:
            if character in "=!<>" and peek() == "=":
                advance()
                add(KIND[character + "="], character + "=", start_line, start_column)
            elif character == "&" and peek() == "&":
                advance()
                add("AND", "&&", start_line, start_column)
            elif character == "|" and peek() == "|":
                advance()
                add("OR", "||", start_line, start_column)
            else:
                add(kind, character, start_line, start_column)
            continue
        if character == '"':
            value: list[str] = []
            while index < length and peek() != '"':
                char = advance()
                if char == "\r":
                    if peek() == "\n":
                        advance()
                    newline()
                    value.append("\n")
                    continue
                if char == "\n":
                    value.append(char)
                    continue
                if char != "\\":
                    value.append(char)
                    continue
                if index >= length:
                    error("Unterminated string escape", start_line, start_column)
                escaped = advance()
                if escaped not in "nrt\"\\":
                    error(f"Unknown escape sequence '\\{escaped}'", start_line, start_column)
                value.append({"n": "\n", "r": "\r", "t": "\t", '"': '"', "\\": "\\"}[escaped])
            if index >= length:
                error("Unterminated string literal", start_line, start_column)
            advance()
            add("STRING", "".join(value), start_line, start_column)
            continue
        if IDENTIFIER_START.match(character):
            add("IDENTIFIER", dotted_identifier(character), start_line, start_column)
            continue
        if DIGIT.match(character):
            if qualified_hash_identifier(start_index, start_line, start_column):
                continue
            number = [character]
            while DIGIT.match(peek()):
                number.append(advance())
            decimal = False
            if peek() == "." and DIGIT.match(peek(1)):
                decimal = True
                advance()
                while DIGIT.match(peek()):
                    number.append(advance())
            text = "".join(number)
            if not decimal:
                try:
                    if int(text) > 2**63 - 1:
                        raise ValueError
                except ValueError:
                    error(f"Invalid number '{text}'", start_line, start_column)
            add("NUMBER", text, start_line, start_column)
            continue
        error(f"Unexpected character '{character}'", start_line, start_column)

    tokens.append(Token("EOF", "", line, column))
    return tokens


class _Validator:
    def __init__(self, source: str) -> None:
        self.tokens = tokenize(source)
        self.current = 0
        self.functions: set[str] = set()

    # -- token helpers ---------------------------------------------------------

    def peek(self, offset: int = 0) -> Token:
        position = min(self.current + offset, len(self.tokens) - 1)
        return self.tokens[position]

    def advance(self) -> Token:
        token = self.tokens[self.current]
        if token.kind != "EOF":
            self.current += 1
        return token

    def check(self, kind: str) -> bool:
        return self.peek().kind == kind

    def check_word(self, word: str) -> bool:
        token = self.peek()
        return token.kind == "IDENTIFIER" and token.text == word

    def match(self, *kinds: str) -> Optional[Token]:
        if self.peek().kind in kinds:
            return self.advance()
        return None

    def match_word(self, word: str) -> bool:
        if self.check_word(word):
            self.advance()
            return True
        return False

    def consume(self, kind: str, message: str) -> Token:
        if not self.check(kind):
            self.fail(self.peek(), message)
        return self.advance()

    def at_statement_end(self) -> bool:
        return self.peek().kind in ("SEMICOLON", "NEWLINE", "RBRACE", "EOF")

    def fail(self, token: Token, message: str) -> None:
        raise FclSyntaxError(message, token.line, token.column)

    # -- statements -----------------------------------------------------------

    def validate(self) -> None:
        self.skip_separators()
        while not self.check("EOF"):
            self.statement(top_level=True, loop_depth=0, function_depth=0)
            self.skip_separators()

    def skip_separators(self) -> None:
        while self.match("SEMICOLON", "NEWLINE"):
            pass

    def finish_simple(self) -> None:
        if self.match("SEMICOLON", "NEWLINE"):
            return
        if self.check("RBRACE") or self.check("EOF"):
            return
        self.fail(self.peek(), "Expected end of statement")

    def statement(self, top_level: bool, loop_depth: int, function_depth: int) -> None:
        start = self.peek()
        if self.match_word("func"):
            if not top_level:
                self.fail(start, "func declarations are only valid at the top level")
            self.function(start)
            return
        if self.match_word("if"):
            self.conditional(start, loop_depth, function_depth)
            return
        if self.match_word("while"):
            self.loop(start, loop_depth, function_depth)
            return
        if self.match_word("break"):
            if loop_depth == 0:
                self.fail(start, "break is only valid inside while")
            self.finish_simple()
            return
        if self.match_word("continue"):
            if loop_depth == 0:
                self.fail(start, "continue is only valid inside while")
            self.finish_simple()
            return
        if self.match_word("return"):
            if function_depth == 0:
                self.fail(start, "return is only valid inside func")
            if not self.at_statement_end():
                self.expression()
            self.finish_simple()
            return
        if self.match_word("import"):
            if not top_level:
                self.fail(start, "import/include must be at the top level")
            self.import_instruction(start)
            return
        if self.match_word("include"):
            if not top_level:
                self.fail(start, "import/include must be at the top level")
            self.include_instruction(start)
            return
        self.assignment_or_expression(start)

    def function(self, start: Token) -> None:
        name = self.consume("IDENTIFIER", "Expected function name")
        self.require_bindable(name, "Function name")
        if name.text in self.functions:
            self.fail(name, "Function is already declared: " + name.text)
        self.consume("LPAREN", "Expected '(' after function name")
        parameters: list[str] = []
        if not self.check("RPAREN"):
            while True:
                parameter = self.consume("IDENTIFIER", "Expected parameter name")
                self.require_bindable(parameter, "Parameter name")
                if parameter.text in parameters:
                    self.fail(parameter, "Duplicate parameter: " + parameter.text)
                parameters.append(parameter.text)
                if not self.match("COMMA"):
                    break
        self.consume("RPAREN", "Expected ')' after parameters")
        self.open_block("Expected '{' before function body")
        self.block(loop_depth=0, function_depth=1)
        self.functions.add(name.text)

    def conditional(self, start: Token, loop_depth: int, function_depth: int) -> None:
        self.condition("if")
        self.open_block("Expected '{' after if condition")
        self.block(loop_depth, function_depth)
        self.skip_separators()
        if self.match_word("else"):
            self.skip_separators()
            if self.check_word("if"):
                self.conditional(self.advance(), loop_depth, function_depth)
            else:
                self.open_block("Expected '{' after else")
                self.block(loop_depth, function_depth)

    def loop(self, start: Token, loop_depth: int, function_depth: int) -> None:
        self.condition("while")
        self.open_block("Expected '{' after while condition")
        self.block(loop_depth + 1, function_depth)

    def condition(self, keyword: str) -> None:
        if self.match("LPAREN"):
            self.expression()
            self.consume("RPAREN", f"Expected ')' after {keyword} condition")
            return
        if self.at_statement_end() or self.check("LBRACE"):
            self.fail(self.peek(), f"Expected {keyword} condition")
        self.expression()

    def block(self, loop_depth: int, function_depth: int) -> None:
        self.skip_separators()
        while not self.check("RBRACE") and not self.check("EOF"):
            self.statement(top_level=False, loop_depth=loop_depth,
                           function_depth=function_depth)
            self.skip_separators()
        self.consume("RBRACE", "Expected '}' after block")

    def open_block(self, message: str) -> None:
        self.skip_separators()
        self.consume("LBRACE", message)

    def import_instruction(self, start: Token) -> None:
        target = self.consume("STRING", "Expected quoted import target, for example: "
                                        "import \"binding\" or import \"<64-hex-sha256>\"")
        identity = target.text[:-2] if target.text.endswith(".*") else target.text
        if not SHA256.fullmatch(identity) and not self.simple_bindable(identity):
            self.fail(target, "Import target must be a package binding or a 64-character "
                              "package database SHA-256")
        if self.match_word("as"):
            alias = self.consume("STRING", "Expected quoted import alias, for example: "
                                           "as \"editor\"")
            if not self.simple_bindable(alias.text):
                self.fail(alias, "Import alias must be a simple identifier")
        self.finish_simple()

    def include_instruction(self, start: Token) -> None:
        self.directive_target("include target")
        self.finish_simple()

    def directive_target(self, description: str) -> None:
        if self.match("STRING"):
            return
        target = self.consume("IDENTIFIER", "Expected " + description)
        if self.match("DOT"):
            self.consume("STAR", "Only .* is valid after a directive target")

    def assignment_or_expression(self, start: Token) -> None:
        marker = self.current
        if self.match("IDENTIFIER"):
            variable = self.tokens[self.current - 1]
            while self.match("LBRACKET"):
                self.expression()
                self.consume("RBRACKET", "Expected ']' after assignment index")
            if self.match("EQUAL"):
                self.require_bindable(variable, "Assignment target")
                self.expression()
                self.finish_simple()
                return
        self.current = marker
        self.expression()
        self.finish_simple()

    def require_bindable(self, token: Token, description: str) -> None:
        if token.text in RESERVED:
            self.fail(token, token.text + " is a reserved word")
        if not self.simple_bindable(token.text):
            self.fail(token, description + " must be a simple non-reserved identifier")

    @staticmethod
    def simple_bindable(value: str) -> bool:
        return bool(SIMPLE_BINDABLE.fullmatch(value)) and value not in RESERVED

    # -- expressions ----------------------------------------------------------

    def expression(self) -> None:
        self.or_expression()

    def or_expression(self) -> None:
        self.and_expression()
        while self.check_word("or") or self.check("OR"):
            self.advance()
            self.and_expression()

    def and_expression(self) -> None:
        self.equality()
        while self.check_word("and") or self.check("AND"):
            self.advance()
            self.equality()

    def equality(self) -> None:
        self.comparison()
        while self.match("EQUAL_EQUAL", "BANG_EQUAL"):
            self.comparison()

    def comparison(self) -> None:
        self.term()
        while self.match("LESS", "LESS_EQUAL", "GREATER", "GREATER_EQUAL"):
            self.term()

    def term(self) -> None:
        self.factor()
        while self.match("PLUS", "MINUS"):
            self.factor()

    def factor(self) -> None:
        self.unary()
        while self.match("STAR", "SLASH", "PERCENT"):
            self.unary()

    def unary(self) -> None:
        while self.match("BANG", "MINUS", "HASH"):
            pass
        self.postfix()

    def postfix(self) -> None:
        expression = self.primary()
        while True:
            if self.match("LPAREN"):
                if expression.kind != "IDENTIFIER":
                    self.fail(self.tokens[self.current - 1],
                              "Only named functions can be called")
                if not self.check("RPAREN"):
                    while True:
                        self.expression()
                        if not self.match("COMMA"):
                            break
                self.consume("RPAREN", "Expected ')' after arguments")
                continue
            if self.match("LBRACKET"):
                self.expression()
                self.consume("RBRACKET", "Expected ']' after index")
                continue
            return

    def primary(self) -> Token:
        if self.match("NUMBER", "STRING"):
            return self.tokens[self.current - 1]
        if self.check_word("true") or self.check_word("false") or self.check_word("null"):
            return self.advance()
        if self.match("IDENTIFIER"):
            token = self.tokens[self.current - 1]
            if token.text in RESERVED:
                self.fail(token, token.text + " is a reserved word")
            return token
        if self.match("LPAREN"):
            self.expression()
            self.consume("RPAREN", "Expected ')' after expression")
            return self.tokens[self.current - 1]
        if self.match("LBRACKET"):
            self.skip_separators()
            if not self.check("RBRACKET"):
                while True:
                    self.skip_separators()
                    self.expression()
                    self.skip_separators()
                    if not self.match("COMMA"):
                        break
            self.consume("RBRACKET", "Expected ']' after array literal")
            return self.tokens[self.current - 1]
        if self.match("LBRACE"):
            self.skip_separators()
            if not self.check("RBRACE"):
                while True:
                    self.skip_separators()
                    if self.check("IDENTIFIER") and self.peek(1).kind == "COLON":
                        self.advance()
                    else:
                        self.expression()
                    self.consume("COLON", "Expected ':' after map key")
                    self.skip_separators()
                    self.expression()
                    self.skip_separators()
                    if not self.match("COMMA"):
                        break
            self.consume("RBRACE", "Expected '}' after map literal")
            return self.tokens[self.current - 1]
        self.fail(self.peek(), "Expected expression")
        raise AssertionError("unreachable")


def validate_module(source: str) -> None:
    """Raises FclSyntaxError when the FCL source is not valid; returns None otherwise."""
    _Validator(source).validate()


def main(arguments: list[str]) -> int:
    if not arguments or arguments[0] in ("-h", "--help"):
        print(__doc__)
        return 0 if arguments else 64
    failures = 0
    for path in arguments:
        try:
            source = open(path, encoding="utf-8").read()
        except OSError as error:
            print(f"fcl_check: cannot read {path}: {error}", file=sys.stderr)
            failures += 1
            continue
        try:
            validate_module(source)
        except FclSyntaxError as error:
            print(f"fcl_check: {path}: {error}", file=sys.stderr)
            failures += 1
            continue
        print(f"fcl_check: {path}: OK")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
