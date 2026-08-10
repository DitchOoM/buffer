package com.ditchoom.buffer

// Raw NSStringEncoding values (platform-agnostic, same numeric value everywhere)
// Using Long to avoid platform-specific type inference issues in metadata compilation
internal const val ENCODING_ASCII: Long = 1
internal const val ENCODING_ISO_LATIN1: Long = 2
internal const val ENCODING_UTF8: Long = 4
internal const val ENCODING_UTF16: Long = 10
internal const val ENCODING_UTF16_BE: Long = 0x90000100
internal const val ENCODING_UTF16_LE: Long = 0x94000100
internal const val ENCODING_UTF32: Long = 0x8c000100
internal const val ENCODING_UTF32_BE: Long = 0x98000100
internal const val ENCODING_UTF32_LE: Long = 0x9c000100

/**
 * Apple implementation of string decoding using NSString.
 * Supports a wide range of charsets via Foundation framework.
 */
internal actual fun decodeByteArrayToString(
    data: ByteArray,
    startIndex: Int,
    endIndex: Int,
    charset: Charset,
): String {
    // For UTF-8, use Kotlin's built-in decoder for better performance
    if (charset == Charset.UTF8) {
        return data.decodeToString(startIndex, endIndex, throwOnInvalidSequence = true)
    }

    // Get the encoding value (platform-agnostic Long)
    val encodingValue: Long =
        when (charset) {
            Charset.UTF8 -> ENCODING_UTF8
            Charset.UTF16 -> ENCODING_UTF16
            Charset.UTF16BigEndian -> ENCODING_UTF16_BE
            Charset.UTF16LittleEndian -> ENCODING_UTF16_LE
            Charset.ASCII -> ENCODING_ASCII
            Charset.ISOLatin1 -> ENCODING_ISO_LATIN1
            Charset.UTF32 -> ENCODING_UTF32
            Charset.UTF32LittleEndian -> ENCODING_UTF32_LE
            Charset.UTF32BigEndian -> ENCODING_UTF32_BE
        }

    // Delegate to platform-specific implementation for Foundation API calls
    return decodeWithFoundation(data, startIndex, endIndex - startIndex, encodingValue)
}

/**
 * Platform-specific NSString decoding using Foundation APIs.
 * Implemented per-target (macosMain, iosMain, watchosArm64Main, …) to handle platform-varying types.
 *
 * **This must stay an `expect`; do not collapse the actuals into one `appleMain` body.** The actuals
 * differ only in `length.toULong()` vs `length.toUInt()`, which reads like pure duplication, but
 * `NSUInteger` is 32-bit on `watchosArm64` (arm64_32) and 64-bit on every other Apple target, and
 * `appleMain` compiles to a *single* shared metadata artifact spanning all of them. Referring to
 * `NSData.create` / `NSString.create` directly from `appleMain` fails
 * `compileAppleMainKotlinMetadata` with "The declaration is using numbers with different bit widths
 * in least two actual platforms" — and `kotlinx.cinterop.convert()` does not rescue it, because the
 * overloads themselves resolve per-target. The `expect` is what keeps `NSUInteger` out of the shared
 * compilation.
 *
 * Consequence: **registering a new Apple target requires adding an actual here**, or that target
 * fails to compile. 64-bit targets copy the `toULong()` form; only arm64_32 uses `toUInt()`.
 */
internal expect fun decodeWithFoundation(
    data: ByteArray,
    offset: Int,
    length: Int,
    encoding: Long,
): String
