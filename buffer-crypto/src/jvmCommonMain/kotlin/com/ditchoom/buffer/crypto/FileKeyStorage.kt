package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toMutableNativeData
import com.ditchoom.buffer.toNativeData
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * A durable, on-disk [KeyStorage] for the JVM / Android software tier: one file per alias under a
 * private directory. Because an alias is validated to `[A-Za-z0-9._-]` it is used verbatim as the
 * filename (no path separators, so no traversal) with a `.key` suffix. Bytes move through direct
 * NIO [java.nio.ByteBuffer]s (never an intermediate `ByteArray`), matching the library's zero-copy
 * philosophy. A backend IO failure surfaces as [KeyStoreException.StorageFailure].
 *
 * The stored bytes are exportable private-key material — this is the [KeyCustody.ExportableSoftware]
 * tier by definition. The directory is created `rwx------` where the filesystem supports POSIX
 * permissions.
 */
internal class FileKeyStorage(
    name: String,
    location: String?,
) : KeyStorage {
    private val dir: Path =
        (location?.let { Path.of(it) } ?: defaultRoot().resolve(name)).also(::ensureDir)

    override suspend fun put(
        alias: String,
        pkcs8: ReadBuffer,
    ) {
        val tmp = dir.resolve("$alias$SUFFIX.tmp")
        val target = dir.resolve("$alias$SUFFIX")
        try {
            Files.newByteChannel(tmp, WRITE_OPTS, *ownerOnlyFileAttrs()).use { channel ->
                val src = pkcs8.toNativeData().byteBuffer
                while (src.hasRemaining()) channel.write(src)
            }
            // Atomic-ish replace so a concurrent read never sees a half-written file.
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tmp) }
            throw KeyStoreException.StorageFailure(retryable = true)
        }
    }

    override suspend fun get(alias: String): ReadBuffer? {
        val path = dir.resolve("$alias$SUFFIX")
        if (!Files.exists(path)) return null
        return try {
            val size = Files.size(path).toInt()
            val out = BufferFactory.Default.allocate(size)
            val dst = out.toMutableNativeData().byteBuffer
            Files.newByteChannel(path, StandardOpenOption.READ).use { channel ->
                while (dst.hasRemaining() && channel.read(dst) >= 0) {
                    // read until the buffer is full or EOF
                }
            }
            // Bytes were written straight into the backing ByteBuffer, which does not advance the
            // PlatformBuffer's own cursor — set it to the byte count so resetForRead() yields 0..size.
            out.position(size)
            out.resetForRead()
            out
        } catch (e: IOException) {
            throw KeyStoreException.StorageFailure(retryable = true)
        }
    }

    override suspend fun delete(alias: String): Boolean =
        try {
            Files.deleteIfExists(dir.resolve("$alias$SUFFIX"))
        } catch (e: IOException) {
            throw KeyStoreException.StorageFailure(retryable = false)
        }

    override suspend fun aliases(): Set<String> =
        try {
            Files.newDirectoryStream(dir, "*$SUFFIX").use { stream ->
                stream.mapTo(mutableSetOf()) { it.fileName.toString().removeSuffix(SUFFIX) }
            }
        } catch (e: IOException) {
            throw KeyStoreException.StorageFailure(retryable = true)
        }

    private fun ensureDir(path: Path) {
        try {
            if (!Files.isDirectory(path)) Files.createDirectories(path, *ownerOnlyDirAttrs())
        } catch (e: IOException) {
            throw KeyStoreException.StorageFailure(retryable = false)
        }
    }

    private companion object {
        const val SUFFIX = ".key"
        val WRITE_OPTS =
            setOf(
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )

        fun defaultRoot(): Path = Path.of(System.getProperty("user.home") ?: ".").resolve(".buffer-crypto")

        fun posixSupported(): Boolean =
            runCatching {
                java.nio.file.FileSystems
                    .getDefault()
                    .supportedFileAttributeViews()
                    .contains("posix")
            }.getOrDefault(false)

        fun ownerOnlyDirAttrs(): Array<FileAttribute<*>> =
            if (posixSupported()) {
                arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                emptyArray()
            }

        fun ownerOnlyFileAttrs(): Array<FileAttribute<*>> =
            if (posixSupported()) {
                arrayOf(
                    PosixFilePermissions.asFileAttribute(
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    ),
                )
            } else {
                emptyArray()
            }
    }
}
