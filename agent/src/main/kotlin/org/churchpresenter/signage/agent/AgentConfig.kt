package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.Platform
import java.nio.file.Path
import kotlin.io.path.Path

/** How the agent was started: the service file's arguments. */
data class AgentConfig(
    val dataDir: Path,
    val kioskPort: Int = DEFAULT_KIOSK_PORT,
    /** `host:port` of ChurchPresenter, when mDNS cannot reach it. Saved into the identity on start. */
    val address: String? = null,
    val cec: Boolean = true,
    val screenWidth: Int = DEFAULT_WIDTH,
    val screenHeight: Int = DEFAULT_HEIGHT,
    /** Run when the kiosk page has not asked for its state in a while, to restart the browser. */
    val browserRestartCommand: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_KIOSK_PORT = 8090
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080

        /**
         * Reads `--data <dir> --kiosk-port <n> --address <host:port> --no-cec --screen <w>x<h>
         * --browser-restart "<command>"`. Throws [IllegalArgumentException] on anything else.
         */
        fun parse(args: List<String>, platform: Platform = currentPlatform()): AgentConfig {
            var config = AgentConfig(defaultDataDir(platform))
            val queue = ArrayDeque(args)
            fun value(flag: String) = requireNotNull(queue.removeFirstOrNull()) { "$flag needs a value" }
            while (queue.isNotEmpty()) {
                config = when (val flag = queue.removeFirst()) {
                    "--data" -> config.copy(dataDir = Path(value(flag)))
                    "--kiosk-port" -> config.copy(kioskPort = requireNotNull(value(flag).toIntOrNull()) { "bad port" })
                    "--address" -> config.copy(address = value(flag).takeIf { it.isNotBlank() })
                    "--no-cec" -> config.copy(cec = false)
                    "--screen" -> {
                        val (w, h) = value(flag).split('x').map { requireNotNull(it.toIntOrNull()) { "bad --screen" } }
                        config.copy(screenWidth = w, screenHeight = h)
                    }
                    "--browser-restart" ->
                        config.copy(browserRestartCommand = value(flag).split(' ').filter(String::isNotBlank))
                    else -> throw IllegalArgumentException("unknown argument $flag")
                }
            }
            return config
        }

        fun currentPlatform(
            osName: String = System.getProperty("os.name"),
            osArch: String = System.getProperty("os.arch"),
        ): Platform = when {
            osName.startsWith("Windows") -> Platform.WINDOWS_X64
            osArch == "aarch64" || osArch == "arm64" -> Platform.LINUX_ARM64
            else -> Platform.LINUX_X64
        }

        fun defaultDataDir(platform: Platform): Path = when (platform) {
            Platform.WINDOWS_X64 -> Path(System.getenv("ProgramData") ?: "C:\\ProgramData", "ChurchPresenterSignage")
            else -> Path("/var/lib/churchpresenter-signage")
        }
    }
}
