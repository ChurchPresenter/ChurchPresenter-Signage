package org.churchpresenter.signage.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.writeBytes

/**
 * The kiosk browser's page, as [KioskSession] drives it over DevTools: the kiosk's own script on every
 * document, the page's requests to open other pages, the keyboard's key presses, and calls into the
 * script.
 */
class KioskPage(private val page: WebRenderer.Page) {
    /** The main frame's id, once [prepare] has run; a request from any other frame is not checked. */
    private var mainFrame: String? = null

    /** Gives every document the kiosk's script and binding, and with [navigation] checks where it goes. */
    suspend fun prepare(navigation: KioskNavigation?) {
        page.call("Page.enable")
        page.call("Runtime.enable")
        page.call("Runtime.addBinding", buildJsonObject { put("name", BINDING) })
        page.call("Page.addScriptToEvaluateOnNewDocument", buildJsonObject { put("source", script) })
        if (navigation != null) page.call("Fetch.enable", documentRequests())
        mainFrame = page.call("Page.getFrameTree")["frameTree"]?.jsonObject
            ?.get("frame")?.jsonObject?.string("id")
    }

    suspend fun open(url: String) {
        page.call("Page.navigate", buildJsonObject { put("url", url) })
    }

    /** A page the main frame is about to open: its own site and allowed ones go ahead, others get a notice. */
    suspend fun request(params: JsonObject, navigation: KioskNavigation?) {
        val id = params.string("requestId") ?: return
        val url = params["request"]?.jsonObject?.string("url").orEmpty()
        val mine = params.string("frameId") == mainFrame
        if (navigation == null || !mine || navigation.allows(url)) {
            page.send("Fetch.continueRequest", buildJsonObject { put("requestId", id) })
            return
        }
        Log.info("The interactive page tried to leave its sites; not opened")
        page.send(
            "Fetch.fulfillRequest",
            buildJsonObject {
                put("requestId", id)
                put("responseCode", HTTP_OK)
                putJsonArray("responseHeaders") {
                    addJsonObject {
                        put("name", "Content-Type")
                        put("value", "text/html; charset=utf-8")
                    }
                }
                put("body", Base64.getEncoder().encodeToString(NOT_HERE_PAGE.toByteArray()))
            },
        )
    }

    /** A key of the on-screen keyboard, typed into the page as a real key press. */
    suspend fun key(key: String) {
        val special = SPECIAL_KEYS[key]
        if (special == null) {
            page.call("Input.insertText", buildJsonObject { put("text", key) })
            return
        }
        val (code, text) = special
        page.call("Input.dispatchKeyEvent", keyEvent(if (text != null) "keyDown" else "rawKeyDown", key, code, text))
        page.call("Input.dispatchKeyEvent", keyEvent("keyUp", key, code, null))
    }

    /** Writes what the page shows now to [file] as a JPEG; false when it could not. */
    suspend fun screenshot(file: Path): Boolean = try {
        val shot = page.call(
            "Page.captureScreenshot",
            buildJsonObject {
                put("format", "jpeg")
                put("quality", SCREENSHOT_QUALITY)
            },
        ).string("data")
        shot?.let { file.writeBytes(Base64.getDecoder().decode(it)) } != null
    } catch (e: IOException) {
        Log.warn("The interactive page gave no picture: ${e.message}")
        false
    }

    /** Calls the kiosk script's [call] (`countdown(3)`); a page between documents just misses it. */
    suspend fun evaluate(call: String) {
        try {
            page.call("Runtime.evaluate", buildJsonObject { put("expression", "window.$SCRIPT_OBJECT?.$call") })
        } catch (e: IOException) {
            Log.warn("The interactive page did not take $call: ${e.message}")
        }
    }

    private fun keyEvent(type: String, key: String, code: Int, text: String?) = buildJsonObject {
        put("type", type)
        put("key", key)
        put("code", key)
        put("windowsVirtualKeyCode", code)
        text?.let { put("text", it) }
    }

    private fun documentRequests() = buildJsonObject {
        putJsonArray("patterns") {
            addJsonObject {
                put("urlPattern", "*")
                put("resourceType", "Document")
                put("requestStage", "Request")
            }
        }
    }

    companion object {
        /** The function the kiosk script calls with its messages. */
        const val BINDING = "cpKiosk"
        const val SCRIPT_OBJECT = "__cpKiosk"
        private const val HTTP_OK = 200
        private const val SCREENSHOT_QUALITY = 80
        private const val BACKSPACE = 8
        private const val TAB = 9
        private const val ENTER = 13

        /** Keys that are not text: their virtual key code, and the text one types (Enter's new line). */
        private val SPECIAL_KEYS = mapOf(
            "Backspace" to (BACKSPACE to null),
            "Enter" to (ENTER to "\r"),
            "Tab" to (TAB to null),
        )

        private val NOT_HERE_PAGE = """
            <!doctype html><meta name="viewport" content="width=device-width">
            <body style="margin:0;display:flex;align-items:center;justify-content:center;height:100vh;
              font:28px sans-serif;background:#111;color:#eee">That page is not available here.
            <script>setTimeout(() => history.back(), 2500)</script>
        """.trimIndent()

        /** The script every page gets: the on-screen keyboard, the countdown, the PIN pad and touch reports. */
        val script: String by lazy {
            checkNotNull(KioskPage::class.java.getResource("/kiosk-page.js")) { "kiosk-page.js is missing" }.readText()
        }
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
