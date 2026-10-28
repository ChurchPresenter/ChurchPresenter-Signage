package org.churchpresenter.signage.agent

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeFalse
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Starting the root setup service: what systemctl is asked, and why it failed, from the end of the
 * service's own log. A stand-in systemctl, so Linux and macOS only.
 */
@OptIn(ExperimentalPathApi::class)
class SystemdSetupTest {
    private val root = createTempDirectory("signage-setup")
    private val log = root.resolve("setup.log")
    private val asked = root.resolve("asked")

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private fun systemctl(exit: Int): String {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "a shell script stands in for systemctl")
        val script = root.resolve("systemctl")
        script.writeText("#!/bin/sh\necho \"$@\" > '$asked'\nexit $exit\n")
        script.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))
        return script.toString()
    }

    @Test
    fun itStartsTheServiceAndWaitsWithoutAsking() = runTest {
        assertNull(SystemdSetup(systemctl(0), log).run())
        assertEquals("--no-ask-password start ${SystemdSetup.UNIT}", asked.readText().trim())
    }

    @Test
    fun aFailureSaysTheLastLinesOfTheServicesLog() = runTest {
        log.writeText(
            "==> Updating\n\nsha256sum: WARNING: 1 computed checksum did NOT match\n" +
                "install.sh does not match the release\n",
        )
        assertEquals(
            "sha256sum: WARNING: 1 computed checksum did NOT match install.sh does not match the release",
            SystemdSetup(systemctl(1), log).run(),
        )
    }

    @Test
    fun aFailureWithNoLogSaysSo() = runTest {
        assertEquals("the system update failed", SystemdSetup(systemctl(1), root.resolve("missing.log")).run())
    }
}
