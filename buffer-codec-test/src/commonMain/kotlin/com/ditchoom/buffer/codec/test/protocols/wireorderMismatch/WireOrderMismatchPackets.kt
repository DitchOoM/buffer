package com.ditchoom.buffer.codec.test.protocols.wireorderMismatch

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Issue [#154](https://github.com/DitchOoM/buffer/issues/154):
 * `@ProtocolMessage(wireOrder = ...)` must beat the buffer's runtime
 * `byteOrder`. The wire format is the source of truth; the buffer's
 * `byteOrder` is a runtime convenience for ad-hoc reads by application
 * code, and should not influence codec encode/decode when the message
 * declares its wire order explicitly.
 *
 * Today the emitter:
 *   - Silently rejects **signed** scalars (`Short` / `Int` / `Long`)
 *     when paired with an explicit `wireOrder` (`CodecEmitter.kt:787`
 *     bails out and no codec is generated). Unsigned scalars work
 *     because the emitter reads them byte-by-byte, which is
 *     byte-order-agnostic.
 *   - Doesn't support `Float` or `Double` at all (`SUPPORTED_SCALARS`
 *     map omits them). The issue reporter's `AccelStream` example
 *     uses `Float` and so silently produces no codec.
 *
 * These two messages exercise every supported scalar shape — Boolean,
 * Byte, UByte, Short, UShort, Int, UInt, Long, ULong, Float, Double —
 * with the message-level `wireOrder` opposite to the buffer's runtime
 * `byteOrder` in the test, so the fix has to thread through every
 * read/write path.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class BigWirePacket(
    val bool: Boolean,
    val byte: Byte,
    val ubyte: UByte,
    val short: Short,
    val ushort: UShort,
    val int: Int,
    val uint: UInt,
    val long: Long,
    val ulong: ULong,
    val float: Float,
    val double: Double,
)

@ProtocolMessage(wireOrder = Endianness.Little)
data class LittleWirePacket(
    val bool: Boolean,
    val byte: Byte,
    val ubyte: UByte,
    val short: Short,
    val ushort: UShort,
    val int: Int,
    val uint: UInt,
    val long: Long,
    val ulong: ULong,
    val float: Float,
    val double: Double,
)

/**
 * Sealed-dispatch variant — exercises the same fix surface inside a
 * `@PacketType` sealed parent. Each variant is its own
 * `@ProtocolMessage` data class and goes through the same field
 * analysis as a top-level message; the dispatcher prepends the
 * 1-byte type discriminator.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface BigWireFrame {
    @ProtocolMessage(wireOrder = Endianness.Big)
    @com.ditchoom.buffer.codec.annotations.PacketType(0x01)
    data class Sample(
        val short: Short,
        val int: Int,
        val long: Long,
        val float: Float,
        val double: Double,
    ) : BigWireFrame

    @ProtocolMessage(wireOrder = Endianness.Big)
    @com.ditchoom.buffer.codec.annotations.PacketType(0x02)
    data class Status(
        val flags: UInt,
        val ratio: Double,
    ) : BigWireFrame
}
