package com.example.photx.core.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.domain.gallery.MediaScanScope
import com.example.photx.core.model.MediaType
import java.io.IOException
import kotlinx.coroutines.withContext

/**
 * Discovers device photos and videos via Android `MediaStore` without reading full media bytes.
 */
public interface MediaStoreScanner {
    public suspend fun scan(scope: MediaScanScope = MediaScanScope()): Outcome<List<ScannedMediaItem>, AppError>
}

internal class AndroidMediaStoreScanner(
    private val context: Context,
    private val permissionChecker: MediaPermissionChecker,
    private val dispatcherProvider: DispatcherProvider
) : MediaStoreScanner {
    override suspend fun scan(scope: MediaScanScope): Outcome<List<ScannedMediaItem>, AppError> =
        withContext(dispatcherProvider.io) {
            val permission = permissionChecker.checkPermissionState()
            if (!permission.canScanDeviceMedia) {
                return@withContext Outcome.Failure(
                    AppError.Unauthorized("Media access permission is required before scanning device media.")
                )
            }

            try {
                val results = LinkedHashMap<String, ScannedMediaItem>()
                if (shouldQueryImages(permission)) {
                    scanCollection(
                        collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        mediaType = MediaType.IMAGE,
                        scope = scope,
                        sink = results
                    )
                }
                if (shouldQueryVideos(permission)) {
                    scanCollection(
                        collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        mediaType = MediaType.VIDEO,
                        scope = scope,
                        sink = results
                    )
                }
                val sorted = results.values.sortedWith(
                    compareByDescending<ScannedMediaItem> { it.takenAt }
                        .thenByDescending { it.localUri }
                )
                Outcome.Success(sorted)
            } catch (_: SecurityException) {
                Outcome.Failure(
                    AppError.Unauthorized("Media access permission was revoked while scanning MediaStore.")
                )
            } catch (error: IOException) {
                Outcome.Failure(
                    AppError.Io("Failed to read media entries from Android MediaStore.", cause = error)
                )
            } catch (error: IllegalStateException) {
                Outcome.Failure(
                    AppError.Io("Unexpected MediaStore cursor failure while indexing media.", cause = error)
                )
            }
        }

    private fun scanCollection(
        collectionUri: Uri,
        mediaType: MediaType,
        scope: MediaScanScope,
        sink: MutableMap<String, ScannedMediaItem>
    ) {
        val projection = buildProjection(mediaType)
        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns._ID} DESC"
        context.contentResolver.query(collectionUri, projection, null, null, sortOrder)?.use { cursor ->
            val columns = CursorColumns(cursor, mediaType)
            while (cursor.moveToNext()) {
                val item = mapCursorRow(cursor, columns, collectionUri, mediaType)
                if (item != null && matchesRelativePathScope(item.relativePath, scope)) {
                    sink.putIfAbsent(item.localUri, item)
                }
            }
        }
    }

    private fun shouldQueryImages(permission: MediaPermissionState): Boolean = when (permission) {
        MediaPermissionState.Full -> true
        is MediaPermissionState.Partial -> permission.imagesGranted || permission.userSelectedOnly
        MediaPermissionState.Denied -> false
    }

    private fun shouldQueryVideos(permission: MediaPermissionState): Boolean = when (permission) {
        MediaPermissionState.Full -> true
        is MediaPermissionState.Partial -> permission.videosGranted || permission.userSelectedOnly
        MediaPermissionState.Denied -> false
    }

    private fun buildProjection(mediaType: MediaType): Array<String> {
        val columns = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columns += MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            columns += MediaStore.MediaColumns.DATA
        }
        if (mediaType == MediaType.VIDEO) {
            columns += MediaStore.Video.VideoColumns.DURATION
        }
        return columns.toTypedArray()
    }

    private fun mapCursorRow(
        cursor: Cursor,
        cols: CursorColumns,
        collectionUri: Uri,
        mediaType: MediaType
    ): ScannedMediaItem? {
        val rowId = if (cols.idIdx >= 0 && !cursor.isNull(cols.idIdx)) cursor.getLong(cols.idIdx) else -1L
        if (rowId <= 0L) return null

        val localUri = ContentUris.withAppendedId(collectionUri, rowId).toString()
        val rawRelativePath = readRelativePath(cursor, cols)
        return ScannedMediaItem(
            localUri = localUri,
            mediaType = mediaType,
            fileName = readNonBlankString(cursor, cols.nameIdx) ?: defaultFileName(mediaType, rowId),
            mimeType = readNonBlankString(cursor, cols.mimeIdx) ?: defaultMimeType(mediaType),
            sizeBytes = readNonNegativeLong(cursor, cols.sizeIdx),
            width = readNonNegativeInt(cursor, cols.widthIdx),
            height = readNonNegativeInt(cursor, cols.heightIdx),
            durationMs = if (mediaType == MediaType.VIDEO) readNonNegativeLong(cursor, cols.durationIdx) else 0L,
            takenAt = extractTimestampMillis(cursor, cols),
            relativePath = normalizeRelativePath(rawRelativePath)
        )
    }

    private fun readRelativePath(cursor: Cursor, cols: CursorColumns): String {
        val rel = if (cols.relativePathIdx >= 0 && !cursor.isNull(cols.relativePathIdx)) {
            cursor.getString(cols.relativePathIdx).orEmpty()
        } else {
            ""
        }
        return if (rel.isNotBlank()) {
            rel
        } else if (cols.dataIdx >= 0 && !cursor.isNull(cols.dataIdx)) {
            extractParentFromLegacyDataPath(cursor.getString(cols.dataIdx).orEmpty())
        } else {
            ""
        }
    }

    private fun extractTimestampMillis(cursor: Cursor, cols: CursorColumns): Long {
        val dateTaken = readNonNegativeLong(cursor, cols.takenIdx)
        val dateModifiedSec = readNonNegativeLong(cursor, cols.modifiedIdx)
        val dateAddedSec = readNonNegativeLong(cursor, cols.addedIdx)
        return when {
            dateTaken > 0L -> dateTaken
            dateModifiedSec > 0L -> dateModifiedSec * MILLIS_PER_SECOND
            dateAddedSec > 0L -> dateAddedSec * MILLIS_PER_SECOND
            else -> 0L
        }
    }

    internal class CursorColumns(cursor: Cursor, mediaType: MediaType) {
        val idIdx: Int = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
        val nameIdx: Int = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeIdx: Int = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val sizeIdx: Int = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val widthIdx: Int = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val heightIdx: Int = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val takenIdx: Int = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val modifiedIdx: Int = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val addedIdx: Int = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        val relativePathIdx: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        } else {
            -1
        }

        @Suppress("DEPRECATION")
        val dataIdx: Int = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
        val durationIdx: Int = if (mediaType == MediaType.VIDEO) {
            cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
        } else {
            -1
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

private fun readNonBlankString(cursor: Cursor, index: Int): String? = if (index >= 0 && !cursor.isNull(index)) {
    cursor.getString(index)?.trim()?.takeIf { it.isNotEmpty() }
} else {
    null
}

private fun readNonNegativeLong(cursor: Cursor, index: Int): Long =
    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index).coerceAtLeast(0L) else 0L

