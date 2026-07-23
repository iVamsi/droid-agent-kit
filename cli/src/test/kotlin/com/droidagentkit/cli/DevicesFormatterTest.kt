package com.droidagentkit.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicesFormatterTest {
    @Test
    fun `parses adb devices -l output into rows with key value details`() {
        val output =
            """
            List of devices attached
            emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64x86_64 transport_id:1
            1234567890abcdef        unauthorized transport_id:2
            """.trimIndent()

        val rows = parseAdbDevices(output)

        assertEquals(2, rows.size)
        assertEquals("emulator-5554", rows[0].serial)
        assertEquals("device", rows[0].state)
        assertEquals("sdk_gphone64_x86_64", rows[0].details["product"])
        assertEquals("emu64x86_64", rows[0].details["device"])
        assertEquals("1", rows[0].details["transport_id"])
        assertEquals("unauthorized", rows[1].state)
        assertEquals("2", rows[1].details["transport_id"])
    }

    @Test
    fun `markdown renders a table when devices are present`() {
        val output =
            """
            List of devices attached
            emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64x86_64 transport_id:1
            """.trimIndent()

        val markdown = renderDevicesMarkdown(output)

        assertTrue(markdown.contains("# Connected adb devices"))
        assertTrue(markdown.contains("| emulator-5554 | device | sdk_gphone64_x86_64"))
        assertTrue(markdown.contains("| Serial | State | Product | Model | Device | Transport |"))
    }

    @Test
    fun `markdown renders no-devices placeholder when adb reports nothing`() {
        val output = "List of devices attached\n"

        val markdown = renderDevicesMarkdown(output)

        assertTrue(markdown.contains("_(no devices)_"))
    }

    @Test
    fun `parser skips daemon banner lines and blank lines`() {
        val output =
            """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached

            emulator-5554          device transport_id:1
            """.trimIndent()

        val rows = parseAdbDevices(output)

        assertEquals(1, rows.size)
        assertEquals("emulator-5554", rows[0].serial)
    }
}
