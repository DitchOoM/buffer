package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WhenTrue

/**
 * Test message with multiple consecutive @WhenTrue fields interleaved with fixed-size fields.
 * Validates that conditional fields correctly break batch boundaries.
 */
@ProtocolMessage
data class ConditionalBatchTestMessage(
    val header: UByte,
    val hasCond1: Boolean,
    val hasCond2: Boolean,
    @WhenTrue("hasCond1") val cond1: UByte? = null,
    @WhenTrue("hasCond2") val cond2: UByte? = null,
    val trailer: UByte,
)
