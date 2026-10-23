package org.churchpresenter.signage.agent

import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalPathApi::class)
class PreviewMakerTest {
    private val dir = createTempDirectory("signage-preview")

    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    private val screen = PreviewMaker.jpeg(BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB))

    @Test
    fun theScreenshotComesBackSmall() = runTest {
        val maker = PreviewMaker({ file -> file.writeBytes(screen); true }, dir) { Instant.ofEpochMilli(42) }
        val preview = assertNotNull(maker.make(480))
        assertEquals(480 to 270, preview.width to preview.height)
        assertEquals(42, preview.takenAtMs)
        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(preview.jpegBase64)))
        assertEquals(480, image.width)
        assertFalse(dir.listDirectoryEntries().any { it.exists() }, "the screenshot is not left behind")
    }

    @Test
    fun noScreenshotMeansNoPreview() = runTest {
        assertNull(PreviewMaker({ false }, dir).make(480))
        assertNull(PreviewMaker({ file -> file.writeBytes(byteArrayOf(1, 2, 3)); true }, dir).make(480))
    }

    @Test
    fun aSmallPictureIsNotEnlarged() {
        val small = BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB)
        assertEquals(small, PreviewMaker.scale(small, 480))
    }
}
