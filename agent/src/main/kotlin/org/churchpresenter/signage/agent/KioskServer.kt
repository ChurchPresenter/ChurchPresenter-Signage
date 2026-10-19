package org.churchpresenter.signage.agent

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.churchpresenter.signage.core.SignageJson
import java.time.Instant

/**
 * The page the kiosk browser shows, served on `127.0.0.1` only. `GET /state` is the page's once-a-second
 * question, and its last answer is what the watchdog checks.
 */
class KioskServer(
    private val player: PlayerEngine,
    private val store: PlayerStore,
    private val clock: () -> Instant = Instant::now,
) {
    @Volatile
    var lastPoll: Instant? = null
        private set

    fun module(application: Application) = with(application) {
        install(PartialContent)
        routing {
            get("/") { call.respondText(resource("index.html"), ContentType.Text.Html) }
            get("/kiosk.js") { call.respondText(resource("kiosk.js"), ContentType.Application.JavaScript) }
            get("/kiosk.css") { call.respondText(resource("kiosk.css"), ContentType.Text.CSS) }
            get("/state") {
                lastPoll = clock()
                call.respondText(
                    SignageJson.encodeToString(KioskState.serializer(), player.state.value),
                    ContentType.Application.Json,
                )
            }
            get("/files/{sha}") {
                val path = store.files.path(call.parameters["sha"].orEmpty())
                if (path == null) call.respond(HttpStatusCode.NotFound) else call.respondFile(path.toFile())
            }
        }
    }

    fun start(port: Int): EmbeddedServer<*, *> =
        embeddedServer(CIO, port = port, host = "127.0.0.1") { module(this) }.start(wait = false)

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/kiosk/$name")) { "missing kiosk/$name" }.readText()
}
