package com.example.photx.core.data.di

import android.content.Context
import androidx.work.WorkManager
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.data.DefaultGalleryRepository
import com.example.photx.core.data.GalleryPagingRepository
import com.example.photx.core.data.RoomGalleryPagingRepository
import com.example.photx.core.data.backup.WorkManagerActiveUploadRegistry
import com.example.photx.core.data.backup.WorkManagerBackupScheduler
import com.example.photx.core.database.BackupLeaseDao
import com.example.photx.core.database.FolderBackupDao
import com.example.photx.core.database.MediaDao
import com.example.photx.core.database.SyncStateDao
import com.example.photx.core.database.di.PhotxMediaDaos
import com.example.photx.core.domain.backup.BackupScheduler
import com.example.photx.core.domain.backup.TelegramBackupGateway
import com.example.photx.core.domain.gallery.ActiveUploadRegistry
import com.example.photx.core.domain.gallery.GalleryRepository
import com.example.photx.core.media.MediaStoreRestorer
import com.example.photx.core.media.di.PhotxMediaServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Hilt dependency bindings exposing [ActiveUploadRegistry], [BackupScheduler],
 * [GalleryRepository], and [GalleryPagingRepository].
 */
@Module
@InstallIn(SingletonComponent::class)
public object DataModule {
    @Provides
    @Singleton
    public fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    public fun provideActiveUploadRegistry(
        workManager: WorkManager,
        backupLeaseDao: BackupLeaseDao,
        syncStateDao: SyncStateDao,
        dispatcherProvider: DispatcherProvider
    ): ActiveUploadRegistry = WorkManagerActiveUploadRegistry(
        workManager = workManager,
        backupLeaseDao = backupLeaseDao,
        syncStateDao = syncStateDao,
        dispatcherProvider = dispatcherProvider
    )

    @Provides
    @Singleton
    public fun provideBackupScheduler(
        workManager: WorkManager,
        syncStateDao: SyncStateDao,
        backupLeaseDao: BackupLeaseDao,
        dispatcherProvider: DispatcherProvider
    ): BackupScheduler = WorkManagerBackupScheduler(
        workManager = workManager,
        syncStateDao = syncStateDao,
        backupLeaseDao = backupLeaseDao,
        dispatcherProvider = dispatcherProvider
    )

    @Provides
    @Singleton
    public fun provideGalleryRepository(
        @ApplicationContext context: Context,
        daos: PhotxMediaDaos,
        mediaServices: PhotxMediaServices,
        activeUploadRegistry: ActiveUploadRegistry,
        telegramBackupGateway: TelegramBackupGateway,
        mediaStoreRestorer: MediaStoreRestorer,
        folderBackupDao: FolderBackupDao,
        backupScheduler: BackupScheduler,
        dispatcherProvider: DispatcherProvider
    ): GalleryRepository = DefaultGalleryRepository(
        daos = daos,
        mediaServices = mediaServices,
        activeUploadRegistry = activeUploadRegistry,
        dispatcherProvider = dispatcherProvider,
        telegramBackupGateway = telegramBackupGateway,
        thumbnailCacheDir = File(context.filesDir, "thumbnails"),
        restoreCacheDir = File(context.cacheDir, "viewer_originals"),
        mediaStoreRestorer = mediaStoreRestorer,
        folderBackupDao = folderBackupDao,
        backupScheduler = backupScheduler
    )

    @Provides
    @Singleton
    public fun provideGalleryPagingRepository(mediaDao: MediaDao): GalleryPagingRepository =
        RoomGalleryPagingRepository(mediaDao)
}
