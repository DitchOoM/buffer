package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
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
    Le32LengthCodec.applyBound(buffer, length)
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

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 3) return PeekResult.NeedsMoreData
    val __lengthPeekView = stream.peekBuffer(baseOffset + 2, 5) ?: return PeekResult.NeedsMoreData
    try {
      val __lengthPriorPos = __lengthPeekView.position()
      val length = try {
        Le32LengthCodec.decode(__lengthPeekView, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return PeekResult.NeedsMoreData
          else -> throw __e
        }
      }
      val __lengthWidth = __lengthPeekView.position() - __lengthPriorPos
      val __total = 2 + __lengthWidth + length.toInt()
      return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
    } finally {
      (__lengthPeekView as? PlatformBuffer)?.freeNativeMemory()
    }
  }

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val tag = buffer.readShort()
    val __lengthOuterLimit = buffer.limit()
    val length = Le32LengthCodec.decode(buffer, context)
    Le32LengthCodec.applyBound(buffer, length)
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
