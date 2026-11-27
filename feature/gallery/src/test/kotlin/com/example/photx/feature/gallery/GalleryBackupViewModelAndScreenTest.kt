package com.example.photx.feature.gallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import com.example.photx.core.data.GalleryPagingRepository
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.domain.auth.AuthRepository
import com.example.photx.core.domain.auth.AuthState
import com.example.photx.core.domain.backup.BackupScheduler
import com.example.photx.core.domain.gallery.ActiveUploadRegistry
import com.example.photx.core.domain.gallery.ActiveUploadSnapshot
import com.example.photx.core.domain.gallery.BackupExecutionState
import com.example.photx.core.domain.gallery.BackupStatusCounts
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.domain.gallery.GalleryReconcileSummary
import com.example.photx.core.domain.gallery.GalleryRepository
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.domain.gallery.MediaScanScope
import com.example.photx.core.domain.gallery.ObserveGalleryTimelineUseCase
import com.example.photx.core.domain.gallery.RefreshLocalMediaUseCase
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GalleryBackupViewModelAndScreenTest {
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

    @Test
    fun `given selection_when queueing backup_then handles empty eligible and scheduler failure`() = runTest {
        val repo = FakeGalleryRepository(
            listOf(
                media("local-1", SyncState.LOCAL_ONLY, "content://media/1"),
                media("synced-1", SyncState.SYNCED, "content://media/2")
            )
        )
        val scheduler = FakeBackupScheduler()
        val registry = FakeActiveUploadRegistry()
        val vm = createViewModel(repo, scheduler, registry)
        val collectJob = backgroundScope.launch(testDispatcher) { vm.uiState.collect() }
        val recentJob = backgroundScope.launch(testDispatcher) { vm.recentItems.collect() }

        // 1. Empty selection
        vm.enterSelectionMode()
        vm.queueSelectedForBackup()
        assertTrue(vm.uiState.value.backupErrorMessage?.contains("Select at least one") == true)
        assertTrue(scheduler.enqueuedBatches.isEmpty())

        // 2. Mixed selection queues only eligible LOCAL_ONLY item
        vm.toggleSelection("local-1")
        vm.toggleSelection("synced-1")
        vm.queueSelectedForBackup()
        assertEquals(listOf(listOf("local-1")), scheduler.enqueuedBatches)
        assertFalse(vm.uiState.value.isSelectionMode)
        assertTrue(vm.uiState.value.backupFeedbackMessage?.contains("Queued 1 item(s)") == true)

        // 3. Scheduler failure surfaces error and preserves selection
        scheduler.shouldThrow = true
        vm.toggleSelection("local-1")
        vm.queueSelectedForBackup()
        assertEquals("Scheduler offline", vm.uiState.value.backupErrorMessage)
        assertTrue(vm.uiState.value.isSelectionMode)
        collectJob.cancel()
    }

    @Test
    fun `given registry state updates_when observing uiState_then reflects live execution states`() = runTest {
        val repo = FakeGalleryRepository(listOf(media("m1", SyncState.QUEUED, "content://media/1")))
        val scheduler = FakeBackupScheduler()
        val registry = FakeActiveUploadRegistry()
        val vm = createViewModel(repo, scheduler, registry)
        val collectJob = backgroundScope.launch(testDispatcher) { vm.uiState.collect() }
        val recentJob = backgroundScope.launch(testDispatcher) { vm.recentItems.collect() }

        assertEquals(BackupExecutionState.Idle, vm.uiState.value.backupExecutionState)
        registry.state.value = BackupExecutionState.Running("m1")
        assertEquals(BackupExecutionState.Running("m1"), vm.uiState.value.backupExecutionState)
        registry.state.value = BackupExecutionState.WaitingForReconciliation(listOf("m1"))
        assertEquals(
            BackupExecutionState.WaitingForReconciliation(listOf("m1")),
            vm.uiState.value.backupExecutionState
        )
        registry.state.value = BackupExecutionState.Completed
        assertEquals(BackupExecutionState.Completed, vm.uiState.value.backupExecutionState)
        collectJob.cancel()
    }

    @Test
    fun `given backup screen reconciliation_when rendered in dark theme_then renders honestly`() {
        val reconState = GalleryUiState(
            permissionState = MediaPermissionState.Full,
            accountLabel = "@PrinceJr",
            totalIndexedCount = 3,
            backupSummary = BackupSummaryUiState(
                totalCount = 3,
                localOnlyCount = 1,
                queuedCount = 1,
                syncedCount = 0,
                waitingForReconciliationCount = 1
            ),
            backupExecutionState = BackupExecutionState.WaitingForReconciliation(listOf("u1"))
        )
        composeRule.setContent {
            PhotxTheme(darkTheme = true) {
                BackupScreen(uiState = reconState, onOpenAuth = {}, onRetryScan = {})
            }
        }
        composeRule.onNodeWithText("Total local & indexed media: 3").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Waiting for Reconciliation"))
        composeRule.onNodeWithText("Waiting for Reconciliation").assertIsDisplayed()
    }

    @Test
    fun `given backup screen empty queue_when rendered in light theme_then renders honestly`() {
        var clickedBackup = false
        composeRule.setContent {
            PhotxTheme(darkTheme = false) {
                BackupScreen(
                    uiState = GalleryUiState(
                        permissionState = MediaPermissionState.Full,
                        totalIndexedCount = 2,
                        backupSummary = BackupSummaryUiState(totalCount = 2, localOnlyCount = 2),
                        backupExecutionState = BackupExecutionState.Idle
                    ),
                    onOpenAuth = { clickedBackup = true },
                    onRetryScan = {}
                )
            }
        }
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("No backups queued yet"))
        composeRule.onNodeWithText("Engine Idle").assertIsDisplayed()
        composeRule.onNodeWithText("No backups queued yet").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Sign In to Telegram"))
        composeRule.onNodeWithText("Sign In to Telegram").performClick()
        assertTrue(clickedBackup)
    }

    private fun createViewModel(
        repo: FakeGalleryRepository,
        scheduler: FakeBackupScheduler,
        registry: FakeActiveUploadRegistry
    ): GalleryViewModel = GalleryViewModel(
        galleryRepository = repo,
        pagingRepository = FakePagingRepository(),
        observeTimelineUseCase = ObserveGalleryTimelineUseCase(repo),
        refreshLocalMediaUseCase = RefreshLocalMediaUseCase(repo),
        backupScheduler = scheduler,
        activeUploadRegistry = registry,
        authRepository = FakeAuthRepository()
    )

    private fun media(id: String, syncState: SyncState, uri: String?): GalleryMediaItem = GalleryMediaItem(
        id = id,
        localUri = uri,
        localThumbPath = null,
        mediaType = MediaType.IMAGE,
        fileName = "$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        width = 100,
        height = 100,
        durationMs = 0L,
        takenAt = 1000L,
        syncState = syncState
    )

    private class FakeBackupScheduler : BackupScheduler {
        val enqueuedBatches = mutableListOf<List<String>>()
        var shouldThrow = false

        override suspend fun enqueueBackup(mediaIds: List<String>) {
            if (shouldThrow) error("Scheduler offline")
            enqueuedBatches.add(mediaIds)
        }

        override suspend fun scheduleSequentialDrain(replaceIfTail: Boolean) = Unit

        override suspend fun pauseBackup() = Unit
    }

    private class FakeActiveUploadRegistry : ActiveUploadRegistry {
        val state = MutableStateFlow<BackupExecutionState>(BackupExecutionState.Idle)

        override suspend fun queryActiveUploads(): ActiveUploadSnapshot =
            ActiveUploadSnapshot.Authoritative(emptySet(), 0L)

        override fun observeExecutionState(): Flow<BackupExecutionState> = state
    }

    private class FakeGalleryRepository(items: List<GalleryMediaItem>) : GalleryRepository {
        private val itemsFlow = MutableStateFlow(items)

        override fun observeTotalCount(): Flow<Int> = MutableStateFlow(itemsFlow.value.size)

        override fun observeBackupStatusCounts(): Flow<BackupStatusCounts> = MutableStateFlow(
            BackupStatusCounts(
                totalCount = itemsFlow.value.size,
                localOnlyCount = itemsFlow.value.count { it.syncState == SyncState.LOCAL_ONLY },
                queuedCount = itemsFlow.value.count { it.syncState == SyncState.QUEUED },
                syncedCount = itemsFlow.value.count { it.syncState == SyncState.SYNCED }
            )
        )

        override fun observeRecentMedia(limit: Int): Flow<List<GalleryMediaItem>> = itemsFlow

        override suspend fun getMediaById(id: String): GalleryMediaItem? = itemsFlow.value.firstOrNull { it.id == id }

        override fun evaluatePermissionState(): MediaPermissionState = MediaPermissionState.Full

        override suspend fun refreshLocalMedia(scope: MediaScanScope): Outcome<GalleryReconcileSummary, AppError> =
            Outcome.Success(GalleryReconcileSummary(0, 0, 0, 0, 0, 0, 0))

        override suspend fun ensureLocalThumbnail(id: String): Outcome<String, AppError> =
            Outcome.Failure(AppError.Io("Unused"))

        override suspend fun recoverInterruptedUploads(requeue: Boolean): Outcome<Int, AppError> = Outcome.Success(0)
    }

    private class FakePagingRepository : GalleryPagingRepository {
        override fun observePagedTimeline(pageSize: Int) =
            MutableStateFlow(androidx.paging.PagingData.empty<GalleryMediaItem>())
    }

    private class FakeAuthRepository : AuthRepository {
        override val authState: StateFlow<AuthState> = MutableStateFlow(
            AuthState.Authenticated("@PrinceJr")
        ).asStateFlow()

        override suspend fun initialize(): Outcome<Unit, AppError> = Outcome.Success(Unit)

        override suspend fun submitPhoneNumber(phoneNumber: String): Outcome<Unit, AppError> = Outcome.Success(Unit)

        override suspend fun submitAuthenticationCode(code: String): Outcome<Unit, AppError> = Outcome.Success(Unit)

        override suspend fun submitPassword(password: String): Outcome<Unit, AppError> = Outcome.Success(Unit)

        override suspend fun logOut(): Outcome<Unit, AppError> = Outcome.Success(Unit)
    }
}
