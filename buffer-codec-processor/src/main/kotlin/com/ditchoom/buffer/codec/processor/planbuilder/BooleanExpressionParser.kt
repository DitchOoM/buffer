package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.ir.BooleanExpression

/**
 * Recursive-descent parser for the `@When(expression)` DSL.
 *
 * Grammar:
 * ```
 * expression  := orExpr
 * orExpr      := <reserved>           // not yet implemented; single-term only in Phase 1
 * primary     := remainingPredicate
 *              | dottedFieldRef ( comparator integer )?
 * remainingPredicate := "remaining" ">=" integer
 * dottedFieldRef     := identifier ( "." identifier )*
 * comparator         := "==" | ">"
 * integer            := digit+
 * identifier         := letter ( letter | digit | "_" )*
 * ```
 *
 * The parser is total: malformed input returns [BooleanParseResult.Failure] with a
 * column-pointed message; PhaseB surfaces those as `KspError` entries naming the
 * offending field. The parser never throws.
 */
internal object BooleanExpressionParser {
    fun parse(input: String): BooleanParseResult {
        val tokens = tokenize(input)
        if (tokens is TokenizeResult.Failure) {
            return BooleanParseResult.Failure(tokens.message)
        }
        val parser = Parser((tokens as TokenizeResult.Success).tokens, input)
        val expr =
            try {
                parser.parseExpression()
            } catch (e: ParseException) {
                return BooleanParseResult.Failure(e.message ?: "parse error")
            }
        if (!parser.atEnd()) {
            val tok = parser.peek()
            return BooleanParseResult.Failure(
                "Unexpected token '${tok.text}' at column ${tok.start + 1} in '$input'.",
            )
        }
        return BooleanParseResult.Success(expr)
    }

    private fun tokenize(input: String): TokenizeResult {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_')) i++
                    tokens += Token(TokenKind.Identifier, input.substring(start, i), start)
                }
                c.isDigit() -> {
                    val start = i
                    while (i < input.length && input[i].isDigit()) i++
                    tokens += Token(TokenKind.Number, input.substring(start, i), start)
                }
                c == '.' -> {
                    tokens += Token(TokenKind.Dot, ".", i)
                    i++
                }
                c == '=' -> {
                    if (i + 1 < input.length && input[i + 1] == '=') {
                        tokens += Token(TokenKind.EqEq, "==", i)
                        i += 2
                    } else {
                        return TokenizeResult.Failure(
                            "Unexpected '=' at column ${i + 1} in '$input'; did you mean '=='?",
                        )
                    }
                }
                c == '>' -> {
                    if (i + 1 < input.length && input[i + 1] == '=') {
                        tokens += Token(TokenKind.GtEq, ">=", i)
                        i += 2
                    } else {
                        tokens += Token(TokenKind.Gt, ">", i)
                        i++
                    }
                }
                else ->
                    return TokenizeResult.Failure(
                        "Unexpected character '$c' at column ${i + 1} in '$input'.",
                    )
            }
        }
        return TokenizeResult.Success(tokens)
    }

    private class Parser(
        private val tokens: List<Token>,
        private val source: String,
    ) {
        private var pos: Int = 0

        fun atEnd(): Boolean = pos >= tokens.size

        fun peek(): Token = if (atEnd()) Token(TokenKind.End, "<end>", source.length) else tokens[pos]

        private fun consume(): Token = tokens[pos++]

        private fun expect(
            kind: TokenKind,
            label: String,
        ): Token {
            if (atEnd() || tokens[pos].kind != kind) {
                val tok = peek()
                throw ParseException(
                    "Expected $label but found '${tok.text}' at column ${tok.start + 1} in '$source'.",
                )
            }
            return consume()
        }

        fun parseExpression(): BooleanExpression {
            if (atEnd()) {
                throw ParseException("Empty expression — '$source' produced no tokens.")
            }
            val first = peek()
            if (first.kind == TokenKind.Identifier && first.text == "remaining") {
                return parseRemainingPredicate()
            }
            return parseFieldRefWithOptionalComparison()
        }

        private fun parseRemainingPredicate(): BooleanExpression.RemainingGte {
            consume() // "remaining"
            if (atEnd() || tokens[pos].kind != TokenKind.GtEq) {
                val tok = peek()
                throw ParseException(
                    "Expected '>=' after 'remaining' but found '${tok.text}' at column ${tok.start + 1} in '$source'.",
                )
            }
            consume() // ">="
            val numToken = expect(TokenKind.Number, "an integer literal after 'remaining >='")
            val value = parseIntOrThrow(numToken)
            require(value >= 0) { "remaining >= N requires N to be non-negative; got $value" }
            return BooleanExpression.RemainingGte(value)
        }

        private fun parseFieldRefWithOptionalComparison(): BooleanExpression {
            val path = mutableListOf<String>()
            val first = expect(TokenKind.Identifier, "a field-reference identifier")
            path += first.text
            while (!atEnd() && tokens[pos].kind == TokenKind.Dot) {
                consume() // "."
                val seg = expect(TokenKind.Identifier, "an identifier after '.'")
                path += seg.text
            }
            if (atEnd()) {
                return BooleanExpression.FieldRef(path.toList())
            }
            val tok = tokens[pos]
            return when (tok.kind) {
                TokenKind.EqEq -> {
                    consume()
                    val numTok = expect(TokenKind.Number, "an integer after '=='")
                    BooleanExpression.Eq(BooleanExpression.FieldRef(path.toList()), parseIntOrThrow(numTok))
                }
                TokenKind.Gt -> {
                    consume()
                    val numTok = expect(TokenKind.Number, "an integer after '>'")
                    BooleanExpression.Gt(BooleanExpression.FieldRef(path.toList()), parseIntOrThrow(numTok))
                }
                TokenKind.GtEq ->
                    throw ParseException(
                        "Operator '>=' is only valid after 'remaining'; for field comparisons use '>'." +
                            " Source: '$source'.",
                    )
                else ->
                    throw ParseException(
                        "Unexpected token '${tok.text}' at column ${tok.start + 1} in '$source'.",
                    )
            }
        }

        private fun parseIntOrThrow(token: Token): Int {
            val v = token.text.toIntOrNull()
            if (v == null) {
                throw ParseException(
                    "Integer literal '${token.text}' at column ${token.start + 1} of '$source' overflows Int.",
                )
            }
            return v
        }
    }

    private data class Token(
        val kind: TokenKind,
        val text: String,
        val start: Int,
    )

    private enum class TokenKind {
        Identifier,
        Number,
        Dot,
        EqEq,
        Gt,
        GtEq,
        End,
    }

    private sealed interface TokenizeResult {
        data class Success(
            val tokens: List<Token>,
        ) : TokenizeResult

        data class Failure(
            val message: String,
        ) : TokenizeResult
    }

    private class ParseException(
        message: String,
    ) : RuntimeException(message)
}

sealed interface BooleanParseResult {
    data class Success(
        val expression: BooleanExpression,
    ) : BooleanParseResult

    data class Failure(
        val message: String,
    ) : BooleanParseResult
}
