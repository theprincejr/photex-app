package com.example.photx.core.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.model.MediaType
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

/**
 * Generates bounded-memory ~320x320 WebP thumbnails in app-private storage using atomic writes.
 */
public interface LocalThumbnailGenerator {
    public suspend fun generateThumbnail(
        itemId: String,
        sourceUri: String,
        mediaType: MediaType
    ): Outcome<String, AppError>

    public fun thumbnailFileFor(itemId: String): File

    public fun isThumbnailUsable(path: String?): Boolean

    public suspend fun pruneOrphanThumbnails(
        referencedPaths: Set<String>,
        activeMediaIds: Set<String>,
        staleTempAgeMs: Long = DEFAULT_STALE_TEMP_AGE_MS,
        nowMs: Long = System.currentTimeMillis()
    ): Int

    public companion object {
        public const val DEFAULT_STALE_TEMP_AGE_MS: Long = 600_000L
    }
}

internal interface VideoFrameDecoder {
    fun decodeVideoFrame(context: Context, uri: Uri, maxDimensionPx: Int): Bitmap?
}

internal interface VideoRetrieverAdapter {
    fun setDataSource(context: Context, uri: Uri)
    fun getScaledFrameAtTime(timeUs: Long, option: Int, maxWidth: Int, maxHeight: Int): Bitmap?
    fun release()
}

private class PlatformVideoRetrieverAdapter : VideoRetrieverAdapter {
    private val retriever = MediaMetadataRetriever()

    override fun setDataSource(context: Context, uri: Uri) {
        retriever.setDataSource(context, uri)
    }

    override fun getScaledFrameAtTime(timeUs: Long, option: Int, maxWidth: Int, maxHeight: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, option, maxWidth, maxHeight)
        } else {
            null
        }

    override fun release() {
        retriever.release()
    }
}

