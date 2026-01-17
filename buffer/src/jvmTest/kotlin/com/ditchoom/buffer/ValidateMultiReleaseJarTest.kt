package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Validates multi-release JAR functionality.
 *
 * For manual validation with different JVM versions, run:
 *   ./gradlew :buffer:jvmJar
 *   /path/to/java11/bin/java -cp buffer/build/libs/buffer-jvm-*.jar \
 *       -jar buffer/build/libs/buffer-jvm-*.jar
 */
class ValidateMultiReleaseJarTest {
    @Test
    fun `BufferMismatchHelper loads correct implementation`() {
        val javaVersion = System.getProperty("java.specification.version")
        val majorVersion = javaVersion?.substringBefore('.')?.toIntOrNull() ?: 8

        val helperClass = BufferMismatchHelper::class.java
        val resourceName = helperClass.name.replace('.', '/') + ".class"
        val classUrl = helperClass.classLoader?.getResource(resourceName)
        val urlStr = classUrl?.toString() ?: ""

        // Verify class loads successfully
        assertTrue(urlStr.isNotEmpty(), "BufferMismatchHelper class should be loadable")

        // On Java 11+, should use META-INF/versions/11 if running from JAR
        // When running from build directory, this check is skipped
        if (urlStr.contains(".jar!")) {
            if (majorVersion >= 11) {
                assertTrue(
                    urlStr.contains("META-INF/versions/11"),
                    "On Java $majorVersion, should load from META-INF/versions/11",
                )
            }
        }
    }

    @Test
    fun `DirectBufferAddressHelper loads correct implementation`() {
        val javaVersion = System.getProperty("java.specification.version")
        val majorVersion = javaVersion?.substringBefore('.')?.toIntOrNull() ?: 8

        val addressHelperClass = Class.forName("com.ditchoom.buffer.DirectBufferAddressHelperKt")
        val resourceName = addressHelperClass.name.replace('.', '/') + ".class"
        val classUrl = addressHelperClass.classLoader?.getResource(resourceName)
        val urlStr = classUrl?.toString() ?: ""

        // Verify class loads successfully
        assertTrue(urlStr.isNotEmpty(), "DirectBufferAddressHelperKt class should be loadable")

        // On Java 21+, should use META-INF/versions/21 if running from JAR
        if (urlStr.contains(".jar!")) {
            if (majorVersion >= 21) {
                assertTrue(
                    urlStr.contains("META-INF/versions/21"),
                    "On Java $majorVersion, should load from META-INF/versions/21",
                )
            }
        }
    }

    @Test
    fun `BufferMismatchHelper finds mismatch correctly`() {
        val buffer1 = java.nio.ByteBuffer.allocate(1000)
        val buffer2 = java.nio.ByteBuffer.allocate(1000)
        repeat(1000) {
            buffer1.put(it.toByte())
            buffer2.put(it.toByte())
        }
        buffer1.flip()
        buffer2.flip()

        // Modify one byte
        buffer2.put(500, 99.toByte())

        val result = BufferMismatchHelper.mismatch(buffer1, buffer2)

        if (result != USE_DEFAULT_MISMATCH) {
            // On Java 11+, ByteBuffer.mismatch is used
            assertEquals(500, result, "Mismatch should be at position 500")
        }
        // On Java 8, USE_DEFAULT_MISMATCH is returned and ReadBuffer.mismatch handles it
    }

    @Test
    fun `DirectBufferAddressHelper returns valid address`() {
        val directBuffer = java.nio.ByteBuffer.allocateDirect(64)
        val address = getDirectBufferAddress(directBuffer)

        assertNotEquals(0L, address, "Direct buffer should have a non-zero address")
        assertTrue(address > 0, "Direct buffer address should be positive")
    }
}
