package org.churchpresenter.signage.agent

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Turns the TV on and off. */
interface Cec {
    /** Whether a TV answers on the HDMI-CEC bus. */
    fun probe(): Boolean

    fun send(command: CecCommand): Boolean
}

/** No CEC on this machine (most mini PCs): the TV's own timer turns it on and off. */
object NoCec : Cec {
    override fun probe(): Boolean = false

    override fun send(command: CecCommand): Boolean = false
}

/**
 * CEC through `cec-ctl` (v4l-utils) on the Raspberry Pi's HDMI port. Registers as a playback device
 * once, then talks to the TV at logical address 0.
 */
class CecCtl(private val device: String = "/dev/cec0") : Cec {
    private var registered = false

    override fun probe(): Boolean = register() && run("--to", "0", "--give-device-power-status")

    override fun send(command: CecCommand): Boolean {
        if (!register()) return false
        return when (command) {
            CecCommand.POWER_ON -> run("--to", "0", "--image-view-on")
            CecCommand.STANDBY -> run("--to", "0", "--standby")
        }
    }

    private fun register(): Boolean {
        if (!registered) registered = run("--playback", "--osd-name", "Signage")
        return registered
    }

    private fun run(vararg args: String): Boolean = try {
        val process = ProcessBuilder(listOf("cec-ctl", "-d", device) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val ok = finished && process.exitValue() == 0
        if (!ok) Log.warn("cec-ctl ${args.joinToString(" ")} failed: ${output.trim()}")
        ok
    } catch (e: IOException) {
        Log.warn("cec-ctl is not available", e)
        false
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
