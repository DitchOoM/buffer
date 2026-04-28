package com.ditchoom.buffer.codec.processor.discovery

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Per-RawSymbol-kind unit tests for PhaseA Discovery.
 *
 * Each test compiles a tiny source through KSP, captures the [DiscoveryResult] via
 * the harness, and asserts both that the right `RawSymbol` variant lands in the
 * output and that the diagnostics list matches the expected error/warning surface.
 */
class DiscoveryTest {
    @Test
    fun `data class with primary ctor is discovered as DataLike`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data class FixedHeader(val raw: UByte, val flags: Int)
            """,
            )
        val result = runDiscovery(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(result.discovery.diagnostics.isEmpty(), "unexpected diagnostics: ${result.discovery.diagnostics}")
        val symbol = result.discovery.symbols.singleOrNull() ?: fail("expected one symbol, got ${result.discovery.symbols}")
        val data =
            symbol as? RawSymbol.DataLike
                ?: fail("expected DataLike, got ${symbol::class.simpleName}")
        assertEquals("test.FixedHeader", data.fqn)
        assertEquals("FixedHeader", data.simpleName)
        assertEquals("test", data.packageName)
        assertEquals(listOf("FixedHeader"), data.enclosingNames)
        assertEquals(DataLikeKind.DataClass, data.classKind)
        assertEquals(RawDirection.Default, data.direction)
        assertEquals(2, data.constructorParameters.size)
        assertEquals("raw", data.constructorParameters[0].name)
        assertEquals("kotlin.UByte", data.constructorParameters[0].typeRef.fqn)
        assertEquals(false, data.constructorParameters[0].typeRef.isNullable)
        assertEquals("flags", data.constructorParameters[1].name)
        assertEquals("kotlin.Int", data.constructorParameters[1].typeRef.fqn)
        val protocolAnn =
            data.annotations.find { it.fqn == "com.ditchoom.buffer.codec.annotations.ProtocolMessage" }
                ?: fail("expected @ProtocolMessage on annotations: ${data.annotations}")
        assertNotNull(protocolAnn)
    }

    @Test
    fun `value class with primary ctor is discovered as DataLike with ValueClass kind`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @JvmInline
            @ProtocolMessage
            value class Header(val raw: UByte)
            """,
            )
        val result = runDiscovery(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val data = result.discovery.symbols.single() as RawSymbol.DataLike
        assertEquals(DataLikeKind.ValueClass, data.classKind)
        assertEquals(
            "kotlin.UByte",
            data.constructorParameters
                .single()
                .typeRef.fqn,
        )
    }

    @Test
    fun `sealed interface with subclasses is discovered as SealedRoot plus children`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.PacketType

            @ProtocolMessage
            sealed interface Cmd {
                @ProtocolMessage @PacketType(1) data class A(val x: Int) : Cmd
                @ProtocolMessage @PacketType(2) data object B : Cmd
            }
            """,
            )
        val result = runDiscovery(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(result.discovery.diagnostics.isEmpty(), "unexpected diagnostics: ${result.discovery.diagnostics}")
        val byFqn = result.discovery.symbols.associateBy { it.fqn }
        val root =
            byFqn["test.Cmd"] as? RawSymbol.SealedRoot
                ?: fail("expected SealedRoot for test.Cmd, got ${byFqn["test.Cmd"]}")
        assertEquals(setOf("test.Cmd.A", "test.Cmd.B"), root.subclassFqns.toSet())
        val a =
            byFqn["test.Cmd.A"] as? RawSymbol.DataLike
                ?: fail("expected DataLike for A, got ${byFqn["test.Cmd.A"]}")
        assertEquals(listOf("Cmd", "A"), a.enclosingNames)
        assertEquals(DataLikeKind.DataClass, a.classKind)
        val packetType =
            a.annotations.find { it.fqn == "com.ditchoom.buffer.codec.annotations.PacketType" }
                ?: fail("expected @PacketType on A: ${a.annotations}")
        assertEquals(RawAnnotationValue.IntVal(1), packetType.arguments["wire"])
        val b =
            byFqn["test.Cmd.B"] as? RawSymbol.ObjectSymbol
                ?: fail("expected ObjectSymbol for B, got ${byFqn["test.Cmd.B"]}")
        assertEquals("B", b.simpleName)
    }

    @Test
    fun `object without generics is discovered as ObjectSymbol`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data object PingResponse
            """,
            )
        val result = runDiscovery(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(result.discovery.diagnostics.isEmpty(), "unexpected diagnostics: ${result.discovery.diagnostics}")
        val symbol =
            result.discovery.symbols.singleOrNull() ?: fail("expected one symbol, got ${result.discovery.symbols}")
        val obj =
            symbol as? RawSymbol.ObjectSymbol
                ?: fail("expected ObjectSymbol, got ${symbol::class.simpleName}")
        assertEquals("test.PingResponse", obj.fqn)
        assertEquals("PingResponse", obj.simpleName)
        assertEquals(RawDirection.Default, obj.direction)
    }

    @Test
    fun `sealed interface without subclasses produces a discovery error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            sealed interface Empty
            """,
            )
        val result = runDiscovery(source)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.discovery.symbols.none { it.fqn == "test.Empty" },
            "rejected sealed root should not appear in symbols: ${result.discovery.symbols}",
        )
        val diag =
            result.discovery.diagnostics.singleOrNull { it.sourceFqn == "test.Empty" }
                ?: fail("expected one diagnostic for test.Empty, got ${result.discovery.diagnostics}")
        assertEquals(DiscoveryDiagnostic.Severity.Error, diag.severity)
        assertTrue(
            diag.message.contains("has no subclasses"),
            "diagnostic message should explain the failure: ${diag.message}",
        )
    }
}
