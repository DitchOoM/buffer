package com.ditchoom.buffer.codec.processor.codenode

import com.ditchoom.buffer.codec.processor.codenode.CodeNode.BinOp
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.FieldRef
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.IfElse
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.IntLit
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.Local
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.LocalDecl
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.MethodCall
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.Return_
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.Sequence
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.StaticCall
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.WhenExpr
import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Structural assertions over `CodeNode` trees — no emitted-text comparison. Every
 * test pins the lowering pass's contract by constructing an input IR, running
 * [lower], and asserting the result is `==` to the expected IR. Data-class
 * structural equality plus value-class `LocalId` equality make the comparisons
 * total without any reflection or text helper.
 */
class LoweringTest {
    // -------------------------------------------------------------------------
    // Const-fold
    // -------------------------------------------------------------------------

    @Test
    fun `const-folds Plus of two IntLits`() {
        assertEquals(IntLit(5), lower(BinOp(BinOpKind.Plus, IntLit(2), IntLit(3))))
    }

    @Test
    fun `const-folds Minus of two IntLits`() {
        assertEquals(IntLit(4), lower(BinOp(BinOpKind.Minus, IntLit(7), IntLit(3))))
    }

    @Test
    fun `const-folds Times of two IntLits`() {
        assertEquals(IntLit(12), lower(BinOp(BinOpKind.Times, IntLit(4), IntLit(3))))
    }

    @Test
    fun `const-folds nested BinOps bottom-up`() {
        // (2 + 3) + (4 * 5) → 5 + 20 → 25
        val nested =
            BinOp(
                BinOpKind.Plus,
                BinOp(BinOpKind.Plus, IntLit(2), IntLit(3)),
                BinOp(BinOpKind.Times, IntLit(4), IntLit(5)),
            )
        assertEquals(IntLit(25), lower(nested))
    }

    @Test
    fun `does not fold BinOp with a non-literal operand`() {
        val input = BinOp(BinOpKind.Plus, IntLit(2), Local(LocalId("x")))
        assertEquals(input, lower(input))
    }

    // -------------------------------------------------------------------------
    // Single-statement Sequence unwrap
    // -------------------------------------------------------------------------

    @Test
    fun `unwraps a single-node Sequence`() {
        val seq = Sequence(nodes = listOf(IntLit(7)), value = null)
        assertEquals(IntLit(7), lower(seq))
    }

    @Test
    fun `unwraps a value-only Sequence`() {
        val seq = Sequence(nodes = emptyList(), value = IntLit(9))
        assertEquals(IntLit(9), lower(seq))
    }

    @Test
    fun `keeps multi-node Sequence intact`() {
        val seq =
            Sequence(
                nodes = listOf(callVoid("a"), callVoid("b")),
                value = null,
            )
        assertEquals(seq, lower(seq))
    }

    // -------------------------------------------------------------------------
    // Once-used local inlining
    // -------------------------------------------------------------------------

    @Test
    fun `inlines once-used local with IntLit init and unwraps single-stmt Sequence`() {
        // Hard-bar: Sequence(LocalDecl("x", _, IntLit(7), false), Local("x")) lowers to IntLit(7)
        val seq =
            Sequence(
                nodes =
                    listOf(
                        LocalDecl(LocalId("x"), type = null, init = IntLit(7), mutable = false),
                        Local(LocalId("x")),
                    ),
                value = null,
            )
        assertEquals(IntLit(7), lower(seq))
    }

    @Test
    fun `inlines once-used local with FieldRef of a Local init`() {
        // val x = self.field
        // return x
        // → return self.field
        val self = Local(LocalId("self"))
        val seq =
            Sequence(
                nodes =
                    listOf(
                        LocalDecl(
                            name = LocalId("x"),
                            type = null,
                            init = FieldRef(target = self, field = "field"),
                            mutable = false,
                        ),
                        Return_(Local(LocalId("x"))),
                    ),
                value = null,
            )
        assertEquals(Return_(FieldRef(self, "field")), lower(seq))
    }

    @Test
    fun `does not inline a MethodCall init even when used exactly once`() {
        // A call may have side effects — referential transparency forbids inlining.
        val callInit = MethodCall(target = null, name = "compute", args = emptyList(), typeArgs = emptyList())
        val seq =
            Sequence(
                nodes =
                    listOf(
                        LocalDecl(LocalId("x"), type = null, init = callInit, mutable = false),
                        Return_(Local(LocalId("x"))),
                    ),
                value = null,
            )
        // Lowering should leave the structure unchanged: the LocalDecl stays put,
        // the Return_ keeps reading Local("x"), and the Sequence is preserved
        // (size 2, so no single-stmt unwrap).
        assertEquals(seq, lower(seq))
    }

    @Test
    fun `does not inline a StaticCall init even when used exactly once`() {
        val callInit =
            StaticCall(
                target = ClassName("com.example", "Helper"),
                name = "now",
                args = emptyList(),
            )
        val seq =
            Sequence(
                nodes =
                    listOf(
                        LocalDecl(LocalId("t"), type = null, init = callInit, mutable = false),
                        Return_(Local(LocalId("t"))),
                    ),
                value = null,
            )
        assertEquals(seq, lower(seq))
    }

    @Test
    fun `does not inline a mutable local even with transparent init and one use`() {
        val seq =
            Sequence(
                nodes =
                    listOf(
                        LocalDecl(LocalId("size"), type = null, init = IntLit(0), mutable = true),
                        Return_(Local(LocalId("size"))),
                    ),
                value = null,
            )
        assertEquals(seq, lower(seq))
    }

