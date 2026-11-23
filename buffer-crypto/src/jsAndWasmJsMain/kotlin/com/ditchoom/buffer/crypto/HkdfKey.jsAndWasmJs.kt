package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.fromHexString
import com.ditchoom.buffer.toHexString

/*
 * JS / WASM: HKDF keys as non-extractable WebCrypto `CryptoKey`s (WebCrypto never makes an HKDF key
 * extractable), referenced by a token into the same `globalThis.__bcNE` registry the other WebCrypto
 * keys use. A key persisted by [WebCryptoHkdfKeyStore] is a structured-clone copy of the `CryptoKey` in
 * IndexedDB, so a window and a service worker of one origin reopen the same key, and a store written
 * from Kotlin/JS reopens from Kotlin/Wasm and back: the record format is shared.
 *
 * Imported material and salts/infos cross the boundary as hex Strings, the module's accepted web
 * limitation; derived child keys and AES keys never do.
 */

internal actual fun platformHkdfKeyStore(config: KeyStoreConfig): HkdfKeyStore =
    when {
        config.storage != null -> SoftwareHkdfKeyStore(config.storage)
        subtleGenerateKeyAvailable && webCryptoIndexedDbAvailable -> WebCryptoHkdfKeyStore(config.name)
        else -> SoftwareHkdfKeyStore(InMemoryKeyStorage())
    }

internal actual suspend fun platformImportHkdfKey(ikm: ReadBuffer): HkdfKey =
    if (subtleGenerateKeyAvailable) {
        buildWebCryptoHkdfKey(webCryptoImportHkdf(ikm.toHexString()).toInt(), ikm.remaining())
    } else {
        HkdfKey.of(ikm)
    }

/** Wraps the registered non-extractable HKDF key [token] of [sizeBytes] bytes as an [HkdfKey]. */
internal fun buildWebCryptoHkdfKey(
    token: Int,
    sizeBytes: Int,
): HkdfKey =
    ProtectedHkdfKey(
        sizeBytes = sizeBytes,
        custody = KeyCustody.NonExportable.Software,
        gatedDeriveBytes = { salt, info, length, factory ->
            factory.fromHexString(webCryptoHkdfDeriveBits(token, salt.hex(), info.hex(), length * Byte.SIZE_BITS))
        },
        gatedDeriveHkdfKey = { salt, info, childBytes ->
            val child = webCryptoHkdfDeriveHkdf(token, salt.hex(), info.hex(), childBytes * Byte.SIZE_BITS).toInt()
            buildWebCryptoHkdfKey(child, childBytes)
        },
        gatedDeriveAesGcmKey = { salt, info, sizeBits ->
            buildWebCryptoDerivedAesGcmKey(webCryptoHkdfDeriveAesGcm(token, salt.hex(), info.hex(), sizeBits).toInt(), sizeBits)
        },
        onClose = { webCryptoReleaseHandle(token) },
    )

/** Wraps the registered non-extractable AES-GCM key [token] as a [DerivedAesGcmKey] taking the caller's IV. */
internal fun buildWebCryptoDerivedAesGcmKey(
    token: Int,
    sizeBits: Int,
): DerivedAesGcmKey =
    ProtectedDerivedAesGcmKey(
        sizeBits = sizeBits,
        custody = KeyCustody.NonExportable.Software,
        gatedSeal = { nonce, plaintext, aad, factory ->
            factory.fromHexString(webCryptoAesGcmSealHandle(token, nonce.toHexString(), aad.hex(), plaintext.toHexString()))
        },
        gatedOpen = { nonce, ciphertextAndTag, aad, factory ->
            val ptHex =
                webCryptoAesGcmOpenHandle(token, nonce.toHexString(), aad.hex(), ciphertextAndTag.toHexString())
                    ?: throw VerificationFailed()
            factory.fromHexString(ptHex)
        },
        onClose = { webCryptoReleaseHandle(token) },
    )

/**
 * [HkdfKeyStore] over IndexedDB database [dbName]: each entry is `{ meta: "3:<sizeBytes>:", key }`,
 * the record shape [WebCryptoKeyStore] uses, so the IndexedDB glue is shared.
 */
