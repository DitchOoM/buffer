package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * HPKE encryption contexts (RFC 9180 §5.2) and the setup functions that produce them.
 *
 * A context binds an AEAD [key] and [baseNonce] to a monotonic sequence number; each `seal`/`open`
 * computes its per-message nonce as `base_nonce XOR I2OSP(seq, Nn)` and then advances the counter
 * **only on success**. A [MessageLimitReached] is thrown *before* the counter could overflow, so a
 * (key, nonce) pair is never reused. The [export] method derives independent secrets from the
 * context's `exporter_secret` (the HPKE secret-export interface, §5.3).
 *
 * The AEAD key, base nonce, and exporter secret live in wiped [SecureBuffer]s; [close] zeroes them.
 */
sealed class HpkeContext protected constructor(
    internal val suite: HpkeSuite,
    internal val key: PlatformBuffer,
    internal val baseNonce: PlatformBuffer,
    internal val exporterSecret: PlatformBuffer,
) : AutoCloseable {
    private var seq: Long = 0L

    /** RFC 9180 §5.3 secret export: `LabeledExpand(exporter_secret, "sec", context, L)`. */
    fun export(
        exporterContext: ReadBuffer?,
        length: Int,
        factory: BufferFactory = BufferFactory.Default,
    ): ReadBuffer {
        require(length >= 0) { "export length must be non-negative, was $length" }
        val out = factory.allocate(length)
        labeledExpand(suite.kdf, suite.suiteId(), exporterSecret, LABEL_SEC, exporterContext, length, out)
        out.resetForRead()
        return out
    }

    /**
     * Computes the next nonce (`base_nonce XOR seq`) into a fresh buffer, *without* advancing the
     * counter. Throws [MessageLimitReached] if [seq] has reached the AEAD's message limit
     * (`2^(8*Nn) - 1`), the point past which the next increment would wrap (RFC 9180 §5.2).
     */
    internal fun computeNonce(): PlatformBuffer {
        val nn = suite.aead.nn
        // Message limit is 2^(8*Nn) - 1. With Nn = 12 this far exceeds Long, so the practical guard
        // is: the high bytes of seq beyond Nn must be zero. seq is a Long (63 usable bits) and Nn=12
        // bytes (96 bits) so seq can never reach the true limit before Long overflow; still, guard
        // explicitly against the documented overflow boundary.
        if (seq == Long.MAX_VALUE) throw MessageLimitReached()
        val nonce = BufferFactory.Default.allocate(nn)
        // XOR the big-endian seq into the low bytes of base_nonce.
        val baseStart = baseNonce.position()
        for (i in 0 until nn) {
            val shift = (nn - 1 - i) * 8
            val seqByte = if (shift < 64) ((seq ushr shift) and 0xFF).toInt() else 0
            nonce.writeByte((baseNonce.get(baseStart + i).toInt() xor seqByte).toByte())
        }
        nonce.resetForRead()
        return nonce
    }

    /** Advances the sequence number after a successful AEAD op. */
    internal fun incrementSeq() {
        seq++
    }

    /**
     * Forces the sequence number to [value] — **test-only** seam for the RFC 9180 KAT, whose
     * encryption vectors are pinned at specific sequence numbers (0, 1, 2, 4, 255, 256) with gaps.
     * Not part of the public API; the public seal/open advance the counter monotonically.
     */
    internal fun setSeqForTest(value: Long) {
        seq = value
    }

    override fun close() {
        key.freeNativeMemory()
        baseNonce.freeNativeMemory()
        exporterSecret.freeNativeMemory()
    }

    /** A sender context: encrypts successive messages to the recipient. */
    class Sender internal constructor(
        suite: HpkeSuite,
        key: PlatformBuffer,
        baseNonce: PlatformBuffer,
        exporterSecret: PlatformBuffer,
    ) : HpkeContext(suite, key, baseNonce, exporterSecret) {
        /**
         * Seals [plaintext] with [aad] under the next per-message nonce, returning `ct || tag`
         * (read-ready, from [factory]). Advances the sequence number only on success.
         */
        suspend fun seal(
            plaintext: ReadBuffer,
            aad: ReadBuffer? = null,
            factory: BufferFactory = BufferFactory.Default,
        ): PlatformBuffer {
            val nonce = computeNonce()
            return try {
                val ct = hpkeAeadSeal(suite.aead, key, nonce, aad, plaintext, factory)
                incrementSeq()
                ct
            } finally {
                nonce.freeNativeMemory()
            }
        }
    }

    /** A receiver context: decrypts successive messages from the sender. */
    class Receiver internal constructor(
        suite: HpkeSuite,
        key: PlatformBuffer,
        baseNonce: PlatformBuffer,
        exporterSecret: PlatformBuffer,
    ) : HpkeContext(suite, key, baseNonce, exporterSecret) {
        /**
         * Opens a `ct || tag` blob with [aad] under the next per-message nonce, returning the
         * recovered plaintext only if the tag verifies. Advances the sequence number only on a
         * successful verify (a [VerificationFailed] leaves the counter unchanged so the caller can
         * retry — matching RFC 9180's "the sequence is not incremented on failure" note).
         *
         * @throws VerificationFailed if authentication fails — opaque.
         */
        suspend fun open(
            ciphertext: ReadBuffer,
            aad: ReadBuffer? = null,
            factory: BufferFactory = BufferFactory.Default,
        ): PlatformBuffer {
            val nonce = computeNonce()
            return try {
                val pt = hpkeAeadOpen(suite.aead, key, nonce, aad, ciphertext, factory)
                incrementSeq()
                pt
            } finally {
                nonce.freeNativeMemory()
            }
        }
    }
}

