package com.example.photx.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.photx.core.database.MediaDao
import com.example.photx.core.database.PhotxDatabase
import com.example.photx.core.database.SyncStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()

    @Provides
    public fun provideMediaDao(database: PhotxDatabase): MediaDao = database.mediaDao()

    @Provides
    public fun provideSyncStateDao(database: PhotxDatabase): SyncStateDao = database.syncStateDao()
}
