package com.example.photx.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState

private const val PREVIEW_DURATION_MS = 14_000L
private const val PREVIEW_ALBUM_COUNT = 128

@Composable
private fun CoreUiShowcase() {
    val spacing = PhotxTheme.spacing
    Surface(color = PhotxTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            PhotxPermissionBanner(
                permissionState = MediaPermissionState.Partial(
                    imagesGranted = false,
                    videosGranted = false,
                    userSelectedOnly = true
                ),
                onRequestFullAccess = {},
                onManageSelection = {}
            )
            PhotxDateHeader(title = "Today", itemCount = 3)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xxs)
            ) {
                PhotxPhotoThumbnailTile(
                    item = PhotxMediaTileUiModel(
                        id = "1",
                        localThumbPath = null,
                        mediaType = MediaType.IMAGE,
                        syncState = SyncState.CLOUD_ONLY
                    ),
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
                PhotxPhotoThumbnailTile(
                    item = PhotxMediaTileUiModel(
                        id = "2",
                        localThumbPath = null,
                        mediaType = MediaType.VIDEO,
                        syncState = SyncState.UPLOADING,
                        durationMs = PREVIEW_DURATION_MS
                    ),
                    isSelected = true,
                    selectionEnabled = true,
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
            }
            PhotxAlbumCard(
                album = PhotxAlbumCardUiModel(id = "cam", title = "Camera", itemCount = PREVIEW_ALBUM_COUNT),
                onClick = {},
                modifier = Modifier.padding(horizontal = spacing.md)
            )
            PhotxNavigationDock(
                selectedDestination = PhotxDockDestination.PHOTOS,
                onSelectDestination = {}
            )
        }
    }
}

@Preview(name = "Core UI Dark", showBackground = true)
@Composable
private fun CoreUiDarkPreview() {
    PhotxTheme(darkTheme = true) {
        CoreUiShowcase()
    }
}

@Preview(name = "Core UI Light", showBackground = true)
@Composable
private fun CoreUiLightPreview() {
    PhotxTheme(darkTheme = false) {
        CoreUiShowcase()
    }
}
