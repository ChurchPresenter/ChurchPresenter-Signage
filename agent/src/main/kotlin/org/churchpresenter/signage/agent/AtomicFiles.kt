package org.churchpresenter.signage.agent

import kotlinx.serialization.KSerializer
import org.churchpresenter.signage.core.SignageJson
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * JSON files written so a power cut leaves the old file or the new one, never half of one: each write
 * goes to a temporary file beside the target and is renamed over it.
 */
internal object AtomicFiles {
    fun <T> read(file: Path, serializer: KSerializer<T>): T? {
        if (!file.exists()) return null
        return try {
            SignageJson.decodeFromString(serializer, file.readText())
        } catch (e: IOException) {
            Log.warn("Could not read ${file.name}", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.warn("Ignoring unreadable ${file.name}", e)
            null
        }
    }

    fun <T> write(file: Path, serializer: KSerializer<T>, value: T) {
        val temp = file.resolveSibling(".${file.name}.next")
        temp.writeText(SignageJson.encodeToString(serializer, value))
        move(temp, file)
    }

    fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
