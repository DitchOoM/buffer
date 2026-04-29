package com.ditchoom.buffer.codec.processor.emitter

/**
 * Captured snapshots of every canonical fixture's emitted source.
 *
 * Each constant pins the byte-for-byte output of the Phase 7 emitter for the
 * named fixture under [EmitterFixtures]. The snapshots were produced by
 * running the emitter on the fixture and committing the resulting text; any
 * change in the emitter's output requires updating the pinned text here, and
 * that diff is reviewable as part of any emitter PR.
 *
 * The snapshots use plain `\n` line separators; KotlinPoet's `FileSpec.toString()`
 * emits Unix line endings on every platform. They are normalised at compare
 * time via [normalise] for resilience against editor mangling.
 */
object CapturedSnapshots {
    fun normalise(s: String): String =
        s
            .replace("\r\n", "\n")
            .trimEnd('\n')

    val MqttFixedHeader =
        """
        package com.ditchoom.codec.test

        import com.ditchoom.buffer.ReadBuffer
        import com.ditchoom.buffer.WriteBuffer
        import com.ditchoom.buffer.codec.Codec
        import com.ditchoom.buffer.codec.DecodeContext
        import com.ditchoom.buffer.codec.EncodeContext
        import com.ditchoom.buffer.stream.PeekResult
        import com.ditchoom.buffer.stream.StreamProcessor
        import com.ditchoom.buffer.stream.SuspendingStreamProcessor
        import kotlin.Int

        public object MqttFixedHeaderCodec : Codec<MqttFixedHeader> {
          public const val MIN_HEADER_BYTES: Int = 1

          override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttFixedHeader = MqttFixedHeader(buffer.readUnsignedByte())

          override fun encode(
            buffer: WriteBuffer,
            `value`: MqttFixedHeader,
            context: EncodeContext,
          ) {
            buffer.writeUByte(value.raw)
          }

          override fun wireSize(`value`: MqttFixedHeader, context: EncodeContext): Int = 1

          override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.Size(1)

          public suspend fun peekFrameSize(stream: SuspendingStreamProcessor, baseOffset: Int = 0): PeekResult = PeekResult.Size(1)
        }
        """.trimIndent()

    val PingResponse =
        """
        package com.ditchoom.codec.test

        import com.ditchoom.buffer.ReadBuffer
        import com.ditchoom.buffer.WriteBuffer
        import com.ditchoom.buffer.codec.Codec
        import com.ditchoom.buffer.codec.DecodeContext
        import com.ditchoom.buffer.codec.EncodeContext
        import com.ditchoom.buffer.stream.PeekResult
        import com.ditchoom.buffer.stream.StreamProcessor
        import com.ditchoom.buffer.stream.SuspendingStreamProcessor
        import kotlin.Int

        public object PingResponseCodec : Codec<PingResponse> {
          public const val MIN_HEADER_BYTES: Int = 0

          override fun decode(buffer: ReadBuffer, context: DecodeContext): PingResponse = PingResponse

          override fun encode(
            buffer: WriteBuffer,
            `value`: PingResponse,
            context: EncodeContext,
          ) {
          }

          override fun wireSize(`value`: PingResponse, context: EncodeContext): Int = 0

          override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.Size(0)

          public suspend fun peekFrameSize(stream: SuspendingStreamProcessor, baseOffset: Int = 0): PeekResult = PeekResult.Size(0)
        }
        """.trimIndent()

