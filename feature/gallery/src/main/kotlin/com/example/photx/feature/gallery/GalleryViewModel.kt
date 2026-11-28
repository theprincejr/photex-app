package com.example.photx.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.example.photx.core.common.Outcome
import com.example.photx.core.data.GalleryPagingRepository
import com.example.photx.core.domain.auth.AuthRepository
import com.example.photx.core.domain.auth.AuthState
import com.example.photx.core.domain.backup.BackupScheduler
import com.example.photx.core.domain.gallery.ActiveUploadRegistry
import com.example.photx.core.domain.gallery.DeviceFolderSummary
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.domain.gallery.GalleryRepository
import com.example.photx.core.domain.gallery.ObserveGalleryTimelineUseCase
import com.example.photx.core.domain.gallery.RefreshLocalMediaUseCase
import com.example.photx.core.model.SyncState
import com.example.photx.core.ui.PhotxAlbumCardUiModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val RECENT_WINDOW_LIMIT = 500
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * Coordinates offline-first Room Paging 3 timeline, MediaStore reconciliation, permissions,
 * album grouping, Room backup counts, and local search.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
public class GalleryViewModel @Inject constructor(
    private val galleryRepository: GalleryRepository,
    pagingRepository: GalleryPagingRepository,
    observeTimelineUseCase: ObserveGalleryTimelineUseCase,
    private val refreshLocalMediaUseCase: RefreshLocalMediaUseCase,
    private val backupScheduler: BackupScheduler,
    activeUploadRegistry: ActiveUploadRegistry,
    authRepository: AuthRepository
) : ViewModel() {
    private val localState = MutableStateFlow(
        LocalMutableState(permissionState = galleryRepository.evaluatePermissionState())
    )

    public val pagedTimeline: Flow<PagingData<GalleryTimelineUiModel>> =
        pagingRepository.observePagedTimeline()
            .map { pagingData ->
                val today = LocalDate.now()
                pagingData
                    .map { item -> GalleryTimelineUiModel.MediaItem(item = item, tile = item.toTileUiModel()) }
                    .insertSeparators { before, after ->
                        val afterItem = after ?: return@insertSeparators null
                        val afterDate = formatDateSectionKey(afterItem.item.takenAt)
                        val beforeDate = before?.let { formatDateSectionKey(it.item.takenAt) }
                        if (beforeDate == null || beforeDate != afterDate) {
                            GalleryTimelineUiModel.DateHeader(
                                dateKey = afterDate.toString(),
                                title = formatDateHeaderTitle(date = afterDate, today = today)
                            )
                        } else {
                            null
                        }
                    }
            }
            .cachedIn(viewModelScope)

    public val recentItems: StateFlow<List<GalleryMediaItem>> =
        observeTimelineUseCase(limit = RECENT_WINDOW_LIMIT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    public val albums: StateFlow<List<Pair<GalleryAlbumId, PhotxAlbumCardUiModel>>> =
        recentItems
            .map(::deriveAlbums)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    public val searchResults: StateFlow<List<GalleryMediaItem>> =
        combine(recentItems, localState) { items, state ->
            filterSearchItems(items, state.searchQuery)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    public val deviceFolders: StateFlow<List<DeviceFolderSummary>> =
        galleryRepository.observeDeviceFolders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    public val uiState: StateFlow<GalleryUiState> = combine(
        localState,
        observeTimelineUseCase.observeTotalCount(),
        galleryRepository.observeBackupStatusCounts(),
        activeUploadRegistry.observeExecutionState(),
        authRepository.authState
    ) { local, totalCount, backupCounts, executionState, authState ->
        val accountLabel = (authState as? AuthState.Authenticated)
            ?.accountDisplayLabel
            ?.takeIf { it.isNotBlank() }
            ?: if (authState is AuthState.Authenticated) "Connected" else null
        GalleryUiState(
            permissionState = local.permissionState,
            isRefreshing = local.isRefreshing,
            refreshErrorMessage = local.refreshErrorMessage,
            totalIndexedCount = totalCount,
            selectedIds = local.selectedIds,
            isSelectionMode = local.isSelectionMode,
            searchQuery = local.searchQuery,
            accountLabel = accountLabel,
            backupSummary = backupCounts.toUiState(),
            backupExecutionState = executionState,
            backupFeedbackMessage = local.backupFeedbackMessage,
            backupErrorMessage = local.backupErrorMessage,
            pendingDeleteUris = local.pendingDeleteUris
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = GalleryUiState(permissionState = galleryRepository.evaluatePermissionState())
    )

    init {
        refreshPermissionsAndMedia()
    }

    public fun refreshPermissionsAndMedia() {
        val currentPermission = galleryRepository.evaluatePermissionState()
        localState.update { it.copy(permissionState = currentPermission) }
        if (!currentPermission.canScanDeviceMedia || localState.value.isRefreshing) {
            return
        }
        viewModelScope.launch {
            localState.update { it.copy(isRefreshing = true, refreshErrorMessage = null) }
            when (val result = refreshLocalMediaUseCase()) {
                is Outcome.Success -> localState.update { it.copy(isRefreshing = false, refreshErrorMessage = null) }

                is Outcome.Failure -> localState.update {
                    it.copy(isRefreshing = false, refreshErrorMessage = result.error.message)
                }
            }
        }
    }

    public fun toggleSelection(itemId: String) {
        localState.update { state ->
            val updated = if (itemId in state.selectedIds) {
                state.selectedIds - itemId
            } else {
                state.selectedIds + itemId
            }
            state.copy(selectedIds = updated, isSelectionMode = updated.isNotEmpty())
        }
    }

    public fun enterSelectionMode() {
        localState.update { it.copy(isSelectionMode = true) }
    }

    public fun selectAllVisible() {
        val visibleIds = recentItems.value.mapTo(LinkedHashSet()) { it.id }
        localState.update { it.copy(selectedIds = visibleIds, isSelectionMode = true) }
    }

    public fun clearSelection() {
        localState.update { it.copy(selectedIds = emptySet(), isSelectionMode = false) }
    }

    public fun updateSearchQuery(query: String) {
        localState.update { it.copy(searchQuery = query) }
    }

    public fun clearBackupMessages() {
        localState.update { it.copy(backupFeedbackMessage = null, backupErrorMessage = null) }
    }

    public fun ensureCloudThumbnail(itemId: String) {
        viewModelScope.launch {
            galleryRepository.ensureCloudThumbnail(itemId)
        }
    }

    public fun discoverCloudMedia() {
        viewModelScope.launch {
            when (val result = galleryRepository.discoverCloudOnlyMedia()) {
                is Outcome.Success -> {
                    val s = result.value
                    localState.update {
                        it.copy(
                            backupErrorMessage = null,
                            backupFeedbackMessage = "Cloud sync: ${s.discoveredCount} PhotX backup(s), " +
                                "${s.insertedCloudOnlyCount} new cloud-only."
                        )
                    }
                }

                is Outcome.Failure -> localState.update {
                    it.copy(backupFeedbackMessage = null, backupErrorMessage = result.error.message)
                }
            }
        }
    }

    public fun requestVerifiedLocalDeletion() {
        val currentIds = localState.value.selectedIds
        if (currentIds.isEmpty()) {
            localState.update {
                it.copy(backupErrorMessage = "Select at least one backed-up item to remove its local copy.")
            }
            return
        }
        viewModelScope.launch {
            val recentById = recentItems.value.associateBy { it.id }
            val verifiedUris = mutableListOf<String>()
            var firstFailure: String? = null
            currentIds.forEach { id ->
                val item = recentById[id] ?: galleryRepository.getMediaById(id)
                val uri = item?.localUri
                if (uri != null) {
                    when (val verified = galleryRepository.verifyRemoteBackupForDeletion(id)) {
                        is Outcome.Success -> verifiedUris.add(uri)
                        is Outcome.Failure -> if (firstFailure == null) firstFailure = verified.error.message
                    }
                }
            }
            if (verifiedUris.isEmpty()) {
                localState.update {
                    it.copy(
                        backupFeedbackMessage = null,
                        backupErrorMessage =
                            firstFailure ?: "Selected items are not verified in Telegram Saved Messages."
                    )
                }
            } else {
                localState.update { it.copy(pendingDeleteUris = verifiedUris, backupErrorMessage = null) }
            }
        }
    }

    public fun onLocalDeletionResult(confirmed: Boolean) {
        localState.update {
            it.copy(
                pendingDeleteUris = emptyList(),
                selectedIds = if (confirmed) emptySet() else it.selectedIds,
                isSelectionMode = if (confirmed) false else it.isSelectionMode,
                backupFeedbackMessage = if (confirmed) "Local copy removed; cloud-only backup preserved." else null
            )
        }
        if (confirmed) {
            refreshPermissionsAndMedia()
        }
    }

    public fun queueSelectedForBackup() {
        val currentIds = localState.value.selectedIds
        if (currentIds.isEmpty()) {
            localState.update {
                it.copy(
                    backupFeedbackMessage = null,
                    backupErrorMessage = "Select at least one local photo or video to back up."
                )
            }
            return
        }
        viewModelScope.launch {
            val recentById = recentItems.value.associateBy { it.id }
            val eligibleIds = currentIds.mapNotNull { id ->
                val item = recentById[id] ?: galleryRepository.getMediaById(id)
                item?.takeIf { it.localUri != null && it.syncState in ELIGIBLE_BACKUP_STATES }?.id
            }
            if (eligibleIds.isEmpty()) {
                localState.update {
                    it.copy(
                        selectedIds = emptySet(),
                        isSelectionMode = false,
                        backupErrorMessage = null,
                        backupFeedbackMessage = "Selected media is already backed up or queued."
                    )
                }
                return@launch
            }
            runCatching { backupScheduler.enqueueBackup(eligibleIds) }
                .onSuccess {
                    localState.update {
                        it.copy(
                            selectedIds = emptySet(),
                            isSelectionMode = false,
                            backupErrorMessage = null,
                            backupFeedbackMessage = "Queued ${eligibleIds.size} item(s) for backup."
                        )
                    }
                }
                .onFailure { error ->
                    localState.update {
                        it.copy(
                            backupFeedbackMessage = null,
                            backupErrorMessage = error.message ?: "Unable to schedule backup."
                        )
                    }
                }
        }
    }

    public fun toggleFolderAutoBackup(folderId: String, enabled: Boolean) {
        viewModelScope.launch {
            when (val result = galleryRepository.setFolderAutoBackupEnabled(folderId, enabled)) {
                is Outcome.Success -> {
                    val feedback = if (result.value.enabled) {
                        if (result.value.queuedCount > 0) {
                            "Folder backup enabled (${result.value.queuedCount} item(s) queued)."
                        } else {
                            "Folder backup enabled. New items will be backed up automatically."
                        }
                    } else {
                        "Folder backup paused (${result.value.cancelledCount} queued auto-backup item(s) stopped)."
                    }
                    localState.update { it.copy(backupErrorMessage = null, backupFeedbackMessage = feedback) }
                }

                is Outcome.Failure -> {
                    localState.update {
                        it.copy(backupFeedbackMessage = null, backupErrorMessage = result.error.message)
                    }
                }
            }
        }
    }

    private data class LocalMutableState(
        val permissionState: com.example.photx.core.domain.gallery.MediaPermissionState,
        val isRefreshing: Boolean = false,
        val refreshErrorMessage: String? = null,
        val selectedIds: Set<String> = emptySet(),
        val isSelectionMode: Boolean = false,
        val searchQuery: String = "",
        val backupFeedbackMessage: String? = null,
        val backupErrorMessage: String? = null,
        val pendingDeleteUris: List<String> = emptyList()
    )

    private companion object {
        val ELIGIBLE_BACKUP_STATES: Set<SyncState> = setOf(SyncState.LOCAL_ONLY, SyncState.FAILED)
    }
}
