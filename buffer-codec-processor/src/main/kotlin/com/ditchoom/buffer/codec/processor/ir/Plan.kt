package com.ditchoom.buffer.codec.processor.ir

/**
 * Top-level Plan IR for one user-declared protocol type.
 *
 * Each variant carries only the fields its shape needs — mutually exclusive shapes
 * are mutually exclusive types, eliminating the nullable / defaulted-empty fields
 * the legacy `DispatchOnInfo` carried.
 */
sealed interface Plan {
    val decl: TypeFqn
    val dir: Direction

    /**
     * Non-sealed, non-singleton message — fixed/variable-width fields decoded into a primary ctor.
     *
     * [payloadTypeParams] / [payloadFields] mirror the shape on
     * [com.ditchoom.buffer.codec.processor.ir.VariantPlan.WithPayload]: when non-empty,
     * the leaf declares one or more `@Payload`-annotated type parameters and the fields
     * that consume them. Emitters use these to fan out typed-lambda overloads (Cap 2).
     * Defaulted to empty so non-payload classes don't need to opt in.
     */
    data class Leaf(
        override val decl: TypeFqn,
        val fields: List<FieldPlan>,
        val batches: List<Batch>,
        override val dir: Direction,
        val payloadTypeParams: List<PayloadTypeParam> = emptyList(),
        val payloadFields: List<PayloadFieldRef> = emptyList(),
    ) : Plan

    /** Singleton — `object` / `data object`. No fields, no variants by construction. */
    @Suppress("ktlint:standard:class-naming")
    data class Object_(
        override val decl: TypeFqn,
        override val dir: Direction,
    ) : Plan

    /** Sealed root that dispatches to variants on a discriminator. */
    @Suppress("ktlint:standard:class-naming")
    data class Sealed_(
        override val decl: TypeFqn,
        val variants: List<VariantPlan>,
        val dispatch: DispatchShape,
        override val dir: Direction,
        val onUnknown: TypeFqn,
    ) : Plan
}

/** Direction the codec generates code for. The IR carries no `Infer`; absence of markers is resolved at PhaseB. */
enum class Direction { Bidirectional, DecodeOnly, EncodeOnly }

/** Fully-qualified name of a user-declared type. Pure-Kotlin neutral reference (KSP / KotlinPoet free). */
@JvmInline
value class TypeFqn(
    val canonical: String,
) {
    init {
        require(canonical.isNotBlank()) { "TypeFqn must not be blank" }
    }

    override fun toString(): String = canonical
}
