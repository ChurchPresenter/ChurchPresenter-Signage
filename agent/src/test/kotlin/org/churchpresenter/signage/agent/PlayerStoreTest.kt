package org.churchpresenter.signage.agent

import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.churchpresenter.signage.core.OverrideAction
import org.churchpresenter.signage.core.ScreenOverride

@OptIn(ExperimentalPathApi::class)
class PlayerStoreTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    @Test
    fun theIdentityIsMadeOnceAndKept() {
        val fresh = kotlin.io.path.createTempDirectory("signage-id")
        try {
            val id = PlayerStore(fresh).identity().playerId
            assertEquals(id, PlayerStore(fresh).identity().playerId)
            assertFalse(PlayerStore(fresh).identity().isPaired)
        } finally {
            fresh.deleteRecursively()
        }
        assertTrue(store.identity().isPaired)
        assertFalse(store.identity().released().isPaired)
    }

    @Test
    fun aFileIsKeptOnlyWhenItMatchesItsHash() {
        assertTrue(store.files.put(pictureA.sha, pictureA.bytes.inputStream()))
        assertTrue(store.files.has(pictureA.sha))
        assertNotNull(store.files.path(pictureA.sha))

        assertFalse(store.files.put(pictureB.sha, pictureC.bytes.inputStream()))
        assertFalse(store.files.has(pictureB.sha))
        assertEquals(listOf(pictureA.sha), root.resolve("files").listDirectoryEntries().map { it.fileName.toString() })

        assertFailsWith<IllegalArgumentException> { store.files.put("../escape", pictureA.bytes.inputStream()) }
        assertNull(store.files.path("../identity.json"))
    }

    @Test
    fun aManifestIsCommittedOnlyWithAllItsFiles() {
        val manifest = StoredManifest("v1", manifestOf(pictureA, pictureB))
        store.files.put(pictureA.sha, pictureA.bytes.inputStream())
        assertFalse(store.commit(manifest))
        assertNull(store.manifest())

        store.files.put(pictureB.sha, pictureB.bytes.inputStream())
        assertTrue(store.commit(manifest))
        assertEquals(manifest, store.manifest())
    }

    @Test
    fun garbageCollectionKeepsTheCurrentAndPreviousManifestsFiles() {
        listOf(pictureA, pictureB, pictureC).forEach { store.files.put(it.sha, it.bytes.inputStream()) }
        root.resolve("files/.part-stale").writeText("half")
        assertTrue(store.commit(StoredManifest("v1", manifestOf(pictureA))))
        assertTrue(store.commit(StoredManifest("v2", manifestOf(pictureB))))

        assertEquals(2, store.collectGarbage())
        assertTrue(store.files.has(pictureA.sha))
        assertTrue(store.files.has(pictureB.sha))
        assertFalse(store.files.has(pictureC.sha))

        assertTrue(store.commit(StoredManifest("v3", manifestOf(pictureB))))
        store.collectGarbage()
        assertFalse(store.files.has(pictureA.sha), "v1 is two manifests back now")
    }

    @Test
    fun overridesAreSavedAndCleared() {
        val override = ScreenOverride(OverrideAction.TvOff, setAtMs = 5)
        store.saveOverride(override)
        assertEquals(override, PlayerStore(root).override())
        store.saveOverride(null)
        assertNull(store.override())
    }

    @Test
    fun clearingContentForgetsManifestsOverrideAndFiles() {
        store.files.put(pictureA.sha, pictureA.bytes.inputStream())
        store.commit(StoredManifest("v1", manifestOf(pictureA)))
        store.saveOverride(ScreenOverride(OverrideAction.TvOff, setAtMs = 5))
        store.clearContent()
        assertNull(store.manifest())
        assertNull(store.override())
        assertFalse(store.files.has(pictureA.sha))
        assertTrue(store.freeBytes() > 0)
    }

    @Test
    fun anUnreadableFileReadsAsAbsent() {
        root.resolve("manifest.json").writeText("{ not json")
        assertNull(store.manifest())
        assertTrue(root.resolve("manifest.json").exists())
    }

    @Test
    fun aFactoryResetForgetsEverythingButTheAddressAndTheApp() {
        store.files.put(pictureA.sha, pictureA.bytes.inputStream())
        store.commit(StoredManifest("v1", manifestOf(pictureA)))
        store.saveIdentity(store.identity().copy(fixedAddress = "10.0.0.5:8765"))
        val app = root.resolve("app/0.1.0").also { it.toFile().mkdirs() }
        val before = store.identity().playerId

        store.factoryReset()

        val after = store.identity()
        assertFalse(after.isPaired)
        assertTrue(after.playerId != before, "a new player id, so it pairs as a new screen")
        assertEquals("10.0.0.5:8765", after.fixedAddress)
        assertNull(store.manifest())
        assertFalse(store.files.has(pictureA.sha))
        assertTrue(app.exists())
    }
}
