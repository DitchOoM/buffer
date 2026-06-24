package com.ditchoom.onebrc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/**
 * Append-only arena of station-name bytes.
 *
 * Each distinct station name is copied in exactly once (on first insert) and thereafter referenced
 * by an absolute (offset, length) into this buffer — never a per-row String or ByteArray. Backed by
 * a single growable [PlatformBuffer] from the caller's factory (deterministic native memory in the
 * showcase), so the keys cost one copy total per station rather than one per row.
 */
internal class KeyArena(
    private val factory: BufferFactory,
    initialCapacity: Int = DEFAULT_INITIAL_CAPACITY,
) {
    private var buffer: PlatformBuffer = factory.allocate(initialCapacity)
    private var capacity: Int = initialCapacity

    /** Number of bytes appended so far. */
    var length: Int = 0
        private set

    /** Absolute-access view for regionEquals/hashRange/name reads. */
    val readable: ReadBuffer get() = buffer

    /** Copies [len] bytes at absolute [offset] of [source] into the arena; returns their start offset. */
    fun append(
        source: ReadBuffer,
        offset: Int,
        len: Int,
    ): Int {
        ensureCapacity(length + len)
        val start = length
        var i = 0
        while (i < len) {
            buffer[start + i] = source[offset + i]
            i++
        }
        length += len
        return start
    }

    /** Reads an appended name back as a String (finalize-time only, not a hot path). */
    fun nameAt(
        offset: Int,
        len: Int,
    ): String {
        buffer.position(offset)
        return buffer.readString(len)
    }

    fun close() {
        buffer.freeNativeMemory()
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= capacity) return
        var newCapacity = capacity * 2
        while (newCapacity < needed) newCapacity *= 2
        val grown = factory.allocate(newCapacity)
        var i = 0
        while (i < length) {
            grown[i] = buffer[i]
            i++
        }
        buffer.freeNativeMemory()
        buffer = grown
        capacity = newCapacity
    }

    private companion object {
        /** Initial arena size: 64 KiB, large enough for the full ~10k distinct station names. */
        private const val DEFAULT_INITIAL_CAPACITY = 64 * 1024
    }
}
