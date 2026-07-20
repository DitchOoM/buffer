package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int
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

  override fun wireSize(`value`: TlsHandshakeBody, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: TlsHandshakeBody, context: EncodeContext): Int = 2

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming

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
