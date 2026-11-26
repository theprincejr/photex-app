package com.example.photx.core.media

import android.Manifest
import android.os.Build
import com.example.photx.core.domain.gallery.MediaPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPermissionCheckerTest {
    @Test
    fun `given android 12 or earlier_when evaluating read external storage_then maps full or denied`() {
        val sdk = Build.VERSION_CODES.S_V2
        assertEquals(
            MediaPermissionState.Full,
            evaluateMediaPermissionState(sdk) { it == Manifest.permission.READ_EXTERNAL_STORAGE }
        )
        assertEquals(
            MediaPermissionState.Denied,
            evaluateMediaPermissionState(sdk) { false }
        )
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), requiredPermissionsForApi(sdk))
    }

    @Test
    fun `given android 13_when evaluating split media permissions_then distinguishes full partial and denied`() {
        val sdk = Build.VERSION_CODES.TIRAMISU
        assertEquals(
            MediaPermissionState.Full,
            evaluateMediaPermissionState(sdk) {
                it == Manifest.permission.READ_MEDIA_IMAGES || it == Manifest.permission.READ_MEDIA_VIDEO
            }
        )
        assertEquals(
            MediaPermissionState.Partial(imagesGranted = true, videosGranted = false, userSelectedOnly = false),
            evaluateMediaPermissionState(sdk) { it == Manifest.permission.READ_MEDIA_IMAGES }
        )
        assertEquals(
            MediaPermissionState.Partial(imagesGranted = false, videosGranted = true, userSelectedOnly = false),
            evaluateMediaPermissionState(sdk) { it == Manifest.permission.READ_MEDIA_VIDEO }
        )
        assertEquals(
            MediaPermissionState.Denied,
            evaluateMediaPermissionState(sdk) { false }
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
            requiredPermissionsForApi(sdk)
        )
    }

    @Test
    fun `given android 14 plus_when evaluating visual selection_then maps partial safely`() {
        val sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val selectedOnly = evaluateMediaPermissionState(sdk) {
            it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }
        assertEquals(
            MediaPermissionState.Partial(imagesGranted = false, videosGranted = false, userSelectedOnly = true),
            selectedOnly
        )
        assertTrue(selectedOnly.canScanDeviceMedia)
        assertFalse(selectedOnly.allowsExternalDeletionReconciliation)

        val grantedAll = evaluateMediaPermissionState(sdk) {
            it == Manifest.permission.READ_MEDIA_IMAGES ||
                it == Manifest.permission.READ_MEDIA_VIDEO ||
                it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }
        assertEquals(MediaPermissionState.Full, grantedAll)
        assertTrue(grantedAll.allowsExternalDeletionReconciliation)
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ),
            requiredPermissionsForApi(sdk)
        )

        // Revoking video while keeping images or keeping userSelected + images keeps Partial cleanly.
        val imagesAndUserSelected = evaluateMediaPermissionState(sdk) {
            it == Manifest.permission.READ_MEDIA_IMAGES ||
                it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }
        assertEquals(
            MediaPermissionState.Partial(imagesGranted = true, videosGranted = false, userSelectedOnly = false),
            imagesAndUserSelected
        )
        assertTrue(imagesAndUserSelected.canScanDeviceMedia)
        assertFalse(imagesAndUserSelected.allowsExternalDeletionReconciliation)
    }
}
