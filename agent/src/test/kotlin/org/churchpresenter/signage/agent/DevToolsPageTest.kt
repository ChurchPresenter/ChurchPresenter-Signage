package org.churchpresenter.signage.agent

import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.churchpresenter.signage.core.SignageJson
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A page's DevTools calls: answered by id, and never left waiting on a Chromium that does not answer. */
class DevToolsPageTest {
    private val fromChromium = Channel<Frame>(Channel.UNLIMITED)
    private val toChromium = Channel<Frame>(Channel.UNLIMITED)
    private val page = Chromium.DevToolsPage(fromChromium, toChromium, answerTimeoutMs = TIMEOUT_MS)

    private suspend fun sentId(): Int {
        val sent = SignageJson.parseToJsonElement((toChromium.receive() as Frame.Text).readText()).jsonObject
        return sent.getValue("id").jsonPrimitive.int
    }

    @Test
    fun aCallReturnsTheAnswerWithItsId() = runTest {
        backgroundScope.launch { page.readLoop() }
        val call = backgroundScope.launch {
            val result = page.call("Runtime.evaluate")
            assertEquals("2", result.getValue("value").jsonPrimitive.content)
        }
        val id = sentId()
        fromChromium.send(Frame.Text("""{"id":$id,"result":{"value":2}}"""))
        call.join()
        assertTrue(call.isCompleted)
    }

    @Test
    fun aCallChromiumNeverAnswersFailsInsteadOfWaiting() = runTest {
        backgroundScope.launch { page.readLoop() }
        val failure = assertFailsWith<IOException> { page.call("Page.captureScreenshot") }
        assertTrue(failure.message.orEmpty().startsWith("Page.captureScreenshot: no answer"))
    }

    @Test
    fun aCallOnAPageThatIsGoneFailsAtOnce() = runTest {
        fromChromium.close()
        page.readLoop()
        assertFailsWith<IOException> { page.call("Page.captureScreenshot") }
        toChromium.close()
        assertFailsWith<IOException> { page.call("Runtime.evaluate") }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
