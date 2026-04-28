package com.ditchoom.buffer.codec.processor.codenode

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName

/**
 * Structured emit-time IR for the codec generator.
 *
 * Today's emitter mixes string `addStatement` calls with format-specifier `%T` / `%L`
 * mini-DSL. CodeNode replaces that with typed shapes so the lowering pass and the
 * Phase 7 renderer can reason structurally; tests assert input → output equality on
 * the IR rather than regex over emitted text.
 *
 * Pure-Kotlin: no KSP types, no rendering (KotlinPoet `CodeBlock` is Phase 7's job).
 * `TypeName` / `ClassName` from KotlinPoet leak in only as type-level references —
 * by-value descriptors of the user's type universe — never as resolved code blocks.
 */
sealed interface CodeNode {
    // -----------------------------------------------------------------------------
    // Expressions
    // -----------------------------------------------------------------------------

    /** Integer literal — folded by [lower] when feasible. */
    data class IntLit(
        val value: Int,
    ) : CodeNode

    /**
     * String literal — rendered as a Kotlin double-quoted string.
     *
     * Phase 7 addition: needed to emit error messages such as
     * `throw IllegalArgumentException("Unknown discriminator: ...")`. The renderer
     * passes the value through KotlinPoet's `%S` format specifier so escape
     * handling stays correct without us re-implementing string escaping.
     */
    data class StringLit(
        val value: String,
    ) : CodeNode

    /** Reference to a previously-declared local. */
    data class Local(
        val name: LocalId,
    ) : CodeNode

    /**
     * Free-form text escape — rendered verbatim into the output `CodeBlock`.
     *
     * Phase 7 addition: a small escape hatch for fragments the structured IR does
     * not yet model (e.g. `"0x${rawByte.toString(16)}"` interpolated string
     * fragments) without requiring a new node per case. The lowering pass treats
     * `Raw` as opaque — a [Raw] is **not** referentially transparent, so a
     * once-used local initialised by `Raw(...)` is never auto-inlined. Use only
     * when the structured nodes can't carry the intent.
     */
    data class Raw(
        val text: String,
    ) : CodeNode

    /**
     * Constructor invocation: `Type(arg0, arg1, …)`.
     *
     * Phase 7 addition: distinct from [StaticCall] because constructors render
     * without a member-access dot (`MqttFixedHeader(raw)` not `MqttFixedHeader.MqttFixedHeader(raw)`)
     * and because validation of constructor argument count is meaningful enough
     * to deserve its own node. The lowering pass treats a `CtorCall` as
     * non-transparent — a constructor may allocate, so a once-used local whose
     * init is a `CtorCall` is never auto-inlined.
     */
    data class CtorCall(
        val type: ClassName,
        val args: List<CodeNode>,
    ) : CodeNode

    /** Property access on [target], i.e. `target.field`. */
    data class FieldRef(
        val target: CodeNode,
        val field: String,
    ) : CodeNode

    /**
     * Method call on [target] (or a free function call when `target == null`),
     * i.e. `target.name<typeArgs>(args)`.
     *
     * Method calls are NOT referentially transparent — the lowering pass refuses to
     * inline a single-use local whose init is a MethodCall, even if that local is
     * referenced exactly once.
     */
    data class MethodCall(
        val target: CodeNode?,
        val name: String,
        val args: List<CodeNode>,
        val typeArgs: List<TypeName>,
    ) : CodeNode

    /** Static-target invocation: `Target.name(args)`. */
    data class StaticCall(
        val target: ClassName,
        val name: String,
        val args: List<CodeNode>,
    ) : CodeNode

    /** Binary operator over two sub-expressions. */
    data class BinOp(
        val op: BinOpKind,
        val l: CodeNode,
        val r: CodeNode,
    ) : CodeNode

    // -----------------------------------------------------------------------------
    // Statements
    // -----------------------------------------------------------------------------

    /**
     * Local-variable declaration. `mutable = true` forbids inlining regardless of
     * the init's transparency or use count.
     */
    data class LocalDecl(
        val name: LocalId,
        val type: TypeName?,
        val init: CodeNode,
        val mutable: Boolean,
    ) : CodeNode

    /**
     * `when` expression. `subject == null` encodes the conditional form
     * (`when { cond -> body … }`); a non-null subject encodes the value form
     * (`when (subject) { value -> body … }`).
     */
    data class WhenExpr(
        val subject: CodeNode?,
        val arms: List<WhenArm>,
        val default: CodeNode?,
    ) : CodeNode

    /** `if (cond) then else els` — `els == null` encodes the statement-form `if`. */
    data class IfElse(
        val cond: CodeNode,
        val then: CodeNode,
        val els: CodeNode?,
    ) : CodeNode

    /**
     * Block of [nodes] followed by an optional terminal expression [value].
     * `Sequence(nodes = [a], value = null)` and `Sequence(nodes = [], value = a)`
     * both lower to `a` (single-statement unwrap rule).
     */
    data class Sequence(
        val nodes: List<CodeNode>,
        val value: CodeNode?,
    ) : CodeNode

    /** `return expr`. */
    @Suppress("ktlint:standard:class-naming")
    data class Return_(
        val expr: CodeNode,
    ) : CodeNode
}

/** Identifier of a `LocalDecl` — kept as a value class so a stray String can't masquerade as a local. */
@JvmInline
value class LocalId(
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "LocalId must not be blank" }
    }

    override fun toString(): String = name
}

/**
 * Binary-operator opcodes the IR supports.
 *
 * Phase 6 introduced [Plus], [Minus], [Times] for size arithmetic + const-fold.
 * Phase 7 added the comparison and logical opcodes used by emitted `when`-arm
 * conditions (`type == 4`, `rawByte in 48..63`, `buffer.remaining() >= 1`).
 * Const-folding is only defined for arithmetic at present; the renderer
 * formats comparison/logical opcodes positionally without folding.
 */
enum class BinOpKind {
    Plus,
    Minus,
    Times,
    Eq,
    NotEq,
    Gt,
    Gte,
    Lt,
    Lte,
    And,
    Or,
}

/**
 * One arm of a [CodeNode.WhenExpr]. For the subject form, [pattern] is the value
 * being matched (typically an [CodeNode.IntLit]); for the subjectless form,
 * [pattern] is a boolean condition (typically a [CodeNode.BinOp]).
 */
data class WhenArm(
    val pattern: CodeNode,
    val body: CodeNode,
)
