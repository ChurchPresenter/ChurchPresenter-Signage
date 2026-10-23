package org.churchpresenter.signage.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Where the installed player lives, in the data folder so the agent (which is not root) can update
 * it:
 * ```
 * app/<version>/   one unpacked bundle each: bin/, lib/, runtime/
 * app/current      link to the version the launcher starts
 * app/previous     link to the one before, kept for a rollback
 * app/pending      starts of a new version not yet healthy, counted by the launcher
 * app/rolled-back  the version the launcher gave up on, until the agent has reported it
 * ```
 * `install/launch.sh` reads the same names; change them together.
 */
@OptIn(ExperimentalPathApi::class)
class AppFolder(val root: Path) {
    private val current = root.resolve("current")
    private val previous = root.resolve("previous")
    private val pending = root.resolve("pending")
    private val rolledBack = root.resolve("rolled-back")

    /** False for an install from before updates, which has no `current` link to switch. */
    fun canUpdate(): Boolean = current.isSymbolicLink()

    fun currentVersion(): String? = if (canUpdate()) Files.readSymbolicLink(current).name else null

    /**
     * Makes [unpacked] (a bundle's top folder) the version [version] and the one the launcher starts
     * next, keeping the running one as previous. The launcher rolls back if it never becomes healthy.
     */
    fun install(version: String, unpacked: Path) {
        val target = root.resolve(version)
        if (target.exists()) target.deleteRecursively()
        Files.move(unpacked, target, StandardCopyOption.ATOMIC_MOVE)
        Files.readSymbolicLink(current).let { relink(previous, it) }
        relink(current, root.relativize(target))
        pending.writeText("0")
    }

    /** The new version runs well: the launcher stops counting its starts. */
    fun markHealthy() {
        if (pending.deleteIfExists()) Log.info("Update to ${currentVersion()} is healthy")
    }

    /** The version the launcher rolled back from, once; null if none. */
    fun takeRolledBack(): String? = rolledBack.takeIf { it.exists() }?.let { file ->
        file.readText().trim().also { file.deleteIfExists() }
    }

    /** Deletes versions that are neither current nor previous, and unfinished downloads. */
    fun cleanUp() {
        if (!root.exists()) return
        val keep = listOf(current, previous).filter { it.isSymbolicLink() }.map { Files.readSymbolicLink(it).name }
        root.listDirectoryEntries()
            .filter { it.isDirectory() && !it.isSymbolicLink() && it.name !in keep }
            .forEach { it.deleteRecursively() }
        root.listDirectoryEntries(".download-*").forEach { it.deleteIfExists() }
    }

    /** A staging folder and a download file, inside [root] so the final move stays on one disk. */
    fun staging(version: String): Path = root.resolve(".staging-$version")

    fun download(version: String): Path = root.resolve(".download-$version")

    /** Points [link] at [target] in one step: a new link moved over the old. */
    private fun relink(link: Path, target: Path) {
        val fresh = root.resolve(".${link.name}.new")
        fresh.deleteIfExists()
        Files.createSymbolicLink(fresh, target)
        Files.move(fresh, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
