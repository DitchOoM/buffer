package com.ditchoom.buffer.crypto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a secure element's key-agreement probe found. Only [Supported] makes agreement eligible; [NotSupported] is the
 * element's answer, while [DeviceLocked] and [Undecided] are this probe's failure, which a caller is told as such
 * rather than as an algorithm the element cannot hold.
 */
internal sealed interface AgreementSupport {
    data object Supported : AgreementSupport

    /** The element answered: it does not perform this agreement in secure hardware. */
    data object NotSupported : AgreementSupport

    /** The probe was refused because the device is locked; asked again on the next call. */
    data object DeviceLocked : AgreementSupport

    /** The probe failed in a way that says nothing about the element; asked again after the probe window. */
    data object Undecided : AgreementSupport
}

internal val AgreementSupport.supported: Boolean get() = this == AgreementSupport.Supported

/**
 * Returns when agreement is [AgreementSupport.Supported]; otherwise throws what the caller should act on:
 * [HardwareKeyException.AlgorithmNotEligible] only for the element's own no, the probe's failure for the rest.
 */
internal fun AgreementSupport.requireSupported() {
    when (this) {
        AgreementSupport.Supported -> Unit
        AgreementSupport.NotSupported -> throw HardwareKeyException.AlgorithmNotEligible()
        AgreementSupport.DeviceLocked -> throw HardwareKeyException.DeviceLocked()
        AgreementSupport.Undecided -> throw HardwareKeyException.TransientHardwareFailure(retryable = true)
    }
}

/** A value [withContextReleasingOnCancel]'s block produced. */
private class Produced<T>(
    val value: T,
)

/**
 * [withContext], except that when the caller is cancelled while [block] runs to completion, so that [withContext]
 * throws [CancellationException] instead of returning, the value [block] produced is passed to [release] rather than
 * dropped.
 */
@OptIn(ExperimentalAtomicApi::class)
internal suspend fun <T> withContextReleasingOnCancel(
    context: CoroutineContext,
    release: (T) -> Unit,
    block: suspend CoroutineScope.() -> T,
): T {
    val produced = AtomicReference<Produced<T>?>(null)
    try {
        return withContext(context) { block().also { produced.store(Produced(it)) } }
    } catch (e: CancellationException) {
        produced.load()?.let { release(it.value) }
        throw e
    }
}
