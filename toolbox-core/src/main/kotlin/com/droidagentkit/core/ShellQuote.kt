package com.droidagentkit.core

/**
 * Quotes a string for safe inclusion in an `adb shell …` argv list.
 *
 * `adb shell arg1 arg2 …` re-joins every post-`shell` argument with spaces into a single string
 * that the device executes via `/system/bin/sh -c`, independent of how the host-side argv list was
 * built. Single-quoting each argument (with embedded quotes escaped) makes it always parse as one
 * literal shell word on the device, closing shell-metacharacter injection through agent-supplied
 * strings (package names, permission names, URIs, typed text, etc.).
 */
object ShellQuote {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
