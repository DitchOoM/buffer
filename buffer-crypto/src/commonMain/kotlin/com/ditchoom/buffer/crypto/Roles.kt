package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import kotlin.jvm.JvmInline

/*
 * No-nullable role types for optional crypto inputs.
 *
 * Optional associated data, salt, and info used to be `ReadBuffer?` parameters — silently
 * transposable (a `null` salt and a `null` info look identical at a call site) and nullable.
 * Each is now a `sealed interface` with exactly two states: an explicit [Aad.None]/[Salt.None]/
 * [Info.None] sentinel and an [Aad.Of]/[Salt.Of]/[Info.Of] wrapper carrying the bytes. The
 * defaults are the non-null sentinel, so an absent optional input is expressed in the type system
 * rather than as `null`, and impl code branches it exhaustively with no `?:`/`!!`.
 */

/** Associated data for an AEAD operation: authenticated-but-not-encrypted bytes, or [None]. */
sealed interface Aad {
    /** No associated data. */
    data object None : Aad

    /** Associated data carrying [bytes] (authenticated, never encrypted). */
    @JvmInline
    value class Of(
        val bytes: ReadBuffer,
    ) : Aad
}

/** Optional HKDF / key-agreement salt, or [None] (HKDF treats an absent salt as the zero block). */
sealed interface Salt {
    /** No salt (HKDF-Extract uses an all-zero salt block). */
    data object None : Salt

    /** A salt of [bytes]. */
    @JvmInline
    value class Of(
        val bytes: ReadBuffer,
    ) : Salt
}

/** Optional domain-separation info / context bytes, or [None]. */
sealed interface Info {
    /** No info / empty context. */
    data object None : Info

    /** Info / context of [bytes]. */
    @JvmInline
    value class Of(
        val bytes: ReadBuffer,
    ) : Info
}

/**
 * An AEAD nonce/IV — always required where used, and validated to be exactly [AEAD_NONCE_BYTES]
 * bytes at construction so a wrong-length nonce can never reach a primitive. This subsumes the
 * per-platform `requireNonce` length checks.
 */
@JvmInline
value class Nonce(
    val bytes: ReadBuffer,
) {
    init {
        require(bytes.remaining() == AEAD_NONCE_BYTES) {
            "nonce must be $AEAD_NONCE_BYTES bytes, was ${bytes.remaining()}"
        }
    }
}

/** The carried bytes, or `null` for [Aad.None] — internal seam to the nullable-taking primitives. */
internal val Aad.bytesOrNull: ReadBuffer?
    get() =
        when (this) {
            Aad.None -> null
            is Aad.Of -> bytes
        }

/** The carried bytes, or `null` for [Salt.None]. */
internal val Salt.bytesOrNull: ReadBuffer?
    get() =
        when (this) {
            Salt.None -> null
            is Salt.Of -> bytes
        }

/** The carried bytes, or `null` for [Info.None]. */
internal val Info.bytesOrNull: ReadBuffer?
    get() =
        when (this) {
            Info.None -> null
            is Info.Of -> bytes
        }
