package com.example.photx.core.domain.gallery

import app.cash.turbine.test
import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryUseCasesTest {
    @Test
    fun `given denied permission_when refreshing local media_then returns unauthorized without calling repo`() =
        runTest {
            val repo = FakeGalleryRepository().apply {
                permissionState = MediaPermissionState.Denied
            }
            val useCase = RefreshLocalMediaUseCase(repo)

            val result = useCase()

            assertTrue(result is Outcome.Failure)
            assertTrue((result as Outcome.Failure).error is AppError.Unauthorized)
            assertEquals(0, repo.refreshCalls)
            assertFalse(repo.permissionState.canScanDeviceMedia)
            assertFalse(repo.permissionState.allowsExternalDeletionReconciliation)
        }

    @Test
    fun `given partial visual selection_when checking policies_then scans without deletion prune`() = runTest {
        val partial = MediaPermissionState.Partial(
            imagesGranted = false,
            videosGranted = false,
            userSelectedOnly = true
        )
        val repo = FakeGalleryRepository().apply {
            permissionState = partial
        }
        val customScope = MediaScanScope(setOf("DCIM/Camera", "Pictures/Screenshots"))
        val useCase = RefreshLocalMediaUseCase(repo)

        val result = useCase(scope = customScope)

        assertTrue(result is Outcome.Success)
        assertEquals(1, repo.refreshCalls)
        assertEquals(customScope, repo.lastScope)
        assertTrue(partial.canScanDeviceMedia)
        assertFalse(partial.allowsExternalDeletionReconciliation)
    }

    @Test
    fun `given repository items_when observing timeline use case_then streams bounded items and total count`() =
        runTest {
            val repo = FakeGalleryRepository()
            val useCase = ObserveGalleryTimelineUseCase(repo)

            useCase(limit = 1).test {
                assertEquals(emptyList<GalleryMediaItem>(), awaitItem())
                val first = sampleDomainItem("id-2", takenAt = 2_000L)
                val second = sampleDomainItem("id-1", takenAt = 1_000L)
                repo.itemsFlow.value = listOf(first, second)
                assertEquals(listOf(first), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            useCase.observeTotalCount().test {
                assertEquals(2, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun sampleDomainItem(id: String, takenAt: Long): GalleryMediaItem = GalleryMediaItem(
        id = id,
        mediaType = MediaType.IMAGE,
        fileName = "$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        width = 1920,
        height = 1080,
        durationMs = 0L,
        takenAt = takenAt,
        syncState = SyncState.LOCAL_ONLY
    )
}
