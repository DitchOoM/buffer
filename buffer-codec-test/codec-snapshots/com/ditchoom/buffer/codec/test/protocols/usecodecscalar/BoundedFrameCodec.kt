package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Short
import kotlin.Throwable
import kotlin.UInt

public object BoundedFrameCodec : Codec<BoundedFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BoundedFrame {
    val tag = buffer.readShort()
    val __lengthOuterLimit = buffer.limit()
    val length = Le32LengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, length.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "BoundedFrame.length",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + length + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    Le32LengthCodec.applyBound(buffer, length)
    if (buffer.limit() > __lengthOuterLimit) {
      val __widenedLimit = buffer.limit()
      buffer.setLimit(__lengthOuterLimit)
      throw DecodeException(
            fieldPath = "BoundedFrame.length",
            bufferPosition = buffer.position(),
            expected = "applyBound to narrow within the enclosing limit " + __lengthOuterLimit,
            actual = "limit " + __widenedLimit,
          )
    }
    return try {
      val payload = BinaryDataCodec.decode(buffer, context)
      BoundedFrame(tag = tag, length = length, payload = payload)
    } finally {
      buffer.setLimit(__lengthOuterLimit)
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BoundedFrame,
    context: EncodeContext,
  ) {
    buffer.writeShort(value.tag)
    Le32LengthCodec.encode(buffer, value.length, context)
    BinaryDataCodec.encode(buffer, value.payload, context)
  }

  override fun wireSize(`value`: BoundedFrame, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: BoundedFrame, context: EncodeContext): Int = 2

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek {
    if (stream.available() - baseOffset < 3) return FramePeek.needsMoreData
    val __lengthPeekView = stream.peekBuffer(baseOffset + 2, 5) ?: return FramePeek.needsMoreData
    try {
      val __lengthPriorPos = __lengthPeekView.position()
      val length = try {
        Le32LengthCodec.decode(__lengthPeekView, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return FramePeek.needsMoreData
          else -> throw __e
        }
      }
      val __lengthWidth = __lengthPeekView.position() - __lengthPriorPos
      val __total = declaredFrameBytes(2 + __lengthWidth, length.toLong())
      return FramePeek.forDeclaredSize(__total, stream.available() - baseOffset)
    } finally {
      (__lengthPeekView as? PlatformBuffer)?.freeNativeMemory()
    }
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val tag = buffer.readShort()
    val __lengthOuterLimit = buffer.limit()
    val length = Le32LengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, length.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "BoundedFrame.length",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + length + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    Le32LengthCodec.applyBound(buffer, length)
    if (buffer.limit() > __lengthOuterLimit) {
      val __widenedLimit = buffer.limit()
      buffer.setLimit(__lengthOuterLimit)
      throw DecodeException(
            fieldPath = "BoundedFrame.length",
            bufferPosition = buffer.position(),
            expected = "applyBound to narrow within the enclosing limit " + __lengthOuterLimit,
            actual = "limit " + __widenedLimit,
          )
    }
    return Partial(tag = tag, length = length, outerLimit = __lengthOuterLimit, buffer = buffer, context = context)
  }

  public class Partial internal constructor(
    public val tag: Short,
    public val length: UInt,
    private val outerLimit: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(): BoundedFrame = try {
      val payload = BinaryDataCodec.decode(buffer, context)
      BoundedFrame(tag = tag, length = length, payload = payload)
    } finally {
      buffer.setLimit(outerLimit)
    }
  }
}
