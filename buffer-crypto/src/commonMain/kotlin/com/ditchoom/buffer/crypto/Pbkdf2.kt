package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * The pseudo-random function PBKDF2 iterates (RFC 8018 §5.2). [blockBytes] is the PRF output length
 * `hLen`: one derived block per `hLen` bytes of requested output.
 */
enum class Pbkdf2Prf(
    val blockBytes: Int,
) {
    HmacSha256(HMAC_SHA256_BYTES),
    HmacSha512(HMAC_SHA512_BYTES),
}

/**
 * Upper bound on a single PBKDF2 output, in bytes. RFC 8018 allows far more, but WebCrypto takes the
 * length in bits as a 32-bit value, so this is the largest length every platform accepts identically.
 * Requesting more than one PRF block from PBKDF2 multiplies the defender's cost without raising the
 * attacker's (each block is independent); derive one block and expand it with [HkdfSha512] instead.
 */
const val PBKDF2_MAX_OUTPUT_BYTES: Int = Int.MAX_VALUE / Byte.SIZE_BITS

/**
 * PBKDF2 (RFC 8018 §5.2) — derive a key from a passphrase. Both entry points are `suspend`; on
 * platforms with a synchronous native PBKDF2 the witness is a [Pbkdf2BlockingOps], which adds the
 * non-suspend entry points. The native suspend path runs on the caller's dispatcher, so move a
 * high-iteration derivation off a latency-sensitive thread.
 *
 * The password and salt are the remaining bytes of their buffers, read non-destructively; the
 * password is used byte-for-byte (no character encoding is applied, so encode text passwords
 * explicitly, e.g. as UTF-8). Preconditions are checked in common code before dispatch and throw
 * [IllegalArgumentException] identically on every platform: [iterations] ≥ 1,
 * `length in 1..`[PBKDF2_MAX_OUTPUT_BYTES], and `dest.remaining() >= length`.
 */
interface Pbkdf2AsyncOps {
    val prf: Pbkdf2Prf

    /** Writes [length] bytes of PBKDF2 output into [dest] at its position, advancing it. */
    suspend fun deriveInto(
        password: ReadBuffer,
        salt: ReadBuffer,
        iterations: Int,
        length: Int,
        dest: WriteBuffer,
    )

    /**
     * Returns a freshly allocated, read-ready buffer of [length] derived bytes from [factory]. Use
     * `BufferFactory.deterministic().secure()` if the derived key must be wiped after use.
     */
    suspend fun derive(
        password: ReadBuffer,
        salt: ReadBuffer,
        iterations: Int,
        length: Int,
        factory: BufferFactory,
    ): ReadBuffer
}

/** PBKDF2 with a synchronous (non-`suspend`) path, in addition to the inherited async one. */
interface Pbkdf2BlockingOps : Pbkdf2AsyncOps {
    /** Synchronous [Pbkdf2AsyncOps.deriveInto]. */
    fun deriveIntoBlocking(
        password: ReadBuffer,
        salt: ReadBuffer,
        iterations: Int,
        length: Int,
        dest: WriteBuffer,
    )

    /** Synchronous [Pbkdf2AsyncOps.derive]. */
    fun deriveBlocking(
        password: ReadBuffer,
        salt: ReadBuffer,
        iterations: Int,
        length: Int,
        factory: BufferFactory,
    ): ReadBuffer
}

/**
 * Capability witness for PBKDF2: synchronous ([Blocking], native) or async-only ([AsyncOnly],
 * WebCrypto). Every platform provides it in one of the two forms.
 */
sealed interface Pbkdf2Support {
    val ops: Pbkdf2AsyncOps

    /** Async-only (WebCrypto `deriveBits`). Only [Pbkdf2AsyncOps] is reachable. */
    data class AsyncOnly(
        override val ops: Pbkdf2AsyncOps,
    ) : Pbkdf2Support

    /** A synchronous native path is available; [ops] also satisfies [Pbkdf2AsyncOps]. */
    data class Blocking(
        override val ops: Pbkdf2BlockingOps,
    ) : Pbkdf2Support
}

