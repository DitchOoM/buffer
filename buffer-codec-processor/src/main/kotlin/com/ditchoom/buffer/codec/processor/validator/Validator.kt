package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.discovery.RawClassMetadata
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.Either
import com.ditchoom.buffer.codec.processor.planbuilder.KspError
import com.ditchoom.buffer.codec.processor.planbuilder.Nel
import com.ditchoom.buffer.codec.processor.planbuilder.left
import com.ditchoom.buffer.codec.processor.planbuilder.right

/**
 * PhaseC — whole-program validator. Pure function from
 * `Map<TypeFqn, Plan>` (+ [externalClasses] metadata) to a [ValidatedProgram] success
 * marker, accumulating every cross-program rule violation into a single [Nel] of
 * [KspError]s.
 *
 * One checker per rule from the validator-rules table:
 *
 *  - [RangeDisjointnessChecker] — wire-byte intervals across `Sealed_.variants` may not overlap.
 *  - [DirectionReconciler] — variants of one sealed root must agree on direction with the root.
 *  - [WhenPathResolver] — every `BooleanExpression.FieldRef` resolves against a real field path,
 *    and the diagnostic on failure carries the available paths the user could have meant.
 *  - [CycleDetector] — DFS over plan-to-plan field references catches recursive `@ProtocolMessage`s
 *    before PhaseD generates an infinite-recursion codec.
 *  - [FramerTypeMatcher] — `@DispatchOn(framing = X::class)` references a class whose directly-declared
 *    supertypes contain `DispatchFraming<D>` (or `BodyLengthFraming<D>`) where D matches the
 *    discriminator. Reads only directly-declared supertypes — KSP's all-supertypes walker
 *    returns unresolved type variables on transitive parents and is banned in this package.
 *  - [UseCodecConformanceChecker] — every `@UseCodec(codec = X::class)` resolves to a class whose
 *    directly-declared supertypes include `Codec<T>` / `Encoder<T>` / `Decoder<T>` for the field's
 *    declared type.
 *  - [DiscriminatorFieldTypeChecker] — re-asserts at whole-program scope that every variant's
 *    `@DiscriminatorField` parameter type matches its sealed root's `@DispatchOn` discriminator.
 *  - [SpiDescriptorChecker] — rejects ambiguous SPI descriptors (no fixedSize, no inline raw payload).
 *
 * Errors accumulate; the validator never bails on the first failure. A fixture
 * with N independent violations produces an Either.Left whose [Nel] has at least N entries.
 */
object Validator {
    fun validate(
        plans: Map<TypeFqn, Plan>,
        externalClasses: Map<String, RawClassMetadata> = emptyMap(),
    ): Either<Nel<KspError>, ValidatedProgram> {
        val errors = mutableListOf<KspError>()
        errors += RangeDisjointnessChecker.check(plans)
        errors += DirectionReconciler.check(plans)
        errors += WhenPathResolver.check(plans)
        errors += CycleDetector.check(plans)
        errors += FramerTypeMatcher.check(plans, externalClasses)
        errors += UseCodecConformanceChecker.check(plans, externalClasses)
        errors += DiscriminatorFieldTypeChecker.check(plans)
        errors += SpiDescriptorChecker.check(plans)
        return if (errors.isEmpty()) {
            ValidatedProgram(plans).right()
        } else {
            Nel.fromList(errors.toList()).left()
        }
    }
}
