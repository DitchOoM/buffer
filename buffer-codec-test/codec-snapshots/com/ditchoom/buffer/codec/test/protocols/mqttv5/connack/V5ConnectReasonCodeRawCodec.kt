package com.ditchoom.buffer.codec.test.protocols.mqttv5.connack

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object V5ConnectReasonCodeRawCodec : Codec<V5ConnectReasonCodeRaw> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5ConnectReasonCodeRaw {
    val raw = buffer.readUByte()
    return V5ConnectReasonCodeRaw(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5ConnectReasonCodeRaw,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.raw)
  }

  override fun wireBytes(`value`: V5ConnectReasonCodeRaw, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  override fun sizeHint(`value`: V5ConnectReasonCodeRaw, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)
}
