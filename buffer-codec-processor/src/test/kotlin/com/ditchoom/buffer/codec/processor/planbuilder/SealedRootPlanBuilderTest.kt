package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.ir.DiscriminatorShape
import com.ditchoom.buffer.codec.processor.ir.DispatchShape
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import com.ditchoom.buffer.codec.processor.ir.VariantPlan
import com.ditchoom.buffer.codec.processor.ir.WireMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Sealed-root + variant rules for PhaseB.
 *
 * Covers:
 *  - Plain @PacketType-only dispatch (no @DispatchOn) → DispatchShape.RawByte
 *  - @DispatchOn value-class dispatch → TypedDiscriminator + ValueClass
 *  - @PacketTypeRange requires @DiscriminatorField on the variant
 *  - @PacketType + @PacketTypeRange mutex on a variant
 *  - @DiscriminatorField field type matches parent's @DispatchOn
 *  - Variant outside discriminator's natural range → bound-check error
 */
class SealedRootPlanBuilderTest {
    @Test
    fun `sealed root with raw-byte dispatch and packet-type variants builds`() {
        val a =
            Fixtures.dataLike(
                fqn = "test.Cmd.A",
                ctorParameters = listOf(Fixtures.param("v", Fixtures.primitiveTypeRef("kotlin.Int"))),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.packetType(1)),
            )
        val b = Fixtures.objectSymbol("test.Cmd.B", annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.packetType(2)))
        val root = Fixtures.sealedRoot(fqn = "test.Cmd", subclassFqns = listOf(a.fqn, b.fqn))
        val scope = mapOf(root.fqn to root, a.fqn to a as RawSymbol, b.fqn to b)
        val plan = PlanBuilder.build(root, scope).expectRight()
        val sealed = plan as? Plan.Sealed_ ?: fail("expected Sealed_, got $plan")
        assertEquals(DispatchShape.RawByte, sealed.dispatch)
        assertEquals(2, sealed.variants.size)
        val firstWire = (sealed.variants[0].wire as WireMatch.Point).wire
        val secondWire = (sealed.variants[1].wire as WireMatch.Point).wire
        assertEquals(setOf(1, 2), setOf(firstWire, secondWire))
    }

    @Test
    fun `sealed root with DispatchOn value class produces TypedDiscriminator`() {
        val header =
            Fixtures.dataLike(
                fqn = "test.Header",
                ctorParameters = listOf(Fixtures.param("raw", Fixtures.primitiveTypeRef("kotlin.UByte"))),
                kind = com.ditchoom.buffer.codec.processor.discovery.DataLikeKind.ValueClass,
            )
        val variant =
            Fixtures.dataLike(
                fqn = "test.Cmd.A",
                ctorParameters = listOf(Fixtures.param("v", Fixtures.primitiveTypeRef("kotlin.Int"))),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.packetType(1)),
            )
        val root =
            Fixtures.sealedRoot(
                fqn = "test.Cmd",
                subclassFqns = listOf(variant.fqn),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.dispatchOn("test.Header")),
            )
        val scope = mapOf(root.fqn to root, variant.fqn to variant as RawSymbol, header.fqn to header as RawSymbol)
        val plan = PlanBuilder.build(root, scope).expectRight() as Plan.Sealed_
        val td = plan.dispatch as? DispatchShape.TypedDiscriminator ?: fail("expected TypedDiscriminator")
        val vc = td.disc as? DiscriminatorShape.ValueClass ?: fail("expected ValueClass")
        assertEquals(PrimitiveKind.UByte, vc.inner)
        assertEquals("raw", vc.innerProp)
    }

    @Test
    fun `PacketType plus PacketTypeRange on the same variant is rejected`() {
        val variant =
            Fixtures.dataLike(
                fqn = "test.Cmd.Both",
                ctorParameters = listOf(Fixtures.param("v", Fixtures.primitiveTypeRef("kotlin.Int"))),
                annotations =
                    listOf(
                        Fixtures.protocolMessageAnnotation(),
                        Fixtures.packetType(1),
                        Fixtures.packetTypeRange(0, 15),
                    ),
            )
        val root =
            Fixtures.sealedRoot(
                fqn = "test.Cmd",
                subclassFqns = listOf(variant.fqn),
            )
        val scope = mapOf(root.fqn to root as RawSymbol, variant.fqn to variant as RawSymbol)
        val errors = PlanBuilder.build(root, scope).expectLeft()
        assertTrue(
            errors.all.any { "@PacketType" in it.message && "@PacketTypeRange" in it.message },
            "expected mutex error naming both annotations; got: ${errors.all.map { it.message }}",
        )
    }

    @Test
    fun `PacketTypeRange without DiscriminatorField field is rejected`() {
        val header =
            Fixtures.dataLike(
                fqn = "test.Hdr",
                ctorParameters = listOf(Fixtures.param("raw", Fixtures.primitiveTypeRef("kotlin.UByte"))),
                kind = com.ditchoom.buffer.codec.processor.discovery.DataLikeKind.ValueClass,
            )
        val variant =
            Fixtures.dataLike(
                fqn = "test.Cmd.Pub",
                ctorParameters = listOf(Fixtures.param("v", Fixtures.primitiveTypeRef("kotlin.Int"))),
                annotations =
                    listOf(
                        Fixtures.protocolMessageAnnotation(),
                        Fixtures.packetTypeRange(0x30, 0x3F),
                    ),
            )
        val root =
            Fixtures.sealedRoot(
                fqn = "test.Cmd",
                subclassFqns = listOf(variant.fqn),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.dispatchOn("test.Hdr")),
            )
        val scope = mapOf(root.fqn to root as RawSymbol, variant.fqn to variant as RawSymbol, header.fqn to header as RawSymbol)
        val errors = PlanBuilder.build(root, scope).expectLeft()
        val msg = errors.all.firstOrNull { "@DiscriminatorField" in it.message }?.message
        assertTrue(msg != null, "expected missing-@DiscriminatorField error; got: ${errors.all.map { it.message }}")
    }

    @Test
    fun `PacketTypeRange with DiscriminatorField field of mismatched type is rejected`() {
        val header =
            Fixtures.dataLike(
                fqn = "test.Hdr",
                ctorParameters = listOf(Fixtures.param("raw", Fixtures.primitiveTypeRef("kotlin.UByte"))),
                kind = com.ditchoom.buffer.codec.processor.discovery.DataLikeKind.ValueClass,
            )
        val variant =
            Fixtures.dataLike(
                fqn = "test.Cmd.Pub",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "header",
                            Fixtures.nestedMessageRef("test.OtherHdr"),
                            annotations = listOf(Fixtures.discriminatorField()),
                        ),
                    ),
                annotations =
                    listOf(
                        Fixtures.protocolMessageAnnotation(),
                        Fixtures.packetTypeRange(0x30, 0x3F),
                    ),
            )
        val root =
            Fixtures.sealedRoot(
                fqn = "test.Cmd",
                subclassFqns = listOf(variant.fqn),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.dispatchOn("test.Hdr")),
            )
        val scope = mapOf(root.fqn to root as RawSymbol, variant.fqn to variant as RawSymbol, header.fqn to header as RawSymbol)
        val errors = PlanBuilder.build(root, scope).expectLeft()
        val msg = errors.all.firstOrNull { "must match" in it.message }?.message
        assertTrue(msg != null, "expected DiscriminatorField type-mismatch error; got: ${errors.all.map { it.message }}")
    }

    @Test
    fun `PacketType with wire byte outside discriminator's natural range is rejected`() {
        val header =
            Fixtures.dataLike(
                fqn = "test.Hdr",
                ctorParameters = listOf(Fixtures.param("raw", Fixtures.primitiveTypeRef("kotlin.UByte"))),
                kind = com.ditchoom.buffer.codec.processor.discovery.DataLikeKind.ValueClass,
            )
        val variant =
            Fixtures.dataLike(
                fqn = "test.Cmd.OutOfRange",
                ctorParameters = listOf(Fixtures.param("v", Fixtures.primitiveTypeRef("kotlin.Int"))),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.packetType(0x1FF)),
            )
        val root =
            Fixtures.sealedRoot(
                fqn = "test.Cmd",
                subclassFqns = listOf(variant.fqn),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.dispatchOn("test.Hdr")),
            )
        val scope = mapOf(root.fqn to root as RawSymbol, variant.fqn to variant as RawSymbol, header.fqn to header as RawSymbol)
        val errors = PlanBuilder.build(root, scope).expectLeft()
        val msg = errors.all.firstOrNull { "outside the discriminator's range" in it.message }?.message
        assertTrue(msg != null, "expected bound-check error; got: ${errors.all.map { it.message }}")
    }

    @Test
    fun `Variant lacking PacketType and PacketTypeRange is rejected`() {
        val variant =
            Fixtures.dataLike(
                fqn = "test.Cmd.NoMatch",
                ctorParameters = listOf(Fixtures.param("v", Fixtures.primitiveTypeRef("kotlin.Int"))),
            )
        val root = Fixtures.sealedRoot(fqn = "test.Cmd", subclassFqns = listOf(variant.fqn))
        val scope = mapOf(root.fqn to root as RawSymbol, variant.fqn to variant as RawSymbol)
        val errors = PlanBuilder.build(root, scope).expectLeft()
        assertTrue(
            errors.all.any { "neither @PacketType nor @PacketTypeRange" in it.message },
            "expected unmatched-variant error; got: ${errors.all.map { it.message }}",
        )
    }

    @Test
    fun `Variant whose subclass FQN is missing from scope reports a discovery gap`() {
        val root =
            Fixtures.sealedRoot(
                fqn = "test.Cmd",
                subclassFqns = listOf("test.Cmd.Missing"),
            )
        val errors = PlanBuilder.build(root, mapOf(root.fqn to root)).expectLeft()
        assertTrue(
            errors.all.any {
                "not discovered" in it.message ||
                    "not be discovered" in it.message ||
                    "was not discovered" in it.message
            },
            "expected scope-gap diagnostic; got: ${errors.all.map { it.message }}",
        )
    }

    /**
     * Slice 5a — auto-detect rule: a variant constructor parameter whose type FQN
     * matches the parent's `@DispatchOn(D::class)` discriminator type is treated as
     * `FieldStrategy.DiscriminatorOwned` even when the parameter does NOT carry an
     * explicit `@DiscriminatorField` annotation. Mirrors legacy `FieldAnalyzer` rule.
     */
    @Test
    fun `Variant with header field typed as parent dispatch type auto-detects DiscriminatorOwned`() {
        val header =
            Fixtures.dataLike(
                fqn = "test.Hdr",
                ctorParameters = listOf(Fixtures.param("raw", Fixtures.primitiveTypeRef("kotlin.UByte"))),
                kind = com.ditchoom.buffer.codec.processor.discovery.DataLikeKind.ValueClass,
            )
        val variant =
            Fixtures.dataLike(
                fqn = "test.Cmd.Pub",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "header",
                            Fixtures.nestedMessageRef("test.Hdr"),
                            // No @DiscriminatorField annotation — relies on the auto-detect rule.
                            annotations = emptyList(),
                        ),
                    ),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.packetType(0x30)),
            )
        val root =
            Fixtures.sealedRoot(
                fqn = "test.Cmd",
                subclassFqns = listOf(variant.fqn),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.dispatchOn("test.Hdr")),
            )
        val scope = mapOf(root.fqn to root as RawSymbol, variant.fqn to variant as RawSymbol, header.fqn to header as RawSymbol)
        val plan = PlanBuilder.build(root, scope).expectRight() as Plan.Sealed_
        val pub = plan.variants.single() as VariantPlan.NoPayload
        val headerField = pub.fields.single()
        val strat = headerField.strategy as FieldStrategy.DiscriminatorOwned
        assertEquals("test.Hdr", strat.parentDispatchOn.canonical)
    }

    @Test
    fun `Variant with @DiscriminatorField field matching parent discriminator builds with DiscriminatorOwned strategy`() {
        val header =
            Fixtures.dataLike(
                fqn = "test.Hdr",
                ctorParameters = listOf(Fixtures.param("raw", Fixtures.primitiveTypeRef("kotlin.UByte"))),
                kind = com.ditchoom.buffer.codec.processor.discovery.DataLikeKind.ValueClass,
            )
        val variant =
            Fixtures.dataLike(
                fqn = "test.Cmd.Pub",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "header",
                            Fixtures.nestedMessageRef("test.Hdr"),
                            annotations = listOf(Fixtures.discriminatorField()),
                        ),
                    ),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.packetTypeRange(0x30, 0x3F)),
            )
        val root =
            Fixtures.sealedRoot(
                fqn = "test.Cmd",
                subclassFqns = listOf(variant.fqn),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.dispatchOn("test.Hdr")),
            )
        val scope = mapOf(root.fqn to root as RawSymbol, variant.fqn to variant as RawSymbol, header.fqn to header as RawSymbol)
        val plan = PlanBuilder.build(root, scope).expectRight() as Plan.Sealed_
        val pub = plan.variants.single() as VariantPlan.NoPayload
        assertTrue(pub.selfEncodes, "@PacketTypeRange variants always self-encode the discriminator")
        val headerField = pub.fields.single()
        val strat = headerField.strategy as FieldStrategy.DiscriminatorOwned
        assertEquals("test.Hdr", strat.parentDispatchOn.canonical)
    }

    @Test
    fun `Sealed root with onUnknownDiscriminator string is captured`() {
        val variant =
            Fixtures.dataLike(
                fqn = "test.Cmd.A",
                ctorParameters = listOf(Fixtures.param("v", Fixtures.primitiveTypeRef("kotlin.Int"))),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.packetType(1)),
            )
        val root =
            Fixtures.sealedRoot(
                fqn = "test.Cmd",
                subclassFqns = listOf(variant.fqn),
                annotations = listOf(Fixtures.protocolMessageAnnotation(onUnknownDiscriminator = "ext.MyError")),
            )
        val scope = mapOf(root.fqn to root as RawSymbol, variant.fqn to variant as RawSymbol)
        val plan = PlanBuilder.build(root, scope).expectRight() as Plan.Sealed_
        assertEquals("ext.MyError", plan.onUnknown.canonical)
    }
}
