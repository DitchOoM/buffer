package com.ditchoom.buffer.codec.test.protocols.http3

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
import kotlin.Throwable
import kotlin.ULong

public object Http3FrameDataCodec : Codec<Http3Frame.Data> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http3Frame.Data {
    val frameType = Http3FrameTypeCodec.decode(buffer, context)
    val __lengthOuterLimit = buffer.limit()
    val length = Http3LengthCodec.decode(buffer, context)
    Http3LengthCodec.applyBound(buffer, length)
    return try {
      val payload = BinaryDataCodec.decode(buffer, context)
      Http3Frame.Data(frameType = frameType, length = length, payload = payload)
    } finally {
      buffer.setLimit(__lengthOuterLimit)
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http3Frame.Data,
    context: EncodeContext,
  ) {
    Http3FrameTypeCodec.encode(buffer, value.frameType, context)
    Http3LengthCodec.encode(buffer, value.length, context)
    BinaryDataCodec.encode(buffer, value.payload, context)
  }

  override fun wireSize(`value`: Http3Frame.Data, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: Http3Frame.Data, context: EncodeContext): Int = 0

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __priorBytes = 0
    val __frameTypeFrame = Http3FrameTypeCodec.peekFrameSize(stream, baseOffset + __priorBytes)
    if (__frameTypeFrame !is PeekResult.Complete) {
      return __frameTypeFrame
    }
    __priorBytes += __frameTypeFrame.bytes
    if (stream.available() - baseOffset < __priorBytes + 1) return PeekResult.NeedsMoreData
    val __lengthPeekView = stream.peekBuffer(baseOffset + __priorBytes, 10) ?: return PeekResult.NeedsMoreData
    try {
      val __lengthPriorPos = __lengthPeekView.position()
      val length = try {
        Http3LengthCodec.decode(__lengthPeekView, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return PeekResult.NeedsMoreData
          else -> throw __e
        }
      }
      val __lengthWidth = __lengthPeekView.position() - __lengthPriorPos
      val __total = __priorBytes + __lengthWidth + length.toInt()
      return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
    } finally {
      (__lengthPeekView as? PlatformBuffer)?.freeNativeMemory()
    }
  }

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val frameType = Http3FrameTypeCodec.decode(buffer, context)
    val __lengthOuterLimit = buffer.limit()
    val length = Http3LengthCodec.decode(buffer, context)
    Http3LengthCodec.applyBound(buffer, length)
    return Partial(frameType = frameType, length = length, outerLimit = __lengthOuterLimit, buffer = buffer, context = context)
  }

  public class Partial internal constructor(
    public val frameType: Http3FrameType,
    public val length: ULong,
    private val outerLimit: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(): Http3Frame.Data = try {
      val payload = BinaryDataCodec.decode(buffer, context)
      Http3Frame.Data(frameType = frameType, length = length, payload = payload)
    } finally {
      buffer.setLimit(outerLimit)
    }
  }
}
