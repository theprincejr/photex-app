package com.example.photx.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import java.io.File
import java.util.Locale

private val SELECTION_BORDER_WIDTH = 3.dp
private val SELECTION_CHECK_SIZE = 22.dp
private val SELECTION_CHECK_ICON_SIZE = 14.dp
private val SELECTION_UNCHECKED_BORDER = 1.5.dp
private val VIDEO_BADGE_HORIZONTAL_PADDING = 5.dp
private val VIDEO_BADGE_VERTICAL_PADDING = 2.dp
private val VIDEO_PLAY_ICON_SIZE = 12.dp
private val PLACEHOLDER_ICON_SIZE = 24.dp
private const val SELECTED_SCRIM_ALPHA = 0.25f
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

/**
 * Immutable presentation model for a gallery grid thumbnail tile.
 */
@Immutable
public data class PhotxMediaTileUiModel(
    public val id: String,
    public val localThumbPath: String?,
    public val mediaType: MediaType,
    public val syncState: SyncState,
    public val durationMs: Long? = null,
    public val contentDescription: String? = null
)

/**
 * Square (`1:1`), zero-corner-radius photo/video grid tile rendering only the local WebP
 * thumbnail (`localThumbPath`) via Coil 3, plus smart sync badge, duration pill, and selection ring.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
public fun PhotxPhotoThumbnailTile(
    item: PhotxMediaTileUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionEnabled: Boolean = false,
    badgeDisplayMode: PhotxBadgeDisplayMode = PhotxBadgeDisplayMode.SMART,
    onLongClick: (() -> Unit)? = null
) {
    val colors = PhotxTheme.colorScheme
    val tileShape = RoundedCornerShape(PhotxTheme.radii.gridTile)

    val selectionBorder = if (isSelected) {
        Modifier.border(width = SELECTION_BORDER_WIDTH, color = colors.primary, shape = tileShape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(tileShape)
            .background(colors.surfaceContainerHigh)
            .then(selectionBorder)
            .semantics {
                if (selectionEnabled) {
                    selected = isSelected
                }
            }
            .combinedClickable(
                role = Role.Image,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        ThumbnailImageContent(
            localThumbPath = item.localThumbPath,
            contentDescription = item.contentDescription
        )

        TileOverlays(
            item = item,
            isSelected = isSelected,
            selectionEnabled = selectionEnabled,
            badgeDisplayMode = badgeDisplayMode
        )
    }
}

@Composable
private fun BoxScope.TileOverlays(
    item: PhotxMediaTileUiModel,
    isSelected: Boolean,
    selectionEnabled: Boolean,
    badgeDisplayMode: PhotxBadgeDisplayMode
) {
    val spacing = PhotxTheme.spacing

    if (isSelected) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SELECTED_SCRIM_ALPHA))
        )
    }

    if (selectionEnabled) {
        SelectionIndicator(
            isSelected = isSelected,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(spacing.xs)
        )
    }

    PhotxSyncStatusBadge(
        syncState = item.syncState,
        displayMode = badgeDisplayMode,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(spacing.xs)
    )

    if (item.mediaType == MediaType.VIDEO) {
        VideoDurationBadge(
            durationMs = item.durationMs,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(spacing.xs)
        )
    }
}

@Composable
private fun ThumbnailImageContent(localThumbPath: String?, contentDescription: String?) {
    val validFile = localThumbPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
    var decodeFailed by remember(validFile?.absolutePath, validFile?.length()) { mutableStateOf(false) }
    if (validFile != null && !decodeFailed) {
        val context = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(validFile)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            onError = { decodeFailed = true },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Photo,
                contentDescription = contentDescription,
                tint = PhotxTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(PLACEHOLDER_ICON_SIZE)
            )
        }
    }
}

@Composable
private fun SelectionIndicator(isSelected: Boolean, modifier: Modifier = Modifier) {
    val colors = PhotxTheme.colorScheme
    if (isSelected) {
        Box(
            modifier = modifier
                .size(SELECTION_CHECK_SIZE)
                .clip(CircleShape)
                .background(colors.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = colors.onPrimary,
                modifier = Modifier.size(SELECTION_CHECK_ICON_SIZE)
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(SELECTION_CHECK_SIZE)
                .clip(CircleShape)
                .background(PhotxTheme.glass.scrimContainer)
                .border(width = SELECTION_UNCHECKED_BORDER, color = PhotxTheme.glass.onScrim, shape = CircleShape)
        )
    }
}

@Composable
private fun VideoDurationBadge(durationMs: Long?, modifier: Modifier = Modifier) {
    val glass = PhotxTheme.glass
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(glass.scrimContainer)
            .padding(horizontal = VIDEO_BADGE_HORIZONTAL_PADDING, vertical = VIDEO_BADGE_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhotxTheme.spacing.xxs)
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = glass.onScrim,
            modifier = Modifier.size(VIDEO_PLAY_ICON_SIZE)
        )
        Text(
            text = formatVideoDuration(durationMs),
            style = PhotxTheme.typography.labelSmall,
            color = glass.onScrim
        )
    }
}

internal fun formatVideoDuration(durationMs: Long?): String {
    val totalSeconds = ((durationMs ?: 0L).coerceAtLeast(0L)) / MILLIS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
