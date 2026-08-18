package com.droidagentkit.mcp

import org.junit.Assume

/**
 * Skips a test that can only work on a POSIX filesystem.
 *
 * Every caller is about to mark a shell-script fake executable, which `setPosixFilePermissions`
 * cannot do on Windows. The fakes are POSIX scripts on purpose: several re-evaluate joined argv the
 * way a device's `/system/bin/sh` does, which is what makes shell-injection regressions testable.
 * A batch rewrite would look equivalent and test something weaker.
 *
 * The assumption message names the dependency so a Windows run reports what it skipped.
 */
internal fun assumePosixFilesystem() {
    Assume.assumeTrue(
        "requires a POSIX filesystem to mark the fake adb/gradle scripts executable",
        !System.getProperty("os.name").startsWith("Windows"),
    )
}
