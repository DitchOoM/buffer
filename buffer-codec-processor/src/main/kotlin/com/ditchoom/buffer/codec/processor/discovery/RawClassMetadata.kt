package com.ditchoom.buffer.codec.processor.discovery

/**
 * KSP-free snapshot of a class declaration's directly-declared supertype list.
 *
 * Discovery captures one of these for any class that is referenced from a
 * `@DispatchOn(framing = X::class)` or `@UseCodec(codec = X::class)` argument,
 * so PhaseC's framer / codec validators can pattern-match supertype parameter
 * bindings without dropping back into KSP.
 *
 * Critically, [directlyDeclaredSupertypes] holds only the supertypes the class
 * literally declares — never `getAllSuperTypes()`. Walking transitive parents
 * via KSP returns unresolved type variables on indirectly-inherited supertypes
 * (the previous BodyLengthFraming attempt was broken by exactly this), so the
 * directly-declared list is the only resolvable surface PhaseC can rely on.
 */
data class RawClassMetadata(
    val fqn: String,
    val directlyDeclaredSupertypes: List<RawTypeRef>,
)
