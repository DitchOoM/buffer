package com.ditchoom.buffer.codec.test.protocols.crosspkg

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Element type living in a different package from the host @ProtocolMessage that wraps it
 * in a `@LengthPrefixed Collection<X>`. Probe 19 (in V5GapProbe.kt) declares the host in
 * `protocols.*` and the codec processor must add an import for this codec into the host's
 * generated codec file.
 */
@ProtocolMessage
data class CrossPkgEntry(
    val key: UByte,
    @LengthPrefixed val value: String,
)
