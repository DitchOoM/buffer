package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * HPKE encryption contexts (RFC 9180 §5.2) and the setup functions that produce them.
 *
 * A context binds an AEAD [key] and [baseNonce] to a monotonic sequence number; each `seal`/`open`
 * computes its per-message nonce as `base_nonce XOR I2OSP(seq, Nn)` and then advances the counter
 * **only on success**. A [MessageLimitReached] is thrown *before* the counter could overflow, so a
 * (key, nonce) pair is never reused. The [export] method derives independent secrets from the
 * context's `exporter_secret` (the HPKE secret-export interface, §5.3).
 *
 * Concurrency: the per-message `seal`/`open` ops are serialized on an internal [Mutex], so
 * concurrent callers on one context are safe — each op claims its sequence number exactly once
 * (and a failed open still leaves the counter unchanged). [close] marks the context closed
 * immediately (later ops throw) and wipes under the same lock: inline when no op is in flight,
 * otherwise deferred to the in-flight op's completion — so a seal/open never reads wiped or freed
 * key material. [export] is *not* serialized on the lock (it cannot suspend); do not race it with
 * [close].
 *
 * The AEAD key, base nonce, and exporter secret live in wiped [SecureBuffer]s; [close] zeroes them
 * (idempotent) along with the wrapped [aeadKey]'s material. Ops on a closed context throw.
 */