// =============================================================================
// Setup — sender side (RFC 9180 §5.1)
// =============================================================================

/** SetupBaseS: encapsulate to [recipientPublicKey] and build a Base-mode sender context. */
suspend fun hpkeSetupBaseSender(
    suite: HpkeSuite,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
): HpkeSenderSetup = hpkeSetupSender(suite, HpkeMode.Base, recipientPublicKey, info, null, null)

/** SetupPSKS: PSK-mode sender context. */
suspend fun hpkeSetupPskSender(
    suite: HpkeSuite,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
    psk: HpkePsk,
): HpkeSenderSetup = hpkeSetupSender(suite, HpkeMode.Psk, recipientPublicKey, info, psk, null)

/** SetupAuthS: sender-authenticated context using the sender's static private key [senderPrivateKey]. */
suspend fun hpkeSetupAuthSender(
    suite: HpkeSuite,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
    senderPrivateKey: HpkePrivateKey,
): HpkeSenderSetup = hpkeSetupSender(suite, HpkeMode.Auth, recipientPublicKey, info, null, senderPrivateKey)

/** SetupAuthPSKS: both PSK and sender authentication. */
suspend fun hpkeSetupAuthPskSender(
    suite: HpkeSuite,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
    psk: HpkePsk,
    senderPrivateKey: HpkePrivateKey,
): HpkeSenderSetup = hpkeSetupSender(suite, HpkeMode.AuthPsk, recipientPublicKey, info, psk, senderPrivateKey)

internal suspend fun hpkeSetupSender(
    suite: HpkeSuite,
    mode: HpkeMode,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
    psk: HpkePsk?,
    senderPrivateKey: HpkePrivateKey?,
): HpkeSenderSetup = hpkeSetupSenderInternal(suite, mode, recipientPublicKey, info, psk, senderPrivateKey, null)

/**
 * Sender setup with an optionally-injected ephemeral key pair. The public setup functions pass
 * `ephemeral = null` so a fresh CSPRNG ephemeral is generated; the RFC 9180 KAT harness injects the
 * pinned `skEm`/`pkEm` so the vector's `enc` and `shared_secret` are reproducible. This is the only
 * way the ephemeral is ever caller-supplied — it is `internal`, never exposed to applications.
 */
internal suspend fun hpkeSetupSenderInternal(
    suite: HpkeSuite,
    mode: HpkeMode,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
    psk: HpkePsk?,
    senderPrivateKey: HpkePrivateKey?,
    ephemeral: KeyAgreementKeyPair?,
): HpkeSenderSetup {
    requireSupported(suite)
    require(recipientPublicKey.kem == suite.kem) { "recipient key KEM does not match suite KEM" }
    validateModeParams(mode, psk, senderPrivateKey)

    val authMode = mode == HpkeMode.Auth || mode == HpkeMode.AuthPsk
    if (authMode) {
        requireNotNull(senderPrivateKey)
        require(senderPrivateKey.kem == suite.kem) { "sender key KEM does not match suite KEM" }
    }
    val encap =
        when {
            authMode && ephemeral != null ->
                dhkemAuthEncapWithEphemeral(suite.kem, recipientPublicKey.key, senderPrivateKey!!, ephemeral)
            authMode -> dhkemAuthEncap(suite.kem, recipientPublicKey.key, senderPrivateKey!!)
            ephemeral != null -> dhkemEncapWithEphemeral(suite.kem, recipientPublicKey.key, ephemeral)
            else -> dhkemEncap(suite.kem, recipientPublicKey.key)
        }

    return try {
        val ctx = keyScheduleSender(suite, mode, encap.sharedSecret, info, psk)
        HpkeSenderSetup(ctx, encap.enc)
    } finally {
        encap.sharedSecret.freeNativeMemory()
    }
}

