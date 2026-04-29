package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Conditionality
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.LengthSource
import com.squareup.kotlinpoet.CodeBlock

/**
 * Determines the body of `wireSize(value, context)` from the IR according to the
 * "wireSize codegen rules" in the rearchitecture plan:
 *
 *  - All fields fixed-width → const literal: `wireSize(value): Int = N`.
 *  - Fixed prefix + one variable suffix → expression-fold: `wireSize(value): Int
 *    = N + value.field.remaining()`.
 *  - Two-or-more unconditional variables OR any conditional → accumulator:
 *    `var size = N; size += …; return size`.
 *
 * The decision is structural over the field list — the emitter does not need to
 * inspect Conditional or VarInt strategies beyond a kind check.
 */
internal object WireSizeEmitter {
    sealed interface Plan {
        /** All fields contribute a known constant byte count. */
        data class ConstLiteral(
            val totalBytes: Int,
        ) : Plan

        /** Fixed prefix N bytes + one variable-length suffix expression. */
        data class FixedPlusOneVariable(
            val prefixBytes: Int,
            val variableExpr: CodeBlock,
        ) : Plan

        /** Generic accumulator: each contribution is an expression added to a `var size`. */
        data class Accumulator(
            val contributions: List<CodeBlock>,
        ) : Plan
    }

    /**
     * Decide which plan applies for [fields].
     *
     * [contributionFor] turns one field plan into the byte-count contribution it
     * makes to `wireSize` — typically a `CodeBlock` that resolves to an `Int`
     * (e.g. `value.body.remaining()`). [fixedSizeOf] returns the fixed byte
     * count for a fixed-width strategy or `-1` for a variable-width one.
     */
    fun choose(
        fields: List<FieldPlan>,
        fixedSizeOf: (FieldPlan) -> Int,
        contributionFor: (FieldPlan) -> CodeBlock,
    ): Plan {
        val anyConditional = fields.any { it.conditionality !is Conditionality.Always }
        val variableCount = fields.count { fixedSizeOf(it) < 0 && it.conditionality is Conditionality.Always }

        if (!anyConditional && variableCount == 0) {
            // All fixed-width.
            val total = fields.sumOf { fixedSizeOf(it).coerceAtLeast(0) }
            return Plan.ConstLiteral(total)
        }
        if (!anyConditional && variableCount == 1) {
            val prefix = fields.filter { fixedSizeOf(it) >= 0 }.sumOf { fixedSizeOf(it) }
            val variableField = fields.first { fixedSizeOf(it) < 0 }
            return Plan.FixedPlusOneVariable(prefix, contributionFor(variableField))
        }
        // Accumulator path. Always-fixed fields collapse to a single literal at
        // the head; everything else contributes one expression each.
        val literalHead =
            fields
                .filter { it.conditionality is Conditionality.Always && fixedSizeOf(it) >= 0 }
                .sumOf { fixedSizeOf(it) }
        val parts = mutableListOf<CodeBlock>()
        if (literalHead > 0) parts += CodeBlock.of("%L", literalHead)
        for (f in fields) {
            if (f.conditionality is Conditionality.Always && fixedSizeOf(f) >= 0) continue
            parts += contributionFor(f)
        }
        return Plan.Accumulator(parts)
    }

    /** Default IR-aware fixed-size determination for a field strategy. */
    fun defaultFixedSizeOf(field: FieldPlan): Int {
        if (field.conditionality !is Conditionality.Always) return -1
        return when (val s = field.strategy) {
            is FieldStrategy.Primitive -> s.wireBytes
            is FieldStrategy.VarInt -> -1
            is FieldStrategy.StringField -> -1
            is FieldStrategy.Collection_ -> -1
            is FieldStrategy.NestedMessage -> -1
            is FieldStrategy.External -> -1
            // Step 4-redo C6: variant writes the discriminator itself
            // (mirrors legacy behavior — see LeafEmitter encode site for
            // DiscriminatorOwned). The emitter expresses the contribution
            // as `${codec}.wireSize(value.<field>, context)`, so flag it
            // as variable-size so it flows through `contributionFor`.
            is FieldStrategy.DiscriminatorOwned -> -1
            is FieldStrategy.PayloadSlot ->
                when (val l = s.length) {
                    is LengthSource.Inline -> -1
                    is LengthSource.FromField -> -1
                    is LengthSource.Remaining -> -1
                }
            is FieldStrategy.Spi -> if (s.descriptor.fixedSize >= 0) s.descriptor.fixedSize else -1
            // Phase 9 Step 3: a value-class field's fixed size mirrors the inner
            // strategy's fixed size — for primitives, that's `wireBytes`. The wrapper
            // adds zero overhead on the wire.
            is FieldStrategy.ValueClass -> {
                val inner = s.inner
                if (inner is FieldStrategy.Primitive) inner.wireBytes else -1
            }
        }
    }
}
