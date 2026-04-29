package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.RawAnnotation
import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.discovery.RawTypeParameter
import com.ditchoom.buffer.codec.processor.ir.PayloadFieldRef
import com.ditchoom.buffer.codec.processor.ir.PayloadTypeParam
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 9 Step 2 — `Plan.Leaf` carries first-class `@Payload` metadata.
 *
 * These tests pin the IR contract that emitters consume in Cap 2: when a
 * `@ProtocolMessage data class` declares one or more `@Payload`-annotated
 * type parameters, [Plan.Leaf.payloadTypeParams] enumerates them in source
 * order and [Plan.Leaf.payloadFields] enumerates the constructor `val`s
 * whose declared type is one of those type parameters.
 *
 * No emitter changes ride on these fields yet — the assertions are pure IR
 * shape. Cap 2 (Step 4) reads the metadata to drive typed-lambda fan-out.
 */
class PlanBuilderPayloadIRTest {
    @Test
    fun `single Payload type param yields PayloadTypeParam and PayloadFieldRef`() {
        // @ProtocolMessage data class Foo<@Payload P>(val tag: Int, @RemainingBytes val payload: P)
        // Step 4-redo C5: payload fields require a length annotation; @RemainingBytes
        // is the simplest choice for a tail-position payload.
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Foo",
                ctorParameters =
                    listOf(
                        Fixtures.param("tag", Fixtures.primitiveTypeRef("kotlin.Int")),
                        Fixtures.param(
                            "payload",
                            Fixtures.typeParameterRef("P"),
                            annotations = listOf(Fixtures.remainingBytes()),
                        ),
                    ),
                typeParameters = listOf(Fixtures.payloadTypeParam("P")),
            )
        val plan = PlanBuilder.build(symbol).expectRight()
        val leaf = plan as? Plan.Leaf ?: fail("expected Leaf, got $plan")
        assertEquals(
            listOf(PayloadTypeParam(name = "P", upperBound = null)),
            leaf.payloadTypeParams,
            "payloadTypeParams should carry the @Payload type-param declaration",
        )
        assertEquals(
            listOf(
                PayloadFieldRef(
                    fieldName = "payload",
                    typeParamName = "P",
                    contextClassFqn = "test.FooContext",
                ),
            ),
            leaf.payloadFields,
            "payloadFields should reference the ctor val whose type is the @Payload type param, " +
                "and carry the synthesized *Context FQN (legacy convention: " +
                "${'$'}{packageName}.${'$'}{enclosingSimpleNames.joinToString(\"\")}Context).",
        )
    }

    @Test
    fun `multiple Payload type params preserve declaration order`() {
        // @ProtocolMessage data class Multi<@Payload P1, @Payload P2>(
        //   @LengthPrefixed val a: P1, val b: Int, @RemainingBytes val c: P2,
        // )
        // Step 4-redo C5: each payload field needs its own length annotation;
        // an interior payload uses a length prefix while the trailing one uses
        // @RemainingBytes.
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Multi",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "a",
                            Fixtures.typeParameterRef("P1"),
                            annotations = listOf(Fixtures.lengthPrefixed()),
                        ),
                        Fixtures.param("b", Fixtures.primitiveTypeRef("kotlin.Int")),
                        Fixtures.param(
                            "c",
                            Fixtures.typeParameterRef("P2"),
                            annotations = listOf(Fixtures.remainingBytes()),
                        ),
                    ),
                typeParameters =
                    listOf(
                        Fixtures.payloadTypeParam("P1"),
                        Fixtures.payloadTypeParam("P2"),
                    ),
            )
        val plan = PlanBuilder.build(symbol).expectRight()
        val leaf = plan as? Plan.Leaf ?: fail("expected Leaf, got $plan")
        assertEquals(
            listOf(
                PayloadTypeParam(name = "P1", upperBound = null),
                PayloadTypeParam(name = "P2", upperBound = null),
            ),
            leaf.payloadTypeParams,
            "payloadTypeParams should preserve type-parameter declaration order",
        )
        assertEquals(
            listOf(
                PayloadFieldRef(
                    fieldName = "a",
                    typeParamName = "P1",
                    contextClassFqn = "test.MultiContext",
                ),
                PayloadFieldRef(
                    fieldName = "c",
                    typeParamName = "P2",
                    contextClassFqn = "test.MultiContext",
                ),
            ),
            leaf.payloadFields,
            "payloadFields should preserve constructor-parameter order, skipping non-payload params. " +
                "All refs share the variant's single context class FQN.",
        )
    }

    @Test
    fun `non-payload data class has empty payload IR fields`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Plain",
                ctorParameters =
                    listOf(
                        Fixtures.param("a", Fixtures.primitiveTypeRef("kotlin.Int")),
                        Fixtures.param("b", Fixtures.primitiveTypeRef("kotlin.UByte")),
                    ),
            )
        val plan = PlanBuilder.build(symbol).expectRight()
        val leaf = plan as? Plan.Leaf ?: fail("expected Leaf, got $plan")
        assertTrue(leaf.payloadTypeParams.isEmpty(), "non-payload class should have no payloadTypeParams")
        assertTrue(leaf.payloadFields.isEmpty(), "non-payload class should have no payloadFields")
    }

    @Test
    fun `nested @Payload data class derives context FQN from enclosing simple-name chain`() {
        // @ProtocolMessage data class ControlPacketV5.Publish<@Payload P>(@RemainingBytes val payload: P)
        // The synthesized context class for a nested variant must concatenate
        // every enclosing simple name (legacy `enclosingSimpleNames().joinToString("")`)
        // so the receiver type is `ControlPacketV5PublishContext`, not the
        // bare-name `PublishContext` that would clash with sibling variants.
        val symbol =
            RawSymbol.DataLike(
                fqn = "com.example.ControlPacketV5.Publish",
                simpleName = "Publish",
                packageName = "com.example",
                enclosingNames = listOf("ControlPacketV5", "Publish"),
                annotations =
                    listOf(
                        RawAnnotation(AnnotationFqns.ProtocolMessage, emptyMap()),
                    ),
                direction = com.ditchoom.buffer.codec.processor.discovery.RawDirection.Default,
                classKind = com.ditchoom.buffer.codec.processor.discovery.DataLikeKind.DataClass,
                typeParameters = listOf(Fixtures.payloadTypeParam("P")),
                constructorParameters =
                    listOf(
                        Fixtures.param(
                            "payload",
                            Fixtures.typeParameterRef("P"),
                            annotations = listOf(Fixtures.remainingBytes()),
                        ),
                    ),
            )
        val plan = PlanBuilder.build(symbol).expectRight()
        val leaf = plan as? Plan.Leaf ?: fail("expected Leaf, got $plan")
        assertEquals(
            "com.example.ControlPacketV5PublishContext",
            leaf.payloadFields.single().contextClassFqn,
            "nested variant context FQN must concatenate every enclosing simple name " +
                "(legacy convention) — bare `PublishContext` would clash with siblings.",
        )
    }

    @Test
    fun `payload field without length annotation surfaces requires-a-length-annotation error`() {
        // Phase 9 Step 4-redo C5 — port the legacy diagnostic.
        // Mirrors DataClassCodegenTest > "payload field without length annotation causes error".
        // @ProtocolMessage data class BadPayload<@Payload P>(val id: UShort, val data: P)
        val symbol =
            Fixtures.dataLike(
                fqn = "test.BadPayload",
                ctorParameters =
                    listOf(
                        Fixtures.param("id", Fixtures.primitiveTypeRef("kotlin.UShort")),
                        Fixtures.param("data", Fixtures.typeParameterRef("P")),
                    ),
                typeParameters = listOf(Fixtures.payloadTypeParam("P")),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        val msg =
            errors.all.firstOrNull { it.message.contains("requires a length annotation") }?.message
                ?: fail("expected length-annotation error; got: ${errors.all.map { it.message }}")
        assertTrue("data" in msg, "diagnostic should name the offending field; got '$msg'")
        assertTrue(
            "@LengthPrefixed" in msg && "@RemainingBytes" in msg && "@LengthFrom" in msg,
            "diagnostic should enumerate the three options the user can add; got '$msg'",
        )
    }

    @Test
    fun `payload field with @RemainingBytes is accepted`() {
        // @ProtocolMessage data class TrailingPayload<@Payload P>(val id: UShort, @RemainingBytes val data: P)
        val symbol =
            Fixtures.dataLike(
                fqn = "test.TrailingPayload",
                ctorParameters =
                    listOf(
                        Fixtures.param("id", Fixtures.primitiveTypeRef("kotlin.UShort")),
                        Fixtures.param(
                            "data",
                            Fixtures.typeParameterRef("P"),
                            annotations = listOf(Fixtures.remainingBytes()),
                        ),
                    ),
                typeParameters = listOf(Fixtures.payloadTypeParam("P")),
            )
        PlanBuilder.build(symbol).expectRight()
    }

    @Test
    fun `Payload type param with upper bound carries the bound FQN`() {
        // @ProtocolMessage data class Bounded<@Payload P : Any>(val tag: Int, @RemainingBytes val payload: P)
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Bounded",
                ctorParameters =
                    listOf(
                        Fixtures.param("tag", Fixtures.primitiveTypeRef("kotlin.Int")),
                        Fixtures.param(
                            "payload",
                            Fixtures.typeParameterRef("P"),
                            annotations = listOf(Fixtures.remainingBytes()),
                        ),
                    ),
                typeParameters =
                    listOf(
                        RawTypeParameter(
                            name = "P",
                            upperBoundFqn = "kotlin.Any",
                            annotations = listOf(RawAnnotation(AnnotationFqns.Payload, emptyMap())),
                        ),
                    ),
            )
        val plan = PlanBuilder.build(symbol).expectRight()
        val leaf = plan as? Plan.Leaf ?: fail("expected Leaf, got $plan")
        assertEquals(
            listOf(PayloadTypeParam(name = "P", upperBound = TypeFqn("kotlin.Any"))),
            leaf.payloadTypeParams,
        )
    }
}
