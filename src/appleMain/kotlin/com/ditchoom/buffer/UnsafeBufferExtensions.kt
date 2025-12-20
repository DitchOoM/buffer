@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSData
import platform.Foundation.dataWithBytesNoCopy

/**
 * Wraps the UnsafeBuffer's memory as an NSData object for zero-copy interop with Apple APIs.
 *
 * IMPORTANT: The returned NSData is only valid while the UnsafeBuffer is alive.
 * Do not use the NSData after calling close() on the buffer.
 *
 * @param length The number of bytes to wrap (defaults to remaining bytes from position to limit)
 * @return An NSData view of the buffer's memory
 */
@OptIn(UnsafeNumber::class)
fun DefaultUnsafeBuffer.asNSData(length: Int = remaining()): NSData {
    val ptr = address.toCPointer<ByteVar>()
    val offset = position()
    return NSData.dataWithBytesNoCopy(
        bytes = ptr?.plus(offset),
        length = length.toULong(),
        freeWhenDone = false, // Buffer manages its own memory
    )
}

/**
 * Executes a block with the buffer's memory wrapped as NSData.
 * This is safer than [asNSData] as it ensures the NSData is only used within the scope.
 *
 * @param length The number of bytes to wrap (defaults to remaining bytes from position to limit)
 * @param block The block to execute with the NSData
 * @return The result of the block
 */
@OptIn(UnsafeNumber::class)
inline fun <R> DefaultUnsafeBuffer.useAsNSData(
    length: Int = remaining(),
    block: (NSData) -> R,
): R {
    val nsData = asNSData(length)
    return block(nsData)
}

@Suppress("UNCHECKED_CAST")
private inline fun <reified T : kotlinx.cinterop.CPointed> Long.toCPointer(): CPointer<T>? =
    kotlinx.cinterop.interpretCPointer(kotlinx.cinterop.NativePtr.NULL + this)

private fun CPointer<ByteVar>.plus(offset: Int): CPointer<ByteVar>? =
    (kotlinx.cinterop.NativePtr.NULL + (this.rawValue.toLong() + offset)).let {
        kotlinx.cinterop.interpretCPointer(it)
    }