    val GrpcFrame =
        """
        package com.ditchoom.codec.test

        import com.ditchoom.buffer.ReadBuffer
        import com.ditchoom.buffer.WriteBuffer
        import com.ditchoom.buffer.codec.Codec
        import com.ditchoom.buffer.codec.DecodeContext
        import com.ditchoom.buffer.codec.EncodeContext
        import com.ditchoom.buffer.stream.PeekResult
        import com.ditchoom.buffer.stream.StreamProcessor
        import com.ditchoom.buffer.stream.SuspendingStreamProcessor
        import kotlin.Int

        public object GrpcFrameCodec : Codec<GrpcFrame> {
          public const val MIN_HEADER_BYTES: Int = 5

          override fun decode(buffer: ReadBuffer, context: DecodeContext): GrpcFrame {
            val compressed = buffer.readUnsignedByte()
            val length = buffer.readUnsignedInt()
            val body = buffer.readBytes(length.toInt())
            return GrpcFrame(compressed = compressed, length = length, body = body)
          }

          override fun encode(
            buffer: WriteBuffer,
            `value`: GrpcFrame,
            context: EncodeContext,
          ) {
            buffer.writeUByte(value.compressed)
            buffer.writeUInt(value.length)
            buffer.writeBytes(value.body)
          }

          override fun wireSize(`value`: GrpcFrame, context: EncodeContext): Int = 5 + value.body.remaining()

          override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.Size(5)

          public suspend fun peekFrameSize(stream: SuspendingStreamProcessor, baseOffset: Int = 0): PeekResult = PeekResult.Size(5)
        }
        """.trimIndent()

    /**
     * Slice 2 fixture — `Plan.Leaf` with `FieldStrategy.StringField` (Inline.Short)
     * and `FieldStrategy.VarInt`. Captures the byte-for-byte expected emit text.
     */
    val StringHeader =
        """
        package com.ditchoom.codec.test

        import com.ditchoom.buffer.ReadBuffer
        import com.ditchoom.buffer.WriteBuffer
        import com.ditchoom.buffer.codec.Codec
        import com.ditchoom.buffer.codec.DecodeContext
        import com.ditchoom.buffer.codec.EncodeContext
        import com.ditchoom.buffer.readLengthPrefixedUtf8String
        import com.ditchoom.buffer.readVariableByteInteger
        import com.ditchoom.buffer.stream.PeekResult
        import com.ditchoom.buffer.stream.StreamProcessor
        import com.ditchoom.buffer.stream.SuspendingStreamProcessor
        import com.ditchoom.buffer.utf8Length
        import com.ditchoom.buffer.variableByteSizeInt
        import com.ditchoom.buffer.writeLengthPrefixedUtf8String
        import com.ditchoom.buffer.writeVariableByteInteger
        import com.ditchoom.buffer.writeVariableByteIntegerLengthPrefixed
        import kotlin.Int

        public object StringHeaderCodec : Codec<StringHeader> {
          public const val MIN_HEADER_BYTES: Int = 0

          override fun decode(buffer: ReadBuffer, context: DecodeContext): StringHeader {
            val topic = buffer.readLengthPrefixedUtf8String().second
            val packetId = buffer.readVariableByteInteger()
            return StringHeader(topic = topic, packetId = packetId)
          }

          override fun encode(
            buffer: WriteBuffer,
            `value`: StringHeader,
            context: EncodeContext,
          ) {
            buffer.writeLengthPrefixedUtf8String(value.topic)
            buffer.writeVariableByteInteger(value.packetId)
          }

          override fun wireSize(`value`: StringHeader, context: EncodeContext): Int {
            var size = 0
            size += (2 + value.topic.utf8Length())
            size += variableByteSizeInt(value.packetId)
            return size
          }

          override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.Size(0)

          public suspend fun peekFrameSize(stream: SuspendingStreamProcessor, baseOffset: Int = 0): PeekResult = PeekResult.Size(0)
        }
        """.trimIndent()

