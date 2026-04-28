package com.ditchoom.buffer.codec.processor.codenode

import com.ditchoom.buffer.codec.processor.codenode.CodeNode.BinOp
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.CtorCall
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.FieldRef
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.IfElse
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.IntLit
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.Local
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.LocalDecl
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.MethodCall
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.Raw
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.Return_
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.Sequence
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.StaticCall
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.StringLit
import com.ditchoom.buffer.codec.processor.codenode.CodeNode.WhenExpr

/**
 * Lower a [CodeNode] tree to a structurally simpler equivalent.
 *
 * The pass applies, recursively bottom-up:
 *
 *  - **Constant folding** for `BinOp(op, IntLit(n), IntLit(m))` over `Plus` /
 *    `Minus` / `Times` — eliminates the wireSize accumulator's
 *    `IntLit(N) + IntLit(M)` chains in favour of a single `IntLit(N+M)`.
 *  - **Single-statement unwrap** for `Sequence` — `Sequence(nodes = [a], value = null)`
 *    and `Sequence(nodes = [], value = a)` both lower to `a`.
 *  - **Once-used local inlining** for `Sequence` whose `LocalDecl` has a
 *    referentially-transparent init and a single use in the rest of the sequence.
 *    The substitution re-runs lowering on the rewritten sequence so cascaded
 *    folds (inline → const-fold → unwrap) collapse in one call.
 *
 * **Referential transparency.** Only [IntLit], [Local], and [FieldRef] of a
 * transparent target are eligible. A [MethodCall] init is never auto-inlined,
 * even at use-count 1 — calls may have side effects or read mutable state.
 *
 * **Mutability.** A `LocalDecl` with `mutable = true` is never inlined.
 *
 * **Shadowing.** The lowering pass assumes [LocalId]s introduced by the upstream
 * generator are unique within the tree; it does not rewrite [LocalDecl]s out of
 * shadowing scope explicitly. The Phase D emitter is the only producer and gives
 * each local a fresh name, so shadowing does not arise in practice.
 */
fun lower(node: CodeNode): CodeNode =
    when (node) {
        is IntLit -> node
        is StringLit -> node
        is Raw -> node
        is Local -> node
        is FieldRef -> FieldRef(lower(node.target), node.field)
        is MethodCall ->
            MethodCall(
                target = node.target?.let(::lower),
                name = node.name,
                args = node.args.map(::lower),
                typeArgs = node.typeArgs,
            )
        is StaticCall -> StaticCall(node.target, node.name, node.args.map(::lower))
        is CtorCall -> CtorCall(node.type, node.args.map(::lower))
        is BinOp -> lowerBinOp(BinOp(node.op, lower(node.l), lower(node.r)))
        is LocalDecl -> LocalDecl(node.name, node.type, lower(node.init), node.mutable)
        is WhenExpr ->
            WhenExpr(
                subject = node.subject?.let(::lower),
                arms = node.arms.map { WhenArm(lower(it.pattern), lower(it.body)) },
                default = node.default?.let(::lower),
            )
        is IfElse -> IfElse(lower(node.cond), lower(node.then), node.els?.let(::lower))
        is Sequence -> lowerSequence(Sequence(node.nodes.map(::lower), node.value?.let(::lower)))
        is Return_ -> Return_(lower(node.expr))
    }

private fun lowerBinOp(b: BinOp): CodeNode {
    val l = b.l
    val r = b.r
    if (l is IntLit && r is IntLit) {
        when (b.op) {
            BinOpKind.Plus -> return IntLit(l.value + r.value)
            BinOpKind.Minus -> return IntLit(l.value - r.value)
            BinOpKind.Times -> return IntLit(l.value * r.value)
            // Comparisons / logicals: no const-fold; emitter rarely produces literal-vs-literal for these.
            else -> Unit
        }
    }
    return b
}

private fun lowerSequence(seq: Sequence): CodeNode {
    val inlined = tryInlineOneLocal(seq)
    if (inlined != null) {
        return lower(inlined)
    }
    return when {
        seq.nodes.isEmpty() && seq.value != null -> seq.value
        seq.nodes.size == 1 && seq.value == null -> seq.nodes.single()
        else -> seq
    }
}

/**
 * Find the first [LocalDecl] in [seq] that is eligible for once-inlining and
 * return a new [Sequence] with the decl removed and its single use replaced by
 * the init expression. Returns `null` if no eligible decl exists.
 */
