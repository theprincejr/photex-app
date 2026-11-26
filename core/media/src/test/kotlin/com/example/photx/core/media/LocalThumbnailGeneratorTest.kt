package com.example.photx.core.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.model.MediaType
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalThumbnailGeneratorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FileBackedTestProvider(private val filesByPath: MutableMap<String, File> = mutableMapOf()) :
        ContentProvider() {
        fun registerFile(pathKey: String, file: File): String {
            filesByPath["/$pathKey"] = file
            return "content://com.example.photx.testmedia/$pathKey"
        }

        override fun onCreate(): Boolean = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val file = filesByPath[uri.path] ?: throw java.io.FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
    }

    private fun testDispatchers(scheduler: TestCoroutineScheduler): DispatcherProvider {
        val dispatcher = StandardTestDispatcher(scheduler)
        return object : DispatcherProvider {
            override val main: CoroutineDispatcher = dispatcher
            override val mainImmediate: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
        }
    }

    @Test
    fun `given landscape and rotated images_when generating_then resizes atomically`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = FileBackedTestProvider().apply {
            attachInfo(
                context,
                android.content.pm.ProviderInfo().apply { authority = "com.example.photx.testmedia" }
            )
        }
        ShadowContentResolver.registerProviderInternal("com.example.photx.testmedia", provider)

        val landscapeFile = tempFolder.newFile("landscape.jpg")
        writeTestJpeg(landscapeFile, width = 800, height = 400, exifOrientation = ExifInterface.ORIENTATION_NORMAL)
        val landscapeUri = provider.registerFile("img1", landscapeFile)

        val rotatedFile = tempFolder.newFile("rotated.jpg")
        writeTestJpeg(
            rotatedFile,
            width = 800,
            height = 400,
            exifOrientation = ExifInterface.ORIENTATION_ROTATE_90
        )
        val rotatedUri = provider.registerFile("img2", rotatedFile)

        val generator = AndroidLocalThumbnailGenerator(context, testDispatchers(testScheduler))

        val landscapeResult = generator.generateThumbnail("item-1", landscapeUri, MediaType.IMAGE)
        assertTrue("Expected Success for landscape image but got: $landscapeResult", landscapeResult is Outcome.Success)
        val landscapeThumb = File((landscapeResult as Outcome.Success).value)
        assertTrue(landscapeThumb.isFile)
        assertTrue(landscapeThumb.length() > 0L)
        assertTrue(generator.isThumbnailUsable(landscapeThumb.absolutePath))
        val landscapeBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(landscapeThumb.absolutePath, landscapeBounds)
        assertEquals(320, landscapeBounds.outWidth)
        assertEquals(160, landscapeBounds.outHeight)

        val rotatedResult = generator.generateThumbnail("item-2", rotatedUri, MediaType.IMAGE)
        assertTrue("Expected Success for rotated image but got: $rotatedResult", rotatedResult is Outcome.Success)
        val rotatedBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile((rotatedResult as Outcome.Success).value, rotatedBounds)
        assertEquals(160, rotatedBounds.outWidth)
        assertEquals(320, rotatedBounds.outHeight)

        // Verify pure aspect-ratio math and EXIF matrices explicitly.
        assertEquals(320 to 160, computeScaledDimensions(800, 400, 320))
        assertEquals(160 to 320, computeScaledDimensions(400, 800, 320))
        assertEquals(200 to 100, computeScaledDimensions(200, 100, 320))
        assertEquals(2, calculateInSampleSize(1280, 720, 320))
        assertTrue(computeExifMatrix(ExifInterface.ORIENTATION_ROTATE_90) != null)
        assertTrue(computeExifMatrix(ExifInterface.ORIENTATION_ROTATE_270) != null)

        // No leftover .tmp files in thumbnails directory.
        val thumbDir = File(context.filesDir, AndroidLocalThumbnailGenerator.THUMBNAILS_DIR_NAME)
        assertTrue(thumbDir.listFiles()?.none { it.name.contains(".tmp.") } == true)
    }

    @Test
    fun `given video frame decoder and corrupted or missing sources_when generating_then handles cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = FileBackedTestProvider().apply {
            attachInfo(
                context,
                android.content.pm.ProviderInfo().apply { authority = "com.example.photx.testmedia" }
            )
        }
        ShadowContentResolver.registerProviderInternal("com.example.photx.testmedia", provider)

        val corruptFile = tempFolder.newFile("corrupt.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val corruptUri = provider.registerFile("bad", corruptFile)
        val missingUri = "content://com.example.photx.testmedia/does-not-exist"

        val videoDecoder = object : VideoFrameDecoder {
            override fun decodeVideoFrame(context: Context, uri: Uri, maxDimensionPx: Int): Bitmap? =
                if (uri.path == "/vid_ok") {
                    Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                } else {
                    null
                }
        }
        val generator = AndroidLocalThumbnailGenerator(
            context = context,
            dispatcherProvider = testDispatchers(testScheduler),
            videoFrameDecoder = videoDecoder
        )

        // Existing thumbnail must stay untouched if regeneration on a bad URI fails.
        val existing = generator.thumbnailFileFor("vid-item").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9, 9, 9))
        }
        val failedVideo = generator.generateThumbnail(
            itemId = "vid-item",
            sourceUri = "content://com.example.photx.testmedia/vid_bad",
            mediaType = MediaType.VIDEO
        )
        assertTrue(failedVideo is Outcome.Failure)
        assertEquals(listOf(9.toByte(), 9.toByte(), 9.toByte()), existing.readBytes().toList())

        // Valid video frame atomically replaces previous thumbnail with scaled WebP.
        val okVideo = generator.generateThumbnail(
            itemId = "vid-item",
            sourceUri = "content://com.example.photx.testmedia/vid_ok",
            mediaType = MediaType.VIDEO
        )
        assertTrue(okVideo is Outcome.Success)
        assertTrue(File((okVideo as Outcome.Success).value).length() > 3L)

        val corruptOutcome = generator.generateThumbnail("corrupt", corruptUri, MediaType.IMAGE)
        assertTrue(corruptOutcome is Outcome.Failure)
        assertTrue((corruptOutcome as Outcome.Failure).error is AppError.Io)

        val missingOutcome = generator.generateThumbnail("missing", missingUri, MediaType.IMAGE)
        assertTrue(missingOutcome is Outcome.Failure)

        val thumbDir = File(context.filesDir, AndroidLocalThumbnailGenerator.THUMBNAILS_DIR_NAME)
        assertTrue(thumbDir.listFiles()?.none { it.name.contains(".tmp.") } == true)
    }

    @Test
    fun `given api26 bounded video fallback_when present or absent_then avoids full alloc`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatchers = testDispatchers(testScheduler)
        var retrieverCalls = 0
        var requestedVideoId: Long? = null

        val api26Decoder = AndroidVideoFrameDecoder(
            sdkIntProvider = { Build.VERSION_CODES.O },
            legacyMediaStoreThumb = { _, videoId ->
                requestedVideoId = videoId
                Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
            },
            retrieverFactory = {
                retrieverCalls += 1
                error("Unbounded MediaMetadataRetriever must not run on API 26!")
            }
        )
        val decodedLegacy = api26Decoder.decodeVideoFrame(
            context,
            Uri.parse("content://media/external/video/media/42"),
            maxDimensionPx = 320
        )
        assertEquals(42L, requestedVideoId)
        assertEquals(0, retrieverCalls)
        assertTrue(decodedLegacy != null)
        decodedLegacy?.recycle()

        val unavailableGen = AndroidLocalThumbnailGenerator(
            context = context,
            dispatcherProvider = dispatchers,
            videoFrameDecoder = AndroidVideoFrameDecoder(
                sdkIntProvider = { Build.VERSION_CODES.O },
                legacyMediaStoreThumb = { _, _ -> null },
                retrieverFactory = {
                    retrieverCalls += 1
                    error("Must never fall back to unbounded getFrameAtTime on API 26!")
                }
            )
        )
        val existing = unavailableGen.thumbnailFileFor("api26-missing").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(5, 6, 7))
        }
        val outcome = unavailableGen.generateThumbnail(
            itemId = "api26-missing",
            sourceUri = "content://media/external/video/media/999",
            mediaType = MediaType.VIDEO
        )
        assertTrue(outcome is Outcome.Failure)
        assertEquals(0, retrieverCalls)
        assertEquals(listOf(5.toByte(), 6.toByte(), 7.toByte()), existing.readBytes().toList())
    }

    @Test
    fun `given api26 bounded thumb scaling and errors_when generating_then recycles and guards`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatchers = testDispatchers(testScheduler)
        var retrieverCalls = 0

        val sourceFrame = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
        val okApi26Gen = AndroidLocalThumbnailGenerator(
            context = context,
            dispatcherProvider = dispatchers,
            videoFrameDecoder = AndroidVideoFrameDecoder(
                sdkIntProvider = { Build.VERSION_CODES.O },
                legacyMediaStoreThumb = { _, _ -> sourceFrame },
                retrieverFactory = {
                    retrieverCalls += 1
                    error("Must never use MediaMetadataRetriever on API 26!")
                }
            )
        )
        val api26Thumb = okApi26Gen.generateThumbnail(
            itemId = "api26-ok",
            sourceUri = "content://media/external/video/media/77",
            mediaType = MediaType.VIDEO
        ) as Outcome.Success
        assertTrue(sourceFrame.isRecycled)
        val api26Bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(api26Thumb.value, api26Bounds)
        assertEquals(320, api26Bounds.outWidth)
        assertEquals(240, api26Bounds.outHeight)

        val throwingApi26Gen = AndroidLocalThumbnailGenerator(
            context = context,
            dispatcherProvider = dispatchers,
            videoFrameDecoder = AndroidVideoFrameDecoder(
                sdkIntProvider = { Build.VERSION_CODES.O },
                legacyMediaStoreThumb = { _, _ -> throw SecurityException("Denied") },
                retrieverFactory = {
                    retrieverCalls += 1
                    error("Must never use MediaMetadataRetriever on API 26!")
                }
            )
        )
        val existing = throwingApi26Gen.thumbnailFileFor("api26-missing").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(5, 6, 7))
        }
        assertTrue(
            throwingApi26Gen.generateThumbnail(
                itemId = "api26-nonnum",
                sourceUri = "content://com.example.photx.testmedia/video.mp4",
                mediaType = MediaType.VIDEO
            ) is Outcome.Failure
        )
        assertTrue(
            throwingApi26Gen.generateThumbnail(
                itemId = "api26-missing",
                sourceUri = "content://media/external/video/media/88",
                mediaType = MediaType.VIDEO
            ) is Outcome.Failure
        )
        assertEquals(0, retrieverCalls)
        assertEquals(listOf(5.toByte(), 6.toByte(), 7.toByte()), existing.readBytes().toList())
    }

    @Test
    fun `given recycled or oom video frame_when generating_then fails safely keeping existing`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatchers = testDispatchers(testScheduler)
        val existing = AndroidLocalThumbnailGenerator(context, dispatchers)
            .thumbnailFileFor("api26-missing")
            .apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(5, 6, 7))
            }

        val preRecycled = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { recycle() }
        val badFrameGen = AndroidLocalThumbnailGenerator(
            context = context,
            dispatcherProvider = dispatchers,
            videoFrameDecoder = object : VideoFrameDecoder {
                override fun decodeVideoFrame(context: Context, uri: Uri, maxDimensionPx: Int): Bitmap = preRecycled
            }
        )
        assertTrue(
            badFrameGen.generateThumbnail(
                itemId = "api26-missing",
                sourceUri = "content://media/external/video/media/90",
                mediaType = MediaType.VIDEO
            ) is Outcome.Failure
        )
        assertEquals(listOf(5.toByte(), 6.toByte(), 7.toByte()), existing.readBytes().toList())

        val oomGen = AndroidLocalThumbnailGenerator(
            context = context,
            dispatcherProvider = dispatchers,
            videoFrameDecoder = AndroidVideoFrameDecoder(
                sdkIntProvider = { Build.VERSION_CODES.O },
                legacyMediaStoreThumb = { _, _ -> throw OutOfMemoryError("Simulated low heap") }
            )
        )
        assertTrue(
            oomGen.generateThumbnail(
                itemId = "api26-missing",
                sourceUri = "content://media/external/video/media/91",
                mediaType = MediaType.VIDEO
            ) is Outcome.Failure
        )
        assertEquals(listOf(5.toByte(), 6.toByte(), 7.toByte()), existing.readBytes().toList())
    }

    @Test
    fun `given api27 plus video decoder_when succeeding or failing_then releases retriever cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatchers = testDispatchers(testScheduler)
        var releasedCount = 0

        val failingDecoder = AndroidVideoFrameDecoder(
            sdkIntProvider = { Build.VERSION_CODES.O_MR1 },
            retrieverFactory = { fakeRetriever(throwOnSource = true, onRelease = { releasedCount += 1 }) }
        )
        assertEquals(null, failingDecoder.decodeVideoFrame(context, Uri.parse("content://vid/1"), 320))
        assertEquals(1, releasedCount)

        val generator = AndroidLocalThumbnailGenerator(
            context = context,
            dispatcherProvider = dispatchers,
            videoFrameDecoder = AndroidVideoFrameDecoder(
                sdkIntProvider = { Build.VERSION_CODES.O_MR1 },
                retrieverFactory = { fakeRetriever(throwOnSource = false, onRelease = { releasedCount += 1 }) }
            )
        )
        val scaledVideoThumb = generator.generateThumbnail(
            itemId = "vid-4k",
            sourceUri = "content://media/external/video/media/99",
            mediaType = MediaType.VIDEO
        ) as Outcome.Success
        assertEquals(2, releasedCount)
        val vidBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(scaledVideoThumb.value, vidBounds)
        assertEquals(320, vidBounds.outWidth)
        assertEquals(180, vidBounds.outHeight)
    }

    private fun fakeRetriever(throwOnSource: Boolean, onRelease: () -> Unit): VideoRetrieverAdapter =
        object : VideoRetrieverAdapter {
            override fun setDataSource(context: Context, uri: Uri) {
                if (throwOnSource) throw IllegalArgumentException("Codec err")
            }

            override fun getScaledFrameAtTime(timeUs: Long, option: Int, maxWidth: Int, maxHeight: Int): Bitmap =
                Bitmap.createBitmap(3840, 2160, Bitmap.Config.ARGB_8888)

            override fun release() = onRelease()
        }

    @Test
    fun `given corrupt zero byte and orphan thumbnails_when checking and pruning_then recovers safely`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val generator = AndroidLocalThumbnailGenerator(context, testDispatchers(testScheduler))

        val validThumb = generator.thumbnailFileFor("vid-4k").apply {
            parentFile?.mkdirs()
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            FileOutputStream(this).use { out ->
                bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
            }
            bmp.recycle()
        }
        val zeroByte = generator.thumbnailFileFor("corrupt-zero").apply {
            writeBytes(ByteArray(0))
        }
        val junkBytes = generator.thumbnailFileFor("corrupt-junk").apply {
            writeBytes(byteArrayOf(7, 8, 9, 10))
        }
        assertTrue(generator.isThumbnailUsable(validThumb.absolutePath))
        assertFalse(generator.isThumbnailUsable(zeroByte.absolutePath))
        assertFalse(generator.isThumbnailUsable(junkBytes.absolutePath))
        assertFalse(generator.isThumbnailUsable(null))

        val cloudThumb = generator.thumbnailFileFor("cloud-keep").apply {
            writeBytes(validThumb.readBytes())
        }
        val orphanThumb = generator.thumbnailFileFor("orphan-delete").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val thumbDir = cloudThumb.parentFile!!
        val freshTmp = File(thumbDir, "active.webp.tmp.1").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(9_950_000L)
        }
        val staleTmp = File(thumbDir, "crashed.webp.tmp.2").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(1_000_000L)
        }

        val deletedCount = generator.pruneOrphanThumbnails(
            referencedPaths = setOf(validThumb.absolutePath),
            activeMediaIds = setOf("vid-4k", "cloud-keep"),
            staleTempAgeMs = 600_000L,
            nowMs = 10_000_000L
        )
        assertTrue(deletedCount >= 2)
        assertTrue(validThumb.exists())
        assertTrue(cloudThumb.exists())
        assertTrue(freshTmp.exists())
        assertFalse(orphanThumb.exists())
        assertFalse(staleTmp.exists())
    }

    private fun writeTestJpeg(target: File, width: Int, height: Int, exifOrientation: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
        val exif = ExifInterface(target.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
        exif.saveAttributes()
    }
}
