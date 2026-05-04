package com.ditchoom.buffer.codec.test.protocols.slice10e

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize

/** JS `actual` — delegates to the shared impl. See JVM peer for rationale. */
actual object RemoteCommandPayloadCodec : Codec<RemoteCommandPayload> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): RemoteCommandPayload = RemoteCommandPayloadCodecImpl.decode(buffer, context)

    override fun encode(
        buffer: WriteBuffer,
        value: RemoteCommandPayload,
        context: EncodeContext,
    ) = RemoteCommandPayloadCodecImpl.encode(buffer, value, context)

    override fun wireSize(
        value: RemoteCommandPayload,
        context: EncodeContext,
    ): WireSize = RemoteCommandPayloadCodecImpl.wireSize(value, context)
}
