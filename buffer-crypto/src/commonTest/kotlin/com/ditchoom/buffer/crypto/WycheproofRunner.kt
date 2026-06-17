package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.fail

/**
 * One Wycheproof test case, exposed algorithm-agnostically. Group-level params (key, iv, curve,
 * public key, …) and per-test fields are read by name; hex material is materialized through the
 * buffer API ([hexBuffer]) so the "no ByteArray in tests" rule holds.
 */
class WycheproofCase internal constructor(
    val tcId: Int,
    val comment: String,
    val flags: List<String>,
    /** `"valid"`, `"invalid"`, or `"acceptable"`. */
    val result: String,
    private val group: JsonObject,
    private val test: JsonObject,
) {
    private fun field(
        scope: JsonObject,
        name: String,
    ): String = scope[name]?.jsonPrimitive?.content ?: error("tcId $tcId: missing field '$name'")

    /** Group-level hex field (e.g. `"key"`, `"iv"`) as a read-ready buffer. */
    fun groupHex(name: String): ReadBuffer = hexBuffer(field(group, name))

    /** Per-test hex field (e.g. `"msg"`, `"ct"`, `"tag"`, `"sig"`, `"shared"`) as a read-ready buffer. */
    fun testHex(name: String): ReadBuffer = hexBuffer(field(test, name))

    /** Per-test hex field, or `null` if absent (for optional fields like `"aad"`). */
    fun testHexOrNull(name: String): ReadBuffer? = test[name]?.jsonPrimitive?.content?.let(::hexBuffer)

    /** Group-level integer field (e.g. `"keySize"`, `"tagSize"`). */
    fun groupInt(name: String): Int = group[name]?.jsonPrimitive?.int ?: error("tcId $tcId: missing int '$name'")
}

/**
 * Drives a vendored Wycheproof `testvectors_v1` file (embedded as a JSON string) through the
 * implementation under test, enforcing the spec verdict for every case.
 *
 * Verdict contract:
 *  - `result == "valid"` ⇒ the op MUST accept the input.
 *  - `result == "invalid"` ⇒ the op MUST reject it (return `false`, or throw a [CryptoException]
 *    / [IllegalArgumentException] — both count as rejection).
 *  - `result == "acceptable"` ⇒ either decision is spec-defensible; the outcome is **recorded,
 *    not asserted** here. Callers that want a stricter policy assert that decision separately
 *    (e.g. require low-S signatures to be rejected).
 *
 * [accepts] performs the operation under test and returns whether the implementation accepted
 * the input (tag/signature verified, ciphertext decrypted to the expected plaintext).
 */
object Wycheproof {
    private val json = Json { ignoreUnknownKeys = true }

    /** Outcome tally for `acceptable` cases, surfaced so a behavior change is at least visible. */
    data class Summary(
        val total: Int,
        val acceptableAccepted: Int,
        val acceptableRejected: Int,
    )

    fun run(
        vectorJson: String,
        accepts: (WycheproofCase) -> Boolean,
    ): Summary =
        runInternal(vectorJson) { case ->
            try {
                accepts(case)
            } catch (_: CryptoException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }

    /**
     * Suspending counterpart of [run], for primitives whose only cross-platform surface is a
     * suspend function (e.g. AES-GCM via WebCrypto on the web). Same verdict contract.
     */
    suspend fun runSuspending(
        vectorJson: String,
        accepts: suspend (WycheproofCase) -> Boolean,
    ): Summary {
        val file = json.parseToJsonElement(vectorJson).jsonObject
        val groups = file["testGroups"]?.jsonArray ?: error("no testGroups in vector file")
        var total = 0
        var acceptableAccepted = 0
        var acceptableRejected = 0
        for (groupEl in groups) {
            val group = groupEl.jsonObject
            val tests = group["tests"]?.jsonArray ?: continue
            for (testEl in tests) {
                val test = testEl.jsonObject
                val case = parseCase(group, test)
                total++
                val accepted =
                    try {
                        accepts(case)
                    } catch (_: CryptoException) {
                        false
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                assertVerdict(case, accepted)
                if (case.result == "acceptable") {
                    if (accepted) acceptableAccepted++ else acceptableRejected++
                }
            }
        }
        return Summary(total, acceptableAccepted, acceptableRejected)
    }

    private inline fun runInternal(
        vectorJson: String,
        accepts: (WycheproofCase) -> Boolean,
    ): Summary {
        val file = json.parseToJsonElement(vectorJson).jsonObject
        val groups = file["testGroups"]?.jsonArray ?: error("no testGroups in vector file")
        var total = 0
        var acceptableAccepted = 0
        var acceptableRejected = 0
        for (groupEl in groups) {
            val group = groupEl.jsonObject
            val tests = group["tests"]?.jsonArray ?: continue
            for (testEl in tests) {
                val case = parseCase(group, testEl.jsonObject)
                total++
                val accepted = accepts(case)
                assertVerdict(case, accepted)
                if (case.result == "acceptable") {
                    if (accepted) acceptableAccepted++ else acceptableRejected++
                }
            }
        }
        return Summary(total, acceptableAccepted, acceptableRejected)
    }

    private fun parseCase(
        group: JsonObject,
        test: JsonObject,
    ): WycheproofCase =
        WycheproofCase(
            tcId = test["tcId"]?.jsonPrimitive?.int ?: error("test without tcId"),
            comment = test["comment"]?.jsonPrimitive?.content ?: "",
            flags = test["flags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            result = test["result"]?.jsonPrimitive?.content ?: error("test without result"),
            group = group,
            test = test,
        )

    private fun assertVerdict(
        case: WycheproofCase,
        accepted: Boolean,
    ) {
        when (case.result) {
            "valid" ->
                if (!accepted) {
                    fail("tcId ${case.tcId} (${case.comment}): valid vector was REJECTED")
                }
            "invalid" ->
                if (accepted) {
                    fail("tcId ${case.tcId} (${case.comment}) flags=${case.flags}: invalid vector was ACCEPTED")
                }
            "acceptable" -> Unit
            else -> fail("tcId ${case.tcId}: unknown result '${case.result}'")
        }
    }
}
