package org.churchpresenter.signage.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.churchpresenter.signage.core.Orientation
import java.io.IOException

/**
 * The screen's touches, read from `libinput debug-events` (libinput-tools) whatever has the screen,
 * mpv or the kiosk browser: each one counts as someone using the screen ([onActivity]), and a finger
 * held in the top-left corner for [CornerHold.HOLD_MS] is the gesture that switches between the
 * playlist and the interactive page ([onGesture]). libinput reports where a touch is as a share of
 * the panel, so no device needs calibrating; [orientation] turns that into the corner people see.
 */
class TouchInput(
    private val executable: String,
    private val orientation: () -> Orientation,
    private val onActivity: () -> Unit,
    private val onGesture: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val hold = CornerHold()
    private val events = LibinputEvents()

    /** Runs until cancelled, starting libinput again when it exits. */
    suspend fun run(): Unit = coroutineScope {
        launch {
            while (isActive) {
                if (hold.check(now())) onGesture()
                delay(CHECK_EVERY_MS)
            }
        }
        while (isActive) {
            try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.warn("Touch input could not be read: ${e.message}")
            }
            delay(RESTART_DELAY_MS)
        }
    }

    private suspend fun read() {
        val process = ProcessBuilder(executable, "debug-events")
            .redirectErrorStream(true)
            .start()
        try {
            runInterruptible(Dispatchers.IO) {
                process.inputReader().useLines { lines -> lines.forEach(::line) }
            }
        } finally {
            process.destroy()
        }
    }

    private fun line(text: String) {
        val event = events.parse(text) ?: return
        onActivity()
        val at = now()
        when (event) {
            is TouchEvent.Down -> hold.down(event.slot, upright(event.x, event.y), at)
            is TouchEvent.Move -> hold.move(event.slot, upright(event.x, event.y))
            is TouchEvent.Up -> hold.up(event.slot)
        }
    }

    private fun upright(x: Double, y: Double): Pair<Double, Double> = upright(orientation(), x, y)

    companion object {
        private const val CHECK_EVERY_MS = 250L
        private const val RESTART_DELAY_MS = 5_000L
        private const val FULL = 100.0

        /**
         * A point on the panel, in hundredths, as the picture shows it on a screen mounted at
         * [orientation]: the picture is turned that far clockwise, so the panel's point is turned back.
         */
        internal fun upright(orientation: Orientation, x: Double, y: Double): Pair<Double, Double> =
            when (orientation) {
                Orientation.LANDSCAPE -> x to y
                Orientation.PORTRAIT -> y to FULL - x
                Orientation.LANDSCAPE_FLIPPED -> FULL - x to FULL - y
                Orientation.PORTRAIT_FLIPPED -> FULL - y to x
            }
    }
}

/** A touch as [LibinputEvents] reads it: [x] and [y] in hundredths of the panel, from its top left. */
sealed interface TouchEvent {
    val slot: Int

    data class Down(override val slot: Int, val x: Double, val y: Double) : TouchEvent

    data class Move(override val slot: Int, val x: Double, val y: Double) : TouchEvent

    data class Up(override val slot: Int) : TouchEvent
}

/**
 * Reads the lines of `libinput debug-events`: touches (`TOUCH_DOWN`, `TOUCH_MOTION`, `TOUCH_UP`),
 * and a touchscreen that reports itself as an absolute pointer, whose left button is the finger.
 * libinput prints a touch at a screen of 100×100, so its first pair of numbers is in hundredths.
 */
class LibinputEvents {
    private companion object {
        val TOUCH =
            Regex("""\b(TOUCH_DOWN|TOUCH_MOTION|TOUCH_UP|TOUCH_CANCEL)\s+\+\S+\s+(\d+)(?:\s+\(\d+\))?\s*""" + POINT)
        const val POINT = """(?:([\d.]+)/\s*([\d.]+))?"""
        val POINTER_MOVE = Regex("""\bPOINTER_MOTION_ABSOLUTE\s+\+\S+\s+([\d.]+)/\s*([\d.]+)""")
        val POINTER_BUTTON = Regex("""\bPOINTER_BUTTON\s+\+\S+\s+BTN_LEFT\s+\(\d+\)\s+(pressed|released)""")
        const val POINTER_SLOT = -1
    }

    /** The pointer's last place, for its button; a touchscreen moves it before it presses. */
    private var pointer: Pair<Double, Double>? = null

    fun parse(line: String): TouchEvent? {
        TOUCH.find(line)?.let { match ->
            val groups = match.groupValues
            val at = groups[2].toInt()
            val point = groups[3].toDoubleOrNull()?.let { it to (groups[4].toDoubleOrNull() ?: return null) }
            return when (groups[1]) {
                "TOUCH_DOWN" -> point?.let { TouchEvent.Down(at, it.first, it.second) }
                "TOUCH_MOTION" -> point?.let { TouchEvent.Move(at, it.first, it.second) }
                else -> TouchEvent.Up(at)
            }
        }
        POINTER_MOVE.find(line)?.let { match ->
            val (x, y) = match.destructured
            val point = (x.toDoubleOrNull() ?: return null) to (y.toDoubleOrNull() ?: return null)
            pointer = point
            return TouchEvent.Move(POINTER_SLOT, point.first, point.second)
        }
        POINTER_BUTTON.find(line)?.let { match ->
            return if (match.groupValues[1] == "pressed") {
                pointer?.let { TouchEvent.Down(POINTER_SLOT, it.first, it.second) }
            } else {
                TouchEvent.Up(POINTER_SLOT)
            }
        }
        return null
    }
}

/**
 * The switch gesture: one finger held still in the top-left corner (the first [CORNER] hundredths
 * each way) for [HOLD_MS]. Leaving the corner, or lifting, before then cancels it; it fires once per hold.
 */
class CornerHold(private val holdMs: Long = HOLD_MS) {
    private data class Hold(val since: Long, var fired: Boolean = false)

    private val holds = mutableMapOf<Int, Hold>()

    @Synchronized
    fun down(slot: Int, point: Pair<Double, Double>, nowMs: Long) {
        if (inCorner(point)) holds[slot] = Hold(nowMs) else holds.remove(slot)
    }

    @Synchronized
    fun move(slot: Int, point: Pair<Double, Double>) {
        if (!inCorner(point)) holds.remove(slot)
    }

    @Synchronized
    fun up(slot: Int) {
        holds.remove(slot)
    }

    /** Whether a hold has just lasted long enough; true once for each. */
    @Synchronized
    fun check(nowMs: Long): Boolean {
        val due = holds.values.firstOrNull { !it.fired && nowMs - it.since >= holdMs } ?: return false
        due.fired = true
        return true
    }

    private fun inCorner(point: Pair<Double, Double>): Boolean = point.first <= CORNER && point.second <= CORNER

    companion object {
        const val HOLD_MS = 5_000L
        const val CORNER = 12.0
    }
}
