package com.example.photx.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.photx.core.domain.gallery.MediaPermissionState

/**
 * Evaluates runtime Android storage/media permissions dynamically on every check without caching.
 */
public interface MediaPermissionChecker {
    public fun checkPermissionState(): MediaPermissionState

    public fun requiredPermissionsForCurrentApi(): List<String>
}

internal class AndroidMediaPermissionChecker(private val context: Context) : MediaPermissionChecker {
    override fun checkPermissionState(): MediaPermissionState = evaluateMediaPermissionState(
        sdkInt = Build.VERSION.SDK_INT,
        hasPermission = { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    )

    override fun requiredPermissionsForCurrentApi(): List<String> = requiredPermissionsForApi(Build.VERSION.SDK_INT)
}

internal fun evaluateMediaPermissionState(sdkInt: Int, hasPermission: (String) -> Boolean): MediaPermissionState =
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        evaluateApi33PlusPermission(
            images = hasPermission(Manifest.permission.READ_MEDIA_IMAGES),
            videos = hasPermission(Manifest.permission.READ_MEDIA_VIDEO),
            userSelected = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        )
    } else if (hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
        MediaPermissionState.Full
    } else {
        MediaPermissionState.Denied
    }

private fun evaluateApi33PlusPermission(images: Boolean, videos: Boolean, userSelected: Boolean): MediaPermissionState =
    when {
        images && videos -> MediaPermissionState.Full

        images || videos || userSelected -> MediaPermissionState.Partial(
            imagesGranted = images,
            videosGranted = videos,
            userSelectedOnly = userSelected && !images && !videos
        )

        else -> MediaPermissionState.Denied
    }

internal fun requiredPermissionsForApi(sdkInt: Int): List<String> = when {
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )

    sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}
