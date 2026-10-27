package org.churchpresenter.signage.agent

import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The machine's memory, uptime and load, as a player reports them with each status. */
@OptIn(ExperimentalPathApi::class)
class SystemHealthTest {
    private val dir = createTempDirectory("health")

    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    @Test
    fun memoryAndUptimeAreReadFromLinux() {
        val meminfo = dir.resolve("meminfo")
        assertNull(Memory.read(meminfo))
        meminfo.writeText("MemTotal:        4000000 kB\nMemFree:          100000 kB\nMemAvailable:    1000000 kB\n")
        assertEquals(Memory(4_000_000, 1_000_000), Memory.read(meminfo))
        assertEquals(75, Memory(4_000_000, 1_000_000).usedPercent)
        meminfo.writeText("MemTotal: 2000 kB\nMemFree: 500 kB\n")
        assertEquals(Memory(2000, 500), Memory.read(meminfo))
        val uptime = dir.resolve("uptime")
        assertNull(uptimeSeconds(uptime))
        uptime.writeText("90061.52 300000.10\n")
        assertEquals(90_061L, uptimeSeconds(uptime))
    }

    @Test
    fun eachReadReportsTheLoadSinceTheOneBefore() {
        val stat = dir.resolve("stat")
        stat.writeText("cpu  100 0 0 100 0 0 0 0 0 0\n")
        val reader =
            HealthReader(CpuLoad(stat), memory = { Memory(1000, 400) }, temperature = { 48.0 }, uptime = { 60 })
        // The first read has nothing to compare with.
        assertEquals(Health(null, 60, 48.0, 60), reader.read())
        stat.writeText("cpu  130 0 0 170 0 0 0 0 0 0\n")
        assertEquals(30, reader.read().cpuPercent)
        assertEquals(Health(), HealthReader(CpuLoad(dir.resolve("none")), { null }, { null }, { null }).read())
    }
}
