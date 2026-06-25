package com.ditchoom.onebrc

// Node.js file access shared by JS and WASM. The `= js("…")` function-body form is the supported
// way to pass Kotlin params into JS in both Kotlin/JS and Kotlin/WASM, so one source covers both.

private var webTempCounter = 0

internal actual fun onebrcTempFile(tag: String): String {
    val id = webTempCounter++
    return nodeTmpDir() + "/onebrc-" + tag + "-" + id + ".txt"
}

internal actual fun onebrcWriteText(
    path: String,
    text: String,
) = nodeWriteFileUtf8(path, text)

internal actual fun onebrcDeleteFile(path: String) = nodeRemoveFile(path)

// Parameters below are consumed inside the js(...) bodies, which detekt cannot see (interop).
@Suppress("UnusedParameter")
internal fun nodeReadFileUtf8(path: String): String = js("require('fs').readFileSync(path, 'utf8')")

@Suppress("UnusedParameter")
internal fun nodeWriteFileUtf8(
    path: String,
    data: String,
): Unit = js("require('fs').writeFileSync(path, data)")

@Suppress("UnusedParameter")
internal fun nodeRemoveFile(path: String): Unit = js("require('fs').rmSync(path, { force: true })")

private fun nodeTmpDir(): String = js("require('os').tmpdir()")
