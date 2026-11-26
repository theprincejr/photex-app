package com.example.photx.feature.viewer

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch

private const val VIEWER_WINDOW_LIMIT = 500
private const val STOP_TIMEOUT_MS = 5_000L

@Immutable
public data class MediaViewerUiState(
    public val items: List<GalleryMediaItem> = emptyList(),
    public val initialIndex: Int = 0,
    public val isLoading: Boolean = true
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
        resolvedInitial
    ) { recent, single, loaded ->
        val list = when {
            recent.any { it.id == initialItemId } -> recent
            single != null -> listOf(single) + recent.filterNot { it.id == single.id }
            else -> recent
        }
        val idx = list.indexOfFirst { it.id == initialItemId }.coerceAtLeast(0)
        MediaViewerUiState(
            items = list,
            initialIndex = idx,
            isLoading = !loaded && list.isEmpty()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = MediaViewerUiState()
    )

    public companion object {
        public const val ARG_MEDIA_ID: String = "mediaId"
    }
}
