package com.ditchoom.onebrc

import androidx.collection.MutableLongObjectMap
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import kotlin.math.floor

/**
 * Per-station running aggregate. Temperatures are stored as tenths (scaled x10) integers so that
 * min/max/sum stay exact integer arithmetic. The station name is held as an absolute (offset,
 * length) into a shared [KeyArena] — no String or ByteArray per station.
 *
 * [next] forms a collision chain: if two distinct names ever share a 64-bit hash, the second is
 * linked here so results stay bit-exact.
 */
internal class Stats(
    val nameOffset: Int,
    val nameLength: Int,
) {
    var min: Int = Int.MAX_VALUE
    var max: Int = Int.MIN_VALUE
    var sum: Long = 0
    var count: Long = 0
    var next: Stats? = null

    fun record(tenths: Int) {
        if (tenths < min) min = tenths
        if (tenths > max) max = tenths
        sum += tenths
        count += 1
    }

    fun combine(other: Stats) {
        if (other.min < min) min = other.min
        if (other.max > max) max = other.max
        sum += other.sum
        count += other.count
    }
}

/**
 * Aggregation table mapping a station's name bytes to its [Stats].
 *
 * Keyed on a fast 64-bit content hash (ReadBuffer.hashRange) via Romain Guy's Swiss-table
 * [MutableLongObjectMap] — the map owns the open-addressing slots internally, so this code holds no
 * primitive arrays. Collisions are resolved by an explicit byte comparison (ReadBuffer.regionEquals)
 * against the name stored in the [KeyArena].
 */
internal class StationTable(
    factory: BufferFactory,
) {
    private val arena = KeyArena(factory)
    private val table = MutableLongObjectMap<Stats>()

    /** Records a single measurement: name = [source] bytes [nameOffset, nameOffset+nameLength). */
    fun merge(
        source: ReadBuffer,
        nameOffset: Int,
        nameLength: Int,
        tenths: Int,
    ) {
        locate(source, nameOffset, nameLength).record(tenths)
    }

    /** Folds another worker's table into this one (parallel reduction). */
    fun mergeFrom(other: StationTable) {
        other.table.forEach { _, head ->
            var node: Stats? = head
            while (node != null) {
                locate(other.arena.readable, node.nameOffset, node.nameLength).combine(node)
                node = node.next
            }
        }
    }

    private fun locate(
        source: ReadBuffer,
        nameOffset: Int,
        nameLength: Int,
    ): Stats {
        val hash = source.hashRange(nameOffset, nameLength)
        val head = table[hash]
        var node: Stats? = head
        while (node != null) {
            if (node.nameLength == nameLength &&
                source.regionEquals(nameOffset, arena.readable, node.nameOffset, nameLength)
            ) {
                return node
            }
            node = node.next
        }
        // Empty bucket, or a genuine hash collision with a different name: prepend a fresh entry.
        val created = Stats(arena.append(source, nameOffset, nameLength), nameLength)
        created.next = head
        table[hash] = created
        return created
    }

    /** Renders the canonical 1BRC output: {Name=min/mean/max, ...} sorted by name. */
    fun formatOutput(): String {
        val entries = ArrayList<Pair<String, Stats>>(table.size)
        table.forEach { _, head ->
            var node: Stats? = head
            while (node != null) {
                entries.add(arena.nameAt(node.nameOffset, node.nameLength) to node)
                node = node.next
            }
        }
        entries.sortBy { it.first }

        val sb = StringBuilder(entries.size * 24)
        sb.append('{')
        for (i in entries.indices) {
            if (i > 0) sb.append(", ")
            val (name, s) = entries[i]
            sb.append(name).append('=')
            appendTenths(sb, s.min.toLong())
            sb.append('/')
            appendTenths(sb, meanTenths(s.sum, s.count))
            sb.append('/')
            appendTenths(sb, s.max.toLong())
        }
        sb.append('}')
        return sb.toString()
    }

    fun close() {
        arena.close()
    }

    private fun meanTenths(
        sum: Long,
        count: Long,
    ): Long {
        // Mean temperature in tenths = sum / count, rounded to nearest with ties toward +infinity
        // (matches the reference 1BRC's Math.round semantics).
        return floor(sum.toDouble() / count.toDouble() + 0.5).toLong()
    }

    private fun appendTenths(
        sb: StringBuilder,
        tenths: Long,
    ) {
        if (tenths < 0) sb.append('-')
        val abs = if (tenths < 0) -tenths else tenths
        sb.append(abs / 10)
        sb.append('.')
        sb.append(abs % 10)
    }
}
