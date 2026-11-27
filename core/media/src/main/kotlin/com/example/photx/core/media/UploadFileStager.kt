package com.example.photx.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.StatFs
import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Result of a single-pass stage-and-hash copy from a `content://` URI into app-private staging.
 *
 * Guarantees [sha256] was computed over the exact bytes persisted in [stagedFile].
 */
public data class StagedUploadFile(public val stagedFile: File, public val sha256: String, public val byteCount: Long)

/**
 * Stages local media into app-private storage while streaming SHA-256 in a single bounded-memory pass.
 */
public interface UploadFileStager {
    public suspend fun stageAndHash(
        itemId: String,
        sourceUri: String,
        expectedSizeBytes: Long = 0L
    ): Outcome<StagedUploadFile, AppError>

    public suspend fun computeSha256(sourceUri: String): Outcome<String, AppError>

    public suspend fun prepareUploadThumbnail(itemId: String, localThumbPath: String?): String? = null

    public suspend fun deleteStagedFile(itemId: String): Boolean

    public suspend fun sweepOrphanedStagingFiles(
        activeMediaIds: Set<String> = emptySet(),
        staleAgeMs: Long = DEFAULT_STALE_STAGING_AGE_MS,
        nowMs: Long = System.currentTimeMillis()
    ): Int

    public companion object {
        public const val STREAM_BUFFER_BYTES: Int = 65_536
        public const val MIN_FREE_SPACE_HEADROOM_BYTES: Long = 50L * 1024L * 1024L
        public const val DEFAULT_STALE_STAGING_AGE_MS: Long = 1_800_000L
    }
}

