package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.PlatformBuffer
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
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Short
import kotlin.Suppress
import kotlin.Throwable

public object SignedBoundedFrameCodec : Codec<SignedBoundedFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedBoundedFrame {
    val tag = buffer.readShort()
    val __lengthOuterLimit = buffer.limit()
    val length = SignedBe32LengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, length.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "SignedBoundedFrame.length",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + length + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    SignedBe32LengthCodec.applyBound(buffer, length)
    if (buffer.limit() > __lengthOuterLimit) {
      val __widenedLimit = buffer.limit()
      buffer.setLimit(__lengthOuterLimit)
      throw DecodeException(
            fieldPath = "SignedBoundedFrame.length",
            bufferPosition = buffer.position(),
            expected = "applyBound to narrow within the enclosing limit " + __lengthOuterLimit,
            actual = "limit " + __widenedLimit,
          )
    }
    return try {
      val payload = BinaryDataCodec.decode(buffer, context)
      SignedBoundedFrame(tag = tag, length = length, payload = payload)
    } finally {
      buffer.setLimit(__lengthOuterLimit)
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedBoundedFrame,
    context: EncodeContext,
  ) {
    buffer.writeShort(value.tag)
    SignedBe32LengthCodec.encode(buffer, value.length, context)
    BinaryDataCodec.encode(buffer, value.payload, context)
  }

  override fun wireBytes(`value`: SignedBoundedFrame, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: SignedBoundedFrame, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: SignedBoundedFrame, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 3) return FrameSize.NeedsMoreData
    val __lengthPeekView = stream.peekBuffer(baseOffset + 2, 5) ?: return FrameSize.NeedsMoreData
    try {
      val __lengthPriorPos = __lengthPeekView.position()
      val length = try {
        SignedBe32LengthCodec.decode(__lengthPeekView, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return FrameSize.NeedsMoreData
          else -> throw __e
        }
      }
      val __lengthWidth = __lengthPeekView.position() - __lengthPriorPos
      return FrameSize.declared(2 + __lengthWidth, length.toLong())
    } finally {
      (__lengthPeekView as? PlatformBuffer)?.freeNativeMemory()
    }
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val tag = buffer.readShort()
    val __lengthOuterLimit = buffer.limit()
    val length = SignedBe32LengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, length.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "SignedBoundedFrame.length",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + length + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    SignedBe32LengthCodec.applyBound(buffer, length)
    if (buffer.limit() > __lengthOuterLimit) {
      val __widenedLimit = buffer.limit()
      buffer.setLimit(__lengthOuterLimit)
      throw DecodeException(
            fieldPath = "SignedBoundedFrame.length",
            bufferPosition = buffer.position(),
            expected = "applyBound to narrow within the enclosing limit " + __lengthOuterLimit,
            actual = "limit " + __widenedLimit,
          )
    }
    return Partial(tag = tag, length = length, outerLimit = __lengthOuterLimit, buffer = buffer, context = context)
  }

  public class Partial internal constructor(
    public val tag: Short,
    public val length: Int,
    private val outerLimit: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(): SignedBoundedFrame = try {
      val payload = BinaryDataCodec.decode(buffer, context)
      SignedBoundedFrame(tag = tag, length = length, payload = payload)
    } finally {
      buffer.setLimit(outerLimit)
    }
  }
}
