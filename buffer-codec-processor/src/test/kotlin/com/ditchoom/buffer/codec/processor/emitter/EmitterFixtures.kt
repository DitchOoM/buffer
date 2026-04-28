package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Batch
import com.ditchoom.buffer.codec.processor.ir.BatchExtraction
import com.ditchoom.buffer.codec.processor.ir.Conditionality
import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.DiscParam
import com.ditchoom.buffer.codec.processor.ir.DiscriminatorShape
import com.ditchoom.buffer.codec.processor.ir.DispatchShape
import com.ditchoom.buffer.codec.processor.ir.Endianness
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.FramingMode
import com.ditchoom.buffer.codec.processor.ir.LengthSource
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.ir.VariantPlan
import com.ditchoom.buffer.codec.processor.ir.WireMatch
import com.squareup.kotlinpoet.ClassName

/**
 * Shared `Plan` constructors for emitter snapshot + structural tests.
 *
 * Each fixture is named for the protocol it pins to and constructs the IR by
 * hand so the emitter is exercised end-to-end without involving the
 * Discovery / PlanBuilder / Validator pipeline. Resolution to KotlinPoet
 * `ClassName` happens via a [TypeRegistry] built at the call site, since the
 * Plan IR is KSP-free.
 */
object EmitterFixtures {
    private const val PKG = "com.ditchoom.codec.test"

    fun fqn(simple: String): TypeFqn = TypeFqn("$PKG.$simple")

    fun cn(simple: String): ClassName = ClassName(PKG, simple)

    fun codecCn(simple: String): ClassName = ClassName(PKG, simple + "Codec")

    /**
     * Shape 1 — fixed-width primary ctor.
     * `MqttFixedHeader(raw: UByte)`.
     */
    fun mqttFixedHeader(): Plan.Leaf =
        Plan.Leaf(
            decl = fqn("MqttFixedHeader"),
            fields =
                listOf(
                    FieldPlan(
                        name = "raw",
                        type = TypeFqn("kotlin.UByte"),
                        strategy = FieldStrategy.Primitive(PrimitiveKind.UByte, 1, Endianness.Big),
                    ),
                ),
            batches = emptyList(),
            dir = Direction.Bidirectional,
        )

    /**
     * Shape 2 — fixed prefix + tail buffer slice.
     * `GrpcFrame(compressed: UByte, length: UInt, body: ReadBuffer)`.
     */
    fun grpcFrame(): Plan.Leaf =
        Plan.Leaf(
            decl = fqn("GrpcFrame"),
            fields =
                listOf(
                    FieldPlan("compressed", TypeFqn("kotlin.UByte"), FieldStrategy.Primitive(PrimitiveKind.UByte, 1, Endianness.Big)),
                    FieldPlan("length", TypeFqn("kotlin.UInt"), FieldStrategy.Primitive(PrimitiveKind.UInt, 4, Endianness.Big)),
                    FieldPlan(
                        "body",
                        TypeFqn("com.ditchoom.buffer.ReadBuffer"),
                        FieldStrategy.PayloadSlot("P", LengthSource.FromField("length", PrimitiveKind.UInt)),
                    ),
                ),
            batches = emptyList(),
            dir = Direction.Bidirectional,
        )

    /**
     * Shape 4 — batched bit-extraction.
     * `MqttConnectFlags` packs 6 boolean / 2-bit fields into a single byte.
     */
    fun mqttConnectFlags(): Plan.Leaf =
        Plan.Leaf(
            decl = fqn("MqttConnectFlags"),
            fields =
                listOf(
                    FieldPlan("cleanSession", TypeFqn("kotlin.Boolean"), FieldStrategy.Primitive(PrimitiveKind.Bool, 0, Endianness.Big)),
                    FieldPlan("willFlag", TypeFqn("kotlin.Boolean"), FieldStrategy.Primitive(PrimitiveKind.Bool, 0, Endianness.Big)),
                    FieldPlan("willQos", TypeFqn("kotlin.UByte"), FieldStrategy.Primitive(PrimitiveKind.UByte, 0, Endianness.Big)),
                    FieldPlan("willRetain", TypeFqn("kotlin.Boolean"), FieldStrategy.Primitive(PrimitiveKind.Bool, 0, Endianness.Big)),
                    FieldPlan("hasPassword", TypeFqn("kotlin.Boolean"), FieldStrategy.Primitive(PrimitiveKind.Bool, 0, Endianness.Big)),
                    FieldPlan("hasUserName", TypeFqn("kotlin.Boolean"), FieldStrategy.Primitive(PrimitiveKind.Bool, 0, Endianness.Big)),
                ),
            batches =
                listOf(
                    Batch(
                        sourceField = "raw",
                        widthBytes = 1,
                        extractions =
                            listOf(
                                BatchExtraction("cleanSession", 0x02, 0),
                                BatchExtraction("willFlag", 0x04, 0),
                                BatchExtraction("willQos", 0x03, 3),
                                BatchExtraction("willRetain", 0x20, 0),
                                BatchExtraction("hasPassword", 0x40, 0),
                                BatchExtraction("hasUserName", 0x80, 0),
                            ),
                    ),
                ),
            dir = Direction.Bidirectional,
        )

