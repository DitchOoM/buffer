@file:JvmName("ValidateMultiReleaseJar")

package com.ditchoom.buffer

/**
 * Standalone validator for multi-release JAR.
 *
 * Run this with different JVM versions to verify the correct class is loaded:
 *
 *   # Build the JAR first
 *   ./gradlew :buffer:jvmJar
 *
 *   # Run with Java 8 (should use fallback)
 *   /path/to/java8/bin/java -cp buffer/build/libs/buffer-jvm-*.jar \
 *       com.ditchoom.buffer.ValidateMultiReleaseJar
 *
 *   # Run with Java 11+ (should use ByteBuffer.mismatch)
 *   /path/to/java11/bin/java -cp buffer/build/libs/buffer-jvm-*.jar \
 *       com.ditchoom.buffer.ValidateMultiReleaseJar
 */
fun main() {
    val javaVersion = System.getProperty("java.specification.version")
    val javaHome = System.getProperty("java.home")

    println("=== Multi-Release JAR Validation ===")
    println("Java version: $javaVersion")
    println("Java home: $javaHome")
    println()

    // Check which class file is being used
    val helperClass = BufferMismatchHelper::class.java
    val resourceName = helperClass.name.replace('.', '/') + ".class"
    val classUrl = helperClass.classLoader?.getResource(resourceName)

    println("BufferMismatchHelper loaded from:")
    println("  $classUrl")
    println()

    // Determine expected behavior
    val majorVersion = javaVersion?.substringBefore('.')?.toIntOrNull() ?: 8
    val expectedImpl =
        if (majorVersion >= 11) {
            "META-INF/versions/11 (ByteBuffer.mismatch)"
        } else {
            "root (Long comparisons fallback)"
        }

    println("Expected implementation: $expectedImpl")

    // Verify by checking URL
    val urlStr = classUrl?.toString() ?: ""
    val actualImpl =
        when {
            urlStr.contains("META-INF/versions/11") -> "META-INF/versions/11 (ByteBuffer.mismatch)"
            urlStr.contains(".jar!") -> "root (Long comparisons fallback)"
            else -> "build directory (not from JAR)"
        }

    println("Actual implementation: $actualImpl")
    println()

    // Quick functional test
    println("=== Functional Test ===")
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

    if (result == USE_DEFAULT_MISMATCH) {
        println("Result: USE_DEFAULT_MISMATCH (fallback to ReadBuffer.mismatch)")
        println("This is expected on Java 8")
    } else {
        println("Mismatch at position: $result (expected: 500)")
        println("Test ${if (result == 500) "PASSED" else "FAILED"}")
    }
}
