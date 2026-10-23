package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.isSha256Hex
import java.io.InputStream
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream

/** Content named by its SHA-256, in one folder. Nothing else is ever looked up in it. */
class ContentFiles(private val dir: Path) {
    init {
        dir.createDirectories()
    }

    fun has(sha256: String): Boolean = isSha256Hex(sha256) && dir.resolve(sha256).exists()

    /** Where a file lives, for the player; null for anything that is not a stored hash. */
    fun path(sha256: String): Path? = if (has(sha256)) dir.resolve(sha256) else null

    /** Stores [content] as [sha256] if its bytes hash to that; returns false, keeping nothing, if not. */
    fun put(sha256: String, content: InputStream): Boolean {
        require(isSha256Hex(sha256)) { "not a sha256: $sha256" }
        if (has(sha256)) return true
        val part = dir.resolve(".part-${UUID.randomUUID()}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestOutputStream(part.outputStream(), digest).use { content.copyTo(it) }
            if (digest.digest().let(::hex) != sha256) return false
            AtomicFiles.move(part, dir.resolve(sha256))
            return true
        } finally {
            part.deleteIfExists()
        }
    }

    /** Deletes every file but [keep], partial downloads included; returns how many went. */
    fun retainOnly(keep: Set<String>): Int = dir.listDirectoryEntries()
        .filter { it.name !in keep }
        .count { it.deleteIfExists() }

    private fun hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }
}