    /**
     * Shape 5 — `Plan.Object_` singleton.
     * `data object PingResponse`.
     */
    fun pingResponse(): Plan.Object_ =
        Plan.Object_(
            decl = fqn("PingResponse"),
            dir = Direction.Bidirectional,
        )

    /**
     * Shape 6 — `Plan.Sealed_` Unframed (RIFF chunk).
     * Discriminator: `RiffChunkId(id: UInt)`.
     */
    fun riffChunk(): Plan.Sealed_ {
        val discType = fqn("RiffChunkId")
        val discCodec = codecCn("RiffChunkId")
        return Plan.Sealed_(
            decl = fqn("RiffChunk"),
            variants =
                listOf(
                    VariantPlan.WithPayload(
                        decl = fqn("RiffChunk.Data"),
                        codec = codecCn("RiffChunkData"),
                        wire = WireMatch.Point(fqn("RiffChunk.Data"), 1684108385),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                        typeParams =
                            listOf(
                                com.ditchoom.buffer.codec.processor.ir
                                    .PayloadTypeParam("P", null),
                            ),
                        payloadFields = emptyList(),
                    ),
                    VariantPlan.NoPayload(
                        decl = fqn("RiffChunk.Fact"),
                        codec = codecCn("RiffChunkFact"),
                        wire = WireMatch.Point(fqn("RiffChunk.Fact"), 1717658484),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                    VariantPlan.NoPayload(
                        decl = fqn("RiffChunk.Fmt"),
                        codec = codecCn("RiffChunkFmt"),
                        wire = WireMatch.Point(fqn("RiffChunk.Fmt"), 1718449184),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                ),
            dispatch =
                DispatchShape.TypedDiscriminator(
                    disc =
                        DiscriminatorShape.ValueClass(
                            discriminatorType = discType,
                            inner = PrimitiveKind.UInt,
                            innerProp = "raw",
                            codec = discCodec,
                            dispatchProp = "id",
                            wireRange = 0..Int.MAX_VALUE,
                        ),
                    framing = FramingMode.Unframed,
                ),
            dir = Direction.Bidirectional,
            onUnknown = TypeFqn("kotlin.IllegalArgumentException"),
        )
    }

    /**
     * Shape 7 — `Plan.Sealed_` PeekOnly (WebSocket frame).
     * Discriminator: `WsFrameHeader` data class with `byte1.opcode`.
     */
    fun wsFrame(): Plan.Sealed_ {
        val discType = fqn("WsFrameHeader")
        val discCodec = codecCn("WsFrameHeader")
        return Plan.Sealed_(
            decl = fqn("WsFrame"),
            variants =
                listOf(
                    VariantPlan.NoPayload(
                        decl = fqn("WsFrame.Text"),
                        codec = codecCn("WsFrameText"),
                        wire = WireMatch.Point(fqn("WsFrame.Text"), 0x01),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                    VariantPlan.NoPayload(
                        decl = fqn("WsFrame.Binary"),
                        codec = codecCn("WsFrameBinary"),
                        wire = WireMatch.Point(fqn("WsFrame.Binary"), 0x02),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                    VariantPlan.NoPayload(
                        decl = fqn("WsFrame.Close"),
                        codec = codecCn("WsFrameClose"),
                        wire = WireMatch.Point(fqn("WsFrame.Close"), 0x08),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                ),
            dispatch =
                DispatchShape.TypedDiscriminator(
                    disc =
                        DiscriminatorShape.DataClass(
                            discriminatorType = discType,
                            params =
                                listOf(
                                    DiscParam("byte1", PrimitiveKind.UByte, 1),
                                    DiscParam("byte2", PrimitiveKind.UByte, 1),
                                ),
                            codec = discCodec,
                            dispatchProp = "byte1.opcode",
                        ),
                    framing = FramingMode.PeekOnly(framerFqn = ClassName("com.ditchoom.codec.test", "WsFraming")),
                ),
            dir = Direction.Bidirectional,
            onUnknown = TypeFqn("kotlin.IllegalArgumentException"),
        )
    }

    /**
     * Shape 8 — `Plan.Sealed_` BodyLength (MQTT v5 control packet).
     * Discriminator: `MqttFixedHeader(raw: UByte)`.
     */
    fun controlPacketV5(): Plan.Sealed_ {
        val discType = fqn("MqttFixedHeader")
        val discCodec = codecCn("MqttFixedHeader")
        return Plan.Sealed_(
            decl = fqn("ControlPacketV5"),
            variants =
                listOf(
                    VariantPlan.WithPayload(
                        decl = fqn("ControlPacketV5.Publish"),
                        codec = codecCn("ControlPacketV5Publish"),
                        wire = WireMatch.Range(fqn("ControlPacketV5.Publish"), 48, 63),
                        selfEncodes = true,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                        typeParams =
                            listOf(
                                com.ditchoom.buffer.codec.processor.ir
                                    .PayloadTypeParam("P", null),
                            ),
                        payloadFields = emptyList(),
                    ),
                    VariantPlan.NoPayload(
                        decl = fqn("ControlPacketV5.PubAck"),
                        codec = codecCn("ControlPacketV5PubAck"),
                        wire = WireMatch.Point(fqn("ControlPacketV5.PubAck"), 4),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields =
                            listOf(
                                FieldPlan(
                                    "header",
                                    discType,
                                    FieldStrategy.DiscriminatorOwned(parentDispatchOn = fqn("ControlPacketV5")),
                                ),
                            ),
                    ),
                    VariantPlan.NoPayload(
                        decl = fqn("ControlPacketV5.PingReq"),
                        codec = codecCn("ControlPacketV5PingReq"),
                        wire = WireMatch.Point(fqn("ControlPacketV5.PingReq"), 12),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                ),
            dispatch =
                DispatchShape.TypedDiscriminator(
                    disc =
                        DiscriminatorShape.ValueClass(
                            discriminatorType = discType,
                            inner = PrimitiveKind.UByte,
                            innerProp = "raw",
                            codec = discCodec,
                            dispatchProp = "packetType",
                            wireRange = 0..255,
                        ),
                    framing =
                        FramingMode.BodyLength(
                            framerFqn = ClassName("com.ditchoom.codec.test", "MqttFixedHeader"),
                            discriminatorBytes = 1,
                        ),
                ),
            dir = Direction.Bidirectional,
            onUnknown = TypeFqn("kotlin.IllegalArgumentException"),
        )
    }

    /**
     * Shape — `Plan.Leaf` conditional fields (MQTT v5 PubAck).
     */
    fun mqttPubAck(): Plan.Leaf =
        Plan.Leaf(
            decl = fqn("ControlPacketV5.PubAck"),
            fields =
                listOf(
                    FieldPlan(
                        "header",
                        fqn("MqttFixedHeader"),
                        FieldStrategy.DiscriminatorOwned(parentDispatchOn = fqn("ControlPacketV5")),
                    ),
                    FieldPlan(
                        "packetIdentifier",
                        TypeFqn("kotlin.UShort"),
                        FieldStrategy.Primitive(PrimitiveKind.UShort, 2, Endianness.Big),
                    ),
                    FieldPlan(
                        "reasonCode",
                        TypeFqn("kotlin.UByte"),
                        FieldStrategy.Primitive(PrimitiveKind.UByte, 1, Endianness.Big),
                        conditionality =
                            Conditionality.WhenExpr(
                                expr =
                                    com.ditchoom.buffer.codec.processor.ir.BooleanExpression
                                        .RemainingGte(1),
                            ),
                    ),
                ),
            batches = emptyList(),
            dir = Direction.Bidirectional,
        )

    /**
     * Shape — `MessagePackFormatByte` (Sealed_ Unframed with Range arms).
     */
    fun messagePackFormatByte(): Plan.Sealed_ {
        val discType = fqn("MessagePackByte")
        val discCodec = codecCn("MessagePackByte")
        return Plan.Sealed_(
            decl = fqn("MessagePackFormatByte"),
            variants =
                listOf(
                    VariantPlan.NoPayload(
                        decl = fqn("MessagePackFormatByte.PositiveFixInt"),
                        codec = codecCn("MessagePackFormatBytePositiveFixInt"),
                        wire = WireMatch.Range(fqn("MessagePackFormatByte.PositiveFixInt"), 0, 127),
                        selfEncodes = true,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                    VariantPlan.NoPayload(
                        decl = fqn("MessagePackFormatByte.Nil"),
                        codec = codecCn("MessagePackFormatByteNil"),
                        wire = WireMatch.Point(fqn("MessagePackFormatByte.Nil"), 192),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                ),
            dispatch =
                DispatchShape.TypedDiscriminator(
                    disc =
                        DiscriminatorShape.ValueClass(
                            discriminatorType = discType,
                            inner = PrimitiveKind.UByte,
                            innerProp = "raw",
                            codec = discCodec,
                            dispatchProp = "rawValue",
                            wireRange = 0..255,
                        ),
                    framing = FramingMode.Unframed,
                ),
            dir = Direction.Bidirectional,
            onUnknown = TypeFqn("kotlin.IllegalArgumentException"),
        )
    }

    /**
     * Shape — TLS record (Sealed_ Unframed with payload variants).
     */
    fun tlsRecord(): Plan.Sealed_ {
        val discType = fqn("TlsContentType")
        val discCodec = codecCn("TlsContentType")
        return Plan.Sealed_(
            decl = fqn("TlsRecord"),
            variants =
                listOf(
                    VariantPlan.NoPayload(
                        decl = fqn("TlsRecord.ChangeCipherSpec"),
                        codec = codecCn("TlsRecordChangeCipherSpec"),
                        wire = WireMatch.Point(fqn("TlsRecord.ChangeCipherSpec"), 20),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                    VariantPlan.NoPayload(
                        decl = fqn("TlsRecord.Alert"),
                        codec = codecCn("TlsRecordAlert"),
                        wire = WireMatch.Point(fqn("TlsRecord.Alert"), 21),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                    VariantPlan.WithPayload(
                        decl = fqn("TlsRecord.Handshake"),
                        codec = codecCn("TlsRecordHandshake"),
                        wire = WireMatch.Point(fqn("TlsRecord.Handshake"), 22),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                        typeParams =
                            listOf(
                                com.ditchoom.buffer.codec.processor.ir
                                    .PayloadTypeParam("P", null),
                            ),
                        payloadFields = emptyList(),
                    ),
                    VariantPlan.WithPayload(
                        decl = fqn("TlsRecord.ApplicationData"),
                        codec = codecCn("TlsRecordApplicationData"),
                        wire = WireMatch.Point(fqn("TlsRecord.ApplicationData"), 23),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                        typeParams =
                            listOf(
                                com.ditchoom.buffer.codec.processor.ir
                                    .PayloadTypeParam("P", null),
                            ),
                        payloadFields = emptyList(),
                    ),
                ),
            dispatch =
                DispatchShape.TypedDiscriminator(
                    disc =
                        DiscriminatorShape.ValueClass(
                            discriminatorType = discType,
                            inner = PrimitiveKind.UByte,
                            innerProp = "raw",
                            codec = discCodec,
                            dispatchProp = "type",
                            wireRange = 0..255,
                        ),
                    framing = FramingMode.Unframed,
                ),
            dir = Direction.Bidirectional,
            onUnknown = TypeFqn("kotlin.IllegalArgumentException"),
        )
    }

    /**
     * Shape — WebSocket header byte 1 (Plan.Leaf primary ctor with one UByte
     * field — same fixed-width category as MqttFixedHeader). The full
     * `WsFrameHeader` fixture uses a batch over two bytes; this is the
     * MQTT-style minimal header used as the discriminator codec input for the
     * sealed dispatcher tests.
     */
    fun wsFrameHeader(): Plan.Leaf =
        Plan.Leaf(
            decl = fqn("WsFrameHeader"),
            fields =
                listOf(
                    FieldPlan(
                        "byte1",
                        fqn("WsHeaderByte1"),
                        FieldStrategy.Primitive(PrimitiveKind.UByte, 1, Endianness.Big),
                    ),
                    FieldPlan(
                        "byte2",
                        fqn("WsHeaderByte2"),
                        FieldStrategy.Primitive(PrimitiveKind.UByte, 1, Endianness.Big),
                    ),
                ),
            batches = emptyList(),
            dir = Direction.Bidirectional,
        )

    /**
     * Slice 3 fixture — `Plan.Leaf` exercising `FieldStrategy.Collection_` with
     * `LengthSource.Inline.Varint` (the MQTT v5 properties shape).
     *
     * Models an `MqttPropertyShape(@LengthPrefixed(LengthPrefix.Varint) val properties: List<MqttProperty>)`.
     * Pinning this snapshot locks the Slice 3 collection-with-varint emit text
     * against drift.
     */
    fun mqttPropertyShape(): Plan.Leaf =
        Plan.Leaf(
            decl = fqn("MqttPropertyShape"),
            fields =
                listOf(
                    FieldPlan(
                        name = "properties",
                        type = TypeFqn("kotlin.collections.List"),
                        strategy =
                            FieldStrategy.Collection_(
                                elementCodec =
                                    com.ditchoom.buffer.codec.processor.ir.ElementCodecRef(
                                        codec = codecCn("MqttProperty"),
                                        elementType = fqn("MqttProperty"),
                                    ),
                                length =
                                    com.ditchoom.buffer.codec.processor.ir.LengthSource.Inline(
                                        encoding = com.ditchoom.buffer.codec.processor.ir.LengthEncoding.Varint,
                                        maxBytes = 4,
                                    ),
                            ),
                    ),
                ),
            batches = emptyList(),
            dir = Direction.Bidirectional,
        )

    /**
     * Slice 2 fixture — `Plan.Leaf` exercising both `FieldStrategy.VarInt` and
     * `FieldStrategy.StringField` (LengthPrefixed UShort).
     *
     * Models an `MqttPropertyHeader(@LengthPrefixed val topic: String, @VariableByteInteger val packetId: Int)`.
     */
    fun stringHeader(): Plan.Leaf =
        Plan.Leaf(
            decl = fqn("StringHeader"),
            fields =
                listOf(
                    FieldPlan(
                        name = "topic",
                        type = TypeFqn("kotlin.String"),
                        strategy =
                            FieldStrategy.StringField(
                                length =
                                    com.ditchoom.buffer.codec.processor.ir.LengthSource.Inline(
                                        encoding =
                                            com.ditchoom.buffer.codec.processor.ir.LengthEncoding.Short,
                                        maxBytes = 2,
                                    ),
                            ),
                    ),
                    FieldPlan(
                        name = "packetId",
                        type = TypeFqn("kotlin.Int"),
                        strategy = FieldStrategy.VarInt(maxBytes = 4),
                    ),
                ),
            batches = emptyList(),
            dir = Direction.Bidirectional,
        )

    /**
     * Slice 4 fixture — `Plan.Sealed_` Unframed (RIFF chunk-shaped) with only
     * `VariantPlan.NoPayload` variants. The `Data` payload variant is dropped so
     * the dispatcher fits Slice 4's coverable() whitelist (no `WithPayload`).
     */
    fun riffChunkSlice4(): Plan.Sealed_ {
        val discType = fqn("RiffChunkId")
        val discCodec = codecCn("RiffChunkId")
        return Plan.Sealed_(
            decl = fqn("RiffChunkSlice4"),
            variants =
                listOf(
                    VariantPlan.NoPayload(
                        decl = fqn("RiffChunkSlice4.Fact"),
                        codec = codecCn("RiffChunkSlice4Fact"),
                        wire = WireMatch.Point(fqn("RiffChunkSlice4.Fact"), 1717658484),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                    VariantPlan.NoPayload(
                        decl = fqn("RiffChunkSlice4.Fmt"),
                        codec = codecCn("RiffChunkSlice4Fmt"),
                        wire = WireMatch.Point(fqn("RiffChunkSlice4.Fmt"), 1718449184),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                ),
            dispatch =
                DispatchShape.TypedDiscriminator(
                    disc =
                        DiscriminatorShape.ValueClass(
                            discriminatorType = discType,
                            inner = PrimitiveKind.UInt,
                            innerProp = "raw",
                            codec = discCodec,
                            dispatchProp = "id",
                            wireRange = 0..Int.MAX_VALUE,
                        ),
                    framing = FramingMode.Unframed,
                ),
            dir = Direction.Bidirectional,
            onUnknown = TypeFqn("kotlin.IllegalArgumentException"),
        )
    }

    /**
     * Slice 4 fixture — `Plan.Sealed_` BodyLength (MQTT FixedHeader-shaped) with
     * only `VariantPlan.NoPayload` variants. Two flavours of variant:
     *  - `PingReq` — point arm, no DiscriminatorOwned: dispatcher writes the disc.
     *  - `PubAck` — point arm with a DiscriminatorOwned field: variant self-encodes.
     */
    fun controlPacketV5Slice4(): Plan.Sealed_ {
        val discType = fqn("MqttFixedHeader")
        val discCodec = codecCn("MqttFixedHeader")
        return Plan.Sealed_(
            decl = fqn("ControlPacketV5Slice4"),
            variants =
                listOf(
                    VariantPlan.NoPayload(
                        decl = fqn("ControlPacketV5Slice4.PubAck"),
                        codec = codecCn("ControlPacketV5Slice4PubAck"),
                        wire = WireMatch.Point(fqn("ControlPacketV5Slice4.PubAck"), 4),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields =
                            listOf(
                                FieldPlan(
                                    "header",
                                    discType,
                                    FieldStrategy.DiscriminatorOwned(parentDispatchOn = discType),
                                ),
                            ),
                    ),
                    VariantPlan.NoPayload(
                        decl = fqn("ControlPacketV5Slice4.PingReq"),
                        codec = codecCn("ControlPacketV5Slice4PingReq"),
                        wire = WireMatch.Point(fqn("ControlPacketV5Slice4.PingReq"), 12),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                ),
            dispatch =
                DispatchShape.TypedDiscriminator(
                    disc =
                        DiscriminatorShape.ValueClass(
                            discriminatorType = discType,
                            inner = PrimitiveKind.UByte,
                            innerProp = "raw",
                            codec = discCodec,
                            dispatchProp = "packetType",
                            wireRange = 0..255,
                        ),
                    framing =
                        FramingMode.BodyLength(
                            framerFqn = ClassName("com.ditchoom.codec.test", "MqttFixedHeader"),
                            discriminatorBytes = 1,
                        ),
                ),
            dir = Direction.Bidirectional,
            onUnknown = TypeFqn("kotlin.IllegalArgumentException"),
        )
    }

    /**
     * Slice 4 fixture — `Plan.Sealed_` PeekOnly with a value-class discriminator
     * and only `VariantPlan.NoPayload` variants. Mirrors the WebSocket frame
     * shape (peek-only framing) but uses a single-byte value-class disc instead
     * of a multi-byte data-class disc so the dispatcher's discriminator-write
     * fits the Slice 4 covered set.
     */
    fun wsFrameSlice4(): Plan.Sealed_ {
        val discType = fqn("WsOpcodeByte")
        val discCodec = codecCn("WsOpcodeByte")
        return Plan.Sealed_(
            decl = fqn("WsFrameSlice4"),
            variants =
                listOf(
                    VariantPlan.NoPayload(
                        decl = fqn("WsFrameSlice4.Text"),
                        codec = codecCn("WsFrameSlice4Text"),
                        wire = WireMatch.Point(fqn("WsFrameSlice4.Text"), 0x01),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                    VariantPlan.NoPayload(
                        decl = fqn("WsFrameSlice4.Close"),
                        codec = codecCn("WsFrameSlice4Close"),
                        wire = WireMatch.Point(fqn("WsFrameSlice4.Close"), 0x08),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                ),
            dispatch =
                DispatchShape.TypedDiscriminator(
                    disc =
                        DiscriminatorShape.ValueClass(
                            discriminatorType = discType,
                            inner = PrimitiveKind.UByte,
                            innerProp = "raw",
                            codec = discCodec,
                            dispatchProp = "opcode",
                            wireRange = 0..255,
                        ),
                    framing =
                        FramingMode.PeekOnly(
                            framerFqn = ClassName("com.ditchoom.codec.test", "WsFraming"),
                        ),
                ),
            dir = Direction.Bidirectional,
            onUnknown = TypeFqn("kotlin.IllegalArgumentException"),
        )
    }

    /**
     * Slice 5a fixture — `Plan.Sealed_` BodyLength (MQTT v5 control packet shape) with
     * a `VariantPlan.WithPayload` variant alongside `NoPayload` siblings. Pins the
     * dispatcher's emit text for the typed-lambda + scratch-buffer-fallback paths
     * routed through `*FromContext` overloads.
     */
    fun controlPacketV5Slice5a(): Plan.Sealed_ {
        val discType = fqn("MqttFixedHeader")
        val discCodec = codecCn("MqttFixedHeader")
        return Plan.Sealed_(
            decl = fqn("ControlPacketV5Slice5a"),
            variants =
                listOf(
                    VariantPlan.WithPayload(
                        decl = fqn("ControlPacketV5Slice5a.Publish"),
                        codec = codecCn("ControlPacketV5Slice5aPublish"),
                        wire = WireMatch.Point(fqn("ControlPacketV5Slice5a.Publish"), 3),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields =
                            listOf(
                                FieldPlan(
                                    "header",
                                    discType,
                                    FieldStrategy.DiscriminatorOwned(parentDispatchOn = discType),
                                ),
                            ),
                        typeParams =
                            listOf(
                                com.ditchoom.buffer.codec.processor.ir
                                    .PayloadTypeParam("P", null),
                            ),
                        payloadFields =
                            listOf(
                                com.ditchoom.buffer.codec.processor.ir
                                    .PayloadFieldRef(fieldName = "payload", typeParamName = "P"),
                            ),
                    ),
                    VariantPlan.NoPayload(
                        decl = fqn("ControlPacketV5Slice5a.PingReq"),
                        codec = codecCn("ControlPacketV5Slice5aPingReq"),
                        wire = WireMatch.Point(fqn("ControlPacketV5Slice5a.PingReq"), 12),
                        selfEncodes = false,
                        dir = Direction.Bidirectional,
                        fields = emptyList(),
                    ),
                ),
            dispatch =
                DispatchShape.TypedDiscriminator(
                    disc =
                        DiscriminatorShape.ValueClass(
                            discriminatorType = discType,
                            inner = PrimitiveKind.UByte,
                            innerProp = "raw",
                            codec = discCodec,
                            dispatchProp = "packetType",
                            wireRange = 0..255,
                        ),
                    framing =
                        FramingMode.BodyLength(
                            framerFqn = ClassName("com.ditchoom.codec.test", "MqttFixedHeader"),
                            discriminatorBytes = 1,
                        ),
                ),
            dir = Direction.Bidirectional,
            onUnknown = TypeFqn("kotlin.IllegalArgumentException"),
        )
    }

    /**
     * Slice 5a fixture — `Plan.Leaf` with a variable-size SPI field. Pins the
     * `wireSize` accumulator that substitutes `descriptor.wireSizeRaw` (e.g.
     * `MyCodec.wireSize(value.field)`) when `fixedSize == -1`.
     */
    fun variableSizeSpiLeaf(): Plan.Leaf =
        Plan.Leaf(
            decl = fqn("VariableSizeSpiLeaf"),
            fields =
                listOf(
                    FieldPlan(
                        "kind",
                        TypeFqn("kotlin.UByte"),
                        FieldStrategy.Primitive(PrimitiveKind.UByte, 1, Endianness.Big),
                    ),
                    FieldPlan(
                        "value",
                        fqn("VarSizePayload"),
                        FieldStrategy.Spi(
                            provider =
                                com.ditchoom.buffer.codec.processor.ir
                                    .ProviderId("var-size-payload"),
                            descriptor =
                                com.ditchoom.buffer.codec.processor.ir.SpiDescriptor(
                                    raw = "VarSizePayloadCodec.decode(buffer, context)",
                                    fixedSize = -1,
                                    wireSizeRaw = "VarSizePayloadCodec.wireSize(value.value)",
                                ),
                        ),
                    ),
                ),
            batches = emptyList(),
            dir = Direction.Bidirectional,
        )

    /**
     * Slice 5.5 fixture — `Plan.Leaf` with an SPI field whose `decodeRaw` and
     * `encodeRaw` differ. Mirrors legacy `CustomFieldDescriptor` with separate
     * `readFunction` (`buffer.readCidr()`) and `writeFunction`
     * (`buffer.writeCidr(value.cidr)`).
     */
    fun asymmetricSpiLeaf(): Plan.Leaf =
        Plan.Leaf(
            decl = fqn("AsymmetricSpiLeaf"),
            fields =
                listOf(
                    FieldPlan(
                        "cidr",
                        TypeFqn("kotlin.Int"),
                        FieldStrategy.Spi(
                            provider =
                                com.ditchoom.buffer.codec.processor.ir
                                    .ProviderId("cidr"),
                            descriptor =
                                com.ditchoom.buffer.codec.processor.ir.SpiDescriptor(
                                    fixedSize = 5,
                                    decodeRaw = "buffer.readCidr()",
                                    encodeRaw = "buffer.writeCidr(value.cidr)",
                                ),
                        ),
                    ),
                ),
            batches = emptyList(),
            dir = Direction.Bidirectional,
        )

    fun standardRegistry(): TypeRegistry =
        TypeRegistry(
            mapOf(
                fqn("MqttFixedHeader") to cn("MqttFixedHeader"),
                fqn("GrpcFrame") to cn("GrpcFrame"),
                fqn("MqttConnectFlags") to cn("MqttConnectFlags"),
                fqn("PingResponse") to cn("PingResponse"),
                fqn("StringHeader") to cn("StringHeader"),
                fqn("MqttPropertyShape") to cn("MqttPropertyShape"),
                fqn("MqttProperty") to cn("MqttProperty"),
                fqn("RiffChunk") to cn("RiffChunk"),
                fqn("RiffChunk.Data") to cn("RiffChunk").nestedClass("Data"),
                fqn("RiffChunk.Fact") to cn("RiffChunk").nestedClass("Fact"),
                fqn("RiffChunk.Fmt") to cn("RiffChunk").nestedClass("Fmt"),
                fqn("RiffChunkId") to cn("RiffChunkId"),
                fqn("WsFrame") to cn("WsFrame"),
                fqn("WsFrame.Text") to cn("WsFrame").nestedClass("Text"),
                fqn("WsFrame.Binary") to cn("WsFrame").nestedClass("Binary"),
                fqn("WsFrame.Close") to cn("WsFrame").nestedClass("Close"),
                fqn("WsFrameHeader") to cn("WsFrameHeader"),
                fqn("WsHeaderByte1") to cn("WsHeaderByte1"),
                fqn("WsHeaderByte2") to cn("WsHeaderByte2"),
                fqn("ControlPacketV5") to cn("ControlPacketV5"),
                fqn("ControlPacketV5.Publish") to cn("ControlPacketV5").nestedClass("Publish"),
                fqn("ControlPacketV5.PubAck") to cn("ControlPacketV5").nestedClass("PubAck"),
                fqn("ControlPacketV5.PingReq") to cn("ControlPacketV5").nestedClass("PingReq"),
                fqn("MessagePackFormatByte") to cn("MessagePackFormatByte"),
                fqn("MessagePackFormatByte.PositiveFixInt") to cn("MessagePackFormatByte").nestedClass("PositiveFixInt"),
                fqn("MessagePackFormatByte.Nil") to cn("MessagePackFormatByte").nestedClass("Nil"),
                fqn("MessagePackByte") to cn("MessagePackByte"),
                fqn("TlsRecord") to cn("TlsRecord"),
                fqn("TlsRecord.ChangeCipherSpec") to cn("TlsRecord").nestedClass("ChangeCipherSpec"),
                fqn("TlsRecord.Alert") to cn("TlsRecord").nestedClass("Alert"),
                fqn("TlsRecord.Handshake") to cn("TlsRecord").nestedClass("Handshake"),
                fqn("TlsRecord.ApplicationData") to cn("TlsRecord").nestedClass("ApplicationData"),
                fqn("TlsContentType") to cn("TlsContentType"),
                fqn("RiffChunkSlice4") to cn("RiffChunkSlice4"),
                fqn("RiffChunkSlice4.Fact") to cn("RiffChunkSlice4").nestedClass("Fact"),
                fqn("RiffChunkSlice4.Fmt") to cn("RiffChunkSlice4").nestedClass("Fmt"),
                fqn("ControlPacketV5Slice4") to cn("ControlPacketV5Slice4"),
                fqn("ControlPacketV5Slice4.PubAck") to cn("ControlPacketV5Slice4").nestedClass("PubAck"),
                fqn("ControlPacketV5Slice4.PingReq") to cn("ControlPacketV5Slice4").nestedClass("PingReq"),
                fqn("WsFrameSlice4") to cn("WsFrameSlice4"),
                fqn("WsFrameSlice4.Text") to cn("WsFrameSlice4").nestedClass("Text"),
                fqn("WsFrameSlice4.Close") to cn("WsFrameSlice4").nestedClass("Close"),
                fqn("WsOpcodeByte") to cn("WsOpcodeByte"),
                fqn("ControlPacketV5Slice5a") to cn("ControlPacketV5Slice5a"),
                fqn("ControlPacketV5Slice5a.Publish") to cn("ControlPacketV5Slice5a").nestedClass("Publish"),
                fqn("ControlPacketV5Slice5a.PingReq") to cn("ControlPacketV5Slice5a").nestedClass("PingReq"),
                fqn("VariableSizeSpiLeaf") to cn("VariableSizeSpiLeaf"),
                fqn("VarSizePayload") to cn("VarSizePayload"),
                fqn("AsymmetricSpiLeaf") to cn("AsymmetricSpiLeaf"),
                // Phase 9 Step 3 Phase B — capability fixtures.
                fqn("PayloadOnlyLeaf") to cn("PayloadOnlyLeaf"),
                fqn("NestedMessageLeaf") to cn("NestedMessageLeaf"),
                fqn("NestedLengthPrefixedLeaf") to cn("NestedLengthPrefixedLeaf"),
                fqn("InnerMessage") to cn("InnerMessage"),
                fqn("ThreeByteInt") to cn("ThreeByteInt"),
                TypeFqn("kotlin.P") to ClassName("kotlin", "Any"),
                TypeFqn("kotlin.IllegalArgumentException") to ClassName("kotlin", "IllegalArgumentException"),
            ),
        )
}
