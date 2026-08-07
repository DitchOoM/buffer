package com.ditchoom.buffer.crypto

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The PKCS#11 backend takes its module path, PIN, and slot index from either a module-neutral
 * `buffer.crypto.pkcs11.*` name or the original `buffer.crypto.tpm2.pkcs11.*` one. The `tpm2`
 * spelling shipped first and is published configuration, so dropping it would break deployments
 * that already set it.
 *
 * These drive the real reader ([pkcs11]) rather than a standalone helper, because the failure this
 * guards against is a *call site* reading the setting names directly and going blind to the legacy
 * spelling — which is exactly what happened to the agreement-side configure, and which a
 * helper-only test does not catch.
 *
 * Only system properties are set here; the environment fallback is not settable in-process. The
 * resolution itself is computed once per process, so nothing below re-drives it.
 */
class Pkcs11ConfigAliasTest {
    private val allProperties =
        listOf(
            "buffer.crypto.pkcs11.module",
            "buffer.crypto.pkcs11.pin",
            "buffer.crypto.pkcs11.slotIndex",
            "buffer.crypto.tpm2.pkcs11.module",
            "buffer.crypto.tpm2.pkcs11.pin",
            "buffer.crypto.tpm2.pkcs11.slotIndex",
        )

    @AfterTest
    fun clearProperties() = allProperties.forEach { System.clearProperty(it) }

    private fun value(setting: Pkcs11Setting): String =
        assertIs<Pkcs11Value.Configured>(pkcs11(setting), "$setting should have read as configured").value

    private fun slotIndex(): Int = pkcs11(Pkcs11Setting.SlotIndex).orElse("").toIntOrNull() ?: 0

    @Test
    fun unsetSettingsAreATypedAbsence() {
        assertIs<Pkcs11Value.NotConfigured>(pkcs11(Pkcs11Setting.Module))
        assertIs<Pkcs11Value.NotConfigured>(pkcs11(Pkcs11Setting.Pin))
        assertIs<Pkcs11Value.NotConfigured>(pkcs11(Pkcs11Setting.SlotIndex))
        assertEquals(0, slotIndex(), "slot index defaults to the first initialized token")
    }

    @Test
    fun legacyTpm2NamesStillResolve() {
        System.setProperty("buffer.crypto.tpm2.pkcs11.module", "/usr/lib/libtpm2_pkcs11.so.1")
        System.setProperty("buffer.crypto.tpm2.pkcs11.pin", "legacy-pin")
        System.setProperty("buffer.crypto.tpm2.pkcs11.slotIndex", "3")
        assertEquals("/usr/lib/libtpm2_pkcs11.so.1", value(Pkcs11Setting.Module))
        assertEquals("legacy-pin", value(Pkcs11Setting.Pin))
        assertEquals(3, slotIndex())
    }

    @Test
    fun moduleNeutralNamesResolve() {
        System.setProperty("buffer.crypto.pkcs11.module", "/opt/homebrew/lib/opensc-pkcs11.so")
        System.setProperty("buffer.crypto.pkcs11.pin", "neutral-pin")
        System.setProperty("buffer.crypto.pkcs11.slotIndex", "2")
        assertEquals("/opt/homebrew/lib/opensc-pkcs11.so", value(Pkcs11Setting.Module))
        assertEquals("neutral-pin", value(Pkcs11Setting.Pin))
        assertEquals(2, slotIndex())
    }

    @Test
    fun moduleNeutralNameWinsWhenBothAreSet() {
        System.setProperty("buffer.crypto.tpm2.pkcs11.module", "/usr/lib/libtpm2_pkcs11.so.1")
        System.setProperty("buffer.crypto.tpm2.pkcs11.pin", "legacy-pin")
        System.setProperty("buffer.crypto.pkcs11.module", "/opt/homebrew/lib/opensc-pkcs11.so")
        System.setProperty("buffer.crypto.pkcs11.pin", "neutral-pin")
        assertEquals("/opt/homebrew/lib/opensc-pkcs11.so", value(Pkcs11Setting.Module))
        assertEquals("neutral-pin", value(Pkcs11Setting.Pin))
    }

    @Test
    fun blankNeutralNameFallsThroughToLegacy() {
        System.setProperty("buffer.crypto.pkcs11.pin", "   ")
        System.setProperty("buffer.crypto.tpm2.pkcs11.pin", "legacy-pin")
        assertEquals(
            "legacy-pin",
            value(Pkcs11Setting.Pin),
            "a blank value is absent, so it must not shadow a real legacy setting",
        )
    }

    @Test
    fun unparseableSlotIndexFallsBackToZero() {
        System.setProperty("buffer.crypto.pkcs11.slotIndex", "not-a-number")
        assertEquals(0, slotIndex())
    }
}
