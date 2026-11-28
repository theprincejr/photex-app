package com.example.photx.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.photx.core.database.BackupLeaseDao
import com.example.photx.core.database.FolderBackupDao
import com.example.photx.core.database.LocalReconciliationDao
import com.example.photx.core.database.MediaDao
import com.example.photx.core.database.PhotxDatabase
import com.example.photx.core.database.PhotxDatabaseMigrations
import com.example.photx.core.database.SyncStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Groups the media DAOs exposed by `:core:database` so downstream modules do not need a
 * direct compile dependency on `androidx.room.RoomDatabase`.
 */
public data class PhotxMediaDaos(
    val mediaDao: MediaDao,
    val syncStateDao: SyncStateDao,
    val localReconciliationDao: LocalReconciliationDao
)

/**
 * Hilt module provisioning the WAL-enabled [PhotxDatabase] and [MediaDao].
 */
@Module
@InstallIn(SingletonComponent::class)
public object DatabaseModule {
    private const val DATABASE_NAME = "photx.db"

    @Provides
    @Singleton
    public fun providePhotxDatabase(@ApplicationContext context: Context): PhotxDatabase = Room.databaseBuilder(
        context,
        PhotxDatabase::class.java,
        DATABASE_NAME
    )
        .addMigrations(PhotxDatabaseMigrations.MIGRATION_1_2, PhotxDatabaseMigrations.MIGRATION_2_3)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()

    @Provides
    public fun provideBackupLeaseDao(database: PhotxDatabase): BackupLeaseDao = database.backupLeaseDao()

    @Provides
    public fun provideFolderBackupDao(database: PhotxDatabase): FolderBackupDao = database.folderBackupDao()

    @Provides
    public fun provideMediaDao(database: PhotxDatabase): MediaDao = database.mediaDao()

    @Provides
    public fun provideSyncStateDao(database: PhotxDatabase): SyncStateDao = database.syncStateDao()

    @Provides
    public fun provideLocalReconciliationDao(database: PhotxDatabase): LocalReconciliationDao =
        database.localReconciliationDao()

    @Provides
    public fun providePhotxMediaDaos(database: PhotxDatabase): PhotxMediaDaos = PhotxMediaDaos(
        mediaDao = database.mediaDao(),
        syncStateDao = database.syncStateDao(),
        localReconciliationDao = database.localReconciliationDao()
    )
}
