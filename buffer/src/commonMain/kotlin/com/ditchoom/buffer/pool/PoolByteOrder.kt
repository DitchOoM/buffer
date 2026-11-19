package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer

/**
 * Re-applies [order] to the storage behind this buffer.
 *
 * On JVM/Android a buffer's multi-byte reads and writes follow the live `java.nio.ByteBuffer`
 * order, which a holder of the platform buffer can change after [PlatformBuffer.byteOrder] was
 * captured. Everywhere else the order is fixed at construction and this does nothing.
 */
internal expect fun PlatformBuffer.pinByteOrder(order: ByteOrder)

/**
 * The byte order a pool hands its buffers out in: the order its factory gave the first buffer
 * the pool allocated itself. Nothing is pooled before that, so a pool never has to guess.
 */
internal sealed interface DeclaredOrder {
    /** Whether [buffer] may be pooled: only buffers already in the declared order are. */
    fun accepts(buffer: PlatformBuffer): Boolean

    /** Puts [buffer]'s live storage back in the declared order. */
    fun pin(buffer: PlatformBuffer)

    /** No buffer has been allocated yet. */
    data object Undeclared : DeclaredOrder {
        override fun accepts(buffer: PlatformBuffer) = false

        override fun pin(buffer: PlatformBuffer) = Unit
    }

    class Declared(
        val order: ByteOrder,
    ) : DeclaredOrder {
        override fun accepts(buffer: PlatformBuffer) = buffer.byteOrder == order

        override fun pin(buffer: PlatformBuffer) = buffer.pinByteOrder(order)
    }
}
