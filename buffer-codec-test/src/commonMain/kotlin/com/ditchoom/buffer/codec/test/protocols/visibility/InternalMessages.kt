package com.ditchoom.buffer.codec.test.protocols.visibility

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Issue #175 regression guard — generated codecs must inherit the
 * visibility of their source class.
 *
 * Before the fix, codecs were always emitted `public`, so a codec for an
 * `internal` class failed to compile: `'public' function exposes its
 * 'internal' return type`. These fixtures are `internal`; the mere fact
 * that this module COMPILES proves the generated codecs are `internal`
 * too. The accompanying round-trip test additionally exercises them.
 *
 * Coverage:
 *  - [InternalPacket] — the data-class codec path (object codec).
 *  - [InternalCommand] — the sealed `@PacketType` dispatcher path plus
 *    its nested variants (each nested in an `internal` parent, so the
 *    variant codecs inherit `internal` via effective visibility).
 */
@ProtocolMessage
internal data class InternalPacket(
    val id: Int,
    @LengthPrefixed val name: String,
)

@ProtocolMessage
internal sealed interface InternalCommand {
    @ProtocolMessage
    @PacketType(0x01)
    data class Ping(
        val ts: Long,
    ) : InternalCommand

    @ProtocolMessage
    @PacketType(0x02)
    data class Echo(
        @LengthPrefixed val msg: String,
    ) : InternalCommand
}
