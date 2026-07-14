@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed file (PosixFileKeyStorage.linux.kt)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.access
import platform.posix.chmod
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.remove
import platform.posix.rename

/**
 * A durable, on-disk [KeyStorage] for the Linux software tier: one file per alias under a private
 * directory, using POSIX stdio (no ByteArray round-trip — bytes move through the buffer's native
 * pointer via [withRemainingBytes] / [withWritablePointer]). Because an alias is validated to
 * `[A-Za-z0-9._-]` it is used verbatim as the filename (no path separators, so no traversal) with a
 * `.key` suffix. A backend IO failure surfaces as [KeyStoreException.StorageFailure].
 *
 * The stored bytes are exportable private-key material — the [KeyCustody.ExportableSoftware] tier by
 * definition. The directory is created `0700` and each file `0600`.
 */
internal class PosixFileKeyStorage(
    name: String,
    location: String?,
) : KeyStorage {
    private val dir: String = (location ?: "${homeDir()}/$ROOT_DIR/$name").trimEnd('/').also(::mkdirs)

    override suspend fun put(
        alias: String,
        pkcs8: ReadBuffer,
    ) {
        mkdirs(dir)
        val tmp = "$dir/$alias$SUFFIX.tmp"
        val target = "$dir/$alias$SUFFIX"
        val file = fopen(tmp, "wb") ?: throw KeyStoreException.StorageFailure(retryable = true)
        try {
            val n = pkcs8.remaining()
            if (n > 0) {
                pkcs8.withRemainingBytes { ptr, len ->
                    val written = fwrite(ptr, 1.convert(), len.convert(), file).toInt()
                    if (written != len) throw KeyStoreException.StorageFailure(retryable = true)
                }
            }
        } finally {
            fclose(file)
        }
        chmod(tmp, OWNER_RW.convert())
        // Atomic-ish replace so a concurrent read never sees a half-written file.
        if (rename(tmp, target) != 0) {
            remove(tmp)
            throw KeyStoreException.StorageFailure(retryable = true)
        }
    }

    override suspend fun get(alias: String): ReadBuffer? {
        val path = "$dir/$alias$SUFFIX"
        if (access(path, F_OK) != 0) return null
        val file = fopen(path, "rb") ?: throw KeyStoreException.StorageFailure(retryable = true)
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            fseek(file, 0, SEEK_SET)
            val out = BufferFactory.Default.allocate(size)
            if (size > 0) {
                out.withWritablePointer(size) { ptr ->
                    val read = fread(ptr, 1.convert(), size.convert(), file).toInt()
                    if (read != size) throw KeyStoreException.StorageFailure(retryable = true)
                }
            }
            out.resetForRead()
            return out
        } finally {
            fclose(file)
        }
    }

    override suspend fun delete(alias: String): Boolean {
        val path = "$dir/$alias$SUFFIX"
        if (access(path, F_OK) != 0) return false
        if (remove(path) != 0) throw KeyStoreException.StorageFailure(retryable = false)
        return true
    }

    override suspend fun aliases(): Set<String> {
        val dirp = opendir(dir) ?: return emptySet()
        val result = mutableSetOf<String>()
        try {
            while (true) {
                val entry = readdir(dirp) ?: break
                val fileName = entry.pointed.d_name.toKString()
                if (fileName.endsWith(SUFFIX)) result += fileName.removeSuffix(SUFFIX)
            }
        } finally {
            closedir(dirp)
        }
        return result
    }

    /** Creates [path] and any missing parents `0700`; an already-existing component is not an error. */
    private fun mkdirs(path: String) {
        val absolute = path.startsWith("/")
        var prefix = ""
        for (component in path.split('/')) {
            if (component.isEmpty()) continue
            prefix = if (prefix.isEmpty() && !absolute) component else "$prefix/$component"
            // Ignore the return code: EEXIST is expected, and a genuine failure surfaces on fopen.
            mkdir(prefix, OWNER_RWX.convert())
        }
    }

    private companion object {
        const val SUFFIX = ".key"
        const val ROOT_DIR = ".buffer-crypto"
        const val OWNER_RWX = 448 // 0700
        const val OWNER_RW = 384 // 0600

        fun homeDir(): String = getenv("HOME")?.toKString()?.ifEmpty { null } ?: "."
    }
}
