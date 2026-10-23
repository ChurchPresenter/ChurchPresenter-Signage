package org.churchpresenter.signage.agent

import kotlinx.coroutines.test.runTest
import org.churchpresenter.signage.core.Platform
import org.churchpresenter.signage.core.PlayerMessage
import org.churchpresenter.signage.core.ServerMessage
import org.churchpresenter.signage.core.UpdateState
import org.churchpresenter.signage.core.sha256Hex
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class UpdaterTest {
    private val root = createTempDirectory("signage-app")
    private val app = AppFolder(root.resolve("app"))
    private val keys: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val build = "the 0.2.0 bundle".toByteArray()
    private var served = build
    private val reports = mutableListOf<PlayerMessage.UpdateProgress>()
    private var restarted = false
    private var fetched = false

    init {
        root.resolve("app/0.1.0/bin").createDirectories()
        Files.createSymbolicLink(root.resolve("app/current"), Path.of("0.1.0"))
    }

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private fun updater() = Updater(
        app = app,
        platform = Platform.LINUX_X64,
        fetch = { _, consume -> fetched = true; consume(served.inputStream()) },
        report = { reports += it },
        restart = { restarted = true },
        // Stands in for tar: a bundle unpacks to one top folder.
        unpack = { _, into -> into.resolve("churchpresenter-signage/bin").createDirectories() },
        publicKey = Base64.getEncoder().encodeToString(keys.public.encoded),
    )

    private fun index(sha256: String = sha256Hex(build), size: Long = build.size.toLong(), version: String = "0.2.0") =
        """{"version":"$version","protocolMin":1,"protocolMax":1,"assets":""" +
            """{"linux_x64":{"name":"b.tar.gz","size":$size,"sha256":"$sha256"}}}"""

    private fun sign(text: String, pair: KeyPair = keys) = Base64.getEncoder().encodeToString(
        Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(text.toByteArray())
            sign()
        },
    )

    private fun offer(release: String = index(), signature: String = sign(release), version: String = "0.2.0") =
        ServerMessage.UpdateAvailable(version, 1, 1, sha256Hex(build), "/agent/linux_x64", release, signature)

    private fun current() = Files.readSymbolicLink(root.resolve("app/current")).name

    @Test
    fun aSignedBuildIsInstalledBesideTheOldOneAndStartedNext() = runTest {
        updater().install(offer())
        assertEquals(
            listOf(UpdateState.DOWNLOADING, UpdateState.CHECKING, UpdateState.INSTALLING, UpdateState.RESTARTING),
            reports.map { it.state }.distinct(),
        )
        assertEquals("0.2.0", current())
        assertEquals("0.1.0", Files.readSymbolicLink(root.resolve("app/previous")).name)
        assertEquals("0", root.resolve("app/pending").readText())
        assertTrue(root.resolve("app/0.2.0/bin").exists())
        assertTrue(restarted)
    }

    @Test
    fun aReleaseSignedWithAnotherKeyIsRefusedBeforeDownloading() = runTest {
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        updater().install(offer(signature = sign(index(), other)))
        assertEquals(UpdateState.FAILED, reports.single().state)
        assertFalse(fetched)
        assertEquals("0.1.0", current())
    }

    @Test
    fun aBuildThatDoesNotMatchTheSignedHashIsRefused() = runTest {
        served = "something else, same size".toByteArray().copyOf(build.size)
        updater().install(offer())
        assertEquals(UpdateState.FAILED, reports.last().state)
        assertTrue("does not match" in reports.last().message.orEmpty())
        assertEquals("0.1.0", current())
        assertFalse(restarted)
    }

    @Test
    fun aDownloadLargerThanSignedStops() = runTest {
        served = build + build
        updater().install(offer())
        assertTrue("larger" in reports.last().message.orEmpty())
        assertEquals("0.1.0", current())
    }

    @Test
    fun theOfferMustMatchTheSignedRelease() = runTest {
        updater().install(offer(version = "0.3.0"))
        assertTrue("0.2.0, not 0.3.0" in reports.single().message.orEmpty())
        updater().install(offer(release = index(version = "0.1.0"), version = "0.1.0"))
        assertTrue("already installed" in reports.last().message.orEmpty())
    }

    @Test
    fun aPlayerInstalledBeforeUpdatesSaysHowToGetThem() = runTest {
        Files.delete(root.resolve("app/current"))
        updater().install(offer())
        assertTrue("install command" in reports.single().message.orEmpty())
    }

    @Test
    fun theFolderKeepsTwoVersionsAndTheLaunchersMarkers() {
        root.resolve("app/0.0.9").createDirectories()
        root.resolve("app/.download-0.3.0").writeText("partial")
        app.install("0.2.0", root.resolve("app/.staging").createDirectories())
        app.cleanUp()
        assertFalse(root.resolve("app/0.0.9").exists())
        assertFalse(root.resolve("app/.download-0.3.0").exists())
        assertTrue(root.resolve("app/0.1.0").exists() && root.resolve("app/0.2.0").exists())

        app.markHealthy()
        assertFalse(root.resolve("app/pending").exists())
        root.resolve("app/rolled-back").writeText("0.2.0\n")
        assertEquals("0.2.0", app.rolledBackVersion())
        assertEquals("0.2.0", app.rolledBackVersion(), "looking does not take it")
        assertEquals("0.2.0", app.takeRolledBack())
        assertNull(app.takeRolledBack())
    }
}