// =============================================================================
// Setup — receiver side (RFC 9180 §5.1)
// =============================================================================

/** SetupBaseR: decapsulate [enc] with [recipientPrivateKey] and build a Base-mode receiver context. */
suspend fun hpkeSetupBaseReceiver(
    suite: HpkeSuite,
    recipientPrivateKey: HpkePrivateKey,
    enc: ReadBuffer,
    info: ReadBuffer,
): HpkeContext.Receiver = hpkeSetupReceiver(suite, HpkeMode.Base, recipientPrivateKey, enc, info, null, null)

/** SetupPSKR: PSK-mode receiver context. */
suspend fun hpkeSetupPskReceiver(
    suite: HpkeSuite,
    recipientPrivateKey: HpkePrivateKey,
    enc: ReadBuffer,
    info: ReadBuffer,
    psk: HpkePsk,
): HpkeContext.Receiver = hpkeSetupReceiver(suite, HpkeMode.Psk, recipientPrivateKey, enc, info, psk, null)

/** SetupAuthR: sender-authenticated receiver context, verifying the sender's static public key. */
suspend fun hpkeSetupAuthReceiver(
    suite: HpkeSuite,
    recipientPrivateKey: HpkePrivateKey,
    enc: ReadBuffer,
    info: ReadBuffer,
    senderPublicKey: HpkePublicKey,
): HpkeContext.Receiver = hpkeSetupReceiver(suite, HpkeMode.Auth, recipientPrivateKey, enc, info, null, senderPublicKey)

/** SetupAuthPSKR: both PSK and sender authentication. */
suspend fun hpkeSetupAuthPskReceiver(
    suite: HpkeSuite,
    recipientPrivateKey: HpkePrivateKey,
    enc: ReadBuffer,
    info: ReadBuffer,
    psk: HpkePsk,
    senderPublicKey: HpkePublicKey,
): HpkeContext.Receiver = hpkeSetupReceiver(suite, HpkeMode.AuthPsk, recipientPrivateKey, enc, info, psk, senderPublicKey)

internal suspend fun hpkeSetupReceiver(
    suite: HpkeSuite,
    mode: HpkeMode,
    recipientPrivateKey: HpkePrivateKey,
    enc: ReadBuffer,
    info: ReadBuffer,
    psk: HpkePsk?,
    senderPublicKey: HpkePublicKey?,
): HpkeContext.Receiver {
    requireSupported(suite)
    require(recipientPrivateKey.kem == suite.kem) { "recipient key KEM does not match suite KEM" }
    validateModeParams(mode, psk, senderPublicKey)

    val shared =
        if (mode == HpkeMode.Auth || mode == HpkeMode.AuthPsk) {
            requireNotNull(senderPublicKey)
            require(senderPublicKey.kem == suite.kem) { "sender key KEM does not match suite KEM" }
            dhkemAuthDecap(suite.kem, enc, recipientPrivateKey, senderPublicKey.key)
        } else {
            dhkemDecap(suite.kem, enc, recipientPrivateKey)
        }

    return try {
        keyScheduleReceiver(suite, mode, shared, info, psk)
    } finally {
        shared.freeNativeMemory()
    }
}

/** Validates that PSK / sender-key presence matches [mode] (RFC 9180 §5.1 inputs). */
private fun validateModeParams(
    mode: HpkeMode,
    psk: HpkePsk?,
    senderKey: Any?,
) {
    val needsPsk = mode == HpkeMode.Psk || mode == HpkeMode.AuthPsk
    val needsSender = mode == HpkeMode.Auth || mode == HpkeMode.AuthPsk
    require(needsPsk == (psk != null)) {
        if (needsPsk) "mode ${mode.value} requires a PSK" else "a PSK must not be supplied in mode ${mode.value}"
    }
    require(needsSender == (senderKey != null)) {
        if (needsSender) "mode ${mode.value} requires a sender key" else "a sender key must not be supplied in mode ${mode.value}"
    }
}

