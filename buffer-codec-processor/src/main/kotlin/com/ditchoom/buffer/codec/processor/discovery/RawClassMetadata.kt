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
 *
 * [dispatchValueProperty] is a Slice 5.5 addition: when a `@DispatchOn(D::class)`
 * discriminator class declares a property annotated `@DispatchValue` (e.g. a
 * derived getter `val packetType: Int get() = (raw.toInt() shr 4) and 0x0F`),
 * PhaseA captures the property name + return-type FQN here so PhaseB's
 * `DiscriminatorBuilder` can prefer it over the synthesised inner-property name.
 * Mirrors legacy `ProtocolMessageProcessor.resolveDispatchOn` walking
 * `discriminatorClass.getAllProperties()`.
 */
data class RawClassMetadata(
    val fqn: String,
    val directlyDeclaredSupertypes: List<RawTypeRef>,
    val dispatchValueProperty: RawDispatchValueProperty? = null,
)

/**
 * A `@DispatchValue`-annotated property captured off a discriminator class.
 *
 * For value-class discriminators the property may be a derived getter (e.g.
 * `val packetType: Int get() = (raw.toInt() shr 4) and 0x0F`); the legacy
 * resolver detects it via `KSClassDeclaration.getAllProperties().filter { it
 * .annotations.any { ... DispatchValue } }`. PhaseA replicates that walk and
 * stores the result here so PhaseB doesn't have to re-resolve KSP.
 */
data class RawDispatchValueProperty(
    val name: String,
    val returnTypeFqn: String,
)
