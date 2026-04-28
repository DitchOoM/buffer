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
    val valueClassInfo: RawValueClassInfo? = null,
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

/**
 * Phase 9 Step 3 — value-class auto-detection metadata captured by Discovery.
 *
 * Set on [RawClassMetadata.valueClassInfo] when the referenced external class is a
 * `@JvmInline value class` (or `inline class`) wrapping a single primitive constructor
 * parameter. PhaseB's `FieldStrategyBuilder` consults this when an unrecognized field
 * type surfaces — if the type matches a known value class with a primitive inner
 * property, the builder synthesises a [com.ditchoom.buffer.codec.processor.ir.FieldStrategy.ValueClass]
 * around the inner-primitive read/write strategy. Mirrors legacy
 * `FieldAnalyzer.ValueClassField` auto-detection (lines 968-996), threaded through
 * the KSP-decoupled IR pipeline.
 *
 * - [innerTypeFqn] — fully-qualified name of the wrapped primitive (e.g. `kotlin.UByte`,
 *   `kotlin.UInt`). PhaseB uses this to look up the matching `PrimitiveKind`.
 * - [innerPropertyName] — name of the value class's single ctor parameter (e.g. `raw`,
 *   `value`). The encode site reads `value.<fieldName>.<innerPropertyName>` to get the
 *   inner primitive; the decode site wraps the inner read in `<valueClassFqn>(innerValue)`.
 */
data class RawValueClassInfo(
    val innerTypeFqn: String,
    val innerPropertyName: String,
)
