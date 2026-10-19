package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.Manifest
import org.churchpresenter.signage.core.PROTOCOL_VERSION
import java.io.IOException
import java.io.InputStream

/** Where manifests and files come from: ChurchPresenter over HTTP, or a fake in tests. */
interface ContentSource {
    /** This player's manifest and its version. */
    suspend fun manifest(): Pair<String, Manifest>

    /** Streams the file named [sha256] to [consume]; throws [IOException] when it cannot. */
    suspend fun <T> file(sha256: String, consume: (InputStream) -> T): T
}

sealed interface SyncResult {
    data class Updated(val version: String, val downloaded: Int) : SyncResult

    data object UpToDate : SyncResult

    /** Nothing changed on disk; the player goes on playing what it has. */
    data class Failed(val reason: String) : SyncResult
}

/**
 * The safe swap: fetch the manifest, download only the files missing here (each checked against its
 * hash), and switch to it only when all are present. Cut off at any step, the old manifest plays on.
 */
class ManifestSync(private val store: PlayerStore, private val source: ContentSource) {

    @Suppress("ReturnCount")
    suspend fun sync(): SyncResult {
        val (version, manifest) = try {
            source.manifest()
        } catch (e: IOException) {
            return SyncResult.Failed("manifest: ${e.message}")
        }
        val owner = store.identity().ownerId
        if (manifest.ownerId != owner) return SyncResult.Failed("manifest from ${manifest.ownerId}, owner is $owner")
        if (manifest.protocol > PROTOCOL_VERSION) return SyncResult.Failed("manifest protocol ${manifest.protocol}")
        if (store.manifest()?.version == version) return SyncResult.UpToDate

        val missing = manifest.fileHashes().filterNot(store.files::has)
        for (sha in missing) {
            val stored = try {
                source.file(sha) { store.files.put(sha, it) }
            } catch (e: IOException) {
                return SyncResult.Failed("file $sha: ${e.message}")
            }
            if (!stored) return SyncResult.Failed("file $sha did not match its hash")
        }
        if (!store.commit(StoredManifest(version, manifest))) return SyncResult.Failed("files missing after download")
        store.collectGarbage()
        return SyncResult.Updated(version, missing.size)
    }
}
