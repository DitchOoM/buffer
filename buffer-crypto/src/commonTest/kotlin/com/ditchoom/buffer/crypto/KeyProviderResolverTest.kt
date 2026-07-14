package com.ditchoom.buffer.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The commonMain custody resolver: [CryptoCapabilities.keyProvider] is total (never null, always at
 * least the software floor), routing is per-algorithm, and [KeyProvider.requireTier] enforces a
 * minimum custody with a structured throw. Routing *with* a stronger tier is driven through
 * [FakeHardware] (commonTest sees the module internals), since no platform ships a non-software
 * provider in a host test run.
 */
class KeyProviderResolverTest {
    @Test
    fun keyProviderIsTotalAndSoftwareFloored() =
        runTest {
            val keys = CryptoCapabilities.keyProvider()
            // Total: it serves every algorithm, resolving each to at least the exportable-software floor.
            for (alg in ProtectedKeyAlgorithm.entries) {
                assertTrue(keys.eligible(alg), "the resolver serves $alg (routing to the floor)")
                assertTrue(
                    keys.custodyFor(alg).tier >= CustodyTier.ExportableSoftware,
                    "$alg resolves to at least the software floor",
                )
            }
        }

    @Test
    fun softwareFloorMintsExportableKeys() =
        runTest {
            val floor = ResolvingKeyProvider(strong = null)
            assertEquals(CustodyTier.ExportableSoftware, floor.custodyFor(ProtectedKeyAlgorithm.AesGcm).tier)
            floor.generateAesGcm(ProtectedKeySpec()).use { aes ->
                assertEquals(KeyCustody.ExportableSoftware, aes.custody)
                assertTrue(aes.custody.exportable, "a software-floor key is exportable")
            }
        }

    @Test
    fun resolverRoutesEachAlgorithmToTheStrongestEligibleTier() =
        runTest {
            val resolver = ResolvingKeyProvider(strong = FakeHardware())
            // Eligible on the strong (hardware) provider → hardware custody.
            assertEquals(CustodyTier.Hardware, resolver.custodyFor(ProtectedKeyAlgorithm.EcdsaP256).tier)
            assertEquals(CustodyTier.Hardware, resolver.custodyFor(ProtectedKeyAlgorithm.AesGcm).tier)
            // Not eligible on the strong provider → falls back to the software floor, no throw.
            assertEquals(CustodyTier.ExportableSoftware, resolver.custodyFor(ProtectedKeyAlgorithm.EcdsaP521).tier)
            assertEquals(CustodyTier.ExportableSoftware, resolver.custodyFor(ProtectedKeyAlgorithm.X25519).tier)
            // A routed AES generation actually produces a hardware key (AES keygen exists on every platform).
            resolver.generateAesGcm(ProtectedKeySpec()).use { aes ->
                assertTrue(aes.custody is KeyCustody.NonExportable.Hardware, "AES routed to the secure element")
            }
        }

    @Test
    fun requireTierPassesWhenMetAndIsChainable() {
        val floor = ResolvingKeyProvider(strong = null)
        // The software floor meets ExportableSoftware; requireTier returns the same provider to chain.
        val chained = floor.requireTier(ProtectedKeyAlgorithm.EcdsaP256, CustodyTier.ExportableSoftware)
        assertSame(floor, chained)
    }

    @Test
    fun requireTierThrowsStructuredInsufficientCustody() {
        val floor = ResolvingKeyProvider(strong = null)
        val ex =
            assertFailsWith<InsufficientKeyCustody> {
                floor.requireTier(ProtectedKeyAlgorithm.EcdsaP256, CustodyTier.Hardware)
            }
        // The error names only public tiers — non-secret, branchable.
        assertEquals(ProtectedKeyAlgorithm.EcdsaP256, ex.alg)
        assertEquals(CustodyTier.Hardware, ex.required)
        assertEquals(CustodyTier.ExportableSoftware, ex.available)
    }

    @Test
    fun requireTierIsPerAlgorithmAgainstAStrongProvider() {
        val resolver = ResolvingKeyProvider(strong = FakeHardware())
        // Hardware-eligible alg meets the Hardware tier; a not-eligible alg only reaches the floor.
        resolver.requireTier(ProtectedKeyAlgorithm.EcdsaP256, CustodyTier.Hardware)
        assertFailsWith<InsufficientKeyCustody> {
            resolver.requireTier(ProtectedKeyAlgorithm.EcdsaP521, CustodyTier.Hardware)
        }
    }
}
