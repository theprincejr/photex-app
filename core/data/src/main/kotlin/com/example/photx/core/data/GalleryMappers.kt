package com.example.photx.core.data

import com.example.photx.core.database.LocalMediaDiscovery
import com.example.photx.core.database.LocalTrackedSnapshot
import com.example.photx.core.database.MediaItemEntity
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.media.ScannedMediaItem
import java.util.UUID

internal fun MediaItemEntity.toDomain(): GalleryMediaItem = GalleryMediaItem(
    id = id,
    sha256 = sha256,
    localUri = localUri,
    localThumbPath = localThumbPath,
    mediaType = mediaType,
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = durationMs,
    takenAt = takenAt,
    syncState = syncState,
    hasRemoteBackup = telegramChatId != null && telegramMessageId != null
)

internal fun stableMediaIdForUri(localUri: String): String =
    UUID.nameUUIDFromBytes("photx:mediastore:$localUri".toByteArray(Charsets.UTF_8)).toString()

internal fun ScannedMediaItem.toLocalDiscovery(existingId: String? = null): LocalMediaDiscovery = LocalMediaDiscovery(
    id = existingId ?: stableMediaIdForUri(localUri),
    localUri = localUri,
    mediaType = mediaType,
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = durationMs,
    takenAt = takenAt
)

internal fun LocalTrackedSnapshot.matchesScannedMetadata(scanned: ScannedMediaItem): Boolean =
    mediaType == scanned.mediaType &&
        fileName == scanned.fileName &&
        mimeType == scanned.mimeType &&
        sizeBytes == scanned.sizeBytes &&
        width == scanned.width &&
        height == scanned.height &&
        durationMs == scanned.durationMs &&
        takenAt == scanned.takenAt