    @Test
    fun `does not inline a local used more than once`() {
        // val x = 5; return x + x  — uses 2, so x stays put.
        val seq =
            Sequence(
                nodes =
                    listOf(
                        LocalDecl(LocalId("x"), type = null, init = IntLit(5), mutable = false),
                        Return_(BinOp(BinOpKind.Plus, Local(LocalId("x")), Local(LocalId("x")))),
                    ),
                value = null,
            )
        assertEquals(seq, lower(seq))
    }

    @Test
    fun `does not change Sequence whose only LocalDecl has zero uses`() {
        // val x = 1; method(); — x is unused. We keep the decl untouched at this phase
        // (a separate dead-code pass could remove it; lowering is conservative).
        val callStmt = callVoid("method")
        val seq =
            Sequence(
                nodes =
                    listOf(
                        LocalDecl(LocalId("x"), type = null, init = IntLit(1), mutable = false),
                        callStmt,
                    ),
                value = null,
            )
        assertEquals(seq, lower(seq))
    }

    @Test
    fun `inlining cascades a const-fold after substitution`() {
        // val n = 2; return n + 3   →  inline n  →  return 2 + 3  →  const-fold  →  return 5
        val seq =
            Sequence(
                nodes =
                    listOf(
                        LocalDecl(LocalId("n"), type = null, init = IntLit(2), mutable = false),
                        Return_(BinOp(BinOpKind.Plus, Local(LocalId("n")), IntLit(3))),
                    ),
                value = null,
            )
        assertEquals(Return_(IntLit(5)), lower(seq))
    }

    @Test
    fun `inlines two independent once-used locals in one pass`() {
        // val a = 2; val b = 3; return a + b  →  return 5
        val seq =
            Sequence(
                nodes =
                    listOf(
                        LocalDecl(LocalId("a"), type = null, init = IntLit(2), mutable = false),
                        LocalDecl(LocalId("b"), type = null, init = IntLit(3), mutable = false),
                        Return_(BinOp(BinOpKind.Plus, Local(LocalId("a")), Local(LocalId("b")))),
                    ),
                value = null,
            )
        assertEquals(Return_(IntLit(5)), lower(seq))
    }

    @Test
    fun `inlining looks across the value slot of a Sequence`() {
        val seq =
            Sequence(
                nodes = listOf(LocalDecl(LocalId("x"), type = null, init = IntLit(11), mutable = false)),
                value = BinOp(BinOpKind.Plus, Local(LocalId("x")), IntLit(1)),
            )
        assertEquals(IntLit(12), lower(seq))
    }

    @Test
    fun `inlining recurses into nested Sequences inside a use site`() {
        // val a = 2
        // when {
        //   true -> a + 3   // sole use of a
        // }
        // → when { true -> 5 }
        val sealedWhen =
            WhenExpr(
                subject = null,
                arms =
                    listOf(
                        WhenArm(
                            pattern = IntLit(1),
                            body = BinOp(BinOpKind.Plus, Local(LocalId("a")), IntLit(3)),
                        ),
                    ),
                default = null,
            )
        val seq =
            Sequence(
                nodes =
                    listOf(
                        LocalDecl(LocalId("a"), type = null, init = IntLit(2), mutable = false),
                        sealedWhen,
                    ),
                value = null,
            )
        val expected =
            WhenExpr(
                subject = null,
                arms = listOf(WhenArm(pattern = IntLit(1), body = IntLit(5))),
                default = null,
            )
        assertEquals(expected, lower(seq))
    }

    // -------------------------------------------------------------------------
    // Recursion under non-Sequence shapes
    // -------------------------------------------------------------------------

    @Test
    fun `lower recurses into IfElse arms`() {
        val ifElse =
            IfElse(
                cond = BinOp(BinOpKind.Plus, IntLit(1), IntLit(0)),
                then = BinOp(BinOpKind.Times, IntLit(2), IntLit(3)),
                els = BinOp(BinOpKind.Minus, IntLit(10), IntLit(4)),
            )
        assertEquals(IfElse(IntLit(1), IntLit(6), IntLit(6)), lower(ifElse))
    }

    @Test
    fun `lower recurses into MethodCall args without inlining the call itself`() {
        val call =
            MethodCall(
                target = Local(LocalId("buf")),
                name = "writeInt",
                args = listOf(BinOp(BinOpKind.Plus, IntLit(2), IntLit(2))),
                typeArgs = emptyList(),
            )
        val expected =
            MethodCall(
                target = Local(LocalId("buf")),
                name = "writeInt",
                args = listOf(IntLit(4)),
                typeArgs = emptyList(),
            )
        assertEquals(expected, lower(call))
    }

    @Test
    fun `lower recurses into StaticCall args`() {
        val call =
            StaticCall(
                target = ClassName("com.example", "Math"),
                name = "max",
                args = listOf(BinOp(BinOpKind.Plus, IntLit(1), IntLit(1)), IntLit(5)),
            )
        val expected =
            StaticCall(
                target = ClassName("com.example", "Math"),
                name = "max",
                args = listOf(IntLit(2), IntLit(5)),
            )
        assertEquals(expected, lower(call))
    }

    @Test
    fun `lower recurses into Return_ expression`() {
        assertEquals(
            Return_(IntLit(42)),
            lower(Return_(BinOp(BinOpKind.Times, IntLit(6), IntLit(7)))),
        )
    }

    // -------------------------------------------------------------------------
    // LocalId invariant
    // -------------------------------------------------------------------------

    @Test
    fun `LocalId rejects blank names`() {
        assertFailsWith<IllegalArgumentException> { LocalId("") }
        assertFailsWith<IllegalArgumentException> { LocalId("   ") }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun callVoid(name: String): MethodCall = MethodCall(target = null, name = name, args = emptyList(), typeArgs = emptyList())
}
