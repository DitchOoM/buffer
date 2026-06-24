package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer

/**
 * Shared helpers for the HPKE test suite: suite/mode lookup by RFC 9180 numeric id, and a KAT-only
 * way to build a key-agreement key pair from pinned `sk`/`pk` hex (so the RFC vectors' fixed
 * ephemeral/recipient keys are reproducible). These reach into `internal` constructors, which is
 * legal from `commonTest` in the same module.
 */
internal object HpkeTestSupport {
    fun kemById(id: Int): HpkeKem =
        when (id) {
            0x0020 -> HpkeKem.DhkemX25519HkdfSha256
            0x0010 -> HpkeKem.DhkemP256HkdfSha256
            0x0011 -> HpkeKem.DhkemP384HkdfSha384
            0x0012 -> HpkeKem.DhkemP521HkdfSha512
            else -> error("unknown KEM id $id")
        }

    fun kdfById(id: Int): HpkeKdf =
        when (id) {
            0x0001 -> HpkeKdf.HkdfSha256
            0x0002 -> HpkeKdf.HkdfSha384
            0x0003 -> HpkeKdf.HkdfSha512
            else -> error("unknown KDF id $id")
        }

    fun aeadById(id: Int): HpkeAead =
        when (id) {
            0x0001 -> HpkeAead.Aes128Gcm
            0x0002 -> HpkeAead.Aes256Gcm
            0x0003 -> HpkeAead.ChaCha20Poly1305
            else -> error("unsupported AEAD id $id")
        }

    fun modeByValue(value: Int): HpkeMode =
        when (value) {
            0 -> HpkeMode.Base
            1 -> HpkeMode.Psk
            2 -> HpkeMode.Auth
            3 -> HpkeMode.AuthPsk
            else -> error("unknown mode $value")
        }

    /** A KEM key pair from pinned `sk`/`pk` hex (KAT-only; the public API generates keys randomly). */
    fun keyPairFromHex(
        kem: HpkeKem,
        skHex: String,
        pkHex: String,
    ): KeyAgreementKeyPair {
        val priv = importPrivateKey(kem.curve, hexBuffer(skHex))
        val pub = KeyAgreementPublicKey.of(kem.curve, hexBuffer(pkHex))
        return keyAgreementKeyPairOf(kem.curve, priv, pub)
    }

    /** An [HpkePrivateKey] from pinned `sk`/`pk` hex. */
    fun privateKeyFromHex(
        kem: HpkeKem,
        skHex: String,
        pkHex: String,
    ): HpkePrivateKey = HpkePrivateKey(kem, importPrivateKey(kem.curve, hexBuffer(skHex)), hexBuffer(pkHex))

    /** An [HpkePublicKey] from pinned `pk` hex. */
    fun publicKeyFromHex(
        kem: HpkeKem,
        pkHex: String,
    ): HpkePublicKey = HpkePublicKey(kem, KeyAgreementPublicKey.of(kem.curve, hexBuffer(pkHex)))

    /**
     * Whether every primitive a [suite] needs is available on this platform (KEM curve + AEAD). Uses
     * [asyncAgreementSupported] for the curve so web X25519 is feature-detected (not assumed), and
     * the AEAD-any-path / ChaCha flags for the AEAD.
     */
    fun suiteSupported(suite: HpkeSuite): Boolean {
        val curveOk = asyncAgreementSupported(suite.kem.curve)
        val aeadOk =
            when (suite.aead) {
                HpkeAead.Aes128Gcm, HpkeAead.Aes256Gcm -> supportsAesGcmAnyPath
                HpkeAead.ChaCha20Poly1305 -> supportsChaChaPoly
            }
        return curveOk && aeadOk
    }

    /** Concatenates two buffers into a fresh read-ready buffer (non-destructive). */
    fun concat(
        a: ReadBuffer,
        b: ReadBuffer,
    ): ReadBuffer {
        val out = BufferFactory.Default.allocate(a.remaining() + b.remaining())
        val aStart = a.position()
        val bStart = b.position()
        for (i in 0 until a.remaining()) out.writeByte(a.get(aStart + i))
        for (i in 0 until b.remaining()) out.writeByte(b.get(bStart + i))
        out.resetForRead()
        return out
    }
}
