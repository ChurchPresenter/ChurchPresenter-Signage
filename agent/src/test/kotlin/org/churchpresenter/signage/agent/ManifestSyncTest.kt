package org.churchpresenter.signage.agent

import kotlinx.coroutines.test.runTest
import org.churchpresenter.signage.core.PROTOCOL_VERSION
import java.io.InputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class ManifestSyncTest {
    private val setup = pairedStore()
    private val root = setup.first
    private val store = setup.second

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    @Test
    fun aNewManifestArrivesWithItsFiles() = runTest {
        val source = FakeSource("v1", manifestOf(pictureA, pictureB), listOf(pictureA, pictureB))
        assertEquals(SyncResult.Updated("v1", 2), ManifestSync(store, source).sync())
        assertEquals("v1", store.manifest()?.version)
        assertEquals(SyncResult.UpToDate, ManifestSync(store, source).sync())
    }

    @Test
    fun onlyMissingFilesAreDownloaded() = runTest {
        val source = FakeSource("v1", manifestOf(pictureA), listOf(pictureA, pictureB))
        ManifestSync(store, source).sync()
        source.version = "v2"
        source.manifest = manifestOf(pictureA, pictureB)
        source.downloads.clear()
        assertEquals(SyncResult.Updated("v2", 1), ManifestSync(store, source).sync())
        assertEquals(listOf(pictureB.sha), source.downloads)
    }

    /** v1 then v2 synced: [pictureA] is kept only for the previous set, [pictureB] plays. */
    private suspend fun twoSets(store: PlayerStore): FakeSource {
        val source = FakeSource("v1", manifestOf(pictureA), listOf(pictureA, pictureB, pictureC))
        ManifestSync(store, source).sync()
        source.version = "v2"
        source.manifest = manifestOf(pictureB)
        ManifestSync(store, source).sync()
        assertTrue(store.files.has(pictureA.sha) && store.files.has(pictureB.sha))
        return source
    }

    /** Syncs v3 ([pictureC]) and says whether the previous set's [pictureA] was there as it downloaded. */
    private suspend fun previousDuringDownload(store: PlayerStore, source: FakeSource): Boolean {
        source.version = "v3"
        source.manifest = manifestOf(pictureC)
        var kept = false
        val watching = object : ContentSource by source {
            override suspend fun <T> file(sha256: String, consume: (InputStream) -> T): T {
                kept = store.files.has(pictureA.sha)
                return source.file(sha256, consume)
            }
        }
        assertEquals(SyncResult.Updated("v3", 1), ManifestSync(store, watching).sync())
        return kept
    }

    @Test
    fun aScreenShortOfRoomLetsThePreviousSetGoBeforeItDownloads() = runTest {
        val (tight, tightStore) = pairedStore(freeBytes = { 0 })
        try {
            val source = twoSets(tightStore)
            assertFalse(previousDuringDownload(tightStore, source))
            // What played while it downloaded stayed; it is the previous set now.
            assertTrue(tightStore.files.has(pictureB.sha) && tightStore.files.has(pictureC.sha))
        } finally {
            tight.deleteRecursively()
        }
    }

    @Test
    fun aScreenWithRoomKeepsThePreviousSetWhileItDownloads() = runTest {
        assertTrue(previousDuringDownload(store, twoSets(store)))
        assertEquals(0, store.makeRoom(0))
    }

    @Test
    fun aFailedDownloadLeavesTheOldManifestPlaying() = runTest {
        val source = FakeSource("v1", manifestOf(pictureA), listOf(pictureA))
        ManifestSync(store, source).sync()

        source.version = "v2"
        source.manifest = manifestOf(pictureA, pictureB)
        assertIs<SyncResult.Failed>(ManifestSync(store, source).sync())
        assertEquals("v1", store.manifest()?.version)

        source.files[pictureB.sha] = pictureC.bytes
        assertIs<SyncResult.Failed>(ManifestSync(store, source).sync())
        assertEquals("v1", store.manifest()?.version)

        source.failManifest = true
        assertIs<SyncResult.Failed>(ManifestSync(store, source).sync())
    }

    @Test
    fun aManifestFromAnotherOwnerOrANewerProtocolIsRefused() = runTest {
        val stranger = FakeSource("v1", manifestOf(pictureA, owner = "someone-else"), listOf(pictureA))
        assertIs<SyncResult.Failed>(ManifestSync(store, stranger).sync())
        val future = FakeSource("v1", manifestOf(pictureA).copy(protocol = PROTOCOL_VERSION + 1), listOf(pictureA))
        assertIs<SyncResult.Failed>(ManifestSync(store, future).sync())
        assertEquals(null, store.manifest())
    }
}