internal class AndroidUploadFileStager(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val availableBytesProvider: (File) -> Long = ::defaultAvailableBytes
) : UploadFileStager {
    private val stagingDir: File
        get() = File(context.filesDir, STAGING_DIR_NAME)

    override suspend fun stageAndHash(
        itemId: String,
        sourceUri: String,
        expectedSizeBytes: Long
    ): Outcome<StagedUploadFile, AppError> = withContext(dispatcherProvider.io) {
        if (itemId.isBlank() || sourceUri.isBlank()) {
            return@withContext Outcome.Failure(
                AppError.Validation("Item ID and source URI must not be blank for upload staging.")
            )
        }
        val parsedUri = runCatching { Uri.parse(sourceUri) }.getOrNull()
            ?: return@withContext Outcome.Failure(AppError.Validation("Invalid source media URI."))

        val dir = stagingDir
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            return@withContext Outcome.Failure(AppError.Io("Unable to create upload staging directory."))
        }

        val requiredBytes = expectedSizeBytes.coerceAtLeast(0L) + UploadFileStager.MIN_FREE_SPACE_HEADROOM_BYTES
        val availableBytes = runCatching { availableBytesProvider(dir) }.getOrElse { -1L }
        if (availableBytes in 0 until requiredBytes) {
            return@withContext Outcome.Failure(
                AppError.Io("Insufficient storage space to stage media for backup.")
            )
        }

        val safeKey = sanitizeStagingKey(itemId)
        val partFile = File(dir, "$safeKey.part.${System.nanoTime()}")
        val stagedFile = File(dir, "$safeKey$STAGED_EXTENSION")
        copyAndHashAtomically(parsedUri, partFile, stagedFile, expectedSizeBytes)
    }

    override suspend fun computeSha256(sourceUri: String): Outcome<String, AppError> =
        withContext(dispatcherProvider.io) {
            val parsedUri = runCatching { Uri.parse(sourceUri) }.getOrNull()
                ?: return@withContext Outcome.Failure(AppError.Validation("Invalid source media URI."))
            try {
                val input = context.contentResolver.openInputStream(parsedUri)
                    ?: return@withContext Outcome.Failure(AppError.Io("Unable to open media stream for hashing."))
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(UploadFileStager.STREAM_BUFFER_BYTES)
                var totalBytes = 0L
                input.use { stream ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        totalBytes += read
                    }
                }
                if (totalBytes <= 0L) {
                    Outcome.Failure(AppError.Io("Media stream is empty; cannot compute SHA-256."))
                } else {
                    Outcome.Success(digest.digest().toLowerHex())
                }
            } catch (_: SecurityException) {
                Outcome.Failure(AppError.Unauthorized("Permission denied while hashing media URI."))
            } catch (error: IOException) {
                Outcome.Failure(AppError.Io("I/O error while streaming SHA-256.", cause = error))
            }
        }

    override suspend fun prepareUploadThumbnail(itemId: String, localThumbPath: String?): String? =
        withContext(dispatcherProvider.io) {
            if (itemId.isBlank() || localThumbPath.isNullOrBlank()) return@withContext null
            val source = File(localThumbPath)
            if (!source.isFile || source.length() <= 0L) return@withContext null
            val dir = stagingDir
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return@withContext null
            val target = File(dir, "${sanitizeStagingKey(itemId)}$STAGED_THUMB_EXTENSION")
            val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: return@withContext null
            try {
                FileOutputStream(target).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_THUMB_QUALITY, out)
                }
                target.absolutePath.takeIf { target.isFile && target.length() > 0L }
            } catch (_: IOException) {
                target.delete()
                null
            } finally {
                bitmap.recycle()
            }
        }

    override suspend fun deleteStagedFile(itemId: String): Boolean = withContext(dispatcherProvider.io) {
        if (itemId.isBlank()) return@withContext false
        val safeKey = sanitizeStagingKey(itemId)
        val stagedFile = File(stagingDir, "$safeKey$STAGED_EXTENSION")
        val thumbFile = File(stagingDir, "$safeKey$STAGED_THUMB_EXTENSION")
        val partDeleted = stagingDir.listFiles()
            ?.filter { it.name.startsWith("$safeKey.part.") }
            ?.all { it.delete() }
            ?: true
        val mainDeleted = !stagedFile.exists() || stagedFile.delete()
        val thumbDeleted = !thumbFile.exists() || thumbFile.delete()
        partDeleted && mainDeleted && thumbDeleted
    }

    override suspend fun sweepOrphanedStagingFiles(activeMediaIds: Set<String>, staleAgeMs: Long, nowMs: Long): Int =
        withContext(dispatcherProvider.io) {
            val files = stagingDir.listFiles() ?: return@withContext 0
            val protectedNames = activeMediaIds.mapTo(HashSet()) { id -> "${sanitizeStagingKey(id)}$STAGED_EXTENSION" }
            var deleted = 0
            files.forEach { candidate ->
                val ageMs = nowMs - candidate.lastModified()
                val shouldDelete = when {
                    candidate.name.contains(".part.") -> ageMs >= staleAgeMs || activeMediaIds.isEmpty()
                    candidate.name.endsWith(STAGED_EXTENSION) -> candidate.name !in protectedNames
                    else -> false
                }
                if (shouldDelete && candidate.delete()) {
                    deleted += 1
                }
            }
            deleted
        }

    private suspend fun copyAndHashAtomically(
        uri: Uri,
        partFile: File,
        stagedFile: File,
        expectedSizeBytes: Long
    ): Outcome<StagedUploadFile, AppError> = try {
        val input = context.contentResolver.openInputStream(uri)
            ?: return Outcome.Failure(AppError.Io("Unable to open source media stream for staging."))
        val digest = MessageDigest.getInstance("SHA-256")
        val bytesCopied = streamAndHashToPart(input, partFile, digest)

        when {
            bytesCopied <= 0L -> Outcome.Failure(AppError.Io("Staged media stream produced zero bytes."))

            expectedSizeBytes > 0L && bytesCopied != expectedSizeBytes -> Outcome.Failure(
                AppError.Io("Staged byte count ($bytesCopied) does not match expected size ($expectedSizeBytes).")
            )

            else -> {
                moveStagingFileAtomically(partFile, stagedFile)
                Outcome.Success(
                    StagedUploadFile(
                        stagedFile = stagedFile,
                        sha256 = digest.digest().toLowerHex(),
                        byteCount = bytesCopied
                    )
                )
            }
        }
    } catch (_: SecurityException) {
        Outcome.Failure(AppError.Unauthorized("Permission denied while staging media for upload."))
    } catch (error: IOException) {
        Outcome.Failure(AppError.Io("Failed to stage media file for upload.", cause = error))
    } finally {
        if (partFile.exists()) {
            partFile.delete()
        }
    }

    private suspend fun streamAndHashToPart(input: java.io.InputStream, partFile: File, digest: MessageDigest): Long {
        val buffer = ByteArray(UploadFileStager.STREAM_BUFFER_BYTES)
        var bytesCopied = 0L
        input.use { stream ->
            FileOutputStream(partFile).use { output ->
                while (true) {
                    coroutineContext.ensureActive()
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    bytesCopied += read
                }
                output.flush()
                runCatching { output.fd.sync() }
            }
        }
        return bytesCopied
    }

    private companion object {
        const val STAGING_DIR_NAME = "upload_staging"
        const val STAGED_EXTENSION = ".staged"
        const val STAGED_THUMB_EXTENSION = ".thumb.jpg"
        const val JPEG_THUMB_QUALITY = 82
    }
}

private fun defaultAvailableBytes(dir: File): Long {
    val stat = StatFs(dir.absolutePath)
    return stat.availableBytes
}

private fun sanitizeStagingKey(itemId: String): String = itemId.replace(Regex("[^a-zA-Z0-9._-]"), "_")

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte)
}

private fun moveStagingFileAtomically(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
