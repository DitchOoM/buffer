package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.useKsp2
import org.intellij.lang.annotations.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchCoalescingCodegenTest {
    @Test
    fun `four adjacent UByte fields coalesce into readInt`() {
        val (result, source) =
            compileAndReadCodec(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Quad(val a: UByte, val b: UByte, val c: UByte, val d: UByte)
                """.trimIndent(),
                "QuadCodec.kt",
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "compile failed:\n${result.messages}")
        assertTrue(source.contains("buffer.readInt()"), "expected bulk readInt() in:\n$source")
        assertFalse(
            source.contains("buffer.readUByte()"),
            "did not expect individual readUByte() calls in:\n$source",
        )
    }

    @Test
    fun `eight adjacent UByte fields coalesce into readLong`() {
        val (result, source) =
            compileAndReadCodec(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Octet(
                    val a: UByte, val b: UByte, val c: UByte, val d: UByte,
                    val e: UByte, val f: UByte, val g: UByte, val h: UByte,
                )
                """.trimIndent(),
                "OctetCodec.kt",
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "compile failed:\n${result.messages}")
        assertTrue(source.contains("buffer.readLong()"), "expected bulk readLong() in:\n$source")
        assertTrue(source.contains("buffer.writeLong("), "expected bulk writeLong() in:\n$source")
    }

    @Test
    fun `conditional field breaks the batch on either side`() {
        val (result, source) =
            compileAndReadCodec(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.When

                @ProtocolMessage
                data class Maybe(
                    val header: UByte,
                    val present: Boolean,
                    @When("present") val payload: UByte? = null,
                    val trailer: UByte,
                )
                """.trimIndent(),
                "MaybeCodec.kt",
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "compile failed:\n${result.messages}")
        // header/present/(conditional)/trailer — the conditional and the
        // trailing scalar can't merge across the conditional, so no
        // single bulk read covers all four. header+present can coalesce
        // (two adjacent natural-width UBytes/Boolean), but the trailer
        // stays as a standalone readUByte.
        assertTrue(source.contains("buffer.readUByte()"), "expected at least one standalone readUByte:\n$source")
    }

    @Test
    fun `value-class UByte fields coalesce`() {
        val (result, source) =
            compileAndReadCodec(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @JvmInline @ProtocolMessage
                value class Tag(val raw: UByte)

                @ProtocolMessage
                data class TaggedPair(val first: Tag, val second: Tag)
                """.trimIndent(),
                "TaggedPairCodec.kt",
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "compile failed:\n${result.messages}")
        assertTrue(source.contains("buffer.readShort()"), "expected bulk readShort() for value-class pair:\n$source")
    }

    @Test
    fun `explicit big wireOrder still batches (case 2)`() {
        // Regression guard for the fresh-eyes review's gate critique. The
        // pre-fix gate required Endianness.Default and silently skipped
        // every protocol with @ProtocolMessage(wireOrder = Big|Little) —
        // TCP/IP/TLS in real-world apps. After case 2, explicit Big still
        // bulk-reads and canonicalizes via swapBytes when needed.
        val (result, source) =
            compileAndReadCodec(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.Endianness
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage(wireOrder = Endianness.Big)
                data class BigQuad(val a: UByte, val b: UByte, val c: UByte, val d: UByte)
                """.trimIndent(),
                "BigQuadCodec.kt",
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "compile failed:\n${result.messages}")
        assertTrue(source.contains("buffer.readInt()"), "expected bulk readInt() for explicit Big batch:\n$source")
        assertTrue(source.contains("swapBytes"), "expected canonicalizing swapBytes call:\n$source")
        assertFalse(
            source.contains("buffer.readUByte()"),
            "did not expect individual readUByte() calls in batched Big path:\n$source",
        )
    }

    @Test
    fun `explicit little wireOrder still batches (case 2)`() {
        val (result, source) =
            compileAndReadCodec(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.Endianness
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage(wireOrder = Endianness.Little)
                data class LittleQuad(val a: UByte, val b: UByte, val c: UByte, val d: UByte)
                """.trimIndent(),
                "LittleQuadCodec.kt",
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "compile failed:\n${result.messages}")
        assertTrue(source.contains("buffer.readInt()"), "expected bulk readInt() for explicit Little batch:\n$source")
        assertTrue(source.contains("swapBytes"), "expected canonicalizing swapBytes call:\n$source")
        assertFalse(
            source.contains("buffer.readUByte()"),
            "did not expect individual readUByte() calls in batched Little path:\n$source",
        )
    }

    private fun compileAndReadCodec(
        @Language("kotlin") source: String,
        codecFileName: String,
    ): Pair<JvmCompilationResult, String> {
        val compilation =
            KotlinCompilation().apply {
                sources = listOf(SourceFile.kotlin("Test.kt", source))
                inheritClassPath = true
                messageOutputStream = System.out
                useKsp2()
                configureKsp {
                    symbolProcessorProviders += ProtocolMessageProcessorProvider()
                }
            }
        val result = compilation.compile()
        val generated =
            compilation
                .kspSourcesDir
                .walkTopDown()
                .firstOrNull { it.isFile && it.name == codecFileName }
                ?.readText() ?: ""
        return result to generated
    }
}
