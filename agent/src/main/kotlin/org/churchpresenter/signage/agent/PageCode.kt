package org.churchpresenter.signage.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A church's own [css] and [script] for a web item's pages (`pageCss`, `pageScript`), as the scripts
 * the browser runs on every document it opens, in the top frame only:
 * - the CSS goes in as a constructed style sheet after the site's own, which a site's
 *   Content-Security-Policy does not block, and goes back in if the page drops it;
 * - the script runs once the document is parsed, in a function of its own: an error in it is logged
 *   to the page's console, and one that does not even parse stops only itself.
 */
data class PageCode(val css: String?, val script: String?) {
    /** The scripts for `Page.addScriptToEvaluateOnNewDocument`, the CSS's first. */
    fun scripts(): List<String> = listOfNotNull(css?.let(::styleScript), script?.let(::runScript))

    companion object {
        /** The code a web item asked for; null when it asked for none. */
        fun of(css: String?, script: String?): PageCode? {
            val style = css?.takeIf(String::isNotBlank)
            val run = script?.takeIf(String::isNotBlank)
            return if (style == null && run == null) null else PageCode(style, run)
        }

        private fun styleScript(css: String) = """
            (() => {
              if (window.top !== window) return;
              let sheet = null;
              const put = () => {
                try {
                  if (!sheet) { sheet = new CSSStyleSheet(); sheet.replaceSync(${JsonPrimitive(css)}); }
                  if (!document.adoptedStyleSheets.includes(sheet)) {
                    document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
                  }
                } catch (e) { console.error('ChurchPresenter page CSS:', e); }
              };
              put();
              document.addEventListener('DOMContentLoaded', put);
              window.addEventListener('load', put);
            })();
        """.trimIndent()

        // The church's script is put in as written, on lines of its own, so a comment on its last line
        // cannot swallow what closes it.
        private fun runScript(script: String) = "(() => {\n" +
            "  if (window.top !== window) return;\n" +
            "  const run = function () {\n$script\n};\n" +
            "  const go = () => { try { run(); } catch (e) { console.error('ChurchPresenter page script:', e); } };\n" +
            "  if (document.readyState !== 'loading') go();\n" +
            "  else document.addEventListener('DOMContentLoaded', go, { once: true });\n" +
            "})();"
    }
}

/** Has every document this page opens from now on run [code]'s scripts; nothing when it is null. */
suspend fun WebRenderer.Page.addPageCode(code: PageCode?) {
    code?.scripts()?.forEach { source ->
        call("Page.addScriptToEvaluateOnNewDocument", buildJsonObject { put("source", source) })
    }
}