    /**
     * Slice 3 fixture — `Plan.Leaf` with `FieldStrategy.Collection_` whose length
     * source is `LengthSource.Inline.Varint` (the MQTT v5 properties shape).
     * Pins the byte-for-byte expected emit text.
     */
    val MqttPropertyShape =
        """
        package com.ditchoom.codec.test

        import com.ditchoom.buffer.ReadBuffer
        import com.ditchoom.buffer.WriteBuffer
        import com.ditchoom.buffer.codec.Codec
        import com.ditchoom.buffer.codec.DecodeContext
        import com.ditchoom.buffer.codec.EncodeContext
        import com.ditchoom.buffer.readVariableByteInteger
        import com.ditchoom.buffer.stream.PeekResult
        import com.ditchoom.buffer.stream.StreamProcessor
        import com.ditchoom.buffer.stream.SuspendingStreamProcessor
        import com.ditchoom.buffer.variableByteSizeInt
        import com.ditchoom.buffer.writeVariableByteInteger
        import com.ditchoom.buffer.writeVariableByteIntegerLengthPrefixed
        import kotlin.Int

        public object MqttPropertyShapeCodec : Codec<MqttPropertyShape> {
          public const val MIN_HEADER_BYTES: Int = 0

          override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttPropertyShape {
            val properties = run { val _len = buffer.readVariableByteInteger(); val _slice = buffer.readBytes(_len); buildList<MqttProperty> { while (_slice.remaining() > 0) { add(com.ditchoom.codec.test.MqttPropertyCodec.decode(_slice, context)) } } }
            return MqttPropertyShape(properties = properties)
          }

          override fun encode(
            buffer: WriteBuffer,
            `value`: MqttPropertyShape,
            context: EncodeContext,
          ) {
            run { val _l = value.properties.sumOf { com.ditchoom.codec.test.MqttPropertyCodec.wireSize(it, context) }; buffer.writeVariableByteInteger(_l); value.properties.forEach { com.ditchoom.codec.test.MqttPropertyCodec.encode(buffer, it, context) } }
          }

          override fun wireSize(`value`: MqttPropertyShape, context: EncodeContext): Int = run { val _b = value.properties.sumOf { com.ditchoom.codec.test.MqttPropertyCodec.wireSize(it, context) }; variableByteSizeInt(_b) + _b }

          override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.Size(0)

          public suspend fun peekFrameSize(stream: SuspendingStreamProcessor, baseOffset: Int = 0): PeekResult = PeekResult.Size(0)
        }
        """.trimIndent()

    /**
     * Slice 4 hard-bar fixture — `Plan.Sealed_` Unframed (RIFF chunk-shaped) with
     * only `VariantPlan.NoPayload` variants. Pins the dispatcher's emit text:
     * decode reads the discriminator, dispatches via `when (type)`; encode writes
     * the discriminator then delegates to each variant codec.
     */
    val RiffChunkSlice4 =
        """
        package com.ditchoom.codec.test

        import com.ditchoom.buffer.ReadBuffer
        import com.ditchoom.buffer.WriteBuffer
        import com.ditchoom.buffer.codec.Codec
        import com.ditchoom.buffer.codec.DecodeContext
        import com.ditchoom.buffer.codec.EncodeContext
        import com.ditchoom.buffer.stream.PeekResult
        import com.ditchoom.buffer.stream.StreamProcessor
        import com.ditchoom.buffer.stream.SuspendingStreamProcessor
        import kotlin.IllegalArgumentException
        import kotlin.Int

        public object RiffChunkSlice4Codec : Codec<RiffChunkSlice4> {
          public const val MIN_HEADER_BYTES: Int = 4

          override fun decode(buffer: ReadBuffer, context: DecodeContext): RiffChunkSlice4 {
            val discriminator = RiffChunkIdCodec.decode(buffer, context)
            val type = discriminator.id
            return when (type) {
              1_717_658_484 -> com.ditchoom.codec.test.RiffChunkSlice4FactCodec.decode(buffer, context)
              1_718_449_184 -> com.ditchoom.codec.test.RiffChunkSlice4FmtCodec.decode(buffer, context)
              else -> throw IllegalArgumentException("Unknown discriminator: ${'$'}type")
            }
          }

          override fun encode(
            buffer: WriteBuffer,
            `value`: RiffChunkSlice4,
            context: EncodeContext,
          ) {
            when (value) {
                is RiffChunkSlice4.Fact -> {
                    RiffChunkIdCodec.encode(buffer, RiffChunkId(1717658484.toUInt()), context)
                    RiffChunkSlice4FactCodec.encode(buffer, value, context)
                }
                is RiffChunkSlice4.Fmt -> {
                    RiffChunkIdCodec.encode(buffer, RiffChunkId(1718449184.toUInt()), context)
                    RiffChunkSlice4FmtCodec.encode(buffer, value, context)
                }
            }
          }

          override fun wireSize(`value`: RiffChunkSlice4, context: EncodeContext): Int = when (value) {
              is RiffChunkSlice4.Fact -> RiffChunkIdCodec.wireSize(RiffChunkId(1717658484.toUInt()), context) + com.ditchoom.codec.test.RiffChunkSlice4FactCodec.wireSize(value, context)
              is RiffChunkSlice4.Fmt -> RiffChunkIdCodec.wireSize(RiffChunkId(1718449184.toUInt()), context) + com.ditchoom.codec.test.RiffChunkSlice4FmtCodec.wireSize(value, context)
          }

          override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
            if (stream.available() < baseOffset + 4) return PeekResult.NeedsMoreData
            val _raw = stream.peekInt(baseOffset).toUInt()
            val type = RiffChunkId(_raw).id
            return when (type) {
                1717658484 -> when (val r = RiffChunkSlice4FactCodec.peekFrameSize(stream, baseOffset + 4)) { is PeekResult.Size -> PeekResult.Size(r.bytes + 4); else -> r }
                1718449184 -> when (val r = RiffChunkSlice4FmtCodec.peekFrameSize(stream, baseOffset + 4)) { is PeekResult.Size -> PeekResult.Size(r.bytes + 4); else -> r }
                else -> PeekResult.NeedsMoreData
            }
          }

          public suspend fun peekFrameSize(stream: SuspendingStreamProcessor, baseOffset: Int = 0): PeekResult {
            if (stream.available() < baseOffset + 4) return PeekResult.NeedsMoreData
            val _raw = stream.peekInt(baseOffset).toUInt()
            val type = RiffChunkId(_raw).id
            return when (type) {
                1717658484 -> when (val r = RiffChunkSlice4FactCodec.peekFrameSize(stream, baseOffset + 4)) { is PeekResult.Size -> PeekResult.Size(r.bytes + 4); else -> r }
                1718449184 -> when (val r = RiffChunkSlice4FmtCodec.peekFrameSize(stream, baseOffset + 4)) { is PeekResult.Size -> PeekResult.Size(r.bytes + 4); else -> r }
                else -> PeekResult.NeedsMoreData
            }
          }
        }
        """.trimIndent()

