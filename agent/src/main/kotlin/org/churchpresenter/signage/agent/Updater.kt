package org.churchpresenter.signage.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.churchpresenter.signage.core.Platform
import org.churchpresenter.signage.core.PlayerMessage
import org.churchpresenter.signage.core.ReleaseSigning
import org.churchpresenter.signage.core.ServerMessage
import org.churchpresenter.signage.core.UpdateState
import org.churchpresenter.signage.core.supportsProtocol
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream

/**
 * Installs a player build ChurchPresenter sends, trusting nothing it is told: the release index must
 * carry a signature by [publicKey], the build must hash to that index's entry for [platform], and
 * the release must speak this agent's protocol. Each step goes back to ChurchPresenter through
 * [report]; at the end the new version is switched in and [restart] ends this process for the
 * service to start it.
 */
@OptIn(ExperimentalPathApi::class)
class Updater(
    private val app: AppFolder,
    private val platform: Platform,
    /** Calls `consume` with the build's bytes from ChurchPresenter at the given path. */
    private val fetch: suspend (path: String, consume: (InputStream) -> Unit) -> Unit,
    private val report: (PlayerMessage.UpdateProgress) -> Unit,
    private val restart: () -> Unit,
    private val unpack: (archive: Path, into: Path) -> Unit = ::untar,
    private val publicKey: String = ReleaseSigning.PUBLIC_KEY,
) {
    private val busy = Mutex()

    /** Installs [update]; one at a time, and a second request while one runs is dropped. */
    suspend fun install(update: ServerMessage.UpdateAvailable) {
        if (!busy.tryLock()) return
        try {
            withContext(Dispatchers.IO) { installNow(update) }
        } catch (e: IOException) {
            fail(update.agentVersion, e.message ?: "it could not be installed")
        } finally {
            busy.unlock()
        }
    }

    private suspend fun installNow(update: ServerMessage.UpdateAvailable) {
        val version = update.agentVersion
        val problem = refusal(update)
        if (problem != null) return fail(version, problem)
        val asset = checkNotNull(ReleaseSigning.verify(update.release, update.releaseSignature, publicKey))
            .assets.getValue(platform)

        val archive = app.download(version)
        lastPercent = 0
        report(PlayerMessage.UpdateProgress(version, UpdateState.DOWNLOADING, "0%"))
        val digest = MessageDigest.getInstance("SHA-256")
        fetch(update.path) { input ->
            copy(input, archive, digest, asset.size) { percent -> downloading(version, percent) }
        }

        report(PlayerMessage.UpdateProgress(version, UpdateState.CHECKING))
        if (digest.digest().toHex() != asset.sha256) {
            archive.deleteIfExists()
            return fail(version, "the download does not match the signed release")
        }

        report(PlayerMessage.UpdateProgress(version, UpdateState.INSTALLING))
        val staging = app.staging(version)
        staging.deleteRecursively()
        staging.createDirectories()
        unpack(archive, staging)
        archive.deleteIfExists()
        val bundle = staging.listDirectoryEntries().singleOrNull()
            ?: throw IOException("the build does not hold one folder")
        app.install(version, bundle)
        staging.deleteRecursively()

        Log.info("Installed agent $version; restarting into it")
        report(PlayerMessage.UpdateProgress(version, UpdateState.RESTARTING))
        restart()
    }

    /** Why [update] cannot be installed, or null when it can. */
    private fun refusal(update: ServerMessage.UpdateAvailable): String? {
        val release = ReleaseSigning.verify(update.release, update.releaseSignature, publicKey)
        return when {
            !app.canUpdate() -> "this player was installed before updates; run its install command again"
            release == null -> "the release is not signed with ChurchPresenter's release key"
            release.version != update.agentVersion ->
                "the signed release is ${release.version}, not ${update.agentVersion}"
            platform !in release.assets -> "the release has no build for this player"
            !supportsProtocol(release.protocolMin, release.protocolMax) ->
                "the release speaks a protocol this player cannot"
            update.agentVersion == app.currentVersion() -> "this version is already installed"
            else -> null
        }
    }

    private var lastPercent = 0

    private fun downloading(version: String, percent: Int) {
        if (percent >= lastPercent + PROGRESS_STEP) {
            lastPercent = percent
            report(PlayerMessage.UpdateProgress(version, UpdateState.DOWNLOADING, "$percent%"))
        }
    }

    private fun fail(version: String, why: String) {
        Log.warn("Update to $version failed: $why")
        report(PlayerMessage.UpdateProgress(version, UpdateState.FAILED, why))
    }

    companion object {
        private const val PROGRESS_STEP = 10
        private const val PERCENT = 100
        private const val BUFFER = 64 * 1024

        /** Copies [input] to [file], hashing it, and calls [percent] as it goes; refuses more than [size] bytes. */
        internal fun copy(input: InputStream, file: Path, digest: MessageDigest, size: Long, percent: (Int) -> Unit) {
            var total = 0L
            file.outputStream().use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > size) throw IOException("the download is larger than the signed release says")
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                    percent((total * PERCENT / size.coerceAtLeast(1)).toInt())
                }
            }
        }

        /** Unpacks a `.tar.gz` with the system's tar, as every Linux the player runs on has. */
        fun untar(archive: Path, into: Path) {
            val process = ProcessBuilder("tar", "xzf", archive.toString(), "-C", into.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputReader().readText()
            if (process.waitFor() != 0) throw IOException("tar failed: ${output.trim()}")
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
