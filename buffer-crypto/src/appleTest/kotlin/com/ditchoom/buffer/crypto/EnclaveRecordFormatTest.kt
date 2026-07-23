package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The durable Enclave key record — `u8 kind | u16 pointLen | point | blob` — as pure parsing, with
 * no Enclave involved, so it runs on every Apple target including the simulator.
 *
 * Signing and agreement keys are both (public point + restore blob) pairs, structurally identical;
 * the kind byte is the only thing that keeps them from loading as each other. It also has to stay
 * backward compatible with records written before the agreement kind existed.
 */
class EnclaveRecordFormatTest {
    private val point = ascii("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef!")
    private val blob = ascii("an opaque Enclave restore blob")

    @Test
    fun legacyRecordsWithoutAKindByteStillReadAsSigningKeys() {
        // A pre-kind record is `u16 pointLen | point | blob`, so its first byte is the length's high
        // byte — always 0x00 for a 65-byte point. That is what the parse falls back on, so a key
        // persisted by an earlier release keeps loading as the signing key it is.
        val record = BufferFactory.Default.allocate(LEGACY_HEADER_BYTES + point.remaining() + blob.remaining())
        record.writeShort(point.remaining().toShort())
        record.write(point)
        record.write(blob)
        record.resetForRead()
        point.position(0)
        blob.position(0)

        assertEquals(ProtectedKeyAlgorithm.EcdsaP256, enclaveRecordAlgorithm(record))
        val parsed = parseEnclaveRecord(record)
        assertEquals(EnclaveKeyKind.Signing, parsed.kind)
        assertEquals(point.toHex(), parsed.point.toHex())
        assertEquals(blob.toHex(), parsed.blob.toHex())
    }

    @Test
    fun kindTaggedRecordsRoundTripAndStayDistinguishable() {
        for (kind in EnclaveKeyKind.entries) {
            val record = frameEnclaveRecord(kind, point, blob)
            val expected =
                if (kind == EnclaveKeyKind.Signing) {
                    ProtectedKeyAlgorithm.EcdsaP256
                } else {
                    ProtectedKeyAlgorithm.EcdhP256
                }
            assertEquals(expected, enclaveRecordAlgorithm(record), "$kind must report its own algorithm")
            val parsed = parseEnclaveRecord(record)
            assertEquals(kind, parsed.kind)
            assertEquals(point.toHex(), parsed.point.toHex())
            assertEquals(blob.toHex(), parsed.blob.toHex())
        }
    }

    private companion object {
        const val LEGACY_HEADER_BYTES = 2
    }
}
