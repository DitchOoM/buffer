package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object DeviceStateCodec : Codec<DeviceState> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): DeviceState {
    val deviceId = buffer.readUByte()
    val connection = ConnectionStatusCodec.decode(buffer, context)
    return DeviceState(deviceId = deviceId, connection = connection)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: DeviceState,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.deviceId)
    ConnectionStatusCodec.encode(buffer, value.connection, context)
  }

  override fun wireSize(`value`: DeviceState, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: DeviceState, context: EncodeContext): Int = 1 + ConnectionStatusCodec.sizeHint(value.connection, context)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
