package org.churchpresenter.signage.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Updates the machine itself (Chromium, mpv and every other package, and its settings for the player)
 * when Update is pressed in ChurchPresenter: it starts install.sh's root-owned
 * `churchpresenter-signage-setup` service, which fetches the release's install.sh, checks it against
 * the signed release index and runs it. The player may start that service and nothing else; it hands
 * it nothing.
 */
fun interface SystemSetup {
    /** Runs the update to its end; null when it went well, else why it did not. */
    suspend fun run(): String?
}

/** [SystemSetup] through systemd: `systemctl start` waits for the one-shot service to finish. */
class SystemdSetup(
    private val systemctl: String,
    private val log: Path = Path(LOG),
) : SystemSetup {
    override suspend fun run(): String? = withContext(Dispatchers.IO) {
        Log.info("Updating the system")
        try {
            val process = ProcessBuilder(systemctl, "--no-ask-password", "start", UNIT)
                .redirectErrorStream(true).start()
            val output = process.inputReader().readText().trim()
            if (process.waitFor() == 0) {
                Log.info("The system is up to date")
                null
            } else {
                val why = lastLines().ifBlank { output }.ifBlank { "the system update failed" }
                Log.warn("The system update failed: $why")
                why
            }
        } catch (e: IOException) {
            Log.warn("The system update could not start: ${e.message}")
            "the system update could not start"
        }
    }

    /** The end of the update's own log, which says why it stopped. */
    private fun lastLines(): String = try {
        log.readLines().map(String::trim).filter(String::isNotEmpty).takeLast(LAST_LINES).joinToString(" ")
    } catch (_: IOException) {
        ""
    }

    companion object {
        const val UNIT = "churchpresenter-signage-setup.service"
        const val LOG = "/var/log/churchpresenter-signage-setup.log"
        private const val LAST_LINES = 2
        private val UNIT_FILE = Path("/etc/systemd/system/$UNIT")

        /** The setup service where install.sh put one (installs before it have none), or null. */
        fun find(): SystemdSetup? = CageKiosk.find("systemctl")?.takeIf { UNIT_FILE.exists() }?.let(::SystemdSetup)
    }
}
