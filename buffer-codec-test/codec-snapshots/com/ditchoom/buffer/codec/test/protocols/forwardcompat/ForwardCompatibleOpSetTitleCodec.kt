package com.ditchoom.buffer.codec.test.protocols.forwardcompat

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress
import kotlin.Throwable

public object ForwardCompatibleOpSetTitleCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): ForwardCompatibleOp.SetTitle {
    val header = OpCode(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
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
        throw FrameTooLargeException(declaredBytes = titlePrefix.toLong(), maxBytes = Int.MAX_VALUE)
      }
      val titleLength = titlePrefix.toInt()
      val title = buffer.readText(titleLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
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
    buffer.writeText(value.title, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
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

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 2) return FrameSize.NeedsMoreData
    val __framingPeek = stream.peekBuffer(baseOffset + 1, 5) ?: return FrameSize.NeedsMoreData
    try {
      val __framingPeekStart = __framingPeek.position()
      val __framingLength = try {
        MqttRemainingLengthCodec.decode(__framingPeek, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return FrameSize.NeedsMoreData
          else -> throw __e
        }
      }
      val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart
      return FrameSize.declared(1 + __framingPrefixWidth, __framingLength.toLong())
    } finally {
      (__framingPeek as? PlatformBuffer)?.freeNativeMemory()
    }
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
