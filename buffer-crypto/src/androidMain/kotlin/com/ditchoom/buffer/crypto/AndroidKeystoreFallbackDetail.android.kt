package com.ditchoom.buffer.crypto

/** What the Android keystore reported when StrongBox refused a key that was then made in the TEE. */
@ConsistentCopyVisibility
data class AndroidKeystoreFallbackDetail internal constructor(
    val code: AndroidKeystoreCode,
) : SecureElementFallbackDetail()

/** An Android keystore error code, as the platform publishes it. */
sealed interface AndroidKeystoreCode {
    /** `android.security.KeyStoreException.getNumericErrorCode()`, from API 33. */
    @ConsistentCopyVisibility
    data class Public internal constructor(
        val code: Int,
    ) : AndroidKeystoreCode

    /** The keystore published no code (before API 33, or no keystore exception in the cause chain). */
    data object Unpublished : AndroidKeystoreCode
}
