package com.ditchoom.buffer.crypto

/** What the Android keystore reported when StrongBox refused a key that was then made in the TEE. */
class AndroidKeystoreFallbackDetail internal constructor(
    val code: AndroidKeystoreCode,
) : SecureElementFallbackDetail() {
    override fun equals(other: Any?): Boolean =
        this === other || (other is AndroidKeystoreFallbackDetail && code == other.code)

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "AndroidKeystoreFallbackDetail(code=$code)"
}

/** An Android keystore error code, as the platform publishes it. */
sealed interface AndroidKeystoreCode {
    /** `android.security.KeyStoreException.getNumericErrorCode()`, from API 33. */
    class Public internal constructor(
        val code: Int,
    ) : AndroidKeystoreCode {
        override fun equals(other: Any?): Boolean = this === other || (other is Public && code == other.code)

        override fun hashCode(): Int = code

        override fun toString(): String = "Public(code=$code)"
    }

    /** The keystore published no code (before API 33, or no keystore exception in the cause chain). */
    data object Unpublished : AndroidKeystoreCode
}
