package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The Apple AES-GCM actuals, called directly so the destination buffer is observable: the
 * cross-platform suites go through the allocating wrappers and cannot see what a rejected open
 * left behind.
 */
class AeadAppleCryptoKitTest {
    private val key = AesGcmKey.of(hexBuffer("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4"))

    private fun nonce() = hexBuffer("cafebabefacedbaddecaf888")

    private fun aad() = hexBuffer("feedfacedeadbeeffeedfacedeadbeefabaddad2")

    private fun plaintext() = hexBuffer("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4b304447d6dcf9")

    @Test
    fun aFlippedTagBitIsRejectedAndDestIsLeftAtItsPositionHoldingNoPlaintext() {
        val plaintextSize = plaintext().remaining()
        val sealed = BufferFactory.Default.allocate(plaintextSize + AEAD_TAG_BYTES)
        aesGcmSeal(key, nonce(), aad(), plaintext(), sealed)
        sealed.resetForRead()
        val sealedSize = sealed.remaining()
        assertEquals(plaintextSize + AEAD_TAG_BYTES, sealedSize)

        val tampered = BufferFactory.Default.allocate(sealedSize)
        for (i in 0 until sealedSize - 1) tampered.writeByte(sealed.get(i))
        tampered.writeByte((sealed.get(sealedSize - 1).toInt() xor 0x01).toByte())
        tampered.resetForRead()

        val dest = BufferFactory.Default.allocate(1 + plaintextSize)
        dest.writeByte(MARKER)
        val positionBefore = dest.position()

        assertFailsWith<VerificationFailed> { aesGcmOpen(key, nonce(), aad(), tampered, dest) }

        assertEquals(positionBefore, dest.position())
        assertEquals(MARKER, dest.get(0))
        for (i in positionBefore until positionBefore + plaintextSize) {
            assertEquals(0, dest.get(i), "byte $i of dest after a rejected open")
        }
    }

    @Test
    fun openReturnsWhatSealWasGiven() {
        val expected = plaintext()
        val sealed = BufferFactory.Default.allocate(expected.remaining() + AEAD_TAG_BYTES)
        aesGcmSeal(key, nonce(), aad(), plaintext(), sealed)
        sealed.resetForRead()

        val opened = BufferFactory.Default.allocate(expected.remaining())
        aesGcmOpen(key, nonce(), aad(), sealed, opened)
        opened.resetForRead()

        assertEquals(expected.remaining(), opened.remaining())
        for (i in 0 until expected.remaining()) assertEquals(expected.get(i), opened.get(i), "plaintext byte $i")
    }

    private companion object {
        const val MARKER: Byte = 0x5A
    }
}