    /**
     * Slice 4 hard-bar fixture — `Plan.Sealed_` BodyLength (MQTT v5 control packet
     * shape) with only `VariantPlan.NoPayload` variants. Pins the dispatcher's
     * emit text: decode slices the body via the framer, encode writes
     * discriminator (when not self-encoding) → bodyLength prefix → body.
     */
    val ControlPacketV5Slice4 =
        """
        package com.ditchoom.codec.test

        import com.ditchoom.buffer.ReadBuffer
        import com.ditchoom.buffer.WriteBuffer
        import com.ditchoom.buffer.codec.Codec
        import com.ditchoom.buffer.codec.CodecContext
        import com.ditchoom.buffer.codec.DecodeContext
        import com.ditchoom.buffer.codec.EncodeContext
        import com.ditchoom.buffer.stream.PeekResult
        import com.ditchoom.buffer.stream.StreamProcessor
        import kotlin.IllegalArgumentException
        import kotlin.Int

        public object ControlPacketV5Slice4Codec : Codec<ControlPacketV5Slice4> {
          public const val MIN_HEADER_BYTES: Int = 2

          override fun decode(buffer: ReadBuffer, context: DecodeContext): ControlPacketV5Slice4 {
            val discriminator = MqttFixedHeaderCodec.decode(buffer, context)
            val ctx = context.with(DiscriminatorKey, discriminator)
            val _bodyLen = MqttFixedHeader.readBodyLength(buffer)
            val _bodySlice = buffer.readBytes(_bodyLen)
            val type = discriminator.packetType
            val _result = when (type) {
              4 -> com.ditchoom.codec.test.ControlPacketV5Slice4PubAckCodec.decode(_bodySlice, ctx)
              12 -> com.ditchoom.codec.test.ControlPacketV5Slice4PingReqCodec.decode(_bodySlice, ctx)
              else -> throw IllegalArgumentException("Unknown discriminator: ${'$'}type")
            }
            if (_bodySlice.remaining() != 0) {
                throw IllegalArgumentException("Variant decoder consumed ${'$'}{_bodyLen - _bodySlice.remaining()} of " +
                    "${'$'}_bodyLen body bytes; ${'$'}{_bodySlice.remaining()} unread. " +
                    "Wire is malformed or variant codec is buggy.")
            }
            return _result
          }

          override fun encode(
            buffer: WriteBuffer,
            `value`: ControlPacketV5Slice4,
            context: EncodeContext,
          ) {
            when (value) {
                is ControlPacketV5Slice4.PingReq -> {
                    MqttFixedHeaderCodec.encode(buffer, MqttFixedHeader(12.toUByte()), context)
                    val _len_body = ControlPacketV5Slice4PingReqCodec.wireSize(value, context)
                    MqttFixedHeader.writeBodyLength(buffer, _len_body)
                    ControlPacketV5Slice4PingReqCodec.encode(buffer, value, context)
                }
                is ControlPacketV5Slice4.PubAck -> {
                    val _len_body = ControlPacketV5Slice4PubAckCodec.wireSize(value, context)
                    MqttFixedHeader.writeBodyLength(buffer, _len_body)
                    ControlPacketV5Slice4PubAckCodec.encode(buffer, value, context)
                }
            }
          }

          override fun wireSize(`value`: ControlPacketV5Slice4, context: EncodeContext): Int = when (value) {
              is ControlPacketV5Slice4.PingReq -> MqttFixedHeaderCodec.wireSize(MqttFixedHeader(12.toUByte()), context) + run { val _b = com.ditchoom.codec.test.ControlPacketV5Slice4PingReqCodec.wireSize(value, context); com.ditchoom.codec.test.MqttFixedHeader.bodyLengthSize(_b) + _b }
              is ControlPacketV5Slice4.PubAck -> run { val _b = com.ditchoom.codec.test.ControlPacketV5Slice4PubAckCodec.wireSize(value, context); com.ditchoom.codec.test.MqttFixedHeader.bodyLengthSize(_b) + _b }
          }

          override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = MqttFixedHeader.peekFrameSize(stream, baseOffset)

          public data object DiscriminatorKey : CodecContext.Key<MqttFixedHeader>()
        }
        """.trimIndent()

