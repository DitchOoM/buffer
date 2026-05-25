package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object Http2SettingCodec : Codec<Http2Setting> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http2Setting {
    val identifierB0 = buffer.readUByte().toUInt()
    val identifierB1 = buffer.readUByte().toUInt()
    val identifier = ((identifierB0 shl 8) or identifierB1).toUShort()
    val valueB0 = buffer.readUByte().toUInt()
    val valueB1 = buffer.readUByte().toUInt()
    val valueB2 = buffer.readUByte().toUInt()
    val valueB3 = buffer.readUByte().toUInt()
    val value = ((valueB0 shl 24) or (valueB1 shl 16) or (valueB2 shl 8) or valueB3)
    return Http2Setting(identifier = identifier, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http2Setting,
    context: EncodeContext,
  ) {
    buffer.writeUByte(((value.identifier.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.identifier.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.value shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((value.value shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.value shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.value and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: Http2Setting, context: EncodeContext): WireSize = WireSize.Exact(6)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 6) PeekResult.Complete(6) else PeekResult.NeedsMoreData
}