private fun tryInlineOneLocal(seq: Sequence): Sequence? {
    for ((idx, node) in seq.nodes.withIndex()) {
        if (node !is LocalDecl) continue
        if (node.mutable) continue
        if (!isReferentiallyTransparent(node.init)) continue

        val remainder = seq.nodes.drop(idx + 1) + listOfNotNull(seq.value)
        val useCount = remainder.sumOf { countLocalUses(it, node.name) }
        if (useCount != 1) continue

        val rewrittenAfter =
            seq.nodes.drop(idx + 1).map { substituteLocal(it, node.name, node.init) }
        val rewrittenValue = seq.value?.let { substituteLocal(it, node.name, node.init) }
        val newNodes = seq.nodes.take(idx) + rewrittenAfter
        return Sequence(newNodes, rewrittenValue)
    }
    return null
}

private fun isReferentiallyTransparent(node: CodeNode): Boolean =
    when (node) {
        is IntLit -> true
        is StringLit -> true
        is Local -> true
        is FieldRef -> isReferentiallyTransparent(node.target)
        // Raw / CtorCall are treated as opaque (potentially side-effecting).
        is Raw -> false
        is CtorCall -> false
        is MethodCall, is StaticCall, is BinOp, is LocalDecl, is WhenExpr, is IfElse,
        is Sequence, is Return_,
        -> false
    }

private fun countLocalUses(
    node: CodeNode,
    name: LocalId,
): Int =
    when (node) {
        is IntLit -> 0
        is StringLit -> 0
        is Raw -> 0
        is Local -> if (node.name == name) 1 else 0
        is FieldRef -> countLocalUses(node.target, name)
        is MethodCall ->
            (node.target?.let { countLocalUses(it, name) } ?: 0) +
                node.args.sumOf { countLocalUses(it, name) }
        is StaticCall -> node.args.sumOf { countLocalUses(it, name) }
        is CtorCall -> node.args.sumOf { countLocalUses(it, name) }
        is BinOp -> countLocalUses(node.l, name) + countLocalUses(node.r, name)
        is LocalDecl -> countLocalUses(node.init, name)
        is WhenExpr ->
            (node.subject?.let { countLocalUses(it, name) } ?: 0) +
                node.arms.sumOf { countLocalUses(it.pattern, name) + countLocalUses(it.body, name) } +
                (node.default?.let { countLocalUses(it, name) } ?: 0)
        is IfElse ->
            countLocalUses(node.cond, name) +
                countLocalUses(node.then, name) +
                (node.els?.let { countLocalUses(it, name) } ?: 0)
        is Sequence ->
            node.nodes.sumOf { countLocalUses(it, name) } +
                (node.value?.let { countLocalUses(it, name) } ?: 0)
        is Return_ -> countLocalUses(node.expr, name)
    }

private fun substituteLocal(
    node: CodeNode,
    name: LocalId,
    replacement: CodeNode,
): CodeNode =
    when (node) {
        is IntLit -> node
        is StringLit -> node
        is Raw -> node
        is Local -> if (node.name == name) replacement else node
        is FieldRef -> FieldRef(substituteLocal(node.target, name, replacement), node.field)
        is MethodCall ->
            MethodCall(
                target = node.target?.let { substituteLocal(it, name, replacement) },
                name = node.name,
                args = node.args.map { substituteLocal(it, name, replacement) },
                typeArgs = node.typeArgs,
            )
        is StaticCall ->
            StaticCall(node.target, node.name, node.args.map { substituteLocal(it, name, replacement) })
        is CtorCall ->
            CtorCall(node.type, node.args.map { substituteLocal(it, name, replacement) })
        is BinOp ->
            BinOp(
                op = node.op,
                l = substituteLocal(node.l, name, replacement),
                r = substituteLocal(node.r, name, replacement),
            )
        is LocalDecl ->
            LocalDecl(
                name = node.name,
                type = node.type,
                init = substituteLocal(node.init, name, replacement),
                mutable = node.mutable,
            )
        is WhenExpr ->
            WhenExpr(
                subject = node.subject?.let { substituteLocal(it, name, replacement) },
                arms =
                    node.arms.map {
                        WhenArm(
                            pattern = substituteLocal(it.pattern, name, replacement),
                            body = substituteLocal(it.body, name, replacement),
                        )
                    },
                default = node.default?.let { substituteLocal(it, name, replacement) },
            )
        is IfElse ->
            IfElse(
                cond = substituteLocal(node.cond, name, replacement),
                then = substituteLocal(node.then, name, replacement),
                els = node.els?.let { substituteLocal(it, name, replacement) },
            )
        is Sequence ->
            Sequence(
                nodes = node.nodes.map { substituteLocal(it, name, replacement) },
                value = node.value?.let { substituteLocal(it, name, replacement) },
            )
        is Return_ -> Return_(substituteLocal(node.expr, name, replacement))
    }
