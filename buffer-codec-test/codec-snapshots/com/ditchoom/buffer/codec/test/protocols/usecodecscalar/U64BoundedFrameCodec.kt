package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Short
import kotlin.Throwable
import kotlin.ULong

public object U64BoundedFrameCodec : Codec<U64BoundedFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): U64BoundedFrame {
    val tag = buffer.readShort()
    val __lengthOuterLimit = buffer.limit()
    val length = Be64LengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, length) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "U64BoundedFrame.length",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + length + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    Be64LengthCodec.applyBound(buffer, length)
    if (buffer.limit() > __lengthOuterLimit) {
      val __widenedLimit = buffer.limit()
      buffer.setLimit(__lengthOuterLimit)
      throw DecodeException(
            fieldPath = "U64BoundedFrame.length",
            bufferPosition = buffer.position(),
            expected = "applyBound to narrow within the enclosing limit " + __lengthOuterLimit,
            actual = "limit " + __widenedLimit,
          )
    }
    return try {
      val payload = BinaryDataCodec.decode(buffer, context)
      U64BoundedFrame(tag = tag, length = length, payload = payload)
    } finally {
      buffer.setLimit(__lengthOuterLimit)
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: U64BoundedFrame,
    context: EncodeContext,
  ) {
    buffer.writeShort(value.tag)
    Be64LengthCodec.encode(buffer, value.length, context)
    BinaryDataCodec.encode(buffer, value.payload, context)
  }

  override fun wireSize(`value`: U64BoundedFrame, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: U64BoundedFrame, context: EncodeContext): Int = 2

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 3) return PeekResult.NeedsMoreData
    val __lengthPeekView = stream.peekBuffer(baseOffset + 2, 10) ?: return PeekResult.NeedsMoreData
    try {
      val __lengthPriorPos = __lengthPeekView.position()
      val length = try {
        Be64LengthCodec.decode(__lengthPeekView, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return PeekResult.NeedsMoreData
          else -> throw __e
        }
      }
      val __lengthWidth = __lengthPeekView.position() - __lengthPriorPos
      val __total = declaredFrameBytes(2 + __lengthWidth, length)
      return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
    } finally {
      (__lengthPeekView as? PlatformBuffer)?.freeNativeMemory()
    }
  }

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val tag = buffer.readShort()
    val __lengthOuterLimit = buffer.limit()
    val length = Be64LengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, length) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "U64BoundedFrame.length",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + length + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    Be64LengthCodec.applyBound(buffer, length)
    if (buffer.limit() > __lengthOuterLimit) {
      val __widenedLimit = buffer.limit()
      buffer.setLimit(__lengthOuterLimit)
      throw DecodeException(
            fieldPath = "U64BoundedFrame.length",
            bufferPosition = buffer.position(),
            expected = "applyBound to narrow within the enclosing limit " + __lengthOuterLimit,
            actual = "limit " + __widenedLimit,
          )
    }
    return Partial(tag = tag, length = length, outerLimit = __lengthOuterLimit, buffer = buffer, context = context)
  }

  public class Partial internal constructor(
    public val tag: Short,
    public val length: ULong,
    private val outerLimit: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(): U64BoundedFrame = try {
      val payload = BinaryDataCodec.decode(buffer, context)
      U64BoundedFrame(tag = tag, length = length, payload = payload)
    } finally {
      buffer.setLimit(outerLimit)
    }
  }
}
