package com.ditchoom.onebrc

// Small cross-platform file helpers shared by the showcase's tests and benchmark, so the same
// test/benchmark code runs on every platform. Internal — not part of the library surface.

/** Returns a unique, writable temp file path for the given [tag]. */
internal expect fun onebrcTempFile(tag: String): String

/** Writes [text] to [path] as UTF-8 (used to lay down exact test fixtures). */
internal expect fun onebrcWriteText(
    path: String,
    text: String,
)

/** Deletes the file at [path] if it exists. */
internal expect fun onebrcDeleteFile(path: String)
