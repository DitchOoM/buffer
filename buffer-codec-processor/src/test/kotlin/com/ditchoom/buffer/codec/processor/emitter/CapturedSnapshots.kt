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
}