    /**
     * Slice 4 hard-bar fixture — `Plan.Sealed_` PeekOnly (WsFrame-shape without
     * payload variants), value-class discriminator. Pins the dispatcher's emit
     * text: decode reads disc + dispatches; encode writes disc + variant body;
     * peekFrameSize delegates to the framer.
     */
    val WsFrameSlice4 =
        """
        package com.ditchoom.codec.test

        import com.ditchoom.buffer.ReadBuffer
        import com.ditchoom.buffer.WriteBuffer
        import com.ditchoom.buffer.codec.Codec
        import com.ditchoom.buffer.codec.DecodeContext
        import com.ditchoom.buffer.codec.EncodeContext
        import com.ditchoom.buffer.stream.PeekResult
        import com.ditchoom.buffer.stream.StreamProcessor
        import kotlin.IllegalArgumentException
        import kotlin.Int

        public object WsFrameSlice4Codec : Codec<WsFrameSlice4> {
          public const val MIN_HEADER_BYTES: Int = 1

          override fun decode(buffer: ReadBuffer, context: DecodeContext): WsFrameSlice4 {
            val discriminator = WsOpcodeByteCodec.decode(buffer, context)
            val type = discriminator.opcode
            return when (type) {
              1 -> com.ditchoom.codec.test.WsFrameSlice4TextCodec.decode(buffer, context)
              8 -> com.ditchoom.codec.test.WsFrameSlice4CloseCodec.decode(buffer, context)
              else -> throw IllegalArgumentException("Unknown discriminator: ${'$'}type")
            }
          }

          override fun encode(
            buffer: WriteBuffer,
            `value`: WsFrameSlice4,
            context: EncodeContext,
          ) {
            when (value) {
                is WsFrameSlice4.Close -> {
                    WsOpcodeByteCodec.encode(buffer, WsOpcodeByte(8.toUByte()), context)
                    WsFrameSlice4CloseCodec.encode(buffer, value, context)
                }
                is WsFrameSlice4.Text -> {
                    WsOpcodeByteCodec.encode(buffer, WsOpcodeByte(1.toUByte()), context)
                    WsFrameSlice4TextCodec.encode(buffer, value, context)
                }
            }
          }

          override fun wireSize(`value`: WsFrameSlice4, context: EncodeContext): Int = when (value) {
              is WsFrameSlice4.Close -> WsOpcodeByteCodec.wireSize(WsOpcodeByte(8.toUByte()), context) + com.ditchoom.codec.test.WsFrameSlice4CloseCodec.wireSize(value, context)
              is WsFrameSlice4.Text -> WsOpcodeByteCodec.wireSize(WsOpcodeByte(1.toUByte()), context) + com.ditchoom.codec.test.WsFrameSlice4TextCodec.wireSize(value, context)
          }

          override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = WsFraming.peekFrameSize(stream, baseOffset)
        }
        """.trimIndent()

