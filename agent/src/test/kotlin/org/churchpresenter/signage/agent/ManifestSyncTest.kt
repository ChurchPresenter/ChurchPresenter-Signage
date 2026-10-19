package org.churchpresenter.signage.agent

import kotlinx.coroutines.test.runTest
import org.churchpresenter.signage.core.PROTOCOL_VERSION
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