/**
 * PBKDF2 over [prf] on this platform: [Pbkdf2Support.Blocking] on JVM/Android (JCA `Mac`), Apple
 * (CommonCrypto `CCKeyDerivationPBKDF`) and Linux (BoringSSL `PKCS5_PBKDF2_HMAC`);
 * [Pbkdf2Support.AsyncOnly] on js/wasmJs (WebCrypto `deriveBits`).
 */
expect fun CryptoCapabilities.pbkdf2(prf: Pbkdf2Prf): Pbkdf2Support

/** A platform's PBKDF2 primitive, called only after [requirePbkdf2Args] has passed. */
internal fun interface Pbkdf2Primitive {
    fun deriveInto(
        prf: Pbkdf2Prf,
        password: ReadBuffer,
        salt: ReadBuffer,
        iterations: Int,
        length: Int,
        dest: WriteBuffer,
    )
}

/** The web's PBKDF2 primitive, called only after [requirePbkdf2Args] has passed. */
internal fun interface Pbkdf2AsyncPrimitive {
    suspend fun deriveInto(
        prf: Pbkdf2Prf,
        password: ReadBuffer,
        salt: ReadBuffer,
        iterations: Int,
        length: Int,
        dest: WriteBuffer,
    )
}

internal fun requirePbkdf2Args(
    iterations: Int,
    length: Int,
) {
    require(iterations >= 1) { "iterations must be >= 1, was $iterations" }
    require(length in 1..PBKDF2_MAX_OUTPUT_BYTES) { "length must be in 1..$PBKDF2_MAX_OUTPUT_BYTES, was $length" }
}

internal fun requirePbkdf2Dest(
    length: Int,
    dest: WriteBuffer,
) {
    require(dest.remaining() >= length) { "dest needs $length bytes remaining, has ${dest.remaining()}" }
}

internal class BlockingPbkdf2Ops(
    override val prf: Pbkdf2Prf,
    private val primitive: Pbkdf2Primitive,
) : Pbkdf2BlockingOps {
    override fun deriveIntoBlocking(
        password: ReadBuffer,
        salt: ReadBuffer,
        iterations: Int,
        length: Int,
        dest: WriteBuffer,
    ) {
        requirePbkdf2Args(iterations, length)
        requirePbkdf2Dest(length, dest)
        primitive.deriveInto(prf, password, salt, iterations, length, dest)
    }

    override fun deriveBlocking(
        password: ReadBuffer,
        salt: ReadBuffer,
        iterations: Int,
        length: Int,
        factory: BufferFactory,
    ): ReadBuffer {
        requirePbkdf2Args(iterations, length)
        val out = factory.allocate(length)
        primitive.deriveInto(prf, password, salt, iterations, length, out)
        out.resetForRead()
        return out
    }

    override suspend fun deriveInto(
        password: ReadBuffer,
        salt: ReadBuffer,
        iterations: Int,
        length: Int,
        dest: WriteBuffer,
    ) = deriveIntoBlocking(password, salt, iterations, length, dest)

    override suspend fun derive(
        password: ReadBuffer,
        salt: ReadBuffer,
        iterations: Int,
        length: Int,
        factory: BufferFactory,
    ): ReadBuffer = deriveBlocking(password, salt, iterations, length, factory)
}

internal class AsyncPbkdf2Ops(
    override val prf: Pbkdf2Prf,
    private val primitive: Pbkdf2AsyncPrimitive,
) : Pbkdf2AsyncOps {
    override suspend fun deriveInto(
        password: ReadBuffer,
        salt: ReadBuffer,
        iterations: Int,
        length: Int,
        dest: WriteBuffer,
    ) {
        requirePbkdf2Args(iterations, length)
        requirePbkdf2Dest(length, dest)
        primitive.deriveInto(prf, password, salt, iterations, length, dest)
    }

    override suspend fun derive(
        password: ReadBuffer,
        salt: ReadBuffer,
        iterations: Int,
        length: Int,
        factory: BufferFactory,
    ): ReadBuffer {
        requirePbkdf2Args(iterations, length)
        val out = factory.allocate(length)
        primitive.deriveInto(prf, password, salt, iterations, length, out)
        out.resetForRead()
        return out
    }
}
