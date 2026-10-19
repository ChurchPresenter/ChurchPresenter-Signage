package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.BlockAction
import org.churchpresenter.signage.core.ClockTime
import org.churchpresenter.signage.core.Day
import org.churchpresenter.signage.core.Manifest
import org.churchpresenter.signage.core.MediaKind
import org.churchpresenter.signage.core.Playlist
import org.churchpresenter.signage.core.PlaylistItem
import org.churchpresenter.signage.core.ScheduleBlock
import org.churchpresenter.signage.core.ScreenSettings
import org.churchpresenter.signage.core.sha256Hex
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.path.createTempDirectory

/** A file's bytes and their hash. */
class Content(text: String) {
    val bytes: ByteArray = text.toByteArray()
    val sha: String = sha256Hex(bytes)
}

val pictureA = Content("picture a")
val pictureB = Content("picture b")
val pictureC = Content("picture c")

const val OWNER = "owner-1"
val zone: ZoneId = ZoneId.of("America/Chicago")

fun instant(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

fun image(content: Content) = PlaylistItem(content.sha, MediaKind.IMAGE, durationMs = 5_000)

fun block(id: String, days: Set<Day>, start: String, end: String, action: BlockAction) =
    ScheduleBlock(id, days, ClockTime.parse(start), ClockTime.parse(end), action)

/** Welcome plays outside blocks; nights are TV-off; Sunday morning is service hours. */
fun manifestOf(vararg welcome: Content, owner: String = OWNER, events: List<Content> = emptyList()) = Manifest(
    ownerId = owner,
    screen = ScreenSettings("Lobby"),
    playlists = listOf(
        Playlist("welcome", "Welcome", welcome.map(::image)),
        Playlist("events", "Events", events.map(::image)),
    ),
    schedule = listOf(
        block("night", Day.entries.toSet(), "22:00", "06:00", BlockAction.TvOff),
        block("sunday", setOf(Day.SUN), "09:30", "12:30", BlockAction.Service),
    ),
    defaultPlaylistId = "welcome",
    timeZone = zone.id,
)

/** A ChurchPresenter that answers from memory, and counts what it was asked for. */
class FakeSource(var version: String, var manifest: Manifest, files: List<Content>) : ContentSource {
    val files: MutableMap<String, ByteArray> = files.associate { it.sha to it.bytes }.toMutableMap()
    val downloads = mutableListOf<String>()
    var failManifest = false

    override suspend fun manifest(): Pair<String, Manifest> {
        if (failManifest) throw IOException("unreachable")
        return version to manifest
    }

    override suspend fun <T> file(sha256: String, consume: (InputStream) -> T): T {
        downloads += sha256
        val bytes = files[sha256] ?: throw IOException("404")
        return bytes.inputStream().use(consume)
    }
}

class FakeCec(private val answers: Boolean = true) : Cec {
    val sent = mutableListOf<CecCommand>()

    override fun probe(): Boolean = answers

    override fun send(command: CecCommand): Boolean {
        sent += command
        return answers
    }
}

/** A store in its own temp folder, paired to [OWNER]; delete [root] after the test. */
fun pairedStore(): Pair<Path, PlayerStore> {
    val root = createTempDirectory("signage-store")
    val store = PlayerStore(root)
    store.saveIdentity(store.identity().copy(token = "t", ownerId = OWNER, ownerName = "Main PC"))
    return root to store
}

class MutableClock(var now: Instant) {
    fun read(): Instant = now
}