/**
 * Gates a setup call against the platform's capabilities. The AEAD must be available on some path
 * ([supportsAesGcmAnyPath] / [supportsChaChaPoly]); an unavailable AEAD (e.g. ChaCha on the web)
 * throws immediately. The KEM curve must be usable synchronously *or* on the web (WebCrypto async);
 * if neither, it throws. A curve that the web engine claims but cannot actually provide (e.g. an
 * old browser without X25519) is caught by the underlying [dhRawSecret] / [generateKeyPairAsync],
 * which throw [UnsupportedOperationException] that propagates unchanged.
 */
internal fun requireSupported(suite: HpkeSuite) {
    val aeadOk =
        when (suite.aead) {
            HpkeAead.Aes128Gcm, HpkeAead.Aes256Gcm -> supportsAesGcmAnyPath
            HpkeAead.ChaCha20Poly1305 -> supportsChaChaPoly
        }
    if (!aeadOk) {
        throw UnsupportedOperationException(
            "${suite.aead.aeadName} is not supported on this platform (HPKE suite ${suite.kem.kemName})",
        )
    }
    if (!supportsSync(suite.kem.curve) && !isWebPlatformKa) {
        throw UnsupportedOperationException("${suite.kem.kemName} is not supported on this platform")
    }
}

/** Whether this platform is js/wasmJs (WebCrypto async key agreement available even without a sync path). */
internal expect val isWebPlatformKa: Boolean

// =============================================================================
// Key schedule (RFC 9180 §5.1)
// =============================================================================

private suspend fun keyScheduleSender(
    suite: HpkeSuite,
    mode: HpkeMode,
    sharedSecret: PlatformBuffer,
    info: ReadBuffer,
    psk: HpkePsk?,
): HpkeContext.Sender {
    val (key, baseNonce, exporterSecret) = keySchedule(suite, mode, sharedSecret, info, psk)
    return HpkeContext.Sender(suite, key, baseNonce, exporterSecret)
}

private suspend fun keyScheduleReceiver(
    suite: HpkeSuite,
    mode: HpkeMode,
    sharedSecret: PlatformBuffer,
    info: ReadBuffer,
    psk: HpkePsk?,
): HpkeContext.Receiver {
    val (key, baseNonce, exporterSecret) = keySchedule(suite, mode, sharedSecret, info, psk)
    return HpkeContext.Receiver(suite, key, baseNonce, exporterSecret)
}

private class ScheduleOutput(
    val key: PlatformBuffer,
    val baseNonce: PlatformBuffer,
    val exporterSecret: PlatformBuffer,
) {
    operator fun component1() = key

    operator fun component2() = baseNonce

    operator fun component3() = exporterSecret
}

/**
 * The HPKE key schedule (RFC 9180 §5.1, `KeySchedule`). Computes `key`, `base_nonce`, and
 * `exporter_secret` from the KEM `shared_secret`, the [mode], `info`, and the optional [psk].
 * All intermediates (`psk_id_hash`, `info_hash`, `key_schedule_context`, `secret`) live in wiped
 * scratch and are freed before returning. The three returned buffers are secure (wiped on context
 * close).
 */
private fun keySchedule(
    suite: HpkeSuite,
    mode: HpkeMode,
    sharedSecret: PlatformBuffer,
    info: ReadBuffer,
    psk: HpkePsk?,
): ScheduleOutput {
    val kdf = suite.kdf
    val suiteId = suite.suiteId()
    val nh = kdf.nh

    val pskValue: ReadBuffer? = psk?.psk
    val pskIdValue: ReadBuffer = psk?.pskId ?: EMPTY

    // psk_id_hash = LabeledExtract("", "psk_id_hash", psk_id)
    val pskIdHash = secureScratch.allocate(nh)
    // info_hash = LabeledExtract("", "info_hash", info)
    val infoHash = secureScratch.allocate(nh)
    // secret = LabeledExtract(shared_secret, "secret", psk)
    val secret = secureScratch.allocate(nh)
    try {
        labeledExtract(kdf, suiteId, null, LABEL_PSK_ID_HASH, pskIdValue, pskIdHash)
        pskIdHash.resetForRead()
        labeledExtract(kdf, suiteId, null, LABEL_INFO_HASH, info, infoHash)
        infoHash.resetForRead()

        // key_schedule_context = mode || psk_id_hash || info_hash
        val kscLen = 1 + nh + nh
        val ksc = BufferFactory.Default.allocate(kscLen)
        ksc.writeByte(mode.value.toByte())
        copyInto(pskIdHash, ksc)
        copyInto(infoHash, ksc)
        ksc.resetForRead()

        labeledExtract(kdf, suiteId, sharedSecret, LABEL_SECRET, pskValue ?: EMPTY, secret)
        secret.resetForRead()

        // `secret` is read non-destructively by the HMAC (absolute position+remaining), so the same
        // read-ready buffer is reused for all three expands without re-resetting.
        val key = secureScratch.allocate(suite.aead.nk)
        labeledExpand(kdf, suiteId, secret, LABEL_KEY, ksc, suite.aead.nk, key)
        key.resetForRead()

        val baseNonce = secureScratch.allocate(suite.aead.nn)
        labeledExpand(kdf, suiteId, secret, LABEL_BASE_NONCE, ksc, suite.aead.nn, baseNonce)
        baseNonce.resetForRead()

        val exporterSecret = secureScratch.allocate(nh)
        labeledExpand(kdf, suiteId, secret, LABEL_EXP, ksc, nh, exporterSecret)
        exporterSecret.resetForRead()

        return ScheduleOutput(key, baseNonce, exporterSecret)
    } finally {
        pskIdHash.freeNativeMemory()
        infoHash.freeNativeMemory()
        secret.freeNativeMemory()
    }
}

