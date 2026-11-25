package com.example.photx.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Primary PhotX Room database acting as the single local source of truth (`SPEC.md` Section 7.3).
 */
@Database(
    entities = [MediaItemEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(PhotxTypeConverters::class)
public abstract class PhotxDatabase : RoomDatabase() {
    /** DAO managing indexed media metadata queries and upserts. */
    public abstract fun mediaDao(): MediaDao

    /** DAO enforcing centrally validated transactional sync state transitions. */
    public abstract fun syncStateDao(): SyncStateDao
}
