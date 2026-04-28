package com.ditchoom.buffer.codec.processor.ir

import com.squareup.kotlinpoet.ClassName

/**
 * One variant of a `Plan.Sealed_`.
 *
 * `selfEncodes = true` means the variant is responsible for emitting its own discriminator
 * bytes during encode (used by `WireMatch.Range` shapes where the byte is partially carried
 * inside a discriminator data-class field).
 */
sealed interface VariantPlan {
    val decl: TypeFqn
    val codec: ClassName
    val wire: WireMatch
    val selfEncodes: Boolean
    val dir: Direction
    val fields: List<FieldPlan>

    /** Variant whose constructor takes no payload type parameter. */
    data class NoPayload(
        override val decl: TypeFqn,
        override val codec: ClassName,
        override val wire: WireMatch,
        override val selfEncodes: Boolean,
        override val dir: Direction,
        override val fields: List<FieldPlan>,
    ) : VariantPlan

    /**
     * Variant carrying one or more `@Payload` type parameters and the field references that consume them.
     * Construction requires non-empty `typeParams` — invariant from the plan IR.
     */
    data class WithPayload(
        override val decl: TypeFqn,
        override val codec: ClassName,
        override val wire: WireMatch,
        override val selfEncodes: Boolean,
        override val dir: Direction,
        override val fields: List<FieldPlan>,
        val typeParams: List<PayloadTypeParam>,
        val payloadFields: List<PayloadFieldRef>,
    ) : VariantPlan {
        init {
            require(typeParams.isNotEmpty()) {
                "VariantPlan.WithPayload requires non-empty typeParams"
            }
        }
    }
}

/** A `@Payload`-marked type parameter declared on a sealed variant. */
data class PayloadTypeParam(
    val name: String,
    val upperBound: TypeFqn?,
)

/** A field on a variant whose declared type is a payload type parameter. */
data class PayloadFieldRef(
    val fieldName: String,
    val typeParamName: String,
)
