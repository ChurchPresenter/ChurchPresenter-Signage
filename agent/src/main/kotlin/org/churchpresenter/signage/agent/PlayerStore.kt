package org.churchpresenter.signage.agent

import kotlinx.serialization.Serializable
import org.churchpresenter.signage.core.Manifest
import org.churchpresenter.signage.core.ScreenOverride
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.deleteIfExists

/** A manifest the player has every file for, with the version ChurchPresenter gave it. */
@Serializable
data class StoredManifest(val version: String, val manifest: Manifest)

/**
 * Everything the player keeps on disk, under one folder — the only folder that stays writable when the
 * system disk is read-only:
 * ```
 * identity.json   who it is and who owns it
 * manifest.json   the manifest playing now, written only once all its files are present
 * previous.json   the one before it, whose files are kept for one rollback
 * override.json   the manual switch, if any
 * files/<sha256>  content, named by hash
 * ```
 * Every JSON write goes through [AtomicFiles].
 */
class PlayerStore(private val root: Path) {
    val files = ContentFiles(root.resolve("files"))
    private val identityFile = root.resolve("identity.json")
    private val manifestFile = root.resolve("manifest.json")
    private val previousFile = root.resolve("previous.json")
    private val overrideFile = root.resolve("override.json")

    /** This player's identity, made with a fresh random id the first time. */
    fun identity(): PlayerIdentity = AtomicFiles.read(identityFile, PlayerIdentity.serializer())
        ?: PlayerIdentity(UUID.randomUUID().toString()).also(::saveIdentity)

    fun saveIdentity(identity: PlayerIdentity) = AtomicFiles.write(identityFile, PlayerIdentity.serializer(), identity)

    fun manifest(): StoredManifest? = AtomicFiles.read(manifestFile, StoredManifest.serializer())

    fun override(): ScreenOverride? = AtomicFiles.read(overrideFile, ScreenOverride.serializer())

    fun saveOverride(override: ScreenOverride?) {
        if (override == null) {
            overrideFile.deleteIfExists()
        } else {
            AtomicFiles.write(overrideFile, ScreenOverride.serializer(), override)
        }
    }

    /**
     * Makes [manifest] the one playing. Refused, changing nothing, unless every file it names is
     * already stored. The manifest it replaces becomes the previous one.
     */
    fun commit(manifest: StoredManifest): Boolean {
        if (!manifest.manifest.fileHashes().all(files::has)) return false
        manifest()?.takeIf { it.version != manifest.version }?.let {
            AtomicFiles.write(previousFile, StoredManifest.serializer(), it)
        }
        AtomicFiles.write(manifestFile, StoredManifest.serializer(), manifest)
        return true
    }

    /** Deletes files neither the current nor the previous manifest names, and stray partial downloads. */
    fun collectGarbage(): Int = files.retainOnly(
        buildSet {
            manifest()?.let { addAll(it.manifest.fileHashes()) }
            AtomicFiles.read(previousFile, StoredManifest.serializer())?.let { addAll(it.manifest.fileHashes()) }
        },
    )

    /** Forgets the owner's content and any override: what a release or a factory reset does. */
    fun clearContent() {
        listOf(manifestFile, previousFile, overrideFile).forEach { it.deleteIfExists() }
        collectGarbage()
    }

    /**
     * Back to as installed: content gone, owner forgotten, and a new player id, so it pairs as a new
     * screen. The installed versions in `app/` and a fixed ChurchPresenter address are kept.
     */
    fun factoryReset() {
        val address = AtomicFiles.read(identityFile, PlayerIdentity.serializer())?.fixedAddress
        clearContent()
        identityFile.deleteIfExists()
        address?.let { saveIdentity(identity().copy(fixedAddress = it)) }
    }

    fun freeBytes(): Long = Files.getFileStore(root).usableSpace
}
