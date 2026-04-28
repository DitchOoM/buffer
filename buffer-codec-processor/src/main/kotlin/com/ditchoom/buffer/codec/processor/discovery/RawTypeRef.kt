package com.ditchoom.buffer.codec.processor.discovery

/**
 * A KSP-free reference to a type appearing on a constructor parameter or as a
 * type-parameter upper bound.
 *
 * The discovery layer captures only the structural shape (FQN + type arguments +
 * nullability + whether the reference resolves to an in-scope type parameter) so
 * PhaseB can map it to a `FieldStrategy` without walking `KSType` itself.
 *
 * `fqn` is `kotlin.<TypeParameterName>` for `isTypeParameter = true` references —
 * the [name] field carries the simple type-parameter identifier (`P`, `T`, ...).
 * Unresolved references (typo'd `@UseCodec` codecs, missing imports) capture
 * whatever short name the user wrote with [resolved] = false; PhaseA emits a
 * matching diagnostic.
 */
data class RawTypeRef(
    val fqn: String,
    val name: String,
    val typeArguments: List<RawTypeRef>,
    val isNullable: Boolean,
    val isTypeParameter: Boolean,
    val resolved: Boolean,
)
