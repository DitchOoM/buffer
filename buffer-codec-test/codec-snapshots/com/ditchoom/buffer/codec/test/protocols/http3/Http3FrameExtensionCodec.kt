package com.ditchoom.buffer.codec.test.protocols.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress
import kotlin.ULong

public object Http3FrameExtensionCodec : Codec<Http3Frame.Extension> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http3Frame.Extension {
    val frameType = Http3FrameTypeCodec.decode(buffer, context)
    val __lengthOuterLimit = buffer.limit()
    val length = Http3LengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, length) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "Extension.length",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + length + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    Http3LengthCodec.applyBound(buffer, length)
    if (buffer.limit() > __lengthOuterLimit) {
      val __widenedLimit = buffer.limit()
      buffer.setLimit(__lengthOuterLimit)
      throw DecodeException(
            fieldPath = "Extension.length",
            bufferPosition = buffer.position(),
            expected = "applyBound to narrow within the enclosing limit " + __lengthOuterLimit,
            actual = "limit " + __widenedLimit,
          )
    }
    return try {
      val payload = BinaryDataCodec.decode(buffer, context)
      Http3Frame.Extension(frameType = frameType, length = length, payload = payload)
    } finally {
      buffer.setLimit(__lengthOuterLimit)
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http3Frame.Extension,
    context: EncodeContext,
  ) {
    Http3FrameTypeCodec.encode(buffer, value.frameType, context)
    Http3LengthCodec.encode(buffer, value.length, context)
    BinaryDataCodec.encode(buffer, value.payload, context)
  }

  override fun wireBytes(`value`: Http3Frame.Extension, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: Http3Frame.Extension, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: Http3Frame.Extension, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __priorBytes = 0
    val __frameTypeWidth = Http3FrameTypeCodec.frameSize(stream, baseOffset + __priorBytes).fold(declared = { __h, __b -> __h + __b }, needsMoreData = { return FrameSize.NeedsMoreData }, unframed = { return FrameSize.Unframed })
    if (__frameTypeWidth > stream.available() - baseOffset - __priorBytes) return FrameSize.NeedsMoreData
    __priorBytes += __frameTypeWidth.toInt()
    return Http3LengthCodec.peekLength(stream, baseOffset + __priorBytes).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(__priorBytes + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val frameType = Http3FrameTypeCodec.decode(buffer, context)
    val __lengthOuterLimit = buffer.limit()
    val length = Http3LengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, length) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "Extension.length",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + length + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    Http3LengthCodec.applyBound(buffer, length)
    if (buffer.limit() > __lengthOuterLimit) {
      val __widenedLimit = buffer.limit()
      buffer.setLimit(__lengthOuterLimit)
      throw DecodeException(
            fieldPath = "Extension.length",
            bufferPosition = buffer.position(),
            expected = "applyBound to narrow within the enclosing limit " + __lengthOuterLimit,
            actual = "limit " + __widenedLimit,
          )
    }
    return Partial(frameType = frameType, length = length, outerLimit = __lengthOuterLimit, buffer = buffer, context = context)
  }

  public class Partial internal constructor(
    public val frameType: Http3FrameType,
    public val length: ULong,
    private val outerLimit: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(): Http3Frame.Extension = try {
      val payload = BinaryDataCodec.decode(buffer, context)
      Http3Frame.Extension(frameType = frameType, length = length, payload = payload)
    } finally {
      buffer.setLimit(outerLimit)
    }
  }
}
