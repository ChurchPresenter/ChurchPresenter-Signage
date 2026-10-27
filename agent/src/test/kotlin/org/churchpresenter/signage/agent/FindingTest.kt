package org.churchpresenter.signage.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Finding things: ChurchPresenter's address, a Chromium on the PATH, and whether a site answers. */
@OptIn(ExperimentalPathApi::class)
class FindingTest {
    private val locator = ServerLocator { listOf("10.0.0.9:8765") }

    @Test
    fun aGivenAddressComesFirstButNotAlone() {
        val identity = PlayerIdentity("p", fixedAddress = "10.0.0.1:8765", lastAddress = "10.0.0.2:8765")
        assertEquals(listOf("10.0.0.1:8765", "10.0.0.2:8765"), locator.known(identity))
        assertEquals(listOf("10.0.0.1:8765"), locator.known(identity.copy(lastAddress = "10.0.0.1:8765")))
        assertEquals(emptyList(), locator.known(PlayerIdentity("p")))
    }

    @Test
    fun theNetworkSearchListensOnNoLoopback() = runBlocking {
        assertTrue(MdnsServerLocator.lanAddresses().none { it.isLoopbackAddress })
        assertEquals(emptyList(), MdnsServerLocator(browseMs = 1, interfaces = { emptyList() }).browse())
    }

    @Test
    fun aChromiumIsFoundOnThePathButNeverForRoot() {
        val dir = createTempDirectory("chromium-path")
        try {
            assertNull(Chromium.find(dir.toString(), user = "signage"))
            val chrome = dir.resolve("google-chrome").also { it.writeText("#!/bin/sh\n") }
            chrome.toFile().setExecutable(true)
            assertEquals(chrome.toString(), Chromium.find(dir.toString(), user = "signage"))
            assertNull(Chromium.find(dir.toString(), user = "root"))
        } finally {
            dir.deleteRecursively()
        }
        val args = Chromium.args(createTempDirectory("profile").also { it.deleteRecursively() }, 1920, 1080)
        assertTrue("--headless=new" in args && "--mute-audio" in args && "--hide-scrollbars" in args)
        assertTrue("--window-size=1920,1080" in args)
    }

    @Test
    fun aSiteAnswersWithAnyReplyAndNotWhenNothingListens() = runBlocking {
        HttpClient(CIO) { install(HttpTimeout) }.use { client ->
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { site ->
                thread(isDaemon = true) {
                    val refusal = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray()
                    site.accept().use { it.getOutputStream().write(refusal) }
                }
                // A refusal is still an answer: some sites turn away anything that is not a browser.
                assertTrue(client.answers("http://127.0.0.1:${site.localPort}/", TIMEOUT_MS))
            }
            val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            assertFalse(client.answers("http://127.0.0.1:$closed/", TIMEOUT_MS))
            assertTrue(client.answers("data:text/html,hi", TIMEOUT_MS))
        }
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
