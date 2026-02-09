package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

class XorMaskTests {
    // ============================================================================
    // xorMask with maskOffset tests
    // ============================================================================

    @Test
    fun offsetZeroMatchesNoOffset() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val size = 32
            val mask = 0xCAFEBABE.toInt()

            val buf1 = PlatformBuffer.allocate(size, zone)
            val buf2 = PlatformBuffer.allocate(size, zone)
            for (i in 0 until size) {
                buf1.writeByte(i.toByte())
                buf2.writeByte(i.toByte())
            }
            buf1.resetForRead()
            buf2.resetForRead()

            buf1.xorMask(mask)
            buf2.xorMask(mask, 0)

            for (i in 0 until size) {
                assertEquals(buf1.readByte(), buf2.readByte(), "Mismatch at $i for zone $zone")
            }
        }
    }

    @Test
    fun offsetOneRotatesMask() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val buffer = PlatformBuffer.allocate(4, zone)
            buffer.writeInt(0x00000000)
            buffer.resetForRead()
            buffer.xorMask(0x12345678, 1)

            // With offset=1, byte 0 gets mask byte 1 (0x34), byte 1 gets mask byte 2 (0x56), etc.
            assertEquals(0x34.toByte(), buffer.readByte(), "Byte 0")
            assertEquals(0x56.toByte(), buffer.readByte(), "Byte 1")
            assertEquals(0x78.toByte(), buffer.readByte(), "Byte 2")
            assertEquals(0x12.toByte(), buffer.readByte(), "Byte 3")
        }
    }

    @Test
    fun offsetCyclesEveryFour() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val size = 16
            val mask = 0xDEADBEEF.toInt()

            for (base in 0..3) {
                val buf1 = PlatformBuffer.allocate(size, zone)
                val buf2 = PlatformBuffer.allocate(size, zone)
                for (i in 0 until size) {
                    buf1.writeByte(i.toByte())
                    buf2.writeByte(i.toByte())
                }
                buf1.resetForRead()
                buf2.resetForRead()

                buf1.xorMask(mask, base)
                buf2.xorMask(mask, base + 4)

                for (i in 0 until size) {
                    assertEquals(
                        buf1.readByte(),
                        buf2.readByte(),
                        "offset=$base vs offset=${base + 4} mismatch at $i",
                    )
                }
            }
        }
    }

    @Test
    fun offsetWorksWithBulkPath() {
        // Buffer > 8 bytes to exercise the 8-byte (or 4-byte) bulk path
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val mask = 0xABCD1234.toInt()
            val maskBytes =
                byteArrayOf(
                    (mask ushr 24).toByte(),
                    (mask ushr 16).toByte(),
                    (mask ushr 8).toByte(),
                    mask.toByte(),
                )

            for (offset in 1..3) {
                val size = 33 // odd size to test remainder path too
                val buffer = PlatformBuffer.allocate(size, zone)
                for (i in 0 until size) buffer.writeByte(0)
                buffer.resetForRead()
                buffer.xorMask(mask, offset)

                for (i in 0 until size) {
                    assertEquals(
                        maskBytes[(i + offset) % 4],
                        buffer.readByte(),
                        "offset=$offset mismatch at index $i in zone $zone",
                    )
                }
            }
        }
    }

    @Test
    fun offsetPreservesDoubleXorIdentity() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val size = 64
            val mask = 0xFEEDFACE.toInt()

            for (offset in 0..3) {
                val buffer = PlatformBuffer.allocate(size, zone)
                for (i in 0 until size) buffer.writeByte(i.toByte())
                buffer.resetForRead()

                buffer.xorMask(mask, offset)
                buffer.position(0)
                buffer.xorMask(mask, offset)

                for (i in 0 until size) {
                    assertEquals(
                        i.toByte(),
                        buffer.readByte(),
                        "Double XOR not identity at $i with offset=$offset",
                    )
                }
            }
        }
    }

    @Test
    fun offsetChunkedEqualsContiguous() {
        // Mask chunks separately with offset tracking == mask combined buffer at once
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val mask = 0x12345678
            val totalSize = 20
            val data = ByteArray(totalSize) { it.toByte() }

            // Contiguous: mask all at once
            val contiguous = PlatformBuffer.allocate(totalSize, zone)
            contiguous.writeBytes(data)
            contiguous.resetForRead()
            contiguous.xorMask(mask)

            // Chunked: mask in chunks of 7, 6, 7 with offset tracking
            val chunkSizes = intArrayOf(7, 6, 7)
            val chunkedResults = mutableListOf<Byte>()
            var offset = 0
            var dataPos = 0
            for (chunkSize in chunkSizes) {
                val chunk = PlatformBuffer.allocate(chunkSize, zone)
                chunk.writeBytes(data, dataPos, chunkSize)
                chunk.resetForRead()
                chunk.xorMask(mask, offset)
                for (i in 0 until chunkSize) {
                    chunkedResults.add(chunk.readByte())
                }
                offset += chunkSize
                dataPos += chunkSize
            }

            // Compare
            for (i in 0 until totalSize) {
                assertEquals(
                    contiguous.readByte(),
                    chunkedResults[i],
                    "Chunked vs contiguous mismatch at $i",
                )
            }
        }
    }

    @Test
    fun offsetWithOddSizedChunks() {
        // Chunks of 1, 3, 5, 7 bytes
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val mask = 0xAABBCCDD.toInt()
            val chunkSizes = intArrayOf(1, 3, 5, 7)
            val totalSize = chunkSizes.sum()
            val data = ByteArray(totalSize) { (it * 17).toByte() }

            // Contiguous reference
            val contiguous = PlatformBuffer.allocate(totalSize, zone)
            contiguous.writeBytes(data)
            contiguous.resetForRead()
            contiguous.xorMask(mask)

            // Chunked
            val chunkedResults = mutableListOf<Byte>()
            var offset = 0
            var dataPos = 0
            for (chunkSize in chunkSizes) {
                val chunk = PlatformBuffer.allocate(chunkSize, zone)
                chunk.writeBytes(data, dataPos, chunkSize)
                chunk.resetForRead()
                chunk.xorMask(mask, offset)
                for (i in 0 until chunkSize) chunkedResults.add(chunk.readByte())
                offset += chunkSize
                dataPos += chunkSize
            }

            for (i in 0 until totalSize) {
                assertEquals(contiguous.readByte(), chunkedResults[i], "Mismatch at $i")
            }
        }
    }

    @Test
    fun offsetWithEmptyBuffer() {
        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            val buffer = PlatformBuffer.allocate(8, zone)
            buffer.resetForRead() // position=0, limit=0
            // Should not crash for any offset
            buffer.xorMask(0x12345678, 0)
            buffer.xorMask(0x12345678, 1)
            buffer.xorMask(0x12345678, 3)
            assertEquals(0, buffer.remaining())
        }
    }

    @Test
    fun offsetWithSingleByte() {
        val mask = 0x12345678
        val maskBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)

        for (zone in listOf(AllocationZone.Heap, AllocationZone.Direct)) {
            for (offset in 0..3) {
                val buffer = PlatformBuffer.allocate(1, zone)
                buffer.writeByte(0)
                buffer.resetForRead()
                buffer.xorMask(mask, offset)
                assertEquals(
                    maskBytes[offset],
                    buffer.readByte(),
                    "Single byte with offset=$offset in zone $zone",
                )
            }
        }
    }

    // ============================================================================
    // Original xorMask tests (no offset)
    // ============================================================================

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