private fun readNonNegativeInt(cursor: Cursor, index: Int): Int =
    if (index >= 0 && !cursor.isNull(index)) cursor.getInt(index).coerceAtLeast(0) else 0

private fun defaultFileName(mediaType: MediaType, rowId: Long): String = when (mediaType) {
    MediaType.IMAGE -> "image_$rowId"
    MediaType.VIDEO -> "video_$rowId"
}

private fun defaultMimeType(mediaType: MediaType): String = when (mediaType) {
    MediaType.IMAGE -> "image/*"
    MediaType.VIDEO -> "video/*"
}

internal fun normalizeRelativePath(raw: String): String = raw.replace('\\', '/').trim().trim('/')

internal fun extractParentFromLegacyDataPath(dataPath: String): String {
    val normalized = dataPath.replace('\\', '/')
    val lastSlash = normalized.lastIndexOf('/')
    return if (lastSlash <= 0) "" else normalized.substring(0, lastSlash).trim('/')
}

internal fun matchesRelativePathScope(relativePath: String, scope: MediaScanScope): Boolean {
    val normalizedPath = normalizeRelativePath(relativePath)
    if (normalizedPath.isEmpty()) return false
    return scope.relativePathPrefixes.any { prefix ->
        val cleanPrefix = normalizeRelativePath(prefix)
        normalizedPath.equals(cleanPrefix, ignoreCase = true) ||
            normalizedPath.startsWith("$cleanPrefix/", ignoreCase = true) ||
            normalizedPath.contains("/$cleanPrefix/", ignoreCase = true) ||
            normalizedPath.endsWith("/$cleanPrefix", ignoreCase = true)
    }
}
