package com.ditchoom.buffer.crypto

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The PKCS#11 backend takes its module path, PIN, and slot index from either a module-neutral
 * `buffer.crypto.pkcs11.*` name or the original `buffer.crypto.tpm2.pkcs11.*` one. The `tpm2`
 * spelling shipped first and is published configuration, so dropping it would break deployments
 * that already set it; these tests pin that it keeps working, and that the newer name wins when a
 * deployment carries both.
 *
 * Property names here are test-local — the resolution itself is a cached `val` computed once per
 * process, so this exercises the precedence rule rather than re-driving resolution.
 */
class Pkcs11ConfigAliasTest {
    private val neutralProp = "buffer.crypto.test.pkcs11.module"
    private val neutralEnv = "BUFFER_CRYPTO_TEST_PKCS11_MODULE"
    private val legacyProp = "buffer.crypto.test.tpm2.pkcs11.module"
    private val legacyEnv = "BUFFER_CRYPTO_TEST_TPM2_PKCS11_MODULE"

    @AfterTest
    fun clearProperties() {
        System.clearProperty(neutralProp)
        System.clearProperty(legacyProp)
    }

    private fun resolve() = configuredPkcs11(neutralProp, neutralEnv, legacyProp, legacyEnv)

    @Test
    fun neitherNameSetResolvesToNull() {
        assertNull(resolve(), "no configuration means absent, not empty string")
    }

    @Test
    fun legacyTpm2NameStillResolves() {
        System.setProperty(legacyProp, "/usr/lib/libtpm2_pkcs11.so.1")
        assertEquals(
            "/usr/lib/libtpm2_pkcs11.so.1",
            resolve(),
            "the tpm2 spelling is published configuration and must keep working",
        )
    }

    @Test
    fun moduleNeutralNameResolves() {
        System.setProperty(neutralProp, "/opt/homebrew/lib/opensc-pkcs11.so")
        assertEquals("/opt/homebrew/lib/opensc-pkcs11.so", resolve())
    }

    @Test
    fun moduleNeutralNameWinsOverLegacyWhenBothSet() {
        System.setProperty(legacyProp, "/usr/lib/libtpm2_pkcs11.so.1")
        System.setProperty(neutralProp, "/opt/homebrew/lib/opensc-pkcs11.so")
        assertEquals(
            "/opt/homebrew/lib/opensc-pkcs11.so",
            resolve(),
            "the accurate name wins when a deployment carries both",
        )
    }

    @Test
    fun blankNeutralNameFallsThroughToLegacy() {
        System.setProperty(neutralProp, "   ")
        System.setProperty(legacyProp, "/usr/lib/libtpm2_pkcs11.so.1")
        assertEquals(
            "/usr/lib/libtpm2_pkcs11.so.1",
            resolve(),
            "a blank value is absent, so it must not shadow a real legacy setting",
        )
    }
}
