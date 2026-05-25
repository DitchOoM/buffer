package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Regression fixture for issue #156 — two sealed interfaces in the same
 * package each carry a nested `data class LedStateSet`. Pre-fix, both
 * resolved to `LedStateSetCodec` and KSP threw `FileAlreadyExistsException`
 * at the second file write. Post-fix, generated codec names flatten across
 * the enclosing-type chain: `Issue156CommandLedStateSetCodec` and
 * `Issue156ResponseLedStateSetCodec`.
 *
 * Names are prefixed with `Issue156` because this file shares a package
 * with `CommandPayloadProtocol.kt`, which already owns `CommandPayload`.
 */
@ProtocolMessage(wireOrder = Endianness.Little)
sealed interface Issue156Command {
    @PacketType(0x20)
    @ProtocolMessage
    data class LedStateSet(
        val ledId: UByte,
        val duty: UShort,
    ) : Issue156Command
}

@ProtocolMessage(wireOrder = Endianness.Little)
sealed interface Issue156Response {
    @PacketType(0x20)
    @ProtocolMessage
    data class LedStateSet(
        val ledId: UByte,
        val duty: UShort,
    ) : Issue156Response
}
