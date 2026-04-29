package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

class XorMaskTests {
    // ============================================================================
    // xorMask with maskOffset tests
    // ============================================================================

    @Test
    fun offsetZeroMatchesNoOffset() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val size = 32
            val mask = 0xCAFEBABE.toInt()

            val buf1 = factory.allocate(size)
            val buf2 = factory.allocate(size)
            for (i in 0 until size) {
                buf1.writeByte(i.toByte())
                buf2.writeByte(i.toByte())
            }
            buf1.resetForRead()
            buf2.resetForRead()

            buf1.xorMask(mask)
            buf2.xorMask(mask, 0)

            for (i in 0 until size) {
                assertEquals(buf1.readByte(), buf2.readByte(), "Mismatch at $i for factory $factory")
            }
        }
    }

    @Test
    fun offsetOneRotatesMask() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val buffer = factory.allocate(4)
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
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val size = 16
            val mask = 0xDEADBEEF.toInt()

            for (base in 0..3) {
                val buf1 = factory.allocate(size)
                val buf2 = factory.allocate(size)
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
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
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
                val buffer = factory.allocate(size)
                for (i in 0 until size) buffer.writeByte(0)
                buffer.resetForRead()
                buffer.xorMask(mask, offset)

                for (i in 0 until size) {
                    assertEquals(
                        maskBytes[(i + offset) % 4],
                        buffer.readByte(),
                        "offset=$offset mismatch at index $i in factory $factory",
                    )
                }
            }
        }
    }

    @Test
    fun offsetPreservesDoubleXorIdentity() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val size = 64
            val mask = 0xFEEDFACE.toInt()

            for (offset in 0..3) {
                val buffer = factory.allocate(size)
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
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val mask = 0x12345678
            val totalSize = 20
            val data = ByteArray(totalSize) { it.toByte() }

            // Contiguous: mask all at once
            val contiguous = factory.allocate(totalSize)
            contiguous.writeBytes(data)
            contiguous.resetForRead()
            contiguous.xorMask(mask)

            // Chunked: mask in chunks of 7, 6, 7 with offset tracking
            val chunkSizes = intArrayOf(7, 6, 7)
            val chunkedResults = mutableListOf<Byte>()
            var offset = 0
            var dataPos = 0
            for (chunkSize in chunkSizes) {
                val chunk = factory.allocate(chunkSize)
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
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val mask = 0xAABBCCDD.toInt()
            val chunkSizes = intArrayOf(1, 3, 5, 7)
            val totalSize = chunkSizes.sum()
            val data = ByteArray(totalSize) { (it * 17).toByte() }

            // Contiguous reference
            val contiguous = factory.allocate(totalSize)
            contiguous.writeBytes(data)
            contiguous.resetForRead()
            contiguous.xorMask(mask)

            // Chunked
            val chunkedResults = mutableListOf<Byte>()
            var offset = 0
            var dataPos = 0
            for (chunkSize in chunkSizes) {
                val chunk = factory.allocate(chunkSize)
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
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val buffer = factory.allocate(8)
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

        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            for (offset in 0..3) {
                val buffer = factory.allocate(1)
                buffer.writeByte(0)
                buffer.resetForRead()
                buffer.xorMask(mask, offset)
                assertEquals(
                    maskBytes[offset],
                    buffer.readByte(),
                    "Single byte with offset=$offset in factory $factory",
                )
            }
        }
    }

    // ============================================================================
    // Original xorMask tests (no offset)
    // ============================================================================

    @Test
    fun zeroMaskIsNoOp() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val buffer = factory.allocate(8)
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
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val buffer = factory.allocate(8)
            buffer.resetForRead() // position=0, limit=0
            buffer.xorMask(0x12345678) // Should not crash
            assertEquals(0, buffer.remaining())
        }
    }

    @Test
    fun exact4ByteBuffer() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val buffer = factory.allocate(4)
            buffer.writeInt(0x00000000)
            buffer.resetForRead()
            buffer.xorMask(0x12345678)
            assertEquals(0x12345678, buffer.readInt())
        }
    }

    @Test
    fun exact8ByteBuffer() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val buffer = factory.allocate(8)
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
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            // Test buffers of size 1, 2, 3, 5, 6, 7
            for (size in listOf(1, 2, 3, 5, 6, 7)) {
                val buffer = factory.allocate(size)
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
                        "Mismatch at index $i for size $size in factory $factory",
                    )
                }
            }
        }
    }

    @Test
    fun doubleXorIsIdentity() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val size = 1024
            val buffer = factory.allocate(size)
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
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val buffer = factory.allocate(16)
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
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val buffer = factory.allocate(16)
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
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val size = 4096
            val buffer = factory.allocate(size)
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
                    "Mismatch at index $i for factory $factory",
                )
            }
        }
    }

    @Test
    fun allOnesData() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val buffer = factory.allocate(8)
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

    // ============================================================================
    // xorMaskCopy tests
    // ============================================================================

    @Test
    fun xorMaskCopyMatchesWritePlusXorMask() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val mask = 0xCAFEBABE.toInt()
            val size = 33 // odd to test remainder

            val src = factory.allocate(size)
            for (i in 0 until size) src.writeByte((i * 7).toByte())
            src.resetForRead()

            // Reference: write then mask in-place
            val src2 = factory.allocate(size)
            for (i in 0 until size) src2.writeByte((i * 7).toByte())
            src2.resetForRead()

            val ref = factory.allocate(size) as ReadWriteBuffer
            ref.write(src2)
            ref.resetForRead()
            ref.xorMask(mask)

            // Test: xorMaskCopy
            val dst = factory.allocate(size) as ReadWriteBuffer
            dst.xorMaskCopy(src, mask)
            dst.resetForRead()

            for (i in 0 until size) {
                assertEquals(ref.readByte(), dst.readByte(), "Mismatch at $i for factory $factory")
            }
        }
    }

    @Test
    fun xorMaskCopyWithOffset() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val mask = 0x12345678
            val totalSize = 20
            val data = ByteArray(totalSize) { it.toByte() }

            // Contiguous reference
            val contiguous = factory.allocate(totalSize) as ReadWriteBuffer
            contiguous.writeBytes(data)
            contiguous.resetForRead()
            contiguous.xorMask(mask)

            // Chunked xorMaskCopy with offset tracking
            val dst = factory.allocate(totalSize) as ReadWriteBuffer
            val chunkSizes = intArrayOf(7, 6, 7)
            var offset = 0
            var dataPos = 0
            for (chunkSize in chunkSizes) {
                val chunk = factory.allocate(chunkSize)
                chunk.writeBytes(data, dataPos, chunkSize)
                chunk.resetForRead()
                dst.xorMaskCopy(chunk, mask, offset)
                offset += chunkSize
                dataPos += chunkSize
            }
            dst.resetForRead()

            for (i in 0 until totalSize) {
                assertEquals(contiguous.readByte(), dst.readByte(), "Mismatch at $i")
            }
        }
    }

    @Test
    fun xorMaskCopyAdvancesPositions() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val src = factory.allocate(8)
            for (i in 0 until 8) src.writeByte(i.toByte())
            src.resetForRead()

            val dst = factory.allocate(16) as ReadWriteBuffer
            dst.xorMaskCopy(src, 0x12345678)

            assertEquals(8, src.position(), "Source position should advance")
            assertEquals(8, dst.position(), "Dest position should advance")
        }
    }

    @Test
    fun xorMaskCopyZeroMaskIsCopy() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val src = factory.allocate(8)
            for (i in 0 until 8) src.writeByte(i.toByte())
            src.resetForRead()

            val dst = factory.allocate(8) as ReadWriteBuffer
            dst.xorMaskCopy(src, 0)
            dst.resetForRead()

            for (i in 0 until 8) {
                assertEquals(i.toByte(), dst.readByte(), "Zero mask should be plain copy at $i")
            }
        }
    }

    @Test
    fun xorMaskCopyEmptySourceIsNoOp() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default)) {
            val src = factory.allocate(8)
            src.resetForRead() // position=0, limit=0

            val dst = factory.allocate(8) as ReadWriteBuffer
            dst.xorMaskCopy(src, 0x12345678)
            assertEquals(0, dst.position(), "Dest position should not move")
        }
    }

    @Test
    fun littleEndianBuffer() {
        val buffer = BufferFactory.Default.allocate(8, ByteOrder.LITTLE_ENDIAN)
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
