package com.example.photx.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.example.photx.core.database.MediaDao
import com.example.photx.core.domain.gallery.GalleryMediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Data/presentation boundary exposing Room-backed Paging 3 streams (`ADR-004`).
 *
 * Kept in `:core:data` rather than `:core:domain` so pure domain contracts avoid `androidx.paging`
 * coupling while `:feature:gallery` receives efficient SQLite-backed pagination.
 */
public interface GalleryPagingRepository {
    public fun observePagedTimeline(pageSize: Int = DEFAULT_PAGE_SIZE): Flow<PagingData<GalleryMediaItem>>

    public companion object {
        public const val DEFAULT_PAGE_SIZE: Int = 60
        public const val PREFETCH_DISTANCE: Int = 30
        public const val INITIAL_LOAD_MULTIPLIER: Int = 2
    }
}

internal class RoomGalleryPagingRepository(private val mediaDao: MediaDao) : GalleryPagingRepository {
    override fun observePagedTimeline(pageSize: Int): Flow<PagingData<GalleryMediaItem>> = Pager(
        config = PagingConfig(
            pageSize = pageSize,
            prefetchDistance = GalleryPagingRepository.PREFETCH_DISTANCE,
            initialLoadSize = pageSize * GalleryPagingRepository.INITIAL_LOAD_MULTIPLIER,
            enablePlaceholders = false
        ),
        pagingSourceFactory = mediaDao::observeTimelinePagingSource
    ).flow.map { pagingData ->
        pagingData.map { entity -> entity.toDomain() }
    }
}
