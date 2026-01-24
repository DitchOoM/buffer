package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

class XorMaskTests {
    @Test
    fun zeroMaskIsNoOp() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val buffer = PlatformBuffer.allocate(8, zone)
            buffer.writeInt(0x12345678)
            buffer.writeInt(0x9ABCDEF0.toInt())
            buffer.resetForRead()
            buffer.xorMask(0)
            assertEquals(0x12345678, buffer.readInt())
            assertEquals(0x9ABCDEF0.toInt(), buffer.readInt())
        }
    }

    @Test
    fun emptyBufferIsNoOp() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val buffer = PlatformBuffer.allocate(8, zone)
            buffer.resetForRead() // position=0, limit=0
            buffer.xorMask(0x12345678) // Should not crash
            assertEquals(0, buffer.remaining())
        }
    }

    @Test
    fun exact4ByteBuffer() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val buffer = PlatformBuffer.allocate(4, zone)
            buffer.writeInt(0x00000000)
            buffer.resetForRead()
            buffer.xorMask(0x12345678)
            assertEquals(0x12345678, buffer.readInt())
        }
    }

    @Test
    fun exact8ByteBuffer() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val buffer = PlatformBuffer.allocate(8, zone)
            buffer.writeInt(0x00000000)
            buffer.writeInt(0x00000000)
            buffer.resetForRead()
            buffer.xorMask(0x12345678)
            assertEquals(0x12345678, buffer.readInt())
            assertEquals(0x12345678, buffer.readInt())
        }
    }

    @Test
    fun oddLengthRemainder() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            // Test buffers of size 1, 2, 3, 5, 6, 7
            for (size in listOf(1, 2, 3, 5, 6, 7)) {
                val buffer = PlatformBuffer.allocate(size, zone)
                for (i in 0 until size) {
                    buffer.writeByte(0)
                }
                buffer.resetForRead()
                buffer.xorMask(0x12345678)

                // Verify the mask was applied correctly byte by byte
                val maskBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
                for (i in 0 until size) {
                    assertEquals(
                        maskBytes[i % 4],
                        buffer.readByte(),
                        "Mismatch at index $i for size $size in zone $zone",
                    )
                }
            }
        }
    }

    @Test
    fun doubleXorIsIdentity() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val size = 1024
            val buffer = PlatformBuffer.allocate(size, zone)
            // Fill with known data
            for (i in 0 until size) {
                buffer.writeByte(i.toByte())
            }
            buffer.resetForRead()

            val mask = 0xDEADBEEF.toInt()

            // First XOR
            buffer.xorMask(mask)
            // Second XOR should restore original
            buffer.position(0)
            buffer.xorMask(mask)

            // Verify original data is restored
            for (i in 0 until size) {
                assertEquals(
                    i.toByte(),
                    buffer.readByte(),
                    "Mismatch at index $i after double XOR",
                )
            }
        }
    }

    @Test
    fun positionPreservation() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val buffer = PlatformBuffer.allocate(16, zone)
            for (i in 0 until 16) {
                buffer.writeByte(0)
            }
            buffer.resetForRead()

            val originalPos = buffer.position()
            val originalLim = buffer.limit()
            buffer.xorMask(0x12345678)

            assertEquals(originalPos, buffer.position(), "Position should not change")
            assertEquals(originalLim, buffer.limit(), "Limit should not change")
        }
    }

    @Test
    fun rangeRespected() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val buffer = PlatformBuffer.allocate(16, zone)
            for (i in 0 until 16) {
                buffer.writeByte(0)
            }
            buffer.resetForRead()

            // Set position and limit to only XOR bytes 4..11
            buffer.position(4)
            buffer.setLimit(12)
            buffer.xorMask(0x12345678)

            // Check bytes outside range are unchanged
            buffer.position(0)
            buffer.setLimit(16)
            for (i in 0 until 4) {
                assertEquals(0.toByte(), buffer.get(i), "Byte before range should be unchanged at $i")
            }
            for (i in 12 until 16) {
                assertEquals(0.toByte(), buffer.get(i), "Byte after range should be unchanged at $i")
            }
            // Check bytes inside range are XORed
            val maskBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
            for (i in 4 until 12) {
                assertEquals(
                    maskBytes[(i - 4) % 4],
                    buffer.get(i),
                    "Byte inside range incorrect at $i",
                )
            }
        }
    }

    @Test
    fun largeBuffer() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val size = 4096
            val buffer = PlatformBuffer.allocate(size, zone)
            for (i in 0 until size) {
                buffer.writeByte(i.toByte())
            }
            buffer.resetForRead()

            val mask = 0xCAFEBABE.toInt()
            buffer.xorMask(mask)

            val maskBytes =
                byteArrayOf(
                    (mask ushr 24).toByte(),
                    (mask ushr 16).toByte(),
                    (mask ushr 8).toByte(),
                    mask.toByte(),
                )

            for (i in 0 until size) {
                val expected = (i.toByte().toInt() xor maskBytes[i % 4].toInt()).toByte()
                assertEquals(
                    expected,
                    buffer.readByte(),
                    "Mismatch at index $i for zone $zone",
                )
            }
        }
    }

    @Test
    fun allOnesData() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val buffer = PlatformBuffer.allocate(8, zone)
            buffer.writeLong(-1L) // All 0xFF bytes
            buffer.resetForRead()
            buffer.xorMask(0x12345678)

            val maskBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
            for (i in 0 until 8) {
                val expected = (0xFF xor (maskBytes[i % 4].toInt() and 0xFF)).toByte()
                assertEquals(expected, buffer.readByte(), "Mismatch at index $i")
            }
        }
    }

    @Test
    fun littleEndianBuffer() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Direct, ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 8) {
            buffer.writeByte(0)
        }
        buffer.resetForRead()
        buffer.xorMask(0x12345678)

        // Mask is always applied in big-endian order regardless of buffer byte order
        val maskBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        for (i in 0 until 8) {
            assertEquals(maskBytes[i % 4], buffer.readByte(), "Mismatch at $i")
        }
    }
}
