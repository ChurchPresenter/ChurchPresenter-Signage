package org.churchpresenter.signage.agent

import kotlinx.coroutines.delay
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.PlayerMessage
import org.churchpresenter.signage.core.ServerMessage
import org.churchpresenter.signage.core.WebTestRun
import java.util.Base64

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
    /** Whether the page is drawn with the GPU: [WebRenderer.Browser.gpu]. */
    private val gpu: () -> Boolean? = { null },
) {
    suspend fun run(request: ServerMessage.WebTest): PlayerMessage.WebTestResult = try {
        PlayerMessage.WebTestResult(runs = request.fps.map { fps -> measure(fps, request.secondsEach) }, gpu = gpu())
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
