package com.example.photx.core.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Restores a verified temporary image file back into Android's `MediaStore` without
 * recompression or alteration.
 */
public interface MediaStoreRestorer {
    public suspend fun restoreImageToMediaStore(
        sourceFile: File,
        displayName: String,
        mimeType: String,
        relativePath: String = DEFAULT_RESTORE_RELATIVE_PATH
    ): Outcome<String, AppError>

    public suspend fun computeFileSha256(file: File): Outcome<String, AppError>

    public companion object {
        public const val DEFAULT_RESTORE_RELATIVE_PATH: String = "DCIM/Camera"
        public const val STREAM_BUFFER_BYTES: Int = 65_536
        private const val MAX_COLLISION_ATTEMPTS: Int = 100
    }
}

internal class AndroidMediaStoreRestorer(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) : MediaStoreRestorer {
    override suspend fun restoreImageToMediaStore(
        sourceFile: File,
        displayName: String,
        mimeType: String,
        relativePath: String
    ): Outcome<String, AppError> = withContext(dispatcherProvider.io) {
        if (!sourceFile.isFile || !sourceFile.canRead() || sourceFile.length() <= 0L) {
            return@withContext Outcome.Failure(
                AppError.Validation("Verified restore source file is missing or unreadable.")
            )
        }
        val resolver = context.contentResolver
        val safeDisplayName = resolveCollisionSafeDisplayName(
            baseName = displayName.ifBlank { "restored_${System.currentTimeMillis()}.jpg" },
            relativePath = relativePath
        )
        val normalizedMime = mimeType.takeIf { it.startsWith("image/", ignoreCase = true) } ?: "image/jpeg"
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, safeDisplayName)
            put(MediaStore.Images.Media.MIME_TYPE, normalizedMime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val insertedUri: Uri = resolver.insert(collection, values)
            ?: return@withContext Outcome.Failure(
                AppError.Io("Failed to create MediaStore entry for $safeDisplayName.")
            )
        var finalized = false
        return@withContext try {
            copyFileToMediaStoreUri(resolver, sourceFile, insertedUri)
            finalizePendingMediaStoreEntry(resolver, insertedUri)
            finalized = true
            Outcome.Success(insertedUri.toString())
        } catch (error: IOException) {
            Outcome.Failure(AppError.Io("Failed to write restored image to MediaStore.", cause = error))
        } catch (securityError: SecurityException) {
            Outcome.Failure(AppError.Io("Permission denied writing to MediaStore.", cause = securityError))
        } finally {
            if (!finalized) {
                runCatching { resolver.delete(insertedUri, null, null) }
            }
        }
    }

    private suspend fun copyFileToMediaStoreUri(
        resolver: android.content.ContentResolver,
        sourceFile: File,
        insertedUri: Uri
    ) {
        val outputStream = resolver.openOutputStream(insertedUri)
            ?: throw IOException("ContentResolver returned null output stream for $insertedUri.")
        outputStream.use { out ->
            FileInputStream(sourceFile).use { input ->
                val buffer = ByteArray(MediaStoreRestorer.STREAM_BUFFER_BYTES)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
        }
    }

    private fun finalizePendingMediaStoreEntry(resolver: android.content.ContentResolver, insertedUri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finalizeValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(insertedUri, finalizeValues, null, null)
        }
    }

    override suspend fun computeFileSha256(file: File): Outcome<String, AppError> = withContext(dispatcherProvider.io) {
        if (!file.isFile || !file.canRead() || file.length() <= 0L) {
            return@withContext Outcome.Failure(AppError.Io("Cached file is missing or unreadable."))
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(MediaStoreRestorer.STREAM_BUFFER_BYTES)
            FileInputStream(file).use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            Outcome.Success(digest.digest().joinToString("") { "%02x".format(it) })
        } catch (error: IOException) {
            Outcome.Failure(AppError.Io("Failed to compute SHA-256 for cached file.", cause = error))
        }
    }

    private fun resolveCollisionSafeDisplayName(baseName: String, relativePath: String): String {
        val dotIndex = baseName.lastIndexOf('.')
        val stem = if (dotIndex > 0) baseName.substring(0, dotIndex) else baseName
        val ext = if (dotIndex > 0) baseName.substring(dotIndex) else ""
        val uniqueCandidate = (1..MAX_COLLISION_ATTEMPTS)
            .asSequence()
            .map { index -> "${stem}_restored_$index$ext" }
            .firstOrNull { !existsInMediaStore(it, relativePath) }
            ?: "${stem}_restored_${System.currentTimeMillis()}$ext"
        return if (!existsInMediaStore(baseName, relativePath)) baseName else uniqueCandidate
    }

    private fun existsInMediaStore(displayName: String, relativePath: String): Boolean {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        }
        val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(displayName, "$relativePath%")
        } else {
            arrayOf(displayName)
        }
        return runCatching {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)
                ?.use { cursor -> cursor.moveToFirst() }
                ?: false
        }.getOrDefault(false)
    }

    private companion object {
        const val MAX_COLLISION_ATTEMPTS: Int = 100
    }
}
