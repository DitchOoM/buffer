package com.ditchoom.buffer.codec.test.protocols.partialnonterminal

import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * `@RemainingBytes` payload with a **fixed-size trailer** (issue #168). The
 * payload is non-terminal — a `checksum` follows it — so before #168 no
 * `Partial` was generated. Now `CommandFrameCodec<P>` emits a `Partial<P>`:
 * `partial(...)` reads `counter`/`length`/`checksum` eagerly and defers the
 * payload; `complete(payloadCodec)` decodes the deferred payload region.
 *
 * Wire (big-endian): `counter(2) | length(2) | payload(...) | checksum(2)`.
 * The payload's extent is `limit - 2` (the trailing checksum), so the frame
 * is caller-bounded exactly like any other `@RemainingBytes` body.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class CommandFrame<P : Payload>(
    val counter: UShort,
    val length: UShort,
    @RemainingBytes val payload: P,
    val checksum: UShort,
)
