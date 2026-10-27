package org.churchpresenter.signage.agent

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText

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

    fun percentSince(before: Sample): Double? = sample()?.let { percent(before, it) }

    companion object {
        /** How busy the processor was between [before] and [after], 0–100; null when no time passed. */
        fun percent(before: Sample, after: Sample): Double? {
            val total = after.total - before.total
            return if (total <= 0) null else PERCENT * (after.busy - before.busy) / total
        }

        private const val IDLE = 3
        private const val IOWAIT = 4
        private const val PERCENT = 100.0
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

/** The machine's memory, from Linux's `/proc/meminfo`, in KiB. */
data class Memory(val totalKb: Long, val availableKb: Long) {
    /** How much is in use, 0–100. */
    val usedPercent: Int get() = if (totalKb <= 0) 0 else ((totalKb - availableKb) * PERCENT_INT / totalKb).toInt()

    companion object {
        private const val PERCENT_INT = 100L

        /** The memory now; null where there is no `/proc/meminfo` (not Linux). */
        fun read(meminfo: Path = Path("/proc/meminfo")): Memory? = try {
            val fields = meminfo.readLines().associate { line ->
                line.substringBefore(':') to line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
            }
            val total = fields["MemTotal"] ?: return null
            Memory(total, fields["MemAvailable"] ?: fields["MemFree"] ?: return null)
        } catch (_: IOException) {
            null
        }
    }
}

/** How long the machine has been up, from Linux's `/proc/uptime`; null elsewhere. */
fun uptimeSeconds(uptime: Path = Path("/proc/uptime")): Long? = try {
    uptime.readText().trim().substringBefore(' ').toDouble().toLong()
} catch (_: IOException) {
    null
} catch (_: NumberFormatException) {
    null
}

/** What a player reports about itself with each status; each part null where it cannot tell. */
data class Health(
    val cpuPercent: Int? = null,
    val memoryPercent: Int? = null,
    val temperatureC: Double? = null,
    val uptimeSeconds: Long? = null,
)

/** Reads [Health], the processor's load since the read before (none on the first). */
class HealthReader(
    private val cpu: CpuLoad = CpuLoad(),
    private val memory: () -> Memory? = { Memory.read() },
    private val temperature: () -> Double? = ::processorTemperature,
    private val uptime: () -> Long? = ::uptimeSeconds,
) {
    private var last: CpuLoad.Sample? = null

    @Synchronized
    fun read(): Health {
        val now = cpu.sample()
        val busy = last?.let { before -> now?.let { CpuLoad.percent(before, it) } }
        last = now
        return Health(busy?.toInt(), memory()?.usedPercent, temperature(), uptime())
    }
}
