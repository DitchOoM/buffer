package com.ditchoom.buffer.codec.test.protocols.slice14cgeneric

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.test.protocols.slice14c.Slice14cTinyHeader
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress
import kotlin.Throwable

public object Slice14cGenericFramedDispatchHeaderedCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): Slice14cGenericFramedDispatch.Headered {
    val header = Slice14cTinyHeader(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "Headered.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val a = buffer.readUByte()
      val b = buffer.readUShort()
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "Headered.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      Slice14cGenericFramedDispatch.Headered(header = header, a = a, b = b)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: Slice14cGenericFramedDispatch.Headered,
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
    buffer.writeUByte(value.a)
    buffer.writeUShort(value.b)
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
