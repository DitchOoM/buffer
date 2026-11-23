package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.toHexString

internal fun Salt.hex(): String =
    when (this) {
        Salt.None -> ""
        is Salt.Of -> bytes.toHexString()
    }

internal fun Info.hex(): String =
    when (this) {
        Info.None -> ""
        is Info.Of -> bytes.toHexString()
    }

internal fun Aad.hex(): String =
    when (this) {
        Aad.None -> ""
        is Aad.Of -> bytes.toHexString()
    }

/** A registry entry read back from IndexedDB: either an HKDF key of [Hkdf.sizeBytes] or a key of another kind. */
internal sealed interface StoredHkdfEntry {
    data class Hkdf(
        val token: Int,
        val sizeBytes: Int,
    ) : StoredHkdfEntry

    data class OtherKind(
        val token: Int,
    ) : StoredHkdfEntry
}

private const val MARSHALLED_FIELDS = 4
private const val MARSHALLED_MIN_FIELDS = 3

/**
 * Parses `"<token>:<kind>:<tag>:<pubHex>"`; for an HKDF entry the tag is the key size and pubHex is empty.
 */
internal fun parseStoredHkdfEntry(marshalled: String): StoredHkdfEntry {
    val parts = marshalled.split(':', limit = MARSHALLED_FIELDS)
    val token = parts[0].toInt()
    if (parts.size < MARSHALLED_MIN_FIELDS || parts[1].toIntOrNull() != KIND_HKDF) {
        return StoredHkdfEntry.OtherKind(token)
    }
    val sizeBytes = parts[2].toIntOrNull() ?: throw KeyStoreException.CorruptEntry()
    return StoredHkdfEntry.Hkdf(token, sizeBytes)
}
