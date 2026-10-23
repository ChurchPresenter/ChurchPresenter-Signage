package org.churchpresenter.signage.agent

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import org.churchpresenter.signage.core.Manifest
import org.churchpresenter.signage.core.SignageJson
import java.io.IOException
import java.io.InputStream

/** Manifests and files from ChurchPresenter's companion server at [baseUrl] (`http://host:port`). */
class HttpContentSource(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : ContentSource {

    override suspend fun manifest(): Pair<String, Manifest> {
        val response = client.get("$baseUrl/api/signage/manifest") { bearerAuth(token) }
        response.requireOk()
        val version = response.headers[HttpHeaders.ETag]?.trim('"')
            ?: throw IOException("manifest has no version")
        val manifest = try {
            SignageJson.decodeFromString(Manifest.serializer(), response.bodyAsText())
        } catch (e: IllegalArgumentException) {
            throw IOException("unreadable manifest", e)
        }
        return version to manifest
    }

    override suspend fun <T> file(sha256: String, consume: (InputStream) -> T): T =
        client.prepareGet("$baseUrl/api/signage/files/$sha256") { bearerAuth(token) }.execute { response ->
            response.requireOk()
            response.bodyAsChannel().toInputStream().use(consume)
        }

    /** Anything else ChurchPresenter serves the player at [path], e.g. an agent build. */
    suspend fun download(path: String, consume: (InputStream) -> Unit) =
        client.prepareGet("$baseUrl$path") { bearerAuth(token) }.execute { response ->
            response.requireOk()
            response.bodyAsChannel().toInputStream().use(consume)
        }

    private fun HttpResponse.requireOk() {
        if (!status.isSuccess()) throw IOException("HTTP ${status.value}")
    }
}
