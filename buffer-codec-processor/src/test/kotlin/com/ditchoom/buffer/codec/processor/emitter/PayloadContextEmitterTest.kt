package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.Endianness
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.LengthSource
import com.ditchoom.buffer.codec.processor.ir.PayloadFieldRef
import com.ditchoom.buffer.codec.processor.ir.PayloadTypeParam
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.ir.VariantPlan
import com.ditchoom.buffer.codec.processor.ir.WireMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 9 Step 4-redo C2 — pin the synthesised `*Context` class shape.
 *
 * The receiver-style typed-lambda decode overload (`*Context.(ReadBuffer) -> P`)
 * needs the context class to compile. These tests pin the shape of what the
 * new pipeline emits so future receiver-style emit work in C3 / C4 binds
 * against a stable surface.
 */
class PayloadContextEmitterTest {
    private val emitter = PayloadContextEmitter(EmitterFixtures.standardRegistry())

    @Test
    fun `Leaf with no payload fields emits nothing`() {
        val plan =
            Plan.Leaf(
                decl = EmitterFixtures.fqn("PingResponse"),
                fields =
                    listOf(
                        FieldPlan(
                            "tag",
                            TypeFqn("kotlin.UByte"),
                            FieldStrategy.Primitive(PrimitiveKind.UByte, 1, Endianness.Big),
                        ),
                    ),
                batches = emptyList(),
                dir = Direction.Bidirectional,
            )
        assertNull(emitter.emitForLeaf(plan), "no @Payload fields → no synthesised context file")
    }

    @Test
    fun `Leaf with payload field and one non-payload field emits a data class`() {
        // data class GrpcFrame<@Payload P>(val kind: UByte, val payload: P)
        val plan =
            Plan.Leaf(
                decl = TypeFqn("com.example.GrpcFrame"),
                fields =
                    listOf(
                        FieldPlan(
                            "kind",
                            TypeFqn("kotlin.UByte"),
                            FieldStrategy.Primitive(PrimitiveKind.UByte, 1, Endianness.Big),
                        ),
                        FieldPlan(
                            "payload",
                            TypeFqn("kotlin.P"),
                            FieldStrategy.PayloadSlot(
                                typeParam = "P",
                                length = LengthSource.Remaining(trailingBytes = 0),
                            ),
                        ),
                    ),
                batches = emptyList(),
                dir = Direction.Bidirectional,
                payloadTypeParams = listOf(PayloadTypeParam("P", null)),
                payloadFields =
                    listOf(
                        PayloadFieldRef(
                            fieldName = "payload",
                            typeParamName = "P",
                            contextClassFqn = "com.example.GrpcFrameContext",
                        ),
                    ),
            )
        val file = assertNotNull(emitter.emitForLeaf(plan))
        assertEquals("com.example", file.packageName)
        assertEquals("GrpcFrameContext", file.name)
        val text = file.toString()
        val unescaped = text.replace("`", "")
        assertTrue(
            "data class GrpcFrameContext(" in unescaped,
            "expected synthesised data class header; got:\n$text",
        )
        assertTrue(
            "val kind: UByte" in unescaped,
            "expected `val kind: UByte` property carrying the non-payload sibling field; got:\n$text",
        )
        assertTrue(
            "payload" !in unescaped,
            "synthesised context must not include the @Payload field; got:\n$text",
        )
    }

    @Test
    fun `Leaf where every field is @Payload emits an object singleton`() {
        // data class AllPayload<@Payload P>(val payload: P)
        val plan =
            Plan.Leaf(
                decl = TypeFqn("com.example.AllPayload"),
                fields =
                    listOf(
                        FieldPlan(
                            "payload",
                            TypeFqn("kotlin.P"),
                            FieldStrategy.PayloadSlot(
                                typeParam = "P",
                                length = LengthSource.Remaining(trailingBytes = 0),
                            ),
                        ),
                    ),
                batches = emptyList(),
                dir = Direction.Bidirectional,
                payloadTypeParams = listOf(PayloadTypeParam("P", null)),
                payloadFields =
                    listOf(
                        PayloadFieldRef(
                            fieldName = "payload",
                            typeParamName = "P",
                            contextClassFqn = "com.example.AllPayloadContext",
                        ),
                    ),
            )
        val file = assertNotNull(emitter.emitForLeaf(plan))
        val text = file.toString()
        assertTrue(
            "object AllPayloadContext" in text,
            "expected `object AllPayloadContext` (singleton form when no non-payload fields); got:\n$text",
        )
    }

    @Test
    fun `WithPayload variant emits context resolving to its own contextClassFqn`() {
        // sealed ControlPacketV5 { data class Publish<@Payload P>(val header: MqttFixedHeader, val payload: P) }
        // Expected context: ControlPacketV5PublishContext(val header: MqttFixedHeader)
        val variant =
            VariantPlan.WithPayload(
                decl = EmitterFixtures.fqn("ControlPacketV5.Publish"),
                codec = EmitterFixtures.codecCn("ControlPacketV5Publish"),
                wire = WireMatch.Point(EmitterFixtures.fqn("ControlPacketV5.Publish"), 3),
                selfEncodes = false,
                dir = Direction.Bidirectional,
                fields =
                    listOf(
                        FieldPlan(
                            "header",
                            EmitterFixtures.fqn("MqttFixedHeader"),
                            FieldStrategy.DiscriminatorOwned(
                                parentDispatchOn = EmitterFixtures.fqn("MqttFixedHeader"),
                                sealedRootFqn = EmitterFixtures.fqn("ControlPacketV5"),
                            ),
                        ),
                    ),
                typeParams = listOf(PayloadTypeParam("P", null)),
                payloadFields =
                    listOf(
                        PayloadFieldRef(
                            fieldName = "payload",
                            typeParamName = "P",
                            contextClassFqn = "com.ditchoom.codec.test.ControlPacketV5PublishContext",
                        ),
                    ),
            )
        val file = assertNotNull(emitter.emitForVariant(variant))
        assertEquals("com.ditchoom.codec.test", file.packageName)
        assertEquals("ControlPacketV5PublishContext", file.name)
        val text = file.toString()
        assertTrue(
            "data class ControlPacketV5PublishContext" in text,
            "expected variant context to be a data class; got:\n$text",
        )
        // Header type resolves through the registry — it's the value class
        // wrapping a UByte, NOT the inner primitive. Receiver-style decode
        // lets the user read `this.header.packetType` to make payload-shape
        // decisions. KotlinPoet may escape `header` with backticks when
        // emitting properties; strip them for the assertion.
        val unescaped = text.replace("`", "")
        assertTrue(
            "val header: MqttFixedHeader" in unescaped,
            "expected `val header: MqttFixedHeader` property (value class, not the inner UByte); got:\n$text",
        )
    }

    @Test
    fun `NoPayload variant has no context to emit`() {
        val variant =
            VariantPlan.NoPayload(
                decl = EmitterFixtures.fqn("ControlPacketV5.PingReq"),
                codec = EmitterFixtures.codecCn("ControlPacketV5PingReq"),
                wire = WireMatch.Point(EmitterFixtures.fqn("ControlPacketV5.PingReq"), 12),
                selfEncodes = false,
                dir = Direction.Bidirectional,
                fields = emptyList(),
            )
        assertNull(
            CodecEmitter(EmitterFixtures.standardRegistry()).emitForVariant(variant),
            "NoPayload variants do not synthesise a context class",
        )
    }
}
