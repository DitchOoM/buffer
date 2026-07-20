package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
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

  override fun wireSize(`value`: Http3FcSetting, context: EncodeContext): WireSize {
    val __identifierSize = when (val __s = QuicVarintCodec.wireSize(value.identifier, context)) {
      is WireSize.Exact -> __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    val __valueSize = when (val __s = QuicVarintCodec.wireSize(value.value, context)) {
      is WireSize.Exact -> __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(0 + __identifierSize + __valueSize)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