private val EMPTY: ReadBuffer = BufferFactory.Default.allocate(0).also { it.resetForRead() }

internal val LABEL_EAE_PRK: ByteArray = "eae_prk".encodeToByteArray()
internal val LABEL_SHARED_SECRET: ByteArray = "shared_secret".encodeToByteArray()
internal val LABEL_PSK_ID_HASH: ByteArray = "psk_id_hash".encodeToByteArray()
internal val LABEL_INFO_HASH: ByteArray = "info_hash".encodeToByteArray()
internal val LABEL_SECRET: ByteArray = "secret".encodeToByteArray()
internal val LABEL_KEY: ByteArray = "key".encodeToByteArray()
internal val LABEL_BASE_NONCE: ByteArray = "base_nonce".encodeToByteArray()
internal val LABEL_EXP: ByteArray = "exp".encodeToByteArray()
internal val LABEL_SEC: ByteArray = "sec".encodeToByteArray()

// =============================================================================
// LabeledExtract / LabeledExpand (RFC 9180 §4)
// =============================================================================

/**
 * `LabeledExtract(salt, label, ikm) = Extract(salt, "HPKE-v1" || suite_id || label || ikm)`,
 * writing `Nh` bytes into [dest]. The labeled IKM is assembled in a wiped scratch buffer (it
 * contains the IKM, which may be secret) and freed before returning.
 */
internal fun labeledExtract(
    kdf: HpkeKdf,
    suiteId: ReadBuffer,
    salt: ReadBuffer?,
    label: ByteArray,
    ikm: ReadBuffer,
    dest: WriteBuffer,
) {
    val suiteIdStart = suiteId.position()
    val suiteIdLen = suiteId.remaining()
    val len = HPKE_V1.size + suiteIdLen + label.size + ikm.remaining()
    val labeled = secureScratch.allocate(len)
    try {
        labeled.writeBytes(HPKE_V1)
        for (i in 0 until suiteIdLen) labeled.writeByte(suiteId.get(suiteIdStart + i))
        labeled.writeBytes(label)
        copyInto(ikm, labeled)
        labeled.resetForRead()
        kdf.extractInto(salt, labeled, dest)
    } finally {
        labeled.freeNativeMemory()
    }
}

/**
 * `LabeledExpand(prk, label, info, L) = Expand(prk, I2OSP(L,2) || "HPKE-v1" || suite_id || label || info, L)`,
 * writing [length] bytes into [dest]. The labeled info contains no secret beyond what [info] carries
 * (the PRK is the secret and is not embedded), but it is still assembled in wiped scratch.
 */
internal fun labeledExpand(
    kdf: HpkeKdf,
    suiteId: ReadBuffer,
    prk: ReadBuffer,
    label: ByteArray,
    info: ReadBuffer?,
    length: Int,
    dest: WriteBuffer,
) {
    val suiteIdStart = suiteId.position()
    val suiteIdLen = suiteId.remaining()
    val infoLen = info?.remaining() ?: 0
    val len = 2 + HPKE_V1.size + suiteIdLen + label.size + infoLen
    val labeled = secureScratch.allocate(len)
    try {
        i2osp2(length, labeled)
        labeled.writeBytes(HPKE_V1)
        for (i in 0 until suiteIdLen) labeled.writeByte(suiteId.get(suiteIdStart + i))
        labeled.writeBytes(label)
        if (info != null) copyInto(info, labeled)
        labeled.resetForRead()
        kdf.expandInto(prk, labeled, length, dest)
    } finally {
        labeled.freeNativeMemory()
    }
}
