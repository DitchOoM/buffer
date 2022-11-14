package com.ditchoom.buffer

expect fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone = AllocationZone.Heap,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN
): PlatformBuffer

expect fun PlatformBuffer.Companion.wrap(array: ByteArray, byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN): PlatformBuffer
expect fun String.toBuffer(zone: AllocationZone = AllocationZone.Heap): PlatformBuffer

fun String.utf8Length(): Int {
    var count = 0
    var i = 0
    val len = length
    while (i < len) {
        val ch = get(i)
        if (ch.code <= 0x7F) {
            count++
        } else if (ch.code <= 0x7FF) {
            count += 2
        } else if (ch >= '\uD800' && ch.code < '\uDBFF'.code + 1) {
            count += 4
            ++i
        } else {
            count += 3
        }
        i++
    }
    return count
}
