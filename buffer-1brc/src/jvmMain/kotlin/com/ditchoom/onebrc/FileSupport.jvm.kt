package com.ditchoom.onebrc

import java.io.File

internal actual fun onebrcTempFile(tag: String): String =
    File
        .createTempFile("onebrc-$tag-", ".txt")
        .also {
            it.deleteOnExit()
        }.absolutePath

internal actual fun onebrcWriteText(
    path: String,
    text: String,
) {
    File(path).writeText(text, Charsets.UTF_8)
}

internal actual fun onebrcDeleteFile(path: String) {
    File(path).delete()
}
