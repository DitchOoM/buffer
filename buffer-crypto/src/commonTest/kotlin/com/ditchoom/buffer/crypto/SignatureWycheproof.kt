package com.ditchoom.buffer.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.fail

/**
 * Drives a curated Wycheproof signature-verify vector file (group-aware: each group carries its own
 * `publicKeyHex`) through the verify implementation, enforcing the spec verdict for every case.
 *
 *  - `valid`   ⇒ verify MUST accept.
 *  - `invalid` ⇒ verify MUST reject (return `false`, or throw — both count as rejection).
 *  - `acceptable` ⇒ neither asserted, but the outcome is tallied so a behavior change is visible.
 *
 * [verifyHex] runs the verify under test for one case `(publicKeyHex, msgHex, sigHex)` and returns
 * whether the signature was accepted. It is `suspend` so the same suite runs over the WebCrypto
 * async path on JS/WASM and the native sync path elsewhere.
 */
object SignatureWycheproof {
    private val json = Json { ignoreUnknownKeys = true }

    data class Summary(
        val total: Int,
        val acceptableAccepted: Int,
        val acceptableRejected: Int,
    )

    suspend fun run(
        vectorJson: String,
        verifyHex: suspend (publicKeyHex: String, msgHex: String, sigHex: String) -> Boolean,
    ): Summary {
        val file = json.parseToJsonElement(vectorJson).jsonObject
        val groups = file["testGroups"]?.jsonArray ?: error("no testGroups")
        var total = 0
        var accAccepted = 0
        var accRejected = 0
        for (groupEl in groups) {
            val group = groupEl.jsonObject
            val pkHex = group["publicKeyHex"]?.jsonPrimitive?.content ?: error("group missing publicKeyHex")
            val tests = group["tests"]?.jsonArray ?: continue
            for (testEl in tests) {
                val test = testEl.jsonObject
                val tcId = test["tcId"]?.jsonPrimitive?.int ?: error("test without tcId")
                val comment = test["comment"]?.jsonPrimitive?.content ?: ""
                val flags = test["flags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val result = test["result"]?.jsonPrimitive?.content ?: error("test without result")
                val msgHex = test["msg"]?.jsonPrimitive?.content ?: error("test without msg")
                val sigHex = test["sig"]?.jsonPrimitive?.content ?: error("test without sig")
                total++
                val accepted =
                    try {
                        verifyHex(pkHex, msgHex, sigHex)
                    } catch (_: CryptoException) {
                        false
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                when (result) {
                    "valid" ->
                        if (!accepted) fail("tcId $tcId ($comment): valid vector was REJECTED")
                    "invalid" ->
                        if (accepted) fail("tcId $tcId ($comment) flags=$flags: invalid vector was ACCEPTED")
                    "acceptable" -> if (accepted) accAccepted++ else accRejected++
                    else -> fail("tcId $tcId: unknown result '$result'")
                }
            }
        }
        return Summary(total, accAccepted, accRejected)
    }
}
