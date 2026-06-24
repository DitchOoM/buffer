package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/*
 * DHKEM (RFC 9180 §4.1) — the Diffie–Hellman-based KEM that HPKE uses. Built entirely over the
 * key-agreement family (the internal raw-DH seam dhRawSecret) and HKDF (via HpkeKdf); no EC /
 * X25519 / HKDF math is reimplemented here.
 *
 * Encap/Decap produce a `shared_secret` of `kem.nSecret` bytes via ExtractAndExpand:
 *
 *   eae_prk       = LabeledExtract("", "eae_prk", dh)          // suite_id = "KEM" || I2OSP(kem_id,2)
 *   shared_secret = LabeledExpand(eae_prk, "shared_secret", kem_context, Nsecret)
 *
 * where `dh` is the raw DH output and `kem_context = enc || pkRm` (Base) or `enc || pkRm || pkSm`
 * (Auth). The `shared_secret` is returned in a wiped SecureBuffer; the caller frees it.
 */

/** DHKEM.Encap(pkR): generate an ephemeral key pair, then encapsulate. */
internal suspend fun dhkemEncap(
    kem: HpkeKem,
    recipientPublicKey: KeyAgreementPublicKey,
): DhkemEncapResult {
    val ephemeral = generateKeyPairAsync(kem.curve)
    return try {
        dhkemEncapWithEphemeral(kem, recipientPublicKey, ephemeral)
    } finally {
        ephemeral.close()
    }
}

/**
 * DHKEM.Encap with a caller-supplied ephemeral key pair. The public path uses a freshly generated
 * ephemeral ([dhkemEncap]); this seam exists so the RFC 9180 KAT can pin `skEm`/`pkEm`.
 */
internal suspend fun dhkemEncapWithEphemeral(
    kem: HpkeKem,
    recipientPublicKey: KeyAgreementPublicKey,
    ephemeral: KeyAgreementKeyPair,
): DhkemEncapResult {
    // dh = DH(skE, pkR)
    val dh = dhRawSecret(ephemeral.privateKey, recipientPublicKey)
    return try {
        val enc = copyBuffer(ephemeral.publicKey.encoded, BufferFactory.Default)
        // kem_context = enc || pkRm
        val kemContext = concat(enc, recipientPublicKey.encoded)
        val shared = extractAndExpand(kem, dh, kemContext)
        DhkemEncapResult(shared, enc)
    } finally {
        dh.freeNativeMemory()
    }
}

/** DHKEM.Decap(enc, skR): recover the same shared secret on the recipient side. */
internal suspend fun dhkemDecap(
    kem: HpkeKem,
    enc: ReadBuffer,
    recipientPrivateKey: HpkePrivateKey,
): PlatformBuffer {
    require(enc.remaining() == kem.nEnc) { "enc must be ${kem.nEnc} bytes for ${kem.kemName}, was ${enc.remaining()}" }
    val pkE = KeyAgreementPublicKey(kem.curve, enc)
    // dh = DH(skR, pkE)
    val dh = dhRawSecret(recipientPrivateKey.key, pkE)
    return try {
        val pkRm = recipientPrivateKey.publicKeyEncoded
        val kemContext = concat(enc, pkRm)
        extractAndExpand(kem, dh, kemContext)
    } finally {
        dh.freeNativeMemory()
    }
}

/** DHKEM.AuthEncap(pkR, skS): adds sender authentication via a second DH (skS, pkR). */
internal suspend fun dhkemAuthEncap(
    kem: HpkeKem,
    recipientPublicKey: KeyAgreementPublicKey,
    senderPrivateKey: HpkePrivateKey,
): DhkemEncapResult {
    val ephemeral = generateKeyPairAsync(kem.curve)
    return try {
        dhkemAuthEncapWithEphemeral(kem, recipientPublicKey, senderPrivateKey, ephemeral)
    } finally {
        ephemeral.close()
    }
}

