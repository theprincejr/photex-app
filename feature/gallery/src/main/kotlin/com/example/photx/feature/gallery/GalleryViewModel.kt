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
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.domain.gallery.GalleryRepository
import com.example.photx.core.domain.gallery.ObserveGalleryTimelineUseCase
import com.example.photx.core.domain.gallery.RefreshLocalMediaUseCase
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
public class GalleryViewModel @Inject constructor(
    private val galleryRepository: GalleryRepository,
    pagingRepository: GalleryPagingRepository,
    observeTimelineUseCase: ObserveGalleryTimelineUseCase,
    private val refreshLocalMediaUseCase: RefreshLocalMediaUseCase,
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

    public val uiState: StateFlow<GalleryUiState> = combine(
        localState,
        observeTimelineUseCase.observeTotalCount(),
        recentItems,
        authRepository.authState
    ) { local, totalCount, items, authState ->
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
            backupSummary = computeBackupSummary(items = items, totalCount = totalCount)
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

    private data class LocalMutableState(
        val permissionState: com.example.photx.core.domain.gallery.MediaPermissionState,
        val isRefreshing: Boolean = false,
        val refreshErrorMessage: String? = null,
        val selectedIds: Set<String> = emptySet(),
        val isSelectionMode: Boolean = false,
        val searchQuery: String = ""
    )
}
