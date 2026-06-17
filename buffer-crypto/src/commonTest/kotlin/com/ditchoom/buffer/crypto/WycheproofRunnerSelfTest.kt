package com.ditchoom.buffer.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the [Wycheproof] runner enforces each verdict correctly, using a synthetic
 * Wycheproof-shaped HMAC-SHA256 fixture (RFC 4231 Case 1) and the already-landed
 * [hmacSha256]. Real per-family vectors are vendored in each family's PR; this only
 * exercises the harness itself, so it stays independent of network/vendoring.
 */
class WycheproofRunnerSelfTest {
    // RFC 4231 Case 1: key = 0x0b×20, msg = "Hi There", tag = HMAC-SHA256.
    private val fixture =
        """
        {
          "algorithm": "HMACSHA256",
          "testGroups": [
            {
              "keySize": 160,
              "tagSize": 256,
              "key": "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b",
              "tests": [
                {
                  "tcId": 1, "comment": "valid tag", "result": "valid", "flags": [],
                  "msg": "4869205468657265",
                  "tag": "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
                },
                {
                  "tcId": 2, "comment": "tampered tag", "result": "invalid", "flags": ["ModifiedTag"],
                  "msg": "4869205468657265",
                  "tag": "0000000000000000000000000000000000000000000000000000000000000000"
                },
                {
                  "tcId": 3, "comment": "truncated 128-bit tag", "result": "acceptable", "flags": ["TruncatedTag"],
                  "msg": "4869205468657265",
                  "tag": "b0344c61d8db38535ca8afceaf0bf12b"
                }
              ]
            }
          ]
        }
        """.trimIndent()

    @Test
    fun runnerEnforcesVerdicts() {
        val summary =
            Wycheproof.run(fixture) { case ->
                val computed = hmacSha256(case.groupHex("key"), case.testHex("msg"))
                // Constant-time compare (dogfoods the security util); length mismatch ⇒ false.
                computed.constantTimeEquals(case.testHex("tag"))
            }
        // The runner asserted valid→accept and invalid→reject internally (no fail()).
        // We only mismatch-length the truncated "acceptable" case, so it is recorded as rejected.
        assertEquals(3, summary.total)
        assertEquals(0, summary.acceptableAccepted)
        assertEquals(1, summary.acceptableRejected)
    }
}
