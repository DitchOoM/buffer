package com.ditchoom.buffer.codec.test.protocols.forwardcompat

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Throwable

public object ForwardCompatibleOpSetTitleCodec {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): ForwardCompatibleOp.SetTitle {
    val header = OpCode(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (__framingLength.toInt() > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "SetTitle.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val titlePrefixB0 = buffer.readUByte().toUInt()
      val titlePrefixB1 = buffer.readUByte().toUInt()
      val titlePrefix = ((titlePrefixB0 shl 8) or titlePrefixB1)
      if (titlePrefix > Int.MAX_VALUE.toUInt()) {
        throw DecodeException(fieldPath = "SetTitle.title", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = titlePrefix.toString())
      }
      val titleLength = titlePrefix.toInt()
      val title = buffer.readString(titleLength, Charset.UTF8)
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "SetTitle.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      ForwardCompatibleOp.SetTitle(header = header, title = title)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: ForwardCompatibleOp.SetTitle,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer = FramedEncoder.encode(
    factory = factory,
    framingCodec = MqttRemainingLengthCodec,
    context = context,
    headerWireWidth = 1,
    writeHeader = { buffer ->
      buffer.writeUByte(value.header.raw)
    },
  ) { buffer ->
    val titleSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val titleBodyStart = buffer.position()
    buffer.writeString(value.title, Charset.UTF8)
    val titleEndPosition = buffer.position()
    val titleByteCount = titleEndPosition - titleBodyStart
    if (titleByteCount > 65_535) {
      throw EncodeException(fieldPath = "SetTitle.title", reason = """UTF-8 byte length ${titleByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(titleSizePosition)
    val titlePrefix = titleByteCount.toUInt()
    buffer.writeUByte(((titlePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((titlePrefix and 0xFFu).toUByte())
    buffer.position(titleEndPosition)
  }

  public fun peekFrameSize(stream: StreamProcessor, baseOffset: Int = 0): PeekResult {
    if (stream.available() - baseOffset < 2) return PeekResult.NeedsMoreData
    val __framingPeek = stream.peekBuffer(baseOffset + 1, 5) ?: return PeekResult.NeedsMoreData
    try {
      val __framingPeekStart = __framingPeek.position()
      val __framingLength = try {
        MqttRemainingLengthCodec.decode(__framingPeek, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return PeekResult.NeedsMoreData
          else -> throw __e
        }
      }
      val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart
      val __total = 1 + __framingPrefixWidth + __framingLength.toInt()
      return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
    } finally {
      (__framingPeek as? PlatformBuffer)?.freeNativeMemory()
    }
  }
}
