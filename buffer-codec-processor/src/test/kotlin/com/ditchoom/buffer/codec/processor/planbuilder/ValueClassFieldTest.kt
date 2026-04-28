package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.RawClassMetadata
import com.ditchoom.buffer.codec.processor.discovery.RawValueClassInfo
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Phase 9 Step 3 — value-class auto-detect in [FieldStrategyBuilder].
 *
 * Pins the new pipeline's behaviour for `@JvmInline value class` wrappers around
 * primitive ctor parameters: PhaseB synthesises a [FieldStrategy.ValueClass] without
 * requiring an explicit `@UseCodec`, mirroring legacy `FieldAnalyzer.ValueClassField`.
 */
class ValueClassFieldTest {
    @Test
    fun `value class wrapping UInt produces FieldStrategy_ValueClass with inner Primitive`() {
        val wrapperFqn = "test.SomeValueClass"
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Wrapper",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "x",
                            Fixtures.nestedMessageRef(wrapperFqn),
                        ),
                    ),
            )
        val externalClasses =
            mapOf(
                wrapperFqn to
                    RawClassMetadata(
                        fqn = wrapperFqn,
                        directlyDeclaredSupertypes = emptyList(),
                        valueClassInfo =
                            RawValueClassInfo(
                                innerTypeFqn = "kotlin.UInt",
                                innerPropertyName = "raw",
                            ),
                    ),
            )
        val plan = PlanBuilder.build(symbol, mapOf(symbol.fqn to symbol), externalClasses).expectRight()
        val leaf = plan as? Plan.Leaf ?: fail("expected Leaf, got $plan")
        assertEquals(1, leaf.fields.size)
        val vc = leaf.fields[0].strategy as? FieldStrategy.ValueClass
            ?: fail("expected ValueClass strategy, got ${leaf.fields[0].strategy}")
        assertEquals(wrapperFqn, vc.valueClassFqn.canonical)
        assertEquals("raw", vc.innerPropertyName)
        val inner = vc.inner as? FieldStrategy.Primitive
            ?: fail("expected inner Primitive, got ${vc.inner}")
        assertEquals(PrimitiveKind.UInt, inner.kind)
        assertEquals(4, inner.wireBytes)
    }

    @Test
    fun `value class wrapping UByte produces single-byte primitive`() {
        val wrapperFqn = "test.Tag"
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Carrier",
                ctorParameters =
                    listOf(
                        Fixtures.param("tag", Fixtures.nestedMessageRef(wrapperFqn)),
                    ),
            )
        val externalClasses =
            mapOf(
                wrapperFqn to
                    RawClassMetadata(
                        fqn = wrapperFqn,
                        directlyDeclaredSupertypes = emptyList(),
                        valueClassInfo =
                            RawValueClassInfo(
                                innerTypeFqn = "kotlin.UByte",
                                innerPropertyName = "value",
                            ),
                    ),
            )
        val plan = PlanBuilder.build(symbol, mapOf(symbol.fqn to symbol), externalClasses).expectRight()
        val leaf = plan as? Plan.Leaf ?: fail("expected Leaf, got $plan")
        val vc = leaf.fields[0].strategy as FieldStrategy.ValueClass
        val inner = vc.inner as FieldStrategy.Primitive
        assertEquals(PrimitiveKind.UByte, inner.kind)
        assertEquals(1, inner.wireBytes)
        assertEquals("value", vc.innerPropertyName)
    }

    @Test
    fun `value class wrapping non-primitive falls into a clear error`() {
        val wrapperFqn = "test.Weird"
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Carrier",
                ctorParameters =
                    listOf(
                        Fixtures.param("x", Fixtures.nestedMessageRef(wrapperFqn)),
                    ),
            )
        val externalClasses =
            mapOf(
                wrapperFqn to
                    RawClassMetadata(
                        fqn = wrapperFqn,
                        directlyDeclaredSupertypes = emptyList(),
                        valueClassInfo =
                            RawValueClassInfo(
                                innerTypeFqn = "kotlin.String",
                                innerPropertyName = "raw",
                            ),
                    ),
            )
        val errors = PlanBuilder.build(symbol, mapOf(symbol.fqn to symbol), externalClasses).expectLeft()
        val msg = errors.head.message
        assert("not a supported primitive" in msg) {
            "expected non-primitive value-class diagnostic; got '$msg'"
        }
    }

    @Test
    fun `unrecognized type without value-class metadata still errors as before`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Carrier",
                ctorParameters =
                    listOf(
                        Fixtures.param("x", Fixtures.nestedMessageRef("test.Unknown")),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        val msg = errors.head.message
        assert("not a recognized" in msg) {
            "expected legacy unrecognized-type diagnostic; got '$msg'"
        }
    }
}
