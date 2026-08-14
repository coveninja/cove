package com.coveninja.cove.backend.updater

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdateRepositoryTest {
    @Test
    fun `portable helper launches directly with one argument per option`() {
        val process = windowsUpdaterProcess(
            portable = true,
            payload = Path.of("C:/Cove data/update.exe"),
            installDirectory = Path.of("C:/Cove portable"),
            processId = 42,
            version = "1.2.3",
        )

        assertEquals("C:/Cove data/update.exe", process.command()[0])
        assertTrue(process.command().contains("/TARGET=C:/Cove portable"))
        assertEquals("/PID=42", process.command()[3])
    }

    @Test
    fun `installed helper uses UAC without interpolating paths into script`() {
        val process = windowsUpdaterProcess(
            portable = false,
            payload = Path.of("C:/Cove data/update.exe"),
            installDirectory = Path.of("C:/Program Files/Cove"),
            processId = 84,
            version = "1.2.3",
        )

        assertEquals("powershell.exe", process.command()[0])
        val script = process.command().last()
        assertTrue(script.contains("-Verb RunAs"))
        assertTrue(script.contains("-Wait"))
        assertFalse(script.contains("C:/Program Files/Cove"))
        assertEquals("C:/Cove data/update.exe", process.environment()["COVE_UPDATE_HELPER"])
        assertEquals("C:/Program Files/Cove", process.environment()["COVE_UPDATE_TARGET"])
        assertEquals("C:/Program Files/Cove/Cove.exe", process.environment()["COVE_UPDATE_APP"])
    }
}
