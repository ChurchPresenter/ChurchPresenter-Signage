package org.churchpresenter.signage.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How Chromium is started: with the GPU on ARM until it fails there, its heap and memory sized to the machine. */
@OptIn(ExperimentalPathApi::class)
class ChromiumArgsTest {
    private val dir = createTempDirectory("chromium")

    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    @Test
    fun theGpuDrawsOnArmAndSoftwareElsewhere() {
        val gpu = Chromium.args(dir, 1920, 1080, gpu = true, heapMb = 512)
        assertTrue("--use-gl=angle" in gpu && "--enable-gpu-rasterization" in gpu && "--disable-gpu" !in gpu)
        assertTrue("--js-flags=--max-old-space-size=512" in gpu)
        assertEquals("about:blank", gpu.last())
        val software = Chromium.args(dir, 1920, 1080)
        assertTrue("--disable-gpu" in software && "--use-gl=angle" !in software)
    }

    @Test
    fun aGpuThatFailsOnceIsOffForGood() {
        HttpClient(CIO).use { client ->
            val marker = dir.resolve(Chromium.GPU_OFF_MARKER)
            val arm = Chromium("chromium", dir.resolve("profile"), client, marker, arm = true, totalMemoryKb = 1)
            assertTrue(arm.gpu)
            arm.useSoftware()
            assertTrue(marker.exists())
            assertFalse(arm.gpu)
            assertFalse(Chromium("chromium", dir.resolve("profile"), client, marker, arm = false).gpu)
            assertNull(arm.memoryKb())
        }
    }

    @Test
    fun chromiumMayUseMostOfALargeMachineAndLittleOfASmallOne() {
        assertEquals(716_800L, Chromium.memoryLimitKb(900_000))
        assertEquals(1_228_800L, Chromium.memoryLimitKb(1_900_000))
        assertEquals(2_621_440L, Chromium.memoryLimitKb(3_900_000))
        assertEquals(4_194_304L, Chromium.memoryLimitKb(7_900_000))
        assertEquals(6_291_456L, Chromium.memoryLimitKb(16_000_000))
        assertEquals(716_800L, Chromium.memoryLimitKb(null))
    }
}
