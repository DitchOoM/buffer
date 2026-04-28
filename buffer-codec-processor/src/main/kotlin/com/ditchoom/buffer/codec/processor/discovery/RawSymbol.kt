package com.ditchoom.buffer.codec.processor.discovery

/**
 * A pure-Kotlin snapshot of one user-declared symbol that PhaseA hands to PhaseB.
 *
 * Carries every fact the downstream phases need without leaking any KSP type into
 * its public surface — the discovery package owns the only file that imports KSP
 * (`Discovery.kt`); every other file in this package is host-language only. That
 * boundary makes PhaseB / PhaseC unit-testable from plain Kotlin without spinning
 * up KSP fixtures, and it pins the contract that Phase4 will consume.
 *
 * Variants mirror the structural categories the processor already distinguishes
 * (data / value class with a primary ctor, singleton object, sealed root) so PhaseB
 * can branch by type rather than by sniffing nullable fields.
 */
sealed interface RawSymbol {
    /** Fully-qualified canonical name of the declaration. */
    val fqn: String

    /** Simple name of the declaration (the leaf segment of [fqn]). */
    val simpleName: String

    /** Package the declaration lives in (may be empty for top-level no-package classes). */
    val packageName: String

    /** Simple-name chain from the outermost enclosing class down to this declaration. */
    val enclosingNames: List<String>

    /** Annotations declared directly on the class. */
    val annotations: List<RawAnnotation>

    /**
     * `@Decode` / `@Encode` markers and the legacy `@ProtocolMessage(direction = ...)`
     * value collapsed to a single signal. PhaseB reconciles direction across fields.
     */
    val direction: RawDirection

    /**
     * `@ProtocolMessage` data class, regular class, or value class with a primary
     * constructor. Carries the constructor parameters so PhaseB can build per-field
     * IR without re-walking KSP.
     */
    data class DataLike(
        override val fqn: String,
        override val simpleName: String,
        override val packageName: String,
        override val enclosingNames: List<String>,
        override val annotations: List<RawAnnotation>,
        override val direction: RawDirection,
        val classKind: DataLikeKind,
        val typeParameters: List<RawTypeParameter>,
        val constructorParameters: List<RawCtorParameter>,
    ) : RawSymbol

    /**
     * `@ProtocolMessage` `object` (or `data object`). No constructor parameters by
     * construction; type parameters are forbidden (PhaseA enforces and emits a diagnostic).
     */
    data class ObjectSymbol(
        override val fqn: String,
        override val simpleName: String,
        override val packageName: String,
        override val enclosingNames: List<String>,
        override val annotations: List<RawAnnotation>,
        override val direction: RawDirection,
    ) : RawSymbol

    /**
     * `@ProtocolMessage` sealed interface or sealed class. [subclassFqns] is the list
     * of declared sealed children resolved via `KSClassDeclaration.getSealedSubclasses()`.
     * PhaseB joins each FQN against the discovered symbol list to build variant plans.
     */
    data class SealedRoot(
        override val fqn: String,
        override val simpleName: String,
        override val packageName: String,
        override val enclosingNames: List<String>,
        override val annotations: List<RawAnnotation>,
        override val direction: RawDirection,
        val subclassFqns: List<String>,
        val typeParameters: List<RawTypeParameter>,
    ) : RawSymbol
}

/** Concrete shape of a `RawSymbol.DataLike`. */
enum class DataLikeKind {
    /** Declared with the `data` modifier. */
    DataClass,

    /** Declared with the `value` (or `@JvmInline value`) modifier. */
    ValueClass,

    /** Plain class — neither `data` nor `value`. Still has a primary constructor. */
    RegularClass,
}

/**
 * Class-level direction signal collapsed across the new `@Decode` / `@Encode` markers
 * and the legacy `@ProtocolMessage(direction = ...)` parameter.
 *
 * `Conflict` records "both `@Decode` and `@Encode` were applied" so PhaseB can surface a
 * dedicated error rather than silently picking one — the IR itself never carries `Conflict`.
 */
enum class RawDirection {
    /** No explicit class-level direction signal — PhaseB infers from fields. */
    Default,

    /** Class-level `@Decode` marker present (or legacy `direction = DecodeOnly`). */
    DecodeOnly,

    /** Class-level `@Encode` marker present (or legacy `direction = EncodeOnly`). */
    EncodeOnly,

    /** Legacy `@ProtocolMessage(direction = Codec)` — assert bidirectional in PhaseB. */
    Codec,

    /** Both `@Decode` and `@Encode` were applied — PhaseB rejects with a dedicated error. */
    Conflict,
}

/** A type-parameter declaration on a class (e.g. `class Foo<@Payload P>`). */
data class RawTypeParameter(
    val name: String,
    val upperBoundFqn: String?,
    val annotations: List<RawAnnotation>,
)

/** One `val` parameter on the primary constructor of a `RawSymbol.DataLike`. */
data class RawCtorParameter(
    val name: String,
    val typeRef: RawTypeRef,
    val annotations: List<RawAnnotation>,
    val hasDefault: Boolean,
)
