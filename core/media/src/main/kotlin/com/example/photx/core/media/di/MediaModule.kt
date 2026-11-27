package com.example.photx.core.media.di

import android.content.Context
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.media.AndroidLocalThumbnailGenerator
import com.example.photx.core.media.AndroidMediaPermissionChecker
import com.example.photx.core.media.AndroidMediaStoreRestorer
import com.example.photx.core.media.AndroidMediaStoreScanner
import com.example.photx.core.media.AndroidMediaUriExistenceProbe
import com.example.photx.core.media.AndroidUploadFileStager
import com.example.photx.core.media.LocalThumbnailGenerator
import com.example.photx.core.media.MediaPermissionChecker
import com.example.photx.core.media.MediaStoreRestorer
import com.example.photx.core.media.MediaStoreScanner
import com.example.photx.core.media.MediaUriExistenceProbe
import com.example.photx.core.media.UploadFileStager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bundles `:core:media` platform gateways cleanly for downstream injection in `:core:data`.
 */
public data class PhotxMediaServices(
    val permissionChecker: MediaPermissionChecker,
    val uriExistenceProbe: MediaUriExistenceProbe,
    val mediaStoreScanner: MediaStoreScanner,
    val thumbnailGenerator: LocalThumbnailGenerator
)

/**
 * Hilt module exposing Android `MediaStore`, permission evaluation, and WebP thumbnail services.
 */
@Module
@InstallIn(SingletonComponent::class)
public object MediaModule {
    @Provides
    @Singleton
    public fun provideMediaPermissionChecker(@ApplicationContext context: Context): MediaPermissionChecker =
        AndroidMediaPermissionChecker(context)

    @Provides
    @Singleton
    public fun provideMediaUriExistenceProbe(
        @ApplicationContext context: Context,
        dispatcherProvider: DispatcherProvider
    ): MediaUriExistenceProbe = AndroidMediaUriExistenceProbe(context, dispatcherProvider)

    @Provides
    @Singleton
    public fun provideMediaStoreScanner(
        @ApplicationContext context: Context,
        permissionChecker: MediaPermissionChecker,
        dispatcherProvider: DispatcherProvider
    ): MediaStoreScanner = AndroidMediaStoreScanner(context, permissionChecker, dispatcherProvider)

    @Provides
    @Singleton
    public fun provideLocalThumbnailGenerator(
        @ApplicationContext context: Context,
        dispatcherProvider: DispatcherProvider
    ): LocalThumbnailGenerator = AndroidLocalThumbnailGenerator(context, dispatcherProvider)

    @Provides
    @Singleton
    public fun provideUploadFileStager(
        @ApplicationContext context: Context,
        dispatcherProvider: DispatcherProvider
    ): UploadFileStager = AndroidUploadFileStager(context, dispatcherProvider)

    @Provides
    @Singleton
    public fun provideMediaStoreRestorer(
        @ApplicationContext context: Context,
        dispatcherProvider: DispatcherProvider
    ): MediaStoreRestorer = AndroidMediaStoreRestorer(context, dispatcherProvider)

    @Provides
    @Singleton
    public fun providePhotxMediaServices(
        permissionChecker: MediaPermissionChecker,
        uriExistenceProbe: MediaUriExistenceProbe,
        mediaStoreScanner: MediaStoreScanner,
        thumbnailGenerator: LocalThumbnailGenerator
    ): PhotxMediaServices = PhotxMediaServices(
        permissionChecker = permissionChecker,
        uriExistenceProbe = uriExistenceProbe,
        mediaStoreScanner = mediaStoreScanner,
        thumbnailGenerator = thumbnailGenerator
    )
}
