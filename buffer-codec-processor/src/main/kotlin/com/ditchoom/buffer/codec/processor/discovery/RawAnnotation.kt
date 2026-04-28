package com.ditchoom.buffer.codec.processor.discovery

/**
 * A KSP-free snapshot of an annotation site — FQN of the annotation type plus the
 * argument values keyed by parameter name.
 *
 * Argument values are reduced to a small algebraic type ([RawAnnotationValue]) so
 * PhaseB can pattern-match without re-resolving KSP. Class-reference arguments
 * (e.g. `@DispatchOn(Foo::class)`) are stored as FQN strings; if KSP failed to
 * resolve the referenced class, the FQN is empty and `resolved = false` — PhaseA
 * emits a paired diagnostic.
 */
data class RawAnnotation(
    val fqn: String,
    val arguments: Map<String, RawAnnotationValue>,
)

/** Algebraic representation of a single annotation argument value. */
sealed interface RawAnnotationValue {
    data class IntVal(
        val value: Int,
    ) : RawAnnotationValue

    data class LongVal(
        val value: Long,
    ) : RawAnnotationValue

    data class StringVal(
        val value: String,
    ) : RawAnnotationValue

    data class BoolVal(
        val value: Boolean,
    ) : RawAnnotationValue

    /** Enum constant: e.g. `Endianness.Big`. */
    data class EnumVal(
        val typeFqn: String,
        val name: String,
    ) : RawAnnotationValue

    /** Class reference: `SomeClass::class`. [resolved] = false means KSP could not resolve [fqn]. */
    data class ClassRef(
        val fqn: String,
        val resolved: Boolean,
    ) : RawAnnotationValue

    /** Vararg / array argument. */
    data class ListVal(
        val values: List<RawAnnotationValue>,
    ) : RawAnnotationValue

    /**
     * Fallback — KSP returned a value the discovery layer doesn't recognize. PhaseA
     * captures the `toString()` form so PhaseB can inspect it; tests can match on the
     * raw string when KSP versions disagree on representation.
     */
    data class Unknown(
        val raw: String,
    ) : RawAnnotationValue
}
