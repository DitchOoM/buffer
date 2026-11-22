package com.ditchoom.buffer.codec.test.protocols.slice10e

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.String
import kotlin.Suppress

public object RemoteCommandCodec : Codec<RemoteCommand> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RemoteCommand {
    val idPrefixB0 = buffer.readUByte().toUInt()
    val idPrefixB1 = buffer.readUByte().toUInt()
    val idPrefix = ((idPrefixB0 shl 8) or idPrefixB1)
    if (idPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = idPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val idLength = idPrefix.toInt()
    val id = buffer.readText(idLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val payload = RemoteCommandPayloadCodec.decode(buffer, context)
    return RemoteCommand(id = id, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RemoteCommand,
    context: EncodeContext,
  ) {
    val idSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val idBodyStart = buffer.position()
    buffer.writeText(value.id, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
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

  override fun wireBytes(`value`: RemoteCommand, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: RemoteCommand, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: RemoteCommand, context: EncodeContext): Int = 2 + value.id.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val idPrefixB0 = buffer.readUByte().toUInt()
    val idPrefixB1 = buffer.readUByte().toUInt()
    val idPrefix = ((idPrefixB0 shl 8) or idPrefixB1)
    if (idPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = idPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val idLength = idPrefix.toInt()
    val id = buffer.readText(idLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
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