sealed class HpkeContext protected constructor(
    internal val suite: HpkeSuite,
    internal val key: PlatformBuffer,
    internal val baseNonce: PlatformBuffer,
    internal val exporterSecret: PlatformBuffer,
) : AutoCloseable {
    private var seq: Long = 0L

    // Volatile: close() may run on a different thread than the op holding seqLock; the op's
    // completion path must observe the closed flag to perform the deferred wipe.
    @Volatile
    private var closed = false

    @Volatile
    private var wiped = false

    /** Serializes the nonce-compute → AEAD op → counter-advance sequence (see class KDoc). */
    internal val seqLock = Mutex()

    /** The derived AEAD key, wrapped once for the context's lifetime and wiped by [close]. */
    internal val aeadKey: AeadKey = hpkeAeadKeyOf(suite.aead, key)

    /** RFC 9180 §5.3 secret export: `LabeledExpand(exporter_secret, "sec", context, L)`. */
    fun export(
        exporterContext: Info,
        length: Int,
        factory: BufferFactory = BufferFactory.Default,
    ): ReadBuffer {
        requireOpen()
        require(length >= 0) { "export length must be non-negative, was $length" }
        // RFC 9180 §5.3: L must be at most 255*Nh (the HKDF-Expand block limit). Checked here,
        // before the output allocation, so an oversized request cannot allocate first and only
        // fail inside the KDF.
        val maxLength = HKDF_MAX_BLOCKS * suite.kdf.nh
        require(length <= maxLength) { "export length must be <= $maxLength bytes for this KDF, was $length" }
        val out = factory.allocate(length)
        labeledExpand(suite.kdf, suite.suiteId(), exporterSecret, LABEL_SEC, exporterContext.bytesOrNull, length, out)
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
        // nonce = base_nonce XOR I2OSP(seq, Nn). Default allocation is big-endian, so copy the base
        // nonce in bulk and XOR the sequence number straight into the trailing 8 bytes as one Long:
        // I2OSP(seq, Nn) is right-aligned and Nn (12) >= 8, so the leading Nn-8 bytes XOR with zero.
        val nonce = BufferFactory.Default.allocate(nn)
        copyInto(baseNonce, nonce)
        val tailOffset = nn - 8
        nonce.position(tailOffset)
        nonce.writeLong(nonce.getLong(tailOffset) xor seq)
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

    /** Guards every op against use after [close]. `seal`/`open` re-check under [seqLock]. */
    internal fun requireOpen() {
        check(!closed) { "HpkeContext already closed" }
    }

    /**
     * Marks the context closed (later ops throw) and wipes the secrets under [seqLock]: inline if
     * the lock is free, otherwise deferred to the in-flight op's completion path (a [Mutex] cannot
     * be awaited from a non-suspend close). Either way a seal/open never observes wiped or freed
     * material. Idempotent. [export] does not hold the lock — do not race it with close.
     */
    override fun close() {
        closed = true
        if (wiped) return
        if (seqLock.tryLock()) {
            try {
                wipeLocked()
            } finally {
                seqLock.unlock()
            }
        }
    }

    /** Zeroes/frees the derived secrets. Call only while holding [seqLock]; idempotent. */
    private fun wipeLocked() {
        if (wiped) return
        wiped = true
        aeadKey.closeMaterial()
        key.freeNativeMemory()
        baseNonce.freeNativeMemory()
        exporterSecret.freeNativeMemory()
    }

    /**
     * Runs one serialized seal/open [op] under [seqLock]: re-checks [closed] once the lock is
     * held (a close that won the lock first has already wiped — fail before touching freed
     * memory), and performs the wipe a concurrent [close] deferred to us.
     */
    internal suspend fun <R> withOpLock(op: suspend () -> R): R =
        seqLock.withLock {
            try {
                requireOpen()
                op()
            } finally {
                if (closed) wipeLocked()
            }
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
         * (read-ready, from [factory]). Advances the sequence number only on success; serialized
         * against concurrent seals so a sequence number (and thus a nonce) is never reused.
         */
        suspend fun seal(
            plaintext: ReadBuffer,
            aad: Aad = Aad.None,
            factory: BufferFactory = BufferFactory.Default,
        ): PlatformBuffer =
            withOpLock {
                val nonce = computeNonce()
                try {
                    val ct = hpkeAeadSeal(aeadKey, nonce, aad.bytesOrNull, plaintext, factory)
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
            aad: Aad = Aad.None,
            factory: BufferFactory = BufferFactory.Default,
        ): PlatformBuffer =
            withOpLock {
                val nonce = computeNonce()
                try {
                    val pt = hpkeAeadOpen(aeadKey, nonce, aad.bytesOrNull, ciphertext, factory)
                    incrementSeq()
                    pt
                } finally {
                    nonce.freeNativeMemory()
                }
            }
    }
}

// =============================================================================
// Establishment modes carrying their per-mode parameters (RFC 9180 §5.1)
// =============================================================================
//
// Each sender/receiver mode bundles the wire `mode` byte with EXACTLY the parameters that mode
// requires, so an impossible combination (a Base mode carrying a PSK, an Auth mode with no sender
// key) is unrepresentable rather than rejected by a runtime check. `internal`: the public surface is
// the per-mode setup functions below, which construct the right variant from their typed arguments.

/** A sender-side establishment mode bundled with the parameters that mode requires. */
internal sealed interface HpkeSenderMode {
    /** The wire `mode` byte (RFC 9180 §5.1) this establishment mode encodes to. */
    val wire: HpkeMode

    /** The pre-shared key, for the PSK-bearing modes; `null` otherwise. */
    val psk: HpkePsk? get() = null

    /** The sender's static private key, for the authenticated modes; `null` otherwise. */
    val senderPrivateKey: HpkePrivateKey? get() = null

    /** Base mode: no PSK, no sender authentication. */
    data object Base : HpkeSenderMode {
        override val wire: HpkeMode get() = HpkeMode.Base
    }

    /** PSK mode: carries the pre-shared key. */
    data class Psk(
        override val psk: HpkePsk,
    ) : HpkeSenderMode {
        override val wire: HpkeMode get() = HpkeMode.Psk
    }

    /** Auth mode: carries the sender's static private key. */
    data class Auth(
        override val senderPrivateKey: HpkePrivateKey,
    ) : HpkeSenderMode {
        override val wire: HpkeMode get() = HpkeMode.Auth
    }

    /** AuthPSK mode: carries both the sender's static private key and the pre-shared key. */
    data class AuthPsk(
        override val senderPrivateKey: HpkePrivateKey,
        override val psk: HpkePsk,
    ) : HpkeSenderMode {
        override val wire: HpkeMode get() = HpkeMode.AuthPsk
    }
}

/** A receiver-side establishment mode bundled with the parameters that mode requires. */
internal sealed interface HpkeReceiverMode {
    /** The wire `mode` byte (RFC 9180 §5.1) this establishment mode encodes to. */
    val wire: HpkeMode

    /** The pre-shared key, for the PSK-bearing modes; `null` otherwise. */
    val psk: HpkePsk? get() = null

    /** The sender's static public key, for the authenticated modes; `null` otherwise. */
    val senderPublicKey: HpkePublicKey? get() = null

    /** Base mode: no PSK, no sender authentication. */
    data object Base : HpkeReceiverMode {
        override val wire: HpkeMode get() = HpkeMode.Base
    }

    /** PSK mode: carries the pre-shared key. */
    data class Psk(
        override val psk: HpkePsk,
    ) : HpkeReceiverMode {
        override val wire: HpkeMode get() = HpkeMode.Psk
    }

    /** Auth mode: carries the sender's static public key. */
    data class Auth(
        override val senderPublicKey: HpkePublicKey,
    ) : HpkeReceiverMode {
        override val wire: HpkeMode get() = HpkeMode.Auth
    }

    /** AuthPSK mode: carries both the sender's static public key and the pre-shared key. */
    data class AuthPsk(
        override val senderPublicKey: HpkePublicKey,
        override val psk: HpkePsk,
    ) : HpkeReceiverMode {
        override val wire: HpkeMode get() = HpkeMode.AuthPsk
    }
}

// =============================================================================
// Setup — sender side (RFC 9180 §5.1)
// =============================================================================

/** SetupBaseS: encapsulate to [recipientPublicKey] and build a Base-mode sender context. */
internal suspend fun hpkeSetupBaseSender(
    suite: HpkeSuite,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
): HpkeSenderSetup = hpkeSetupSender(suite, HpkeSenderMode.Base, recipientPublicKey, info)

/** SetupPSKS: PSK-mode sender context. */
internal suspend fun hpkeSetupPskSender(
    suite: HpkeSuite,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
    psk: HpkePsk,
): HpkeSenderSetup = hpkeSetupSender(suite, HpkeSenderMode.Psk(psk), recipientPublicKey, info)

/** SetupAuthS: sender-authenticated context using the sender's static private key [senderPrivateKey]. */
internal suspend fun hpkeSetupAuthSender(
    suite: HpkeSuite,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
    senderPrivateKey: HpkePrivateKey,
): HpkeSenderSetup = hpkeSetupSender(suite, HpkeSenderMode.Auth(senderPrivateKey), recipientPublicKey, info)

/** SetupAuthPSKS: both PSK and sender authentication. */
internal suspend fun hpkeSetupAuthPskSender(
    suite: HpkeSuite,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
    psk: HpkePsk,
    senderPrivateKey: HpkePrivateKey,
): HpkeSenderSetup = hpkeSetupSender(suite, HpkeSenderMode.AuthPsk(senderPrivateKey, psk), recipientPublicKey, info)

internal suspend fun hpkeSetupSender(
    suite: HpkeSuite,
    mode: HpkeSenderMode,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
): HpkeSenderSetup = hpkeSetupSenderInternal(suite, mode, recipientPublicKey, info, null)

/**
 * Sender setup with an optionally-injected ephemeral key pair. The public setup functions pass
 * `ephemeral = null` so a fresh CSPRNG ephemeral is generated; the RFC 9180 KAT harness injects the
 * pinned `skEm`/`pkEm` so the vector's `enc` and `shared_secret` are reproducible. This is the only
 * way the ephemeral is ever caller-supplied — it is `internal`, never exposed to applications.
 */
internal suspend fun hpkeSetupSenderInternal(
    suite: HpkeSuite,
    mode: HpkeSenderMode,
    recipientPublicKey: HpkePublicKey,
    info: ReadBuffer,
    ephemeral: KeyAgreementKeyPair?,
): HpkeSenderSetup {
    requireSupported(suite)
    require(recipientPublicKey.kem == suite.kem) { "recipient key KEM does not match suite KEM" }

    // The mode carries its own sender key (only the authenticated modes have one), so the auth branch
    // is keyed off its presence rather than a separately-passed nullable + a runtime mode/param check.
    val senderPrivateKey = mode.senderPrivateKey
    if (senderPrivateKey != null) {
        require(senderPrivateKey.kem == suite.kem) { "sender key KEM does not match suite KEM" }
    }
    val encap =
        when {
            senderPrivateKey != null && ephemeral != null ->
                dhkemAuthEncapWithEphemeral(suite.kem, recipientPublicKey.keyAgreementKey(), senderPrivateKey, ephemeral)
            senderPrivateKey != null ->
                dhkemAuthEncap(suite.kem, recipientPublicKey.keyAgreementKey(), senderPrivateKey)
            ephemeral != null -> dhkemEncapWithEphemeral(suite.kem, recipientPublicKey.keyAgreementKey(), ephemeral)
            else -> dhkemEncap(suite.kem, recipientPublicKey.keyAgreementKey())
        }

    return try {
        val ctx = keyScheduleSender(suite, mode.wire, encap.sharedSecret, info, mode.psk)
        HpkeSenderSetup(ctx, encap.enc)
    } finally {
        encap.sharedSecret.freeNativeMemory()
    }
}

// =============================================================================
// Setup — receiver side (RFC 9180 §5.1)
// =============================================================================

/** SetupBaseR: decapsulate [enc] with [recipientPrivateKey] and build a Base-mode receiver context. */
internal suspend fun hpkeSetupBaseReceiver(
    suite: HpkeSuite,
    recipientPrivateKey: HpkePrivateKey,
    enc: ReadBuffer,
    info: ReadBuffer,
): HpkeContext.Receiver = hpkeSetupReceiver(suite, HpkeReceiverMode.Base, recipientPrivateKey, enc, info)

/** SetupPSKR: PSK-mode receiver context. */
internal suspend fun hpkeSetupPskReceiver(
    suite: HpkeSuite,
    recipientPrivateKey: HpkePrivateKey,
    enc: ReadBuffer,
    info: ReadBuffer,
    psk: HpkePsk,
): HpkeContext.Receiver = hpkeSetupReceiver(suite, HpkeReceiverMode.Psk(psk), recipientPrivateKey, enc, info)

/** SetupAuthR: sender-authenticated receiver context, verifying the sender's static public key. */
internal suspend fun hpkeSetupAuthReceiver(
    suite: HpkeSuite,
    recipientPrivateKey: HpkePrivateKey,
    enc: ReadBuffer,
    info: ReadBuffer,
    senderPublicKey: HpkePublicKey,
): HpkeContext.Receiver = hpkeSetupReceiver(suite, HpkeReceiverMode.Auth(senderPublicKey), recipientPrivateKey, enc, info)

/** SetupAuthPSKR: both PSK and sender authentication. */
internal suspend fun hpkeSetupAuthPskReceiver(
    suite: HpkeSuite,
    recipientPrivateKey: HpkePrivateKey,
    enc: ReadBuffer,
    info: ReadBuffer,
    psk: HpkePsk,
    senderPublicKey: HpkePublicKey,
): HpkeContext.Receiver = hpkeSetupReceiver(suite, HpkeReceiverMode.AuthPsk(senderPublicKey, psk), recipientPrivateKey, enc, info)

internal suspend fun hpkeSetupReceiver(
    suite: HpkeSuite,
    mode: HpkeReceiverMode,
    recipientPrivateKey: HpkePrivateKey,
    enc: ReadBuffer,
    info: ReadBuffer,
): HpkeContext.Receiver {
    requireSupported(suite)
    require(recipientPrivateKey.kem == suite.kem) { "recipient key KEM does not match suite KEM" }

    // The mode carries its own sender public key (only the authenticated modes have one), so the auth
    // branch is keyed off its presence rather than a separately-passed nullable + a runtime check.
    val senderPublicKey = mode.senderPublicKey
    val shared =
        if (senderPublicKey != null) {
            require(senderPublicKey.kem == suite.kem) { "sender key KEM does not match suite KEM" }
            dhkemAuthDecap(suite.kem, enc, recipientPrivateKey, senderPublicKey.keyAgreementKey())
        } else {
            dhkemDecap(suite.kem, enc, recipientPrivateKey)
        }

    return try {
        keyScheduleReceiver(suite, mode.wire, shared, info, mode.psk)
    } finally {
        shared.freeNativeMemory()
    }
}

/**
 * Gates a setup call against the platform's capabilities. The AEAD must be available on some path
 * (via the AEAD capability witness); an unavailable AEAD (e.g. ChaCha on the web)
 * throws immediately. The KEM curve must be usable synchronously *or* on the web (WebCrypto async);
 * if neither, it throws. A curve that the web engine claims but cannot actually provide (e.g. an
 * old browser without X25519) is caught by the underlying [dhRawSecret] / [generateKeyPairAsync],
 * which throw [UnsupportedOperationException] that propagates unchanged.
 */
internal fun requireSupported(suite: HpkeSuite) {
    val aeadOk =
        when (suite.aead) {
            // AES-GCM is reachable on every platform (witness is Blocking or AsyncOnly, never Unavailable).
            HpkeAead.Aes128Gcm, HpkeAead.Aes256Gcm -> true
            HpkeAead.ChaCha20Poly1305 -> chaChaPolyReachable
        }
    if (!aeadOk) {
        throw UnsupportedOperationException(
            "${suite.aead.aeadName} is not supported on this platform (HPKE suite ${suite.kem.kemName})",
        )
    }
    if (CryptoCapabilities.keyAgreement(suite.kem.curve) !is KeyAgreementSupport.Blocking && !isWebPlatformKa) {
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

internal class ScheduleOutput(
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
 * scratch and are freed before returning. The three returned buffers come from [outputFactory] —
 * [secureScratch] in production, so they are wiped on context close. The parameter is a test seam:
 * a `managed().secure()` factory keeps the wiped bytes readable after close (managed free is a GC
 * no-op), letting a test byte-assert the zeroization — the [AeadKeyCloseTest] technique.
 */
internal fun keySchedule(
    suite: HpkeSuite,
    mode: HpkeMode,
    sharedSecret: PlatformBuffer,
    info: ReadBuffer,
    psk: HpkePsk?,
    outputFactory: BufferFactory = secureScratch,
): ScheduleOutput {
    val kdf = suite.kdf
    val suiteId = suite.suiteId()
    val nh = kdf.nh

    val pskValue: ReadBuffer? = psk?.requireOpen()
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
        val key = outputFactory.allocate(suite.aead.nk)
        labeledExpand(kdf, suiteId, secret, LABEL_KEY, ksc, suite.aead.nk, key)
        key.resetForRead()

        val baseNonce = outputFactory.allocate(suite.aead.nn)
        labeledExpand(kdf, suiteId, secret, LABEL_BASE_NONCE, ksc, suite.aead.nn, baseNonce)
        baseNonce.resetForRead()

        val exporterSecret = outputFactory.allocate(nh)
        labeledExpand(kdf, suiteId, secret, LABEL_EXP, ksc, nh, exporterSecret)
        exporterSecret.resetForRead()

        return ScheduleOutput(key, baseNonce, exporterSecret)
    } finally {
        pskIdHash.freeNativeMemory()
        infoHash.freeNativeMemory()
        secret.freeNativeMemory()
    }
}

internal val EMPTY: ReadBuffer = BufferFactory.Default.allocate(0).also { it.resetForRead() }

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
    val suiteIdLen = suiteId.remaining()
    val len = HPKE_V1.size + suiteIdLen + label.size + ikm.remaining()
    val labeled = secureScratch.allocate(len)
    try {
        labeled.writeBytes(HPKE_V1)
        copyInto(suiteId, labeled)
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
    val suiteIdLen = suiteId.remaining()
    val infoLen = info?.remaining() ?: 0
    val len = 2 + HPKE_V1.size + suiteIdLen + label.size + infoLen
    val labeled = secureScratch.allocate(len)
    try {
        i2osp2(length, labeled)
        labeled.writeBytes(HPKE_V1)
        copyInto(suiteId, labeled)
        labeled.writeBytes(label)
        if (info != null) copyInto(info, labeled)
        labeled.resetForRead()
        kdf.expandInto(prk, labeled, length, dest)
    } finally {
        labeled.freeNativeMemory()
    }
}
