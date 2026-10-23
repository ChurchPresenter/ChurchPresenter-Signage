package org.churchpresenter.signage.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.churchpresenter.signage.core.PlayerMessage
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * Makes the Signage tab's preview: a screenshot of what mpv draws ([shoot] writes it to a file),
 * scaled to the width asked for and saved again as a small JPEG, a few tens of kilobytes.
 */
class PreviewMaker(
    private val shoot: suspend (Path) -> Boolean,
    private val dir: Path,
    private val clock: () -> Instant = Instant::now,
) {
    /** Null when there is nothing to show: mpv is restarting, or the screenshot did not come. */
    suspend fun make(maxWidth: Int): PlayerMessage.Preview? = withContext(Dispatchers.IO) {
        val file = dir.resolve(SCREENSHOT_FILE)
        try {
            file.deleteIfExists()
            val full = if (shoot(file) && file.exists()) ImageIO.read(file.toFile()) else null
            full?.let { image ->
                val small = scale(image, maxWidth)
                PlayerMessage.Preview(
                    jpegBase64 = Base64.getEncoder().encodeToString(jpeg(small)),
                    width = small.width,
                    height = small.height,
                    takenAtMs = clock().toEpochMilli(),
                )
            }
        } catch (e: IOException) {
            Log.warn("Preview failed: ${e.message}")
            null
        } finally {
            file.deleteIfExists()
        }
    }

    companion object {
        private const val SCREENSHOT_FILE = "preview-screenshot.jpg"
        private const val QUALITY = 0.75f

        /** [image] no wider than [maxWidth], its shape kept; a smaller one is left as it is. */
        fun scale(image: BufferedImage, maxWidth: Int): BufferedImage {
            if (image.width <= maxWidth) return image
            val height = maxOf(1, image.height * maxWidth / image.width)
            return BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB).also { small ->
                small.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    drawImage(image, 0, 0, maxWidth, height, null)
                    dispose()
                }
            }
        }

        fun jpeg(image: BufferedImage): ByteArray {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = QUALITY
                }
                writer.write(null, IIOImage(image, null, null), params)
            }
            writer.dispose()
            return out.toByteArray()
        }
    }
}
