@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.onebrc

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getpid
import platform.posix.remove

private var tempCounter = 0

internal actual fun onebrcTempFile(tag: String): String {
    val id = tempCounter++
    return "/tmp/onebrc-$tag-${getpid()}-$id.txt"
}

internal actual fun onebrcWriteText(
    path: String,
    text: String,
) {
    val file = fopen(path, "w") ?: error("fopen() failed for $path")
    fputs(text, file)
    fclose(file)
}

internal actual fun onebrcDeleteFile(path: String) {
    remove(path)
}
