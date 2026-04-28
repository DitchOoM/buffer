package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.codenode.BinOpKind
import com.ditchoom.buffer.codec.processor.codenode.CodeNode
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
import com.ditchoom.buffer.codec.processor.codenode.lower
import com.squareup.kotlinpoet.CodeBlock

/**
 * Render a [CodeNode] tree to a KotlinPoet [CodeBlock].
 *
 * Always passes the input through [lower] first; emitter call sites can build
 * verbose IR (chained `BinOp(Plus, IntLit, IntLit)` accumulators, single-use
 * locals) and rely on the lowering pass to collapse it before rendering.
 */
fun render(node: CodeNode): CodeBlock = renderRaw(lower(node))

private fun renderRaw(node: CodeNode): CodeBlock =
    when (node) {
        is IntLit -> CodeBlock.of("%L", node.value)
        is StringLit -> CodeBlock.of("%S", node.value)
        is Raw -> CodeBlock.of(node.text)
        is Local -> CodeBlock.of("%L", node.name.name)
        is FieldRef -> CodeBlock.of("%L.%L", renderRaw(node.target), node.field)
        is MethodCall -> renderMethodCall(node)
        is StaticCall -> renderStaticCall(node)
        is CtorCall -> renderCtorCall(node)
        is BinOp -> renderBinOp(node)
        is LocalDecl -> renderLocalDecl(node)
        is WhenExpr -> renderWhen(node)
        is IfElse -> renderIfElse(node)
        is Sequence -> renderSequence(node)
        is Return_ -> CodeBlock.of("return %L", renderRaw(node.expr))
    }

private fun renderMethodCall(node: MethodCall): CodeBlock {
    val sb = CodeBlock.builder()
    if (node.target != null) {
        sb.add("%L.", renderRaw(node.target))
    }
    sb.add("%L", node.name)
    if (node.typeArgs.isNotEmpty()) {
        sb.add("<")
        node.typeArgs.forEachIndexed { idx, t ->
            if (idx > 0) sb.add(", ")
            sb.add("%T", t)
        }
        sb.add(">")
    }
    sb.add("(")
    node.args.forEachIndexed { idx, a ->
        if (idx > 0) sb.add(", ")
        sb.add(renderRaw(a))
    }
    sb.add(")")
    return sb.build()
}

private fun renderStaticCall(node: StaticCall): CodeBlock {
    val sb = CodeBlock.builder()
    sb.add("%T.%L(", node.target, node.name)
    node.args.forEachIndexed { idx, a ->
        if (idx > 0) sb.add(", ")
        sb.add(renderRaw(a))
    }
    sb.add(")")
    return sb.build()
}

private fun renderCtorCall(node: CtorCall): CodeBlock {
    val sb = CodeBlock.builder()
    sb.add("%T(", node.type)
    node.args.forEachIndexed { idx, a ->
        if (idx > 0) sb.add(", ")
        sb.add(renderRaw(a))
    }
    sb.add(")")
    return sb.build()
}

private fun renderBinOp(node: BinOp): CodeBlock {
    val opSym =
        when (node.op) {
            BinOpKind.Plus -> "+"
            BinOpKind.Minus -> "-"
            BinOpKind.Times -> "*"
            BinOpKind.Eq -> "=="
            BinOpKind.NotEq -> "!="
            BinOpKind.Gt -> ">"
            BinOpKind.Gte -> ">="
            BinOpKind.Lt -> "<"
            BinOpKind.Lte -> "<="
            BinOpKind.And -> "&&"
            BinOpKind.Or -> "||"
        }
    return CodeBlock.of("%L %L %L", renderRaw(node.l), opSym, renderRaw(node.r))
}

private fun renderLocalDecl(node: LocalDecl): CodeBlock {
    val keyword = if (node.mutable) "var" else "val"
    return if (node.type != null) {
        CodeBlock.of("%L %L: %T = %L", keyword, node.name.name, node.type, renderRaw(node.init))
    } else {
        CodeBlock.of("%L %L = %L", keyword, node.name.name, renderRaw(node.init))
    }
}

private fun renderWhen(node: WhenExpr): CodeBlock {
    val sb = CodeBlock.builder()
    if (node.subject != null) {
        sb.add("when (%L) {\n", renderRaw(node.subject))
    } else {
        sb.add("when {\n")
    }
    sb.indent()
    node.arms.forEach { arm ->
        sb.add("%L -> %L\n", renderRaw(arm.pattern), renderRaw(arm.body))
    }
    if (node.default != null) {
        sb.add("else -> %L\n", renderRaw(node.default))
    }
    sb.unindent()
    sb.add("}")
    return sb.build()
}

private fun renderIfElse(node: IfElse): CodeBlock {
    val sb = CodeBlock.builder()
    sb.add("if (%L) {\n", renderRaw(node.cond))
    sb.indent()
    sb.add("%L\n", renderRaw(node.then))
    sb.unindent()
    if (node.els != null) {
        sb.add("} else {\n")
        sb.indent()
        sb.add("%L\n", renderRaw(node.els))
        sb.unindent()
    }
    sb.add("}")
    return sb.build()
}

private fun renderSequence(node: Sequence): CodeBlock {
    val sb = CodeBlock.builder()
    node.nodes.forEach { stmt ->
        sb.add("%L\n", renderRaw(stmt))
    }
    if (node.value != null) {
        sb.add("%L\n", renderRaw(node.value))
    }
    return sb.build()
}
