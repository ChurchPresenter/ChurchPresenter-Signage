package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.Platform
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentConfigTest {
    @Test
    fun readsEveryFlag() {
        val config = AgentConfig.parse(
            listOf(
                "--data", "/srv/signage", "--kiosk-port", "9000", "--address", "10.0.0.5:8765",
                "--no-cec", "--screen", "3840x2160", "--browser-restart", "systemctl restart signage-kiosk",
            ),
            Platform.LINUX_ARM64,
        )
        assertEquals(Path("/srv/signage"), config.dataDir)
        assertEquals(9000, config.kioskPort)
        assertEquals("10.0.0.5:8765", config.address)
        assertEquals(false, config.cec)
        assertEquals(3840 to 2160, config.screenWidth to config.screenHeight)
        assertEquals(listOf("systemctl", "restart", "signage-kiosk"), config.browserRestartCommand)
    }

    @Test
    fun defaultsSuitTheService() {
        val config = AgentConfig.parse(emptyList(), Platform.LINUX_X64)
        assertEquals(Path("/var/lib/churchpresenter-signage"), config.dataDir)
        assertEquals(AgentConfig.DEFAULT_KIOSK_PORT, config.kioskPort)
        assertEquals(true, config.cec)
        assertEquals(null, AgentConfig.parse(listOf("--address", " "), Platform.LINUX_X64).address)
    }

    @Test
    fun refusesWhatItDoesNotKnow() {
        assertFailsWith<IllegalArgumentException> { AgentConfig.parse(listOf("--bogus"), Platform.LINUX_X64) }
        assertFailsWith<IllegalArgumentException> { AgentConfig.parse(listOf("--data"), Platform.LINUX_X64) }
        assertFailsWith<IllegalArgumentException> { AgentConfig.parse(listOf("--screen", "big"), Platform.LINUX_X64) }
    }

    @Test
    fun knowsWhichPlatformItIsOn() {
        assertEquals(Platform.WINDOWS_X64, AgentConfig.currentPlatform("Windows 11", "amd64"))
        assertEquals(Platform.LINUX_ARM64, AgentConfig.currentPlatform("Linux", "aarch64"))
        assertEquals(Platform.LINUX_X64, AgentConfig.currentPlatform("Linux", "amd64"))
    }
}
