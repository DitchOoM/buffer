package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.ir.BooleanExpression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Unit tests for the `@When(expression)` recursive-descent parser.
 *
 * Covers every expression form PhaseB will encounter in production:
 *  - bare boolean field reference: `flags.willFlag`
 *  - remaining-bytes predicate: `remaining >= 1`
 *  - greater-than comparison on a dotted path: `header.qos > 0`
 *  - equality comparison on a dotted path: `header.qos == 1`
 *  - malformed inputs return `Failure` with a column-pointed message
 */
class BooleanExpressionParserTest {
    @Test
    fun `simple identifier becomes FieldRef of one segment`() {
        val expr = parse("hasExtra")
        assertEquals(BooleanExpression.FieldRef(listOf("hasExtra")), expr)
    }

    @Test
    fun `dotted identifier becomes FieldRef of multiple segments`() {
        val expr = parse("flags.willFlag")
        assertEquals(BooleanExpression.FieldRef(listOf("flags", "willFlag")), expr)
    }

    @Test
    fun `triple-dotted identifier produces three-segment FieldRef`() {
        val expr = parse("a.b.c")
        assertEquals(BooleanExpression.FieldRef(listOf("a", "b", "c")), expr)
    }

    @Test
    fun `remaining greater-equal predicate becomes RemainingGte`() {
        val expr = parse("remaining >= 1")
        assertEquals(BooleanExpression.RemainingGte(1), expr)
    }

    @Test
    fun `remaining greater-equal accepts large values`() {
        assertEquals(BooleanExpression.RemainingGte(255), parse("remaining >= 255"))
        assertEquals(BooleanExpression.RemainingGte(0), parse("remaining >= 0"))
    }

    @Test
    fun `field greater-than becomes Gt`() {
        val expr = parse("header.qos > 0")
        assertEquals(
            BooleanExpression.Gt(
                lhs = BooleanExpression.FieldRef(listOf("header", "qos")),
                rhs = 0,
            ),
            expr,
        )
    }

    @Test
    fun `field equality becomes Eq`() {
        val expr = parse("header.qos == 1")
        assertEquals(
            BooleanExpression.Eq(
                lhs = BooleanExpression.FieldRef(listOf("header", "qos")),
                rhs = 1,
            ),
            expr,
        )
    }

    @Test
    fun `whitespace is ignored around operators`() {
        assertEquals(parse("a==1"), parse("  a == 1  "))
        assertEquals(parse("a > 1"), parse("a >1"))
        assertEquals(parse("remaining >= 2"), parse("  remaining   >=   2  "))
    }

    @Test
    fun `empty input returns Failure`() {
        val result = BooleanExpressionParser.parse("")
        val failure = result as? BooleanParseResult.Failure ?: fail("expected Failure for empty input, got $result")
        assertTrue(
            failure.message.contains("Empty expression") || failure.message.contains("Expected"),
            "expected failure to mention emptiness; got '${failure.message}'",
        )
    }

    @Test
    fun `single greater-equal without remaining is rejected`() {
        val res = BooleanExpressionParser.parse("foo >= 1")
        val failure = res as? BooleanParseResult.Failure ?: fail("expected Failure, got $res")
        assertTrue(
            failure.message.contains(">=") && failure.message.contains("remaining"),
            "expected failure to recommend `remaining >=`; got '${failure.message}'",
        )
    }

    @Test
    fun `dangling dot is rejected with column information`() {
        val res = BooleanExpressionParser.parse("flags.")
        val failure = res as? BooleanParseResult.Failure ?: fail("expected Failure, got $res")
        assertTrue(
            failure.message.contains("identifier") && failure.message.contains("column"),
            "expected column-pointed identifier error; got '${failure.message}'",
        )
    }

    @Test
    fun `unknown character is rejected`() {
        val res = BooleanExpressionParser.parse("flags!")
        val failure = res as? BooleanParseResult.Failure ?: fail("expected Failure, got $res")
        assertTrue(failure.message.contains("'!'"), "expected unexpected-char error; got '${failure.message}'")
    }

    @Test
    fun `missing rhs after operator is rejected`() {
        val res = BooleanExpressionParser.parse("flags ==")
        val failure = res as? BooleanParseResult.Failure ?: fail("expected Failure, got $res")
        assertTrue(
            failure.message.contains("integer") && failure.message.contains("=="),
            "expected missing-integer error after ==; got '${failure.message}'",
        )
    }

    @Test
    fun `single equals is rejected with did-you-mean hint`() {
        val res = BooleanExpressionParser.parse("a = 1")
        val failure = res as? BooleanParseResult.Failure ?: fail("expected Failure, got $res")
        assertTrue(
            failure.message.contains("==") && failure.message.contains("="),
            "expected `did you mean ==` hint; got '${failure.message}'",
        )
    }

    @Test
    fun `trailing tokens after a complete expression are rejected`() {
        val res = BooleanExpressionParser.parse("flags.willFlag extra")
        val failure = res as? BooleanParseResult.Failure ?: fail("expected Failure, got $res")
        assertTrue(
            failure.message.contains("Unexpected") && failure.message.contains("extra"),
            "expected unexpected-trailing-token error; got '${failure.message}'",
        )
    }

    @Test
    fun `malformed remaining without bound is rejected`() {
        val res = BooleanExpressionParser.parse("remaining")
        val failure = res as? BooleanParseResult.Failure ?: fail("expected Failure, got $res")
        assertTrue(
            failure.message.contains(">=") && failure.message.contains("remaining"),
            "expected `>=` requirement; got '${failure.message}'",
        )
    }

    @Test
    fun `digit identifier rejection — leading digit not a valid identifier`() {
        val res = BooleanExpressionParser.parse("1.foo")
        val failure = res as? BooleanParseResult.Failure ?: fail("expected Failure, got $res")
        assertTrue(failure.message.isNotBlank(), "expected non-empty failure message")
    }

    private fun parse(input: String): BooleanExpression {
        val res = BooleanExpressionParser.parse(input)
        return when (res) {
            is BooleanParseResult.Success -> res.expression
            is BooleanParseResult.Failure -> fail("expected success for '$input', got: ${res.message}")
        }
    }
}