    val WsFrameHeader =
        """
        package com.ditchoom.codec.test

        import com.ditchoom.buffer.ReadBuffer
        import com.ditchoom.buffer.WriteBuffer
        import com.ditchoom.buffer.codec.Codec
        import com.ditchoom.buffer.codec.DecodeContext
        import com.ditchoom.buffer.codec.EncodeContext
        import com.ditchoom.buffer.stream.PeekResult
        import com.ditchoom.buffer.stream.StreamProcessor
        import com.ditchoom.buffer.stream.SuspendingStreamProcessor
        import kotlin.Int

        public object WsFrameHeaderCodec : Codec<WsFrameHeader> {
          public const val MIN_HEADER_BYTES: Int = 2

          override fun decode(buffer: ReadBuffer, context: DecodeContext): WsFrameHeader {
            val byte1 = buffer.readUnsignedByte()
            val byte2 = buffer.readUnsignedByte()
            return WsFrameHeader(byte1 = byte1, byte2 = byte2)
          }

          override fun encode(
            buffer: WriteBuffer,
            `value`: WsFrameHeader,
            context: EncodeContext,
          ) {
            buffer.writeUByte(value.byte1)
            buffer.writeUByte(value.byte2)
          }

          override fun wireSize(`value`: WsFrameHeader, context: EncodeContext): Int = 2

          override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.Size(2)

          public suspend fun peekFrameSize(stream: SuspendingStreamProcessor, baseOffset: Int = 0): PeekResult = PeekResult.Size(2)
        }
        """.trimIndent()

