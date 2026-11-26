package com.example.photx.di

import com.example.photx.core.common.DefaultDispatcherProvider
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.domain.gallery.GalleryRepository
import com.example.photx.core.domain.gallery.ObserveGalleryTimelineUseCase
import com.example.photx.core.domain.gallery.RefreshLocalMediaUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Application-level dependency bindings shared across feature and core modules.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    fun provideObserveGalleryTimelineUseCase(repository: GalleryRepository): ObserveGalleryTimelineUseCase =
        ObserveGalleryTimelineUseCase(repository)

    @Provides
    fun provideRefreshLocalMediaUseCase(repository: GalleryRepository): RefreshLocalMediaUseCase =
        RefreshLocalMediaUseCase(repository)
}
