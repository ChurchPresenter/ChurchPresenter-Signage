package org.churchpresenter.signage.agent

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.churchpresenter.signage.core.SignageJson
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class KioskServerTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second
    private val clock = MutableClock(instant("2026-10-19T08:00"))

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    @Test
    fun servesThePageItsStateAndItsFiles() = testApplication {
        store.files.put(pictureA.sha, pictureA.bytes.inputStream())
        store.commit(StoredManifest("v1", manifestOf(pictureA)))
        val engine = PlayerEngine(store, NoCec, clock::read).also { it.tick() }
        val kiosk = KioskServer(engine, store, clock::read)
        application { kiosk.module(this) }

        assertTrue("kiosk.js" in client.get("/").bodyAsText())
        assertTrue("POLL_MS" in client.get("/kiosk.js").bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/kiosk.css").status)

        assertNull(kiosk.lastPoll)
        val state = SignageJson.decodeFromString(KioskState.serializer(), client.get("/state").bodyAsText())
        assertEquals("welcome", state.playlistId)
        assertNotNull(kiosk.lastPoll)

        assertContentEquals(pictureA.bytes, client.get(state.items.single().url).readRawBytes())
        assertEquals(HttpStatusCode.NotFound, client.get("/files/${pictureB.sha}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/files/..%2Fidentity.json").status)
    }
}
