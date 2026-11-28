package com.example.photx.core.database

import androidx.room.TypeConverter
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.QueueOrigin
import com.example.photx.core.model.SyncState

/**
 * Room enum [TypeConverter]s persisting stable enum names instead of fragile ordinal positions.
 */
internal class PhotxTypeConverters {
    @TypeConverter
    fun fromSyncState(value: SyncState): String = value.name

    @TypeConverter
    fun toSyncState(value: String): SyncState = SyncState.valueOf(value)

    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter
    fun toMediaType(value: String): MediaType = MediaType.valueOf(value)

    @TypeConverter
    fun fromQueueOrigin(value: QueueOrigin): String = value.name

    @TypeConverter
    fun toQueueOrigin(value: String): QueueOrigin = QueueOrigin.valueOf(value)
}
