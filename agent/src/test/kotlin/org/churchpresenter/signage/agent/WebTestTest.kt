package org.churchpresenter.signage.agent

import kotlinx.coroutines.runBlocking
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.ServerMessage
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web page test: what it measures for each frame rate, and where it reads the processor. */
@OptIn(ExperimentalPathApi::class)
class WebTestTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second
    private val dir = createTempDirectory("web-test")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        dir.deleteRecursively()
    }

    @Test
    fun eachRateIsMeasuredWhileTheTestPageIsOnScreenAndTheScreenIsGivenBack() = runBlocking {
        val engine = PlayerEngine(store, FakeCec())
        var frames = 0L
        val shown = mutableListOf<ScreenItem?>()
        val stat = dir.resolve("stat")
        stat.writeText("cpu  100 0 100 800 0 0 0 0 0 0\n")
        var temperature = 50.0
        // Each second: 12 frames reached the screen, the processor was busy half the time, it warmed by a degree.
        val test = WebTest(
            engine = engine,
            framesSent = { frames },
            cpu = CpuLoad(stat),
            temperature = { temperature },
            pause = { ms ->
                shown += engine.testing
                if (ms == 1_000L) {
                    frames += 12
                    temperature += 1
                    val total = stat.toFile().readText().trim().split(Regex("\\s+")).drop(1).map(String::toLong)
                    stat.writeText("cpu  ${total[0] + 50} 0 ${total[2]} ${total[3] + 50} 0 0 0 0 0 0\n")
                }
            },
            settleMs = 5_000,
        )
        val result = test.run(ServerMessage.WebTest(fps = listOf(15, 30), secondsEach = 4))
        assertEquals(listOf(15, 30), result.runs.map { it.fps })
        assertEquals(listOf(12.0, 12.0), result.runs.map { it.shownFps })
        assertEquals(listOf(50.0, 50.0), result.runs.map { it.cpuPercent })
        assertEquals(54.0, result.runs[0].maxTempC)
        // The test page was on screen throughout, at each rate in turn, and the screen is given back after.
        assertEquals(listOf(15, 30), shown.mapNotNull { it?.maxFps }.distinct())
        assertTrue(shown.all { it?.kind == MediaKind.WEB && it.url == WebTest.PAGE_URL })
        assertNull(engine.testing)
        assertTrue(WebTest.PAGE_URL.startsWith("data:text/html;base64,"))
    }

    @Test
    fun theProcessorIsReadFromLinuxAndNotGuessedElsewhere() {
        val stat = dir.resolve("stat")
        assertNull(CpuLoad(stat).sample())
        stat.writeText("cpu  10 0 10 80 0 0 0 0 0 0\ncpu0 1 2 3 4\n")
        val before = CpuLoad(stat).sample()!!
        assertEquals(CpuLoad.Sample(busy = 20, total = 100), before)
        stat.writeText("cpu  70 0 10 120 0 0 0 0 0 0\n")
        assertEquals(60.0, CpuLoad(stat).percentSince(before))
        assertNull(CpuLoad(stat).percentSince(CpuLoad.Sample(busy = 80, total = 200)))
    }

    @Test
    fun theTemperatureIsReadInDegrees() {
        val zone = dir.resolve("temp")
        assertNull(processorTemperature(zone))
        zone.writeText("61250\n")
        assertEquals(61.25, processorTemperature(zone))
        zone.writeText("hot")
        assertNull(processorTemperature(zone))
    }
}
