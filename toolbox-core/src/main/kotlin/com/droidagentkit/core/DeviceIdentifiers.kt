package com.droidagentkit.core

/**
 * Format checks for the two agent-supplied identifiers that reach a command line.
 *
 * Shell injection is already handled: every post-`shell` argument is single-quoted by [ShellQuote]
 * and commands are built as argv lists, never as strings. What that does not cover is *argument*
 * injection — a serial or package beginning with `-` lands in flag position for `adb -s <serial>`
 * or `run-as <pkg>` and is read as an option rather than a value. Constraining both to their real
 * grammars removes that, and rejects the malformed input that would otherwise produce a confusing
 * failure deeper in the stack.
 */
object DeviceIdentifiers {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    // adb serials cover USB serials, `host:port` for network devices, and `emulator-5554`.
    private val SERIAL = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")

    fun isValidPackageName(value: String): Boolean = PACKAGE.matches(value)

    fun isValidDeviceSerial(value: String): Boolean = SERIAL.matches(value)
}
