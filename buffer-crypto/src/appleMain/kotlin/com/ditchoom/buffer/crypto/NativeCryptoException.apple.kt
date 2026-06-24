package com.ditchoom.buffer.crypto

/** Apple has no JCA umbrella, so the crypto-exception supertype is a plain [Exception]. */
actual open class NativeCryptoException : Exception {
    internal actual constructor(message: String?) : super(message)

    internal actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}
