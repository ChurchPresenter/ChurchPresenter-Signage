package org.churchpresenter.signage.agent

/**
 * Lets a page's frames through at no more than [fps] a second, whatever rate Chromium draws at. Chromium's
 * own `everyNthFrame` assumes it draws 60: a Raspberry Pi draws about 23, so every second frame (for 30)
 * came to 11. Here every frame is taken while the rate allows, so a slow machine shows all it can.
 */
internal class FramePacer(fps: Int) {
    private val intervalMs = MS_PER_SECOND / fps.coerceIn(1, MAX_FPS).toDouble()
    private var nextMs = Double.NEGATIVE_INFINITY

    /** Whether the frame that arrived at [nowMs] goes to the screen. */
    fun take(nowMs: Long): Boolean {
        // A few milliseconds early still counts: frames arrive a little unevenly.
        if (nowMs < nextMs - SLACK_MS) return false
        // On schedule, the next is due one interval after this one was; far behind (the first frame, or
        // a pause), one interval from now, so missed frames are not sent in a burst.
        nextMs = if (nowMs - nextMs > intervalMs) nowMs + intervalMs else nextMs + intervalMs
        return true
    }

    private companion object {
        const val MS_PER_SECOND = 1000.0
        const val MAX_FPS = 60
        const val SLACK_MS = 4.0
    }
}
