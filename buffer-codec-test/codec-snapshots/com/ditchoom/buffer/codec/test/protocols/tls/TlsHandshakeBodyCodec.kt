package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress
import kotlin.UShort

public object TlsHandshakeBodyCodec : Codec<TlsHandshakeBody> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TlsHandshakeBody {
    val legacyVersionRaw = buffer.readShort()
    val legacyVersion = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) legacyVersionRaw else swapBytes(legacyVersionRaw)).toUShort()
    val random = BinaryDataCodec.decode(buffer, context)
    return TlsHandshakeBody(legacyVersion = legacyVersion, random = random)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: TlsHandshakeBody,
    context: EncodeContext,
  ) {
    val legacyVersionRaw = value.legacyVersion.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) legacyVersionRaw else swapBytes(legacyVersionRaw))
    BinaryDataCodec.encode(buffer, value.random, context)
  }

  override fun wireBytes(`value`: TlsHandshakeBody, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: TlsHandshakeBody, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: TlsHandshakeBody, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val legacyVersionRaw = buffer.readShort()
    val legacyVersion = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) legacyVersionRaw else swapBytes(legacyVersionRaw)).toUShort()
    return Partial(legacyVersion = legacyVersion, buffer = buffer, context = context)
  }

  public class Partial internal constructor(
    public val legacyVersion: UShort,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(): TlsHandshakeBody {
      val random = BinaryDataCodec.decode(buffer, context)
      return TlsHandshakeBody(legacyVersion = legacyVersion, random = random)
    }
  }
}
