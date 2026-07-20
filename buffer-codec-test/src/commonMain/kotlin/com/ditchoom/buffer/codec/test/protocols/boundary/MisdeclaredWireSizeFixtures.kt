package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec

/*
 * Adversarial fixtures for the declared-size verification guard: a user
 * codec whose `wireSize` DISAGREES with what its `encode` writes. Since the
 * @UseCodec Exact promotion, a nested message's wireSize probes user codecs
 * and parents write `@LengthPrefixed` prefixes from the declared size — a
 * misdeclaring codec must produce a loud `EncodeException`, never a frame
 * whose prefix and body disagree.
 */

/** Contract violation on purpose: declares 3 bytes, encode writes 4. */
object MisdeclaredSizeCodec : Codec<UInt> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): UInt = buffer.readUInt()

    override fun encode(
        buffer: WriteBuffer,
        value: UInt,
        context: EncodeContext,
    ) {
        buffer.writeUInt(value)
    }

    override fun wireSize(
        value: UInt,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(3)
}

/** Honest twin: declares 4 bytes, encode writes 4. */
object HonestSizeCodec : Codec<UInt> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): UInt = buffer.readUInt()

    override fun encode(
        buffer: WriteBuffer,
        value: UInt,
        context: EncodeContext,
    ) {
        buffer.writeUInt(value)
    }

    override fun wireSize(
        value: UInt,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(4)
}

@ProtocolMessage
data class MisdeclaredInner(
    @UseCodec(MisdeclaredSizeCodec::class) val v: UInt,
)

/** Terminal @LengthPrefixed nested message probing the misdeclaring codec. */
@ProtocolMessage
data class MisdeclaredHost(
    val kind: Byte,
    @LengthPrefixed val inner: MisdeclaredInner,
)

@ProtocolMessage
data class HonestInner(
    @UseCodec(HonestSizeCodec::class) val v: UInt,
)

@ProtocolMessage
data class HonestHost(
    val kind: Byte,
    @LengthPrefixed val inner: HonestInner,
)
