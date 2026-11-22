package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object Http3FcSettingCodec : Codec<Http3FcSetting> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http3FcSetting {
    val identifier = QuicVarintCodec.decode(buffer, context)
    val value = QuicVarintCodec.decode(buffer, context)
    return Http3FcSetting(identifier = identifier, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http3FcSetting,
    context: EncodeContext,
  ) {
    QuicVarintCodec.encode(buffer, value.identifier, context)
    QuicVarintCodec.encode(buffer, value.value, context)
  }

  override fun wireBytes(`value`: Http3FcSetting, context: EncodeContext): WireByteCount {
    val __identifierSize = QuicVarintCodec.encodedLength(value.identifier).toLong()
    val __valueSize = QuicVarintCodec.encodedLength(value.value).toLong()
    return WireByteCount.exact(0L + __identifierSize + __valueSize)
  }

  override fun sizeHint(`value`: Http3FcSetting, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed
}
