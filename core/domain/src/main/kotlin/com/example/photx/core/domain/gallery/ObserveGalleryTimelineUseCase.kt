package com.example.photx.core.domain.gallery

import kotlinx.coroutines.flow.Flow

/**
 * Observes a bounded offline-first domain snapshot of the PhotX timeline and total indexed count.
 */
public class ObserveGalleryTimelineUseCase(private val repository: GalleryRepository) {
    /** Emits the newest-first bounded domain window from the local Room index. */
    public operator fun invoke(limit: Int = GalleryRepository.DEFAULT_WINDOW_LIMIT): Flow<List<GalleryMediaItem>> =
        repository.observeRecentMedia(limit = limit)

    /** Emits the reactive total count of indexed items in Room. */
    public fun observeTotalCount(): Flow<Int> = repository.observeTotalCount()
}
