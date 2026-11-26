package com.example.photx.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.ui.PhotxAlbumCard
import com.example.photx.core.ui.PhotxAlbumCardUiModel
import com.example.photx.core.ui.PhotxEmptyState
import com.example.photx.core.ui.PhotxPhotoThumbnailTile

private const val ALBUMS_COLUMN_COUNT = 2
private const val ALBUM_DETAIL_COLUMN_COUNT = 4

/**
 * Hilt-connected two-column Albums screen backed by live Room-indexed media.
 */
@Composable
public fun AlbumsRoute(
    onOpenAlbum: (GalleryAlbumId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val albumList by viewModel.albums.collectAsStateWithLifecycle()
    AlbumsScreen(
        albums = albumList,
        onOpenAlbum = onOpenAlbum,
        modifier = modifier
    )
}

@Composable
public fun AlbumsScreen(
    albums: List<Pair<GalleryAlbumId, PhotxAlbumCardUiModel>>,
    onOpenAlbum: (GalleryAlbumId) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
    ) {
        Text(
            text = "Albums",
            style = PhotxTheme.typography.headlineMedium,
            color = colors.onBackground,
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.md)
        )
        if (albums.isEmpty()) {
            PhotxEmptyState(
                title = "No albums yet",
                message = "Grant photo access or index local photos to organize albums automatically."
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(ALBUMS_COLUMN_COUNT),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = spacing.md,
                    end = spacing.md,
                    top = spacing.xs,
                    bottom = spacing.dockBottomClearance
                ),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.lg)
            ) {
                items(albums, key = { (id, _) -> id.name }) { (albumId, model) ->
                    PhotxAlbumCard(
                        album = model,
                        onClick = { onOpenAlbum(albumId) }
                    )
                }
            }
        }
    }
}

/**
 * Hilt-connected album detail grid displaying filtered local/cloud items for [albumId].
 */
@Composable
public fun AlbumDetailRoute(
    albumId: GalleryAlbumId,
    onBack: () -> Unit,
    onOpenMedia: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val recentItems by viewModel.recentItems.collectAsStateWithLifecycle()
    val filtered = filterItemsForAlbum(recentItems, albumId)

    AlbumDetailScreen(
        title = albumId.title,
        items = filtered,
        onBack = onBack,
        onOpenMedia = onOpenMedia,
        modifier = modifier
    )
}

@Composable
public fun AlbumDetailScreen(
    title: String,
    items: List<GalleryMediaItem>,
    onBack: () -> Unit,
    onOpenMedia: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xs, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back to albums",
                    tint = colors.onBackground
                )
            }
            Text(
                text = title,
                style = PhotxTheme.typography.titleLarge,
                color = colors.onBackground
            )
        }

        if (items.isEmpty()) {
            PhotxEmptyState(
                title = "Album is empty",
                message = "No indexed items currently match $title."
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(ALBUM_DETAIL_COLUMN_COUNT),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs)
            ) {
                items(items, key = { it.id }) { item ->
                    PhotxPhotoThumbnailTile(
                        item = item.toTileUiModel(),
                        onClick = { onOpenMedia(item.id) }
                    )
                }
            }
        }
    }
}
