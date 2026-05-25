package com.ditchoom.buffer.codec.test.protocols.slice10e

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.String

public object RemoteCommandCodec : Codec<RemoteCommand> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RemoteCommand {
    val idPrefixB0 = buffer.readUByte().toUInt()
    val idPrefixB1 = buffer.readUByte().toUInt()
    val idPrefix = ((idPrefixB0 shl 8) or idPrefixB1)
    if (idPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "RemoteCommand.id", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = idPrefix.toString())
    }
    val idLength = idPrefix.toInt()
    val id = buffer.readString(idLength, Charset.UTF8)
    val payload = RemoteCommandPayloadCodec.decode(buffer, context)
    return RemoteCommand(id = id, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RemoteCommand,
    context: EncodeContext,
  ) {
    val idSizePosition = buffer.position()
    buffer.position(idSizePosition + 2)
    val idBodyStart = buffer.position()
    buffer.writeString(value.id, Charset.UTF8)
    val idEndPosition = buffer.position()
    val idByteCount = idEndPosition - idBodyStart
    if (idByteCount > 65_535) {
      throw EncodeException(fieldPath = "RemoteCommand.id", reason = """UTF-8 byte length ${idByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(idSizePosition)
    val idPrefix = idByteCount.toUInt()
    buffer.writeUByte(((idPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((idPrefix and 0xFFu).toUByte())
    buffer.position(idEndPosition)
    RemoteCommandPayloadCodec.encode(buffer, value.payload, context)
  }

  override fun wireSize(`value`: RemoteCommand, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val idPrefixB0 = buffer.readUByte().toUInt()
    val idPrefixB1 = buffer.readUByte().toUInt()
    val idPrefix = ((idPrefixB0 shl 8) or idPrefixB1)
    if (idPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "RemoteCommand.id", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = idPrefix.toString())
    }
    val idLength = idPrefix.toInt()
    val id = buffer.readString(idLength, Charset.UTF8)
    return Partial(id = id, buffer = buffer, context = context)
  }

  public class Partial internal constructor(
    public val id: String,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(): RemoteCommand {
      val payload = RemoteCommandPayloadCodec.decode(buffer, context)
      return RemoteCommand(id = id, payload = payload)
    }
  }
}