internal class WebCryptoHkdfKeyStore(
    private val dbName: String,
) : HkdfKeyStore {
    override val custody: KeyCustody get() = KeyCustody.NonExportable.Software

    override suspend fun importKey(
        alias: String,
        ikm: ReadBuffer,
    ): HkdfKey {
        requireValidAlias(alias)
        requireHkdfKeySize(ikm.remaining())
        val token = webCryptoImportHkdf(ikm.toHexString()).toInt()
        if (!webCryptoIdbAdd(dbName, alias, token, "$KIND_HKDF:${ikm.remaining()}:")) {
            webCryptoReleaseHandle(token)
            throw KeyStoreException.AliasOccupied(alias)
        }
        return buildWebCryptoHkdfKey(token, ikm.remaining())
    }

    override suspend fun load(alias: String): HkdfKey? {
        requireValidAlias(alias)
        val marshalled = webCryptoIdbLoad(dbName, alias) ?: return null
        // "<token>:<kind>:<tag>:<pubHex>"; for an HKDF entry the tag is the key size and pubHex is empty.
        val parts = marshalled.split(':', limit = 4)
        val token = parts[0].toInt()
        if (parts.size < 3 || parts[1].toIntOrNull() != KIND_HKDF) {
            webCryptoReleaseHandle(token)
            return null
        }
        val sizeBytes = parts[2].toIntOrNull() ?: throw KeyStoreException.CorruptEntry()
        return buildWebCryptoHkdfKey(token, sizeBytes)
    }

    override suspend fun contains(alias: String): Boolean {
        requireValidAlias(alias)
        return webCryptoIdbContains(dbName, alias)
    }

    override suspend fun aliases(): Set<String> = webCryptoIdbAliases(dbName).split('\n').filterTo(mutableSetOf()) { it.isNotEmpty() }

    override suspend fun delete(alias: String): Boolean {
        requireValidAlias(alias)
        return webCryptoIdbDelete(dbName, alias)
    }

    override fun close() = Unit
}

private fun Salt.hex(): String =
    when (this) {
        Salt.None -> ""
        is Salt.Of -> bytes.toHexString()
    }

private fun Info.hex(): String =
    when (this) {
        Info.None -> ""
        is Info.Of -> bytes.toHexString()
    }

private fun Aad.hex(): String =
    when (this) {
        Aad.None -> ""
        is Aad.Of -> bytes.toHexString()
    }

// =============================================================================
// Per-target WebCrypto seam for HKDF keys (dynamic on JS, @JsFun externals on wasmJs)
// =============================================================================

/**
 * Imports [ikmHex] as a non-extractable HKDF `CryptoKey` (usages deriveBits + deriveKey), zeroes the
 * staged bytes, registers the key, and returns its token as a string.
 */
internal expect suspend fun webCryptoImportHkdf(ikmHex: String): String

/** HKDF-SHA256 `deriveBits` of [bits] bits from the registered key [token]; an empty salt is the zero block. */
internal expect suspend fun webCryptoHkdfDeriveBits(
    token: Int,
    saltHex: String,
    infoHex: String,
    bits: Int,
): String

/**
 * Derives [bits] bits from the registered HKDF key [token] and imports them, inside the engine, as a new
 * non-extractable HKDF key; the transient bits are zeroed. Returns the child's token as a string.
 */
internal expect suspend fun webCryptoHkdfDeriveHkdf(
    token: Int,
    saltHex: String,
    infoHex: String,
    bits: Int,
): String

/** `deriveKey` from the registered HKDF key [token] to a non-extractable AES-GCM key of [lengthBits]; returns its token. */
internal expect suspend fun webCryptoHkdfDeriveAesGcm(
    token: Int,
    saltHex: String,
    infoHex: String,
    lengthBits: Int,
): String

/**
 * Stores the registered key [token] plus [meta] under [alias] only if [alias] is free, in one IndexedDB
 * `add` request; `false` if the alias was taken.
 */
internal expect suspend fun webCryptoIdbAdd(
    dbName: String,
    alias: String,
    token: Int,
    meta: String,
): Boolean
