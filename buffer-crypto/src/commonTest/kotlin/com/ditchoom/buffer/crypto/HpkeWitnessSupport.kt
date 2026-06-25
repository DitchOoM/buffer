package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/*
 * Test-only accessors that bridge the HPKE capability witness + role-typed per-message API back to
 * the plain `ReadBuffer`/`ReadBuffer?` shapes the HPKE suites were written against, so the suites
 * stay readable. `hpkeSupported` resolves the witness; the `seal`/`open`/`export` extensions wrap a
 * nullable buffer into the [Aad] / [Info] role types the public members now take.
 */

/** Whether [suite] resolves to [HpkeSupport.Supported] on this platform. */
internal fun hpkeSupported(suite: HpkeSuite): Boolean = CryptoCapabilities.hpke(suite) is HpkeSupport.Supported

/** The HPKE ops for [suite], or null when the suite is [HpkeSupport.Unsupported] here. */
internal fun hpkeOpsOrNull(suite: HpkeSuite): HpkeOps? = (CryptoCapabilities.hpke(suite) as? HpkeSupport.Supported)?.ops

/** Per-message seal taking a nullable AAD (wraps into [Aad]); mirrors the pre-reshape signature. */
internal suspend fun HpkeContext.Sender.seal(
    plaintext: ReadBuffer,
    aad: ReadBuffer?,
    factory: BufferFactory = BufferFactory.Default,
): PlatformBuffer = seal(plaintext, aad.toAad(), factory)

/** Per-message open taking a nullable AAD (wraps into [Aad]). */
internal suspend fun HpkeContext.Receiver.open(
    ciphertext: ReadBuffer,
    aad: ReadBuffer?,
    factory: BufferFactory = BufferFactory.Default,
): PlatformBuffer = open(ciphertext, aad.toAad(), factory)

/** Secret export taking a nullable exporter context (wraps into [Info]). */
internal fun HpkeContext.export(
    exporterContext: ReadBuffer?,
    length: Int,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer = export(exporterContext.toInfo(), length, factory)
