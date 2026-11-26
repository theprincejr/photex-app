package com.example.photx.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.photx.core.designsystem.PhotxTheme
import java.io.File

private val ALBUM_PLACEHOLDER_ICON_SIZE = 32.dp

/**
 * Immutable presentation model for a PhotX two-column album card.
 */
@Immutable
public data class PhotxAlbumCardUiModel(
    public val id: String,
    public val title: String,
    public val itemCount: Int,
    public val coverThumbPath: String? = null
)

/**
 * Album card with a `16.dp` rounded square cover image, title, and tabular item count.
 */
@Composable
public fun PhotxAlbumCard(album: PhotxAlbumCardUiModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing
    val cardShape = RoundedCornerShape(PhotxTheme.radii.albumCard)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(cardShape)
                .background(colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            val coverFile = album.coverThumbPath?.takeIf { it.isNotBlank() }?.let(::File)
            if (coverFile != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverFile)
                        .crossfade(true)
                        .build(),
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Collections,
                    contentDescription = album.title,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(ALBUM_PLACEHOLDER_ICON_SIZE)
                )
            }
        }
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = album.title,
            style = PhotxTheme.typography.titleMedium,
            color = colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${album.itemCount} items",
            style = PhotxTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1
        )
    }
}