    /**
     * Slice 5a hard-bar fixture — `Plan.Sealed_` BodyLength with a `VariantPlan.WithPayload`
     * variant alongside `NoPayload` siblings. Pins the dispatcher's `*FromContext`
     * routing for typed-lambda variants and the `is X<*>` star projection.
     */
    val ControlPacketV5Slice5a =
        """
        package com.ditchoom.codec.test

        import com.ditchoom.buffer.BufferFactory
        import com.ditchoom.buffer.ReadBuffer
        import com.ditchoom.buffer.WriteBuffer
        import com.ditchoom.buffer.codec.Codec
        import com.ditchoom.buffer.codec.CodecContext
        import com.ditchoom.buffer.codec.DecodeContext
        import com.ditchoom.buffer.codec.EncodeContext
        import com.ditchoom.buffer.stream.PeekResult
        import com.ditchoom.buffer.stream.StreamProcessor
        import kotlin.IllegalArgumentException
        import kotlin.Int
        import kotlin.Unit

        public object ControlPacketV5Slice5aCodec : Codec<ControlPacketV5Slice5a> {
          public const val MIN_HEADER_BYTES: Int = 2

          override fun decode(buffer: ReadBuffer, context: DecodeContext): ControlPacketV5Slice5a {
            val discriminator = MqttFixedHeaderCodec.decode(buffer, context)
            val ctx = context.with(DiscriminatorKey, discriminator)
            val _bodyLen = MqttFixedHeader.readBodyLength(buffer)
            val _bodySlice = buffer.readBytes(_bodyLen)
            val type = discriminator.packetType
            val _result = when (type) {
              3 -> com.ditchoom.codec.test.ControlPacketV5Slice5aPublishCodec.decodeFromContext(_bodySlice, ctx)
              12 -> com.ditchoom.codec.test.ControlPacketV5Slice5aPingReqCodec.decode(_bodySlice, ctx)
              else -> throw IllegalArgumentException("Unknown discriminator: ${'$'}type")
            }
            if (_bodySlice.remaining() != 0) {
                throw IllegalArgumentException("Variant decoder consumed ${'$'}{_bodyLen - _bodySlice.remaining()} of " +
                    "${'$'}_bodyLen body bytes; ${'$'}{_bodySlice.remaining()} unread. " +
                    "Wire is malformed or variant codec is buggy.")
            }
            return _result
          }

          override fun encode(
            buffer: WriteBuffer,
            `value`: ControlPacketV5Slice5a,
            context: EncodeContext,
          ) {
            when (value) {
                is ControlPacketV5Slice5a.PingReq -> {
                    MqttFixedHeaderCodec.encode(buffer, MqttFixedHeader(12.toUByte()), context)
                    val _len_body = ControlPacketV5Slice5aPingReqCodec.wireSize(value, context)
                    MqttFixedHeader.writeBodyLength(buffer, _len_body)
                    ControlPacketV5Slice5aPingReqCodec.encode(buffer, value, context)
                }
                is ControlPacketV5Slice5a.Publish<*> -> {
                    val _len_body = ControlPacketV5Slice5aPublishCodec.wireSizeFromContext(value, context)
                    MqttFixedHeader.writeBodyLength(buffer, _len_body)
                    ControlPacketV5Slice5aPublishCodec.encodeFromContext(buffer, value, context)
                }
            }
          }

          override fun wireSize(`value`: ControlPacketV5Slice5a, context: EncodeContext): Int = when (value) {
              is ControlPacketV5Slice5a.PingReq -> MqttFixedHeaderCodec.wireSize(MqttFixedHeader(12.toUByte()), context) + run { val _b = com.ditchoom.codec.test.ControlPacketV5Slice5aPingReqCodec.wireSize(value, context); com.ditchoom.codec.test.MqttFixedHeader.bodyLengthSize(_b) + _b }
              is ControlPacketV5Slice5a.Publish<*> -> run { val _b = com.ditchoom.codec.test.ControlPacketV5Slice5aPublishCodec.wireSizeFromContext(value, context); com.ditchoom.codec.test.MqttFixedHeader.bodyLengthSize(_b) + _b }
          }

          public fun <P> decode(
            buffer: ReadBuffer,
            context: DecodeContext = DecodeContext.Empty,
            decodePublishPayload: ControlPacketV5Slice5aPublishContext.(ReadBuffer) -> P,
          ): ControlPacketV5Slice5a {
            val discriminator = MqttFixedHeaderCodec.decode(buffer, context)
            val ctx = context.with(DiscriminatorKey, discriminator)
            val _bodyLen = MqttFixedHeader.readBodyLength(buffer)
            val _bodySlice = buffer.readBytes(_bodyLen)
            val type = discriminator.packetType
            val _result = when (type) {
              3 -> com.ditchoom.codec.test.ControlPacketV5Slice5aPublishCodec.decode(_bodySlice, ctx, decodePublishPayload)
              12 -> com.ditchoom.codec.test.ControlPacketV5Slice5aPingReqCodec.decode(_bodySlice, ctx)
              else -> throw IllegalArgumentException("Unknown discriminator: ${'$'}type")
            }
            if (_bodySlice.remaining() != 0) {
                throw IllegalArgumentException("Variant decoder consumed ${'$'}{_bodyLen - _bodySlice.remaining()} of " +
                    "${'$'}_bodyLen body bytes; ${'$'}{_bodySlice.remaining()} unread. " +
                    "Wire is malformed or variant codec is buggy.")
            }
            return _result
          }

          public fun <P> encode(
            buffer: WriteBuffer,
            `value`: ControlPacketV5Slice5a,
            context: EncodeContext = EncodeContext.Empty,
            encodePublishPayload: (WriteBuffer, P) -> Unit,
          ) {
            when (value) {
                is ControlPacketV5Slice5a.PingReq -> {
                    MqttFixedHeaderCodec.encode(buffer, MqttFixedHeader(12.toUByte()), context)
                    val _len_body = ControlPacketV5Slice5aPingReqCodec.wireSize(value, context)
                    MqttFixedHeader.writeBodyLength(buffer, _len_body)
                    ControlPacketV5Slice5aPingReqCodec.encode(buffer, value, context)
                }
                is ControlPacketV5Slice5a.Publish<*> -> {
                    val _scratch = BufferFactory.Default.allocate(buffer.remaining().coerceAtLeast(16), buffer.byteOrder)
                    @Suppress("UNCHECKED_CAST") ControlPacketV5Slice5aPublishCodec.encode(_scratch, value as ControlPacketV5Slice5a.Publish<P>, context, encodePublishPayload)
                    _scratch.resetForRead()
                    MqttFixedHeader.writeBodyLength(buffer, _scratch.remaining())
                    buffer.write(_scratch)
                }
            }
          }

          public fun <P> wireSize(
            `value`: ControlPacketV5Slice5a,
            context: EncodeContext = EncodeContext.Empty,
            sizePublishPayload: (P) -> Int,
          ): Int = when (value) {
              is ControlPacketV5Slice5a.PingReq -> MqttFixedHeaderCodec.wireSize(MqttFixedHeader(12.toUByte()), context) + run { val _b = com.ditchoom.codec.test.ControlPacketV5Slice5aPingReqCodec.wireSize(value, context); com.ditchoom.codec.test.MqttFixedHeader.bodyLengthSize(_b) + _b }
              is ControlPacketV5Slice5a.Publish<*> -> run { val _b = @Suppress("UNCHECKED_CAST") com.ditchoom.codec.test.ControlPacketV5Slice5aPublishCodec.wireSize(value as com.ditchoom.codec.test.ControlPacketV5Slice5a.Publish<P>, context, sizePublishPayload); com.ditchoom.codec.test.MqttFixedHeader.bodyLengthSize(_b) + _b }
          }

          override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = MqttFixedHeader.peekFrameSize(stream, baseOffset)

          public data object DiscriminatorKey : CodecContext.Key<MqttFixedHeader>()
        }
        """.trimIndent()

