package com.ditchoom.buffer.codec.test.protocols.slice10e

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize

/**
 * JVM `actual` for the slice 10e expect codec. Delegates to
 * `RemoteCommandPayloadCodecImpl` so wire bytes match the other
 * platform actuals; the `actual object` declaration is what the
 * Kotlin linker resolves the generated code's
 * `RemoteCommandPayloadCodec.decode(...)` call against on JVM.
 */
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
