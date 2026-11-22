package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.usecodecscalar.Le32LengthCodec
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object FramedBatchedBodyCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): FramedBatchedBody {
    val __framingOuterLimit = buffer.limit()
    val __framingLength = Le32LengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "FramedBatchedBody.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    Le32LengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val __batch1Raw = buffer.readLong()
      val __batch1 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch1Raw else swapBytes(__batch1Raw)
      val a = (__batch1 ushr 48 and 0xFFFFL).toUShort()
      val b = (__batch1 ushr 32 and 0xFFFFL).toUShort()
      val c = (__batch1 and 0xFFFFFFFFL).toUInt()
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "FramedBatchedBody.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      FramedBatchedBody(a = a, b = b, c = c)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: FramedBatchedBody,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer = FramedEncoder.encode(
    factory = factory,
    framingCodec = Le32LengthCodec,
    context = context,
  ) { buffer ->
    val __batch2 = ((value.a.toLong() and 0xFFFFL) shl 48) or ((value.b.toLong() and 0xFFFFL) shl 32) or (value.c.toLong() and 0xFFFFFFFFL)
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch2 else swapBytes(__batch2))
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = Le32LengthCodec.peekLength(stream, baseOffset + 0).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(0 + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
