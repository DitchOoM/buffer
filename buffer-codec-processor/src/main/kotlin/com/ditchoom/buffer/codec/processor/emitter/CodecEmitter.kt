package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.VariantPlan
import com.ditchoom.buffer.codec.processor.validator.ValidatedProgram
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec

/**
 * Phase 7 emitter facade.
 *
 * Top-level entry point: takes a [Plan] and the resolved [ClassName] for the
 * user-declared type and produces the codec [FileSpec]. Per-shape emitters
 * (one each for [Plan.Leaf], [Plan.Object_], [Plan.Sealed_]) own the actual
 * code construction; this class is the single dispatcher.
 *
 * In the eventual Phase 7B/8 KSP wiring, callers will iterate
 * [ValidatedProgram.plans] and emit one file per entry. Phase 7 keeps the
 * emitter callable in isolation so unit tests can construct any [Plan]
 * fixture and assert against a pinned snapshot.
 */
class CodecEmitter(
    private val registry: TypeRegistry,
) {
    private val leaf = LeafEmitter(registry)
    private val obj = ObjectEmitter()
    private val sealed = SealedEmitter(registry)
    private val payloadContext = PayloadContextEmitter(registry)

    fun emit(
        plan: Plan,
        classType: ClassName,
    ): FileSpec =
        when (plan) {
            is Plan.Leaf -> leaf.emit(plan, classType)
            is Plan.Object_ -> obj.emit(plan, classType)
            is Plan.Sealed_ -> sealed.emit(plan, classType)
        }

    /**
     * Phase 9 Step 4-redo C2 — files the new pipeline writes alongside the
     * primary codec [FileSpec]. The synthesised payload-receiver `*Context`
     * class is the only supplemental file today; future capabilities can
     * extend without changing the [emit] return shape.
     *
     *  * `Plan.Leaf` with a non-empty `payloadFields` list contributes one
     *    `*Context` file (top-level `@Payload` data class fan-out).
     *  * `Plan.Sealed_` returns no supplementals here. While the dispatcher
     *    is on the new pipeline but variants stay on legacy
     *    `CodecGenerator` (the state in C2/C3/C4), legacy
     *    `PayloadContextGenerator` continues to emit the variant context.
     *    Once Step C6 routes sealed variants through `tryPipeline`, the
     *    variant `*Context` files migrate too — at that point this method
     *    grows to walk `Plan.Sealed_.variants` and contribute one per
     *    `VariantPlan.WithPayload`. (Pre-staged as [emitForVariant] so the
     *    routing change in C6 is mechanical.)
     *  * `Plan.Object_` has no payload metadata.
     */
    fun emitSupplemental(
        plan: Plan,
        @Suppress("UNUSED_PARAMETER") classType: ClassName,
    ): List<FileSpec> =
        when (plan) {
            is Plan.Leaf -> listOfNotNull(payloadContext.emitForLeaf(plan))
            is Plan.Sealed_ -> emptyList()
            is Plan.Object_ -> emptyList()
        }

    /**
     * Direct entry for sealed-variant `*Context` emission. Used after Step C6
     * when `processSealedInterface` routes each variant through `tryPipeline`
     * — the variant emitter calls this to synthesise the context alongside
     * the variant codec. Returns `null` for `NoPayload` variants.
     */
    fun emitForVariant(variant: VariantPlan): FileSpec? =
        when (variant) {
            is VariantPlan.WithPayload -> payloadContext.emitForVariant(variant)
            is VariantPlan.NoPayload -> null
        }

    /** Convenience wrapper for the eventual whole-program path. */
    fun emitAll(program: ValidatedProgram): List<FileSpec> =
        program.plans.entries.map { (fqn, plan) ->
            emit(plan, registry.resolve(fqn))
        }
}