    /**
     * Slice 5a hard-bar fixture — `Plan.Leaf` with a variable-size SPI field
     * (descriptor.fixedSize == -1, descriptor.wireSizeRaw set). Pins the
     * substitution into `wireSize` (`+ VarSizePayloadCodec.wireSize(value.value)`).
     *
     * Note: the SPI strategy's `descriptor.raw` is used at both decode and encode
     * call-sites in the current IR. The `encode` body shows the same expression
     * as decode — this is a pre-existing limitation tracked for Slice 6 (split
     * raw-decode / raw-encode strings).
     */
    val VariableSizeSpiLeaf =
        """
        package com.ditchoom.codec.test

        import com.ditchoom.buffer.ReadBuffer
        import com.ditchoom.buffer.WriteBuffer
        import com.ditchoom.buffer.codec.Codec
        import com.ditchoom.buffer.codec.DecodeContext
        import com.ditchoom.buffer.codec.EncodeContext
        import com.ditchoom.buffer.stream.PeekResult
        import com.ditchoom.buffer.stream.StreamProcessor
        import com.ditchoom.buffer.stream.SuspendingStreamProcessor
        import kotlin.Int

        public object VariableSizeSpiLeafCodec : Codec<VariableSizeSpiLeaf> {
          public const val MIN_HEADER_BYTES: Int = 1

          override fun decode(buffer: ReadBuffer, context: DecodeContext): VariableSizeSpiLeaf {
            val kind = buffer.readUnsignedByte()
            val value = VarSizePayloadCodec.decode(buffer, context)
            return VariableSizeSpiLeaf(kind = kind, value = value)
          }

          override fun encode(
            buffer: WriteBuffer,
            `value`: VariableSizeSpiLeaf,
            context: EncodeContext,
          ) {
            buffer.writeUByte(value.kind)
            VarSizePayloadCodec.decode(buffer, context)
          }

          override fun wireSize(`value`: VariableSizeSpiLeaf, context: EncodeContext): Int = 1 + VarSizePayloadCodec.wireSize(value.value)

          override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.Size(1)

          public suspend fun peekFrameSize(stream: SuspendingStreamProcessor, baseOffset: Int = 0): PeekResult = PeekResult.Size(1)
        }
        """.trimIndent()
}
