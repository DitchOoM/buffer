package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Plan
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

    fun emit(
        plan: Plan,
        classType: ClassName,
    ): FileSpec =
        when (plan) {
            is Plan.Leaf -> leaf.emit(plan, classType)
            is Plan.Object_ -> obj.emit(plan, classType)
            is Plan.Sealed_ -> sealed.emit(plan, classType)
        }

    /** Convenience wrapper for the eventual whole-program path. */
    fun emitAll(program: ValidatedProgram): List<FileSpec> =
        program.plans.entries.map { (fqn, plan) ->
            emit(plan, registry.resolve(fqn))
        }
}