internal class AndroidVideoFrameDecoder(
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val legacyMediaStoreThumb: (Context, Long) -> Bitmap? = ::decodeLegacyMediaStoreVideoThumb,
    private val retrieverFactory: () -> VideoRetrieverAdapter = { PlatformVideoRetrieverAdapter() }
) : VideoFrameDecoder {
    override fun decodeVideoFrame(context: Context, uri: Uri, maxDimensionPx: Int): Bitmap? {
        val sdkInt = sdkIntProvider()
        // On API 26 (below O_MR1), MediaMetadataRetriever lacks getScaledFrameAtTime and calling
        // getFrameAtTime() allocates an unbounded full-resolution 4K/8K frame. Prefer the bounded
        // MediaStore MINI_KIND thumbnail source; if unavailable, fail cleanly so UI shows a placeholder.
        return if (sdkInt < Build.VERSION_CODES.O_MR1) {
            decodeBoundedApi26VideoThumb(context, uri)
        } else {
            decodeScaledApi27PlusVideoFrame(context, uri, maxDimensionPx)
        }
    }

    private fun decodeBoundedApi26VideoThumb(context: Context, uri: Uri): Bitmap? {
        val mediaId = runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it > 0L } ?: return null
        return try {
            legacyMediaStoreThumb(context, mediaId)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun decodeScaledApi27PlusVideoFrame(context: Context, uri: Uri, maxDimensionPx: Int): Bitmap? {
        val retriever = retrieverFactory()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getScaledFrameAtTime(
                -1L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                maxDimensionPx,
                maxDimensionPx
            ) ?: retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                maxDimensionPx,
                maxDimensionPx
            )
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

@Suppress("DEPRECATION")
private fun decodeLegacyMediaStoreVideoThumb(context: Context, videoId: Long): Bitmap? =
    MediaStore.Video.Thumbnails.getThumbnail(
        context.contentResolver,
        videoId,
        MediaStore.Video.Thumbnails.MINI_KIND,
        null
    )

internal class AndroidLocalThumbnailGenerator(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val videoFrameDecoder: VideoFrameDecoder = AndroidVideoFrameDecoder()
) : LocalThumbnailGenerator {
    private val thumbnailsDir: File
        get() = File(context.filesDir, THUMBNAILS_DIR_NAME)

    override fun thumbnailFileFor(itemId: String): File {
        val safeKey = sanitizeThumbnailKey(itemId)
        return File(thumbnailsDir, "$safeKey.webp")
    }

    override fun isThumbnailUsable(path: String?): Boolean {
        val file = path?.takeIf { it.isNotBlank() }?.let(::File)
        return if (file != null && file.isFile && file.length() > 0L) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        } else {
            false
        }
    }

    override suspend fun pruneOrphanThumbnails(
        referencedPaths: Set<String>,
        activeMediaIds: Set<String>,
        staleTempAgeMs: Long,
        nowMs: Long
    ): Int = withContext(dispatcherProvider.io) {
        val dir = thumbnailsDir
        val files = dir.listFiles() ?: return@withContext 0
        val protectedCanonicalPaths = referencedPaths.mapNotNull { path ->
            runCatching { File(path).canonicalPath }.getOrNull()
        }.toSet()
        val protectedFilenames = activeMediaIds.mapTo(HashSet()) { id ->
            "${sanitizeThumbnailKey(id)}.webp"
        }

        var deletedCount = 0
        files.forEach { candidate ->
            val shouldDelete = when {
                candidate.name.contains(".tmp.") -> (nowMs - candidate.lastModified()) >= staleTempAgeMs

                candidate.extension.equals("webp", ignoreCase = true) -> {
                    val canonical = runCatching { candidate.canonicalPath }.getOrNull() ?: candidate.absolutePath
                    canonical !in protectedCanonicalPaths && candidate.name !in protectedFilenames
                }

                else -> false
            }
            if (shouldDelete && candidate.delete()) {
                deletedCount += 1
            }
        }
        deletedCount
    }

    override suspend fun generateThumbnail(
        itemId: String,
        sourceUri: String,
        mediaType: MediaType
    ): Outcome<String, AppError> = withContext(dispatcherProvider.io) {
        if (itemId.isBlank() || sourceUri.isBlank()) {
            return@withContext Outcome.Failure(
                AppError.Validation("Item ID and source URI must not be blank for thumbnail generation.")
            )
        }

        val parsedUri = runCatching { Uri.parse(sourceUri) }.getOrNull()
            ?: return@withContext Outcome.Failure(AppError.Validation("Invalid source media URI."))

        val decoded = when (mediaType) {
            MediaType.IMAGE -> decodeImageThumbnail(parsedUri)
            MediaType.VIDEO -> decodeVideoThumbnail(parsedUri)
        }
        when (decoded) {
            is Outcome.Failure -> decoded
            is Outcome.Success -> writeAtomicWebp(targetFile = thumbnailFileFor(itemId), bitmap = decoded.value)
        }
    }

    private fun decodeImageThumbnail(uri: Uri): Outcome<Bitmap, AppError> = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: return Outcome.Failure(AppError.Io("Unable to open image stream for thumbnail generation."))
        boundsStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return Outcome.Failure(AppError.Io("Image stream is corrupted or has invalid dimensions."))
        }

        val sampleSize = calculateInSampleSize(
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
            targetMaxDimension = MAX_THUMB_DIMENSION_PX
        )
        val orientation = readExifOrientation(uri)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampledBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return Outcome.Failure(AppError.Io("Failed to decode subsampled image bitmap."))

        val orientedBitmap = applyExifOrientation(sampledBitmap, orientation)
        val finalBitmap = scaleToFitBounds(orientedBitmap, MAX_THUMB_DIMENSION_PX)
        Outcome.Success(finalBitmap)
    } catch (_: SecurityException) {
        Outcome.Failure(AppError.Unauthorized("Permission denied while opening image for thumbnailing."))
    } catch (error: IOException) {
        Outcome.Failure(AppError.Io("I/O error while reading image stream for thumbnailing.", cause = error))
    } catch (error: IllegalArgumentException) {
        Outcome.Failure(AppError.Io("Malformed image URI or decode parameters.", cause = error))
    }

    private fun decodeVideoThumbnail(uri: Uri): Outcome<Bitmap, AppError> {
        val frame = videoFrameDecoder.decodeVideoFrame(context, uri, MAX_THUMB_DIMENSION_PX)
            ?: return Outcome.Failure(AppError.Io("Unable to extract video frame thumbnail."))
        return Outcome.Success(scaleToFitBounds(frame, MAX_THUMB_DIMENSION_PX))
    }

    private fun readExifOrientation(uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    } catch (_: SecurityException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = computeExifMatrix(orientation) ?: return source
        val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (transformed !== source) {
            source.recycle()
        }
        return transformed
    }

    private fun scaleToFitBounds(source: Bitmap, maxDimensionPx: Int): Bitmap {
        val (targetWidth, targetHeight) = computeScaledDimensions(
            width = source.width,
            height = source.height,
            maxDimension = maxDimensionPx
        )
        if (targetWidth == source.width && targetHeight == source.height) {
            return source
        }
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        if (scaled !== source) {
            source.recycle()
        }
        return scaled
    }

    private fun writeAtomicWebp(targetFile: File, bitmap: Bitmap): Outcome<String, AppError> {
        val dir = targetFile.parentFile ?: thumbnailsDir
        if (!dir.exists() && !dir.mkdirs()) {
            bitmap.recycle()
            return Outcome.Failure(AppError.Io("Unable to create private thumbnail directory."))
        }

        val tempFile = File(dir, "${targetFile.name}.tmp.${System.nanoTime()}")
        return try {
            FileOutputStream(tempFile).use { output ->
                val format = webpCompressFormat()
                val compressed = bitmap.compress(format, WEBP_QUALITY, output)
                if (!compressed) {
                    throw IOException("Bitmap WebP compression returned false.")
                }
                output.flush()
                runCatching { output.fd.sync() }
            }
            moveReplacingTarget(tempFile, targetFile)
            Outcome.Success(targetFile.absolutePath)
        } catch (error: IOException) {
            Outcome.Failure(AppError.Io("Failed to write WebP thumbnail file.", cause = error))
        } finally {
            bitmap.recycle()
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    internal companion object {
        const val THUMBNAILS_DIR_NAME = "thumbnails"
        const val MAX_THUMB_DIMENSION_PX = 320
        const val WEBP_QUALITY = 80
    }
}

private fun moveReplacingTarget(source: File, target: File) {
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

private fun webpCompressFormat(): Bitmap.CompressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Bitmap.CompressFormat.WEBP_LOSSY
} else {
    @Suppress("DEPRECATION")
    Bitmap.CompressFormat.WEBP
}

internal fun calculateInSampleSize(sourceWidth: Int, sourceHeight: Int, targetMaxDimension: Int): Int {
    if (sourceWidth <= targetMaxDimension && sourceHeight <= targetMaxDimension) return 1
    var inSampleSize = 1
    val halfWidth = sourceWidth / 2
    val halfHeight = sourceHeight / 2
    while ((halfWidth / inSampleSize) >= targetMaxDimension &&
        (halfHeight / inSampleSize) >= targetMaxDimension
    ) {
        inSampleSize *= 2
    }
    return inSampleSize.coerceAtLeast(1)
}

internal fun computeScaledDimensions(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
    val largest = max(width, height)
    return when {
        width <= 0 || height <= 0 -> 1 to 1

        largest <= maxDimension -> width to height

        else -> {
            val scale = maxDimension.toFloat() / largest.toFloat()
            (width * scale).roundToInt().coerceAtLeast(1) to
                (height * scale).roundToInt().coerceAtLeast(1)
        }
    }
}

internal fun computeExifMatrix(orientation: Int): Matrix? {
    val transform = EXIF_ORIENTATION_TRANSFORMS[orientation] ?: return null
    return Matrix().apply {
        if (transform.rotateDegrees != 0f) {
            postRotate(transform.rotateDegrees)
        }
        if (transform.flipHorizontal) {
            postScale(-1f, 1f)
        }
        if (transform.flipVertical) {
            postScale(1f, -1f)
        }
    }
}

private data class ExifTransform(
    val rotateDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false
)

internal fun sanitizeThumbnailKey(itemId: String): String =
    itemId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "thumb" }

private const val DEGREES_90 = 90f
private const val DEGREES_180 = 180f
private const val DEGREES_270 = 270f

private val EXIF_ORIENTATION_TRANSFORMS: Map<Int, ExifTransform> = mapOf(
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL to ExifTransform(flipHorizontal = true),
    ExifInterface.ORIENTATION_ROTATE_180 to ExifTransform(rotateDegrees = DEGREES_180),
    ExifInterface.ORIENTATION_FLIP_VERTICAL to ExifTransform(flipVertical = true),
    ExifInterface.ORIENTATION_TRANSPOSE to ExifTransform(rotateDegrees = DEGREES_90, flipHorizontal = true),
    ExifInterface.ORIENTATION_ROTATE_90 to ExifTransform(rotateDegrees = DEGREES_90),
    ExifInterface.ORIENTATION_TRANSVERSE to ExifTransform(rotateDegrees = DEGREES_270, flipHorizontal = true),
    ExifInterface.ORIENTATION_ROTATE_270 to ExifTransform(rotateDegrees = DEGREES_270)
)
