package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Stage D doctrine vector for simple `@PacketType` sealed dispatch.
 *
 * `Ping` exercises the all-scalar variant path (dispatcher reports
 * `WireSize.Exact(1 + 8)`); `Echo` exercises the variable-terminal
 * variant path (dispatcher reports `WireSize.BackPatch` because the
 * variant terminal is `@LengthPrefixed val: String`).
 *
 * Wire layout:
 *   - `Ping(ts = 0x1122_3344_5566_7788)` → `01 11 22 33 44 55 66 77 88`
 *   - `Echo(msg = "hi")`                  → `02 00 02 68 69`
 */
@ProtocolMessage
sealed interface Command {
    @ProtocolMessage
    @PacketType(0x01)
    data class Ping(
        val ts: Long,
    ) : Command

    @ProtocolMessage
    @PacketType(0x02)
    data class Echo(
        @LengthPrefixed val msg: String,
    ) : Command
}
