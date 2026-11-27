package com.example.photx.feature.viewer

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.photx.core.common.Outcome
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.domain.gallery.GalleryRepository
import com.example.photx.core.domain.gallery.ObserveGalleryTimelineUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val VIEWER_WINDOW_LIMIT = 500
private const val STOP_TIMEOUT_MS = 5_000L

@Immutable
public sealed interface CloudOriginalUiState {
    public data object Idle : CloudOriginalUiState

    public data class Downloading(public val downloadedBytes: Long = 0L, public val totalBytes: Long = 0L) :
        CloudOriginalUiState

    public data class Ready(public val cachedFilePath: String, public val fromCache: Boolean) : CloudOriginalUiState

    public data class Failed(public val message: String, public val isIntegrityError: Boolean) : CloudOriginalUiState
}

@Immutable
public sealed interface CloudRestoreUiState {
    public data object Idle : CloudRestoreUiState

    public data class Restoring(public val downloadedBytes: Long = 0L, public val totalBytes: Long = 0L) :
        CloudRestoreUiState

    public data class Completed(public val restoredUri: String, public val fileName: String) : CloudRestoreUiState

    public data class Failed(public val message: String) : CloudRestoreUiState
}

@Immutable
public data class MediaViewerUiState(
    public val items: List<GalleryMediaItem> = emptyList(),
    public val initialIndex: Int = 0,
    public val isLoading: Boolean = true,
    public val cloudOriginalStates: Map<String, CloudOriginalUiState> = emptyMap(),
    public val restoreStates: Map<String, CloudRestoreUiState> = emptyMap()
)

/**
 * Loads the tapped media item and surrounding timeline items for horizontal swipe navigation.
 */
@HiltViewModel
public class MediaViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val galleryRepository: GalleryRepository,
    observeTimelineUseCase: ObserveGalleryTimelineUseCase
) : ViewModel() {
    private val initialItemId: String = savedStateHandle.get<String>(ARG_MEDIA_ID).orEmpty()
    private val fallbackItem = MutableStateFlow<GalleryMediaItem?>(null)
    private val resolvedInitial = MutableStateFlow(false)
    private val cloudOriginalStates = MutableStateFlow<Map<String, CloudOriginalUiState>>(emptyMap())
    private val restoreStates = MutableStateFlow<Map<String, CloudRestoreUiState>>(emptyMap())

    init {
        if (initialItemId.isNotBlank()) {
            viewModelScope.launch {
                fallbackItem.value = galleryRepository.getMediaById(initialItemId)
                resolvedInitial.value = true
            }
        } else {
            resolvedInitial.value = true
        }
    }

    public val uiState: StateFlow<MediaViewerUiState> = combine(
        observeTimelineUseCase(limit = VIEWER_WINDOW_LIMIT),
        fallbackItem,
        resolvedInitial,
        cloudOriginalStates,
        restoreStates
    ) { recent, single, loaded, originals, restores ->
        val list = when {
            recent.any { it.id == initialItemId } -> recent
            single != null -> listOf(single) + recent.filterNot { it.id == single.id }
            else -> recent
        }
        val idx = list.indexOfFirst { it.id == initialItemId }.coerceAtLeast(0)
        MediaViewerUiState(
            items = list,
            initialIndex = idx,
            isLoading = !loaded && list.isEmpty(),
            cloudOriginalStates = originals,
            restoreStates = restores
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = MediaViewerUiState()
    )

    public fun ensureCloudOriginalLoaded(itemId: String, forceRetry: Boolean = false) {
        val current = cloudOriginalStates.value[itemId]
        if (!forceRetry && (current is CloudOriginalUiState.Downloading || current is CloudOriginalUiState.Ready)) {
            return
        }
        cloudOriginalStates.update { it + (itemId to CloudOriginalUiState.Downloading()) }
        viewModelScope.launch {
            val outcome = galleryRepository.prepareCloudOriginalForViewer(itemId) { progress ->
                cloudOriginalStates.update { map ->
                    map + (itemId to CloudOriginalUiState.Downloading(progress.downloadedBytes, progress.totalBytes))
                }
            }
            when (outcome) {
                is Outcome.Success -> cloudOriginalStates.update { map ->
                    map + (
                        itemId to CloudOriginalUiState.Ready(
                            cachedFilePath = outcome.value.cachedFilePath,
                            fromCache = outcome.value.fromCache
                        )
                        )
                }

                is Outcome.Failure -> {
                    val msg = outcome.error.message
                    val isIntegrity = msg.contains("SHA-256", ignoreCase = true) ||
                        msg.contains("byte count", ignoreCase = true)
                    cloudOriginalStates.update { map ->
                        map + (itemId to CloudOriginalUiState.Failed(msg, isIntegrityError = isIntegrity))
                    }
                }
            }
        }
    }

    public fun restoreToDevice(itemId: String) {
        if (restoreStates.value[itemId] is CloudRestoreUiState.Restoring) return
        restoreStates.update { it + (itemId to CloudRestoreUiState.Restoring()) }
        viewModelScope.launch {
            val outcome = galleryRepository.restoreCloudItemToDevice(itemId) { progress ->
                restoreStates.update { map ->
                    map + (itemId to CloudRestoreUiState.Restoring(progress.downloadedBytes, progress.totalBytes))
                }
            }
            when (outcome) {
                is Outcome.Success -> {
                    restoreStates.update { map ->
                        map + (
                            itemId to CloudRestoreUiState.Completed(
                                restoredUri = outcome.value.restoredUri,
                                fileName = outcome.value.fileName
                            )
                            )
                    }
                    fallbackItem.value = galleryRepository.getMediaById(itemId)
                }

                is Outcome.Failure -> restoreStates.update { map ->
                    map + (itemId to CloudRestoreUiState.Failed(outcome.error.message))
                }
            }
        }
    }

    public companion object {
        public const val ARG_MEDIA_ID: String = "mediaId"
    }
}
