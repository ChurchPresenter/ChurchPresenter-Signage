package org.churchpresenter.signage.agent

import kotlinx.coroutines.delay
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.PlayerMessage
import org.churchpresenter.signage.core.ServerMessage
import org.churchpresenter.signage.core.WebTestRun
import java.io.IOException
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * How smoothly this screen shows a busy web page: a built-in moving page is put on screen at each
 * frame rate asked for, and after a few seconds to settle, the frames that reached mpv, how busy the
 * processor was and how hot it got are measured. Then the screen goes back to what it played.
 */
class WebTest(
    private val engine: PlayerEngine,
    /** New frames sent to mpv so far: [MjpegServer.framesSent]. */
    private val framesSent: () -> Long,
    private val cpu: CpuLoad = CpuLoad(),
    private val temperature: () -> Double? = ::processorTemperature,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val settleMs: Long = SETTLE_MS,
) {
    suspend fun run(request: ServerMessage.WebTest): PlayerMessage.WebTestResult = try {
        PlayerMessage.WebTestResult(runs = request.fps.map { fps -> measure(fps, request.secondsEach) })
    } finally {
        engine.testing = null
    }

    private suspend fun measure(fps: Int, seconds: Int): WebTestRun {
        engine.testing = ScreenItem(null, MediaKind.WEB, null, PAGE_URL, maxFps = fps)
        pause(settleMs)
        val framesBefore = framesSent()
        val cpuBefore = cpu.sample()
        var hottest = temperature()
        repeat(seconds) {
            pause(MS_PER_SECOND)
            hottest = listOfNotNull(hottest, temperature()).maxOrNull()
        }
        val shown = (framesSent() - framesBefore).toDouble() / seconds
        return WebTestRun(fps, shown, cpuBefore?.let(cpu::percentSince), hottest)
    }

    companion object {
        private const val SETTLE_MS = 5_000L
        private const val MS_PER_SECOND = 1_000L

        /** The test page, as a `data:` address Chromium opens with no network. */
        val PAGE_URL: String by lazy {
            val html = checkNotNull(WebTest::class.java.getResourceAsStream("/web-test.html")) { "web-test.html" }
                .use { it.readBytes() }
            "data:text/html;base64," + Base64.getEncoder().encodeToString(html)
        }
    }
}

/**
 * How busy the whole processor is, from Linux's `/proc/stat`: the share of time not idle between
 * two samples, 0–100. Elsewhere there is no sample, and the test reports none.
 */
class CpuLoad(private val stat: Path = Path("/proc/stat")) {
    /** Busy and total time so far. */
    data class Sample(val busy: Long, val total: Long)

    fun sample(): Sample? = try {
        val line = stat.readLines().first { it.startsWith("cpu ") }
        val fields = line.trim().split(Regex("\\s+")).drop(1).map(String::toLong)
        val idle = fields[IDLE] + fields.getOrElse(IOWAIT) { 0 }
        Sample(fields.sum() - idle, fields.sum())
    } catch (_: IOException) {
        null
    } catch (_: NoSuchElementException) {
        null
    } catch (_: NumberFormatException) {
        null
    }

    fun percentSince(before: Sample): Double? {
        val now = sample() ?: return null
        val total = now.total - before.total
        return if (total <= 0) null else PERCENT * (now.busy - before.busy) / total
    }

    private companion object {
        const val IDLE = 3
        const val IOWAIT = 4
        const val PERCENT = 100.0
    }
}

/** The processor's temperature in °C, as Linux reports it (a Raspberry Pi always does); null elsewhere. */
fun processorTemperature(zone: Path = Path("/sys/class/thermal/thermal_zone0/temp")): Double? = try {
    zone.readText().trim().toDouble() / MILLIDEGREES
} catch (_: IOException) {
    null
} catch (_: NumberFormatException) {
    null
}

private const val MILLIDEGREES = 1000.0
