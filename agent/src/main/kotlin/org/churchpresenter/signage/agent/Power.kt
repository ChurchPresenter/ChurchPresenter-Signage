package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.PowerAction
import java.io.IOException

/** Restarts or shuts down the player's machine, as ChurchPresenter asks with `power`. */
fun interface Power {
    /** Starts [action]; false when the system refused it. */
    fun run(action: PowerAction): Boolean
}

/**
 * [Power] through systemd, as the player's own user: install.sh's polkit rule lets it, without a
 * password and without root. Never asks: a refusal is an answer, not a prompt on the TV.
 */
class SystemdPower(private val systemctl: String) : Power {
    override fun run(action: PowerAction): Boolean {
        val verb = when (action) {
            PowerAction.RESTART -> "reboot"
            PowerAction.SHUT_DOWN -> "poweroff"
        }
        return try {
            val process = ProcessBuilder(systemctl, "--no-ask-password", verb).redirectErrorStream(true).start()
            val output = process.inputReader().readText().trim()
            val done = process.waitFor() == 0
            if (!done) Log.warn("The system refused to $verb: $output")
            done
        } catch (e: IOException) {
            Log.warn("Could not $verb: ${e.message}")
            false
        }
    }

    companion object {
        /** systemd's systemctl on Linux, or null where there is none (and no [Power]). */
        fun find(): SystemdPower? = CageKiosk.find("systemctl")
            ?.takeIf { System.getProperty("os.name") == "Linux" }?.let(::SystemdPower)
    }
}