internal suspend fun dhkemAuthEncapWithEphemeral(
    kem: HpkeKem,
    recipientPublicKey: KeyAgreementPublicKey,
    senderPrivateKey: HpkePrivateKey,
    ephemeral: KeyAgreementKeyPair,
): DhkemEncapResult {
    // dh = DH(skE, pkR) || DH(skS, pkR)
    val dh1 = dhRawSecret(ephemeral.privateKey, recipientPublicKey)
    val dh = secureScratch.allocate(kem.curve.sharedSecretBytes * 2)
    try {
        copyInto(dh1, dh)
        dh1.freeNativeMemory()
        val dh2 = dhRawSecret(senderPrivateKey.key, recipientPublicKey)
        copyInto(dh2, dh)
        dh2.freeNativeMemory()
        dh.resetForRead()

        val enc = copyBuffer(ephemeral.publicKey.encoded, BufferFactory.Default)
        val pkSm = senderPrivateKey.publicKeyEncoded
        // kem_context = enc || pkRm || pkSm
        val kemContext = concat3(enc, recipientPublicKey.encoded, pkSm)
        val shared = extractAndExpand(kem, dh, kemContext)
        return DhkemEncapResult(shared, enc)
    } finally {
        dh.freeNativeMemory()
    }
}

/** DHKEM.AuthDecap(enc, skR, pkS): recover the authenticated shared secret on the recipient side. */
internal suspend fun dhkemAuthDecap(
    kem: HpkeKem,
    enc: ReadBuffer,
    recipientPrivateKey: HpkePrivateKey,
    senderPublicKey: KeyAgreementPublicKey,
): PlatformBuffer {
    require(enc.remaining() == kem.nEnc) { "enc must be ${kem.nEnc} bytes for ${kem.kemName}, was ${enc.remaining()}" }
    val pkE = KeyAgreementPublicKey(kem.curve, enc)
    // dh = DH(skR, pkE) || DH(skR, pkS)
    val dh1 = dhRawSecret(recipientPrivateKey.key, pkE)
    val dh = secureScratch.allocate(kem.curve.sharedSecretBytes * 2)
    try {
        copyInto(dh1, dh)
        dh1.freeNativeMemory()
        val dh2 = dhRawSecret(recipientPrivateKey.key, senderPublicKey)
        copyInto(dh2, dh)
        dh2.freeNativeMemory()
        dh.resetForRead()

        val pkRm = recipientPrivateKey.publicKeyEncoded
        val pkSm = senderPublicKey.encoded
        val kemContext = concat3(enc, pkRm, pkSm)
        return extractAndExpand(kem, dh, kemContext)
    } finally {
        dh.freeNativeMemory()
    }
}

/**
 * ExtractAndExpand (RFC 9180 §4.1) over the KEM's own KDF, with `suite_id = "KEM" || I2OSP(kem_id,2)`.
 * Returns `shared_secret` (`kem.nSecret` bytes) in a wiped [SecureBuffer]. The [dh] input is consumed
 * read-ready; the caller frees it.
 */
private fun extractAndExpand(
    kem: HpkeKem,
    dh: ReadBuffer,
    kemContext: ReadBuffer,
): PlatformBuffer {
    val kdf = kem.kdf
    val suiteId = kemSuiteId(kem)
    val eaePrk = secureScratch.allocate(kdf.nh)
    return try {
        // eae_prk = LabeledExtract("", "eae_prk", dh)
        labeledExtract(kdf, suiteId, null, LABEL_EAE_PRK, dh, eaePrk)
        eaePrk.resetForRead()
        // shared_secret = LabeledExpand(eae_prk, "shared_secret", kem_context, Nsecret)
        val shared = secureScratch.allocate(kem.nSecret)
        labeledExpand(kdf, suiteId, eaePrk, LABEL_SHARED_SECRET, kemContext, kem.nSecret, shared)
        shared.resetForRead()
        shared
    } finally {
        eaePrk.freeNativeMemory()
    }
}

/** `suite_id = "KEM" || I2OSP(kem_id, 2)` (RFC 9180 §4.1). */
internal fun kemSuiteId(kem: HpkeKem): ReadBuffer {
    val out = BufferFactory.Default.allocate(3 + 2)
    out.writeBytes(KEM_ASCII)
    i2osp2(kem.id, out)
    out.resetForRead()
    return out
}

private fun concat(
    a: ReadBuffer,
    b: ReadBuffer,
): ReadBuffer {
    val out = BufferFactory.Default.allocate(a.remaining() + b.remaining())
    copyInto(a, out)
    copyInto(b, out)
    out.resetForRead()
    return out
}

private fun concat3(
    a: ReadBuffer,
    b: ReadBuffer,
    c: ReadBuffer,
): ReadBuffer {
    val out = BufferFactory.Default.allocate(a.remaining() + b.remaining() + c.remaining())
    copyInto(a, out)
    copyInto(b, out)
    copyInto(c, out)
    out.resetForRead()
    return out
}
