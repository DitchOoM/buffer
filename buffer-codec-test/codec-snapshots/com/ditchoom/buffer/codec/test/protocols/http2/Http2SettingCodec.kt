package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object Http2SettingCodec : Codec<Http2Setting> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http2Setting {
    val identifierRaw = buffer.readShort()
    val identifier = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) identifierRaw else swapBytes(identifierRaw)).toUShort()
    val valueRaw = buffer.readInt()
    val value = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) valueRaw else swapBytes(valueRaw)).toUInt()
    return Http2Setting(identifier = identifier, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http2Setting,
    context: EncodeContext,
  ) {
    val identifierRaw = value.identifier.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) identifierRaw else swapBytes(identifierRaw))
    val valueRaw = value.value.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) valueRaw else swapBytes(valueRaw))
  }

  override fun wireSize(`value`: Http2Setting, context: EncodeContext): WireSize = WireSize.Exact(6)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 6) PeekResult.Complete(6) else PeekResult.NeedsMoreData
}
