package com.example.photx.core.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.domain.gallery.MediaScanScope
import com.example.photx.core.model.MediaType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaStoreScannerTest {
    private class FakePermissionChecker(var state: MediaPermissionState) : MediaPermissionChecker {
        override fun checkPermissionState(): MediaPermissionState = state
        override fun requiredPermissionsForCurrentApi(): List<String> = emptyList()
    }

    private class FakeMediaContentProvider(
        private val imageCursorProvider: () -> Cursor?,
        private val videoCursorProvider: () -> Cursor?
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = when {
            uri == MediaStore.Images.Media.EXTERNAL_CONTENT_URI -> imageCursorProvider()
            uri == MediaStore.Video.Media.EXTERNAL_CONTENT_URI -> videoCursorProvider()
            else -> null
        }

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

    private fun createCameraAndVideoProvider(): FakeMediaContentProvider = FakeMediaContentProvider(
        imageCursorProvider = ::createImageTestCursor,
        videoCursorProvider = ::createVideoTestCursor
    )

    private fun baseColumns(): Array<String> = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.RELATIVE_PATH
    )

    private fun createImageTestCursor(): Cursor = MatrixCursor(baseColumns()).apply {
        addRow(arrayOf<Any?>(10L, "IMG_10.jpg", "image/jpeg", 2048L, 4000, 3000, 5000L, 4L, 4L, "DCIM/Camera/"))
        addRow(arrayOf<Any?>(10L, "IMG_10_DUP.jpg", "image/jpeg", 2048L, 4000, 3000, 5000L, 4L, 4L, "DCIM/Camera/"))
        addRow(arrayOf<Any?>(-1L, "BAD.jpg", "image/jpeg", 10L, 10, 10, 10L, 1L, 1L, "DCIM/Camera/"))
        addRow(arrayOf<Any?>(11L, "DL.png", "image/png", 100L, 100, 100, 9000L, 9L, 9L, "Download/"))
        addRow(arrayOf<Any?>(12L, null, "", -5L, -10, -20, 0L, 7L, 6L, "DCIM/Camera"))
    }

    private fun createVideoTestCursor(): Cursor = MatrixCursor(baseColumns() + MediaStore.Video.VideoColumns.DURATION)
        .apply {
            addRow(
                arrayOf<Any?>(20L, "VID_20.mp4", "video/mp4", 9000L, 1920, 1080, 6000L, 6L, 6L, "DCIM/Camera/", 12500L)
            )
        }

    @Test
    fun `given camera media malformed rows and duplicates_when scanning_then filters cleanly`() = runTest {
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, createCameraAndVideoProvider())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val scanner = AndroidMediaStoreScanner(
            context = context,
            permissionChecker = FakePermissionChecker(MediaPermissionState.Full),
            dispatcherProvider = testDispatchers(testScheduler)
        )

        val outcome = scanner.scan(MediaScanScope())
        assertTrue(outcome is Outcome.Success)
        val items = (outcome as Outcome.Success).value
        assertEquals(3, items.size)

        assertEquals(listOf("image_12", "VID_20.mp4", "IMG_10.jpg"), items.map(ScannedMediaItem::fileName))
        assertEquals(7000L, items[0].takenAt)
        assertEquals("image/*", items[0].mimeType)
        assertEquals(0L, items[0].sizeBytes)
        assertEquals(0, items[0].width)
        assertEquals(MediaType.VIDEO, items[1].mediaType)
        assertEquals(12500L, items[1].durationMs)
    }

    @Test
    fun `given denied or images only permission_when scanning_then respects permission scope cleanly`() = runTest {
        val provider = FakeMediaContentProvider(
            imageCursorProvider = {
                val cols = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DATE_TAKEN
                )
                MatrixCursor(cols).apply {
                    addRow(arrayOf<Any?>(1L, "only_img.jpg", "DCIM/Camera/", 1000L))
                }
            },
            videoCursorProvider = { error("Video collection must not be queried when videos permission is denied") }
        )
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissionChecker = FakePermissionChecker(MediaPermissionState.Denied)
        val scanner = AndroidMediaStoreScanner(context, permissionChecker, testDispatchers(testScheduler))

        val deniedOutcome = scanner.scan()
        assertTrue(deniedOutcome is Outcome.Failure)
        assertTrue((deniedOutcome as Outcome.Failure).error is AppError.Unauthorized)

        permissionChecker.state = MediaPermissionState.Partial(
            imagesGranted = true,
            videosGranted = false,
            userSelectedOnly = false
        )
        val partialOutcome = scanner.scan()
        assertTrue(partialOutcome is Outcome.Success)
        assertEquals(listOf("only_img.jpg"), (partialOutcome as Outcome.Success).value.map { it.fileName })
    }

    @Test
    fun `given videos only or user selected and security revocation_then behaves cleanly`() = runTest {
        val provider = FakeMediaContentProvider(
            imageCursorProvider = { error("Image collection must not be queried when images permission is denied") },
            videoCursorProvider = {
                val cols = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.Video.VideoColumns.DURATION
                )
                MatrixCursor(cols).apply {
                    addRow(arrayOf<Any?>(2L, "only_vid.mp4", "DCIM/Camera/", 2000L, 3500L))
                    addRow(arrayOf<Any?>(3L, "custom_vid.mp4", "Movies/Trip/", 3000L, 8000L))
                }
            }
        )
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissionChecker = FakePermissionChecker(
            MediaPermissionState.Partial(imagesGranted = false, videosGranted = true, userSelectedOnly = false)
        )
        val scanner = AndroidMediaStoreScanner(context, permissionChecker, testDispatchers(testScheduler))

        val videosOnly = scanner.scan(MediaScanScope(setOf("DCIM/Camera", "Movies/Trip"))) as Outcome.Success
        assertEquals(listOf("custom_vid.mp4", "only_vid.mp4"), videosOnly.value.map { it.fileName })

        // Android 14+ user-selected-only queries both collections safely when accessible.
        val bothProvider = createCameraAndVideoProvider()
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, bothProvider)
        permissionChecker.state = MediaPermissionState.Partial(
            imagesGranted = false,
            videosGranted = false,
            userSelectedOnly = true
        )
        val selectedOnly = scanner.scan() as Outcome.Success
        assertEquals(3, selectedOnly.value.size)

        // Runtime SecurityException mid-query returns Outcome.Failure(AppError.Unauthorized).
        val revokedProvider = FakeMediaContentProvider(
            imageCursorProvider = { throw SecurityException("Permission revoked concurrently") },
            videoCursorProvider = { null }
        )
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, revokedProvider)
        permissionChecker.state = MediaPermissionState.Full
        val revoked = scanner.scan()
        assertTrue(revoked is Outcome.Failure)
        assertTrue((revoked as Outcome.Failure).error is AppError.Unauthorized)
    }
}
