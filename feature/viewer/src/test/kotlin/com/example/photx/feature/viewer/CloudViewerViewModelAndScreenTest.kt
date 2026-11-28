package com.example.photx.feature.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.domain.backup.CloudDownloadProgress
import com.example.photx.core.domain.gallery.CloudOriginalResolution
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.domain.gallery.GalleryReconcileSummary
import com.example.photx.core.domain.gallery.GalleryRepository
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.domain.gallery.MediaScanScope
import com.example.photx.core.domain.gallery.ObserveGalleryTimelineUseCase
import com.example.photx.core.domain.gallery.RestoreToDeviceResult
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CloudViewerViewModelAndScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun cloudItem(id: String = "cloud-1") = GalleryMediaItem(
        id = id,
        fileName = "orchid_06.jpg",
        mimeType = "image/jpeg",
        mediaType = MediaType.IMAGE,
        sizeBytes = 4113L,
        width = 300,
        height = 300,
        durationMs = 0L,
        takenAt = 1_700_000_000_000L,
        localUri = null,
        localThumbPath = null,
        sha256 = "abc123",
        syncState = SyncState.CLOUD_ONLY
    )

    private class FakeViewerRepository(item: GalleryMediaItem) : GalleryRepository {
        val itemsFlow = MutableStateFlow(listOf(item))
        var failNextPrepare = false

        override fun observeTotalCount(): Flow<Int> = MutableStateFlow(1)
        override fun observeRecentMedia(limit: Int): Flow<List<GalleryMediaItem>> = itemsFlow
        override suspend fun getMediaById(id: String): GalleryMediaItem? = itemsFlow.value.firstOrNull { it.id == id }
        override fun evaluatePermissionState(): MediaPermissionState = MediaPermissionState.Full
        override suspend fun refreshLocalMedia(scope: MediaScanScope): Outcome<GalleryReconcileSummary, AppError> =
            Outcome.Success(GalleryReconcileSummary(0, 0, 0, 0, 0, 0, 0))

        override suspend fun ensureLocalThumbnail(id: String): Outcome<String, AppError> =
            Outcome.Failure(AppError.Io("unused"))

        override suspend fun recoverInterruptedUploads(requeue: Boolean): Outcome<Int, AppError> = Outcome.Success(0)

        override suspend fun prepareCloudOriginalForViewer(
            id: String,
            onProgress: (CloudDownloadProgress) -> Unit
        ): Outcome<CloudOriginalResolution, AppError> {
            onProgress(CloudDownloadProgress(2048L, 4113L))
            return if (failNextPrepare) {
                failNextPrepare = false
                Outcome.Failure(AppError.Io("Network timeout"))
            } else {
                Outcome.Success(CloudOriginalResolution(id, "/tmp/cloud-1.orig", "abc123", 4113L, false))
            }
        }

        override suspend fun restoreCloudItemToDevice(
            id: String,
            onProgress: (CloudDownloadProgress) -> Unit
        ): Outcome<RestoreToDeviceResult, AppError> =
            Outcome.Success(RestoreToDeviceResult(id, "content://media/external/images/media/77", "orchid_06.jpg"))
    }

    @Test
    fun `given cloud item_when loading fails and retries_then transitions to ready and restores`() = runTest {
        val item = cloudItem()
        val repo = FakeViewerRepository(item).apply { failNextPrepare = true }
        val viewModel = MediaViewerViewModel(
            savedStateHandle = SavedStateHandle(mapOf(MediaViewerViewModel.ARG_MEDIA_ID to item.id)),
            galleryRepository = repo,
            observeTimelineUseCase = ObserveGalleryTimelineUseCase(repo)
        )

        viewModel.ensureCloudOriginalLoaded(item.id)
        val failedState = viewModel.uiState.first { it.cloudOriginalStates[item.id] is CloudOriginalUiState.Failed }
        assertTrue(failedState.cloudOriginalStates[item.id] is CloudOriginalUiState.Failed)

        viewModel.ensureCloudOriginalLoaded(item.id, forceRetry = true)
        val readyState = viewModel.uiState.first { it.cloudOriginalStates[item.id] is CloudOriginalUiState.Ready }
        assertEquals(
            "/tmp/cloud-1.orig",
            (readyState.cloudOriginalStates[item.id] as CloudOriginalUiState.Ready).cachedFilePath
        )

        viewModel.restoreToDevice(item.id)
        val restoredState = viewModel.uiState.first { it.restoreStates[item.id] is CloudRestoreUiState.Completed }
        assertTrue(restoredState.restoreStates[item.id] is CloudRestoreUiState.Completed)
    }

    @Test
    fun `given failed cloud viewer state_when rendered_then displays retry and restore to device`() {
        val item = cloudItem()
        var retried = false
        var restored = false

        composeRule.setContent {
            PhotxTheme {
                MediaViewerScreen(
                    uiState = MediaViewerUiState(
                        items = listOf(item),
                        initialIndex = 0,
                        isLoading = false,
                        cloudOriginalStates = mapOf(item.id to CloudOriginalUiState.Failed("Network error", false))
                    ),
                    onBack = {},
                    onRetryCloudOriginal = { retried = true },
                    onRestoreToDevice = { restored = true }
                )
            }
        }

        composeRule.onNodeWithText("Download failed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Restore to device").performClick()
        assertTrue(retried)
        assertTrue(restored)
    }

    @Test
    fun `given missing ready file on disk_when rendered_then shows retry overlay instead of black screen`() {
        val item = cloudItem()
        var retried = false
        composeRule.setContent {
            PhotxTheme {
                MediaViewerScreen(
                    uiState = MediaViewerUiState(
                        items = listOf(item),
                        initialIndex = 0,
                        isLoading = false,
                        cloudOriginalStates = mapOf(
                            item.id to CloudOriginalUiState.Ready(
                                cachedFilePath = "/nonexistent/path/missing.orig",
                                fromCache = false
                            )
                        )
                    ),
                    onBack = {},
                    onRetryCloudOriginal = { retried = true }
                )
            }
        }
        composeRule.onNodeWithText("Download failed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }
}
