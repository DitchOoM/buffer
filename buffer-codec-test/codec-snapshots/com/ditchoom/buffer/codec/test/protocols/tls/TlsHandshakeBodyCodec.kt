package com.ditchoom.buffer.codec.test.protocols.tls

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
import kotlin.UShort

public object TlsHandshakeBodyCodec : Codec<TlsHandshakeBody> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TlsHandshakeBody {
    val legacyVersionB0 = buffer.readUByte().toUInt()
    val legacyVersionB1 = buffer.readUByte().toUInt()
    val legacyVersion = ((legacyVersionB0 shl 8) or legacyVersionB1).toUShort()
    val random = BinaryDataCodec.decode(buffer, context)
    return TlsHandshakeBody(legacyVersion = legacyVersion, random = random)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: TlsHandshakeBody,
    context: EncodeContext,
  ) {
    buffer.writeUByte(((value.legacyVersion.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.legacyVersion.toUInt() and 0xFFu).toUByte())
    BinaryDataCodec.encode(buffer, value.random, context)
  }

  override fun wireSize(`value`: TlsHandshakeBody, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val legacyVersionB0 = buffer.readUByte().toUInt()
    val legacyVersionB1 = buffer.readUByte().toUInt()
    val legacyVersion = ((legacyVersionB0 shl 8) or legacyVersionB1).toUShort()
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
