package com.example.photx.core.data.di

import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.data.DefaultGalleryRepository
import com.example.photx.core.data.GalleryPagingRepository
import com.example.photx.core.data.RoomGalleryPagingRepository
import com.example.photx.core.database.MediaDao
import com.example.photx.core.database.di.PhotxMediaDaos
import com.example.photx.core.domain.gallery.ActiveUploadRegistry
import com.example.photx.core.domain.gallery.ActiveUploadSnapshot
import com.example.photx.core.domain.gallery.GalleryRepository
import com.example.photx.core.media.di.PhotxMediaServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency bindings exposing [ActiveUploadRegistry], [GalleryRepository], and
 * [GalleryPagingRepository].
 */
@Module
@InstallIn(SingletonComponent::class)
public object DataModule {
    /**
     * Phase 2 default registry prior to Phase 3 WorkManager upload scheduling: explicitly reports
     * [ActiveUploadSnapshot.Unavailable] so callers cannot mistake an uninitialized worker list
     * for an empty active-worker set during recovery.
     */
    @Provides
    @Singleton
    public fun provideDefaultActiveUploadRegistry(): ActiveUploadRegistry = ActiveUploadRegistry {
        ActiveUploadSnapshot.Unavailable(
            reason = "Upload worker scheduler is not bound yet; authoritative active-worker check skipped."
        )
    }

    @Provides
    @Singleton
    public fun provideGalleryRepository(
        daos: PhotxMediaDaos,
        mediaServices: PhotxMediaServices,
        activeUploadRegistry: ActiveUploadRegistry,
        dispatcherProvider: DispatcherProvider
    ): GalleryRepository = DefaultGalleryRepository(
        daos = daos,
        mediaServices = mediaServices,
        activeUploadRegistry = activeUploadRegistry,
        dispatcherProvider = dispatcherProvider
    )

    @Provides
    @Singleton
    public fun provideGalleryPagingRepository(mediaDao: MediaDao): GalleryPagingRepository =
        RoomGalleryPagingRepository(mediaDao)
}
