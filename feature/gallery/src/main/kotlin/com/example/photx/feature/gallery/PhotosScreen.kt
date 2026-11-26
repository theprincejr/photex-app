package com.example.photx.feature.gallery

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.ui.PhotxDateHeader
import com.example.photx.core.ui.PhotxEmptyState
import com.example.photx.core.ui.PhotxErrorState
import com.example.photx.core.ui.PhotxGalleryLoadingSkeleton
import com.example.photx.core.ui.PhotxPermissionBanner
import com.example.photx.core.ui.PhotxPhotoThumbnailTile

private const val GRID_COLUMN_COUNT = 4
private val STATUS_DOT_SIZE = 8.dp
private val ACCOUNT_PILL_MAX_WIDTH = 132.dp
private val TOP_ICON_SIZE = 18.dp

/**
 * Hilt-connected Photos timeline route with Android runtime permission handling and Paging 3 grid.
 */
@Composable
public fun PhotosRoute(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenAuth: () -> Unit,
    onOpenMedia: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pagedItems = viewModel.pagedTimeline.collectAsLazyPagingItems()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissionsAndMedia()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionsAndMedia()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    PhotosScreen(
        uiState = uiState,
        pagedItems = pagedItems,
        isDarkTheme = isDarkTheme,
        onToggleTheme = onToggleTheme,
        onOpenAuth = onOpenAuth,
        onRefresh = viewModel::refreshPermissionsAndMedia,
        onRequestFullAccess = { permissionLauncher.launch(fullMediaPermissions()) },
        onManageSelection = { permissionLauncher.launch(userSelectedMediaPermissions()) },
        onToggleSelection = viewModel::toggleSelection,
        onEnterSelectionMode = viewModel::enterSelectionMode,
        onSelectAll = viewModel::selectAllVisible,
        onClearSelection = viewModel::clearSelection,
        onOpenMedia = onOpenMedia,
        modifier = modifier
    )
}

/**
 * Stateless chronological Photos screen displaying the 4-column WebP grid, date headers,
 * permission guidance, multi-select controls, and loading/empty/error states.
 */
@Composable
public fun PhotosScreen(
    uiState: GalleryUiState,
    pagedItems: LazyPagingItems<GalleryTimelineUiModel>,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenAuth: () -> Unit,
    onRefresh: () -> Unit,
    onRequestFullAccess: () -> Unit,
    onManageSelection: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onEnterSelectionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenMedia: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PhotxTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
    ) {
        PhotosTopBar(
            uiState = uiState,
            isDarkTheme = isDarkTheme,
            onToggleTheme = onToggleTheme,
            onOpenAuth = onOpenAuth,
            onRefresh = onRefresh,
            onEnterSelectionMode = onEnterSelectionMode,
            onSelectAll = onSelectAll,
            onClearSelection = onClearSelection
        )

        PhotosBody(
            uiState = uiState,
            pagedItems = pagedItems,
            onRefresh = onRefresh,
            onRequestFullAccess = onRequestFullAccess,
            onManageSelection = onManageSelection,
            onToggleSelection = onToggleSelection,
            onOpenMedia = onOpenMedia
        )
    }
}

@Composable
private fun PhotosTopBar(
    uiState: GalleryUiState,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenAuth: () -> Unit,
    onRefresh: () -> Unit,
    onEnterSelectionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit
) {
    if (uiState.isSelectionMode) {
        PhotosSelectionTopBar(
            selectedCount = uiState.selectedIds.size,
            onSelectAll = onSelectAll,
            onClearSelection = onClearSelection
        )
    } else {
        PhotosDefaultTopBar(
            accountLabel = uiState.accountLabel,
            isDarkTheme = isDarkTheme,
            onToggleTheme = onToggleTheme,
            onOpenAuth = onOpenAuth,
            onRefresh = onRefresh,
            onEnterSelectionMode = onEnterSelectionMode
        )
    }
}

@Composable
private fun PhotosSelectionTopBar(selectedCount: Int, onSelectAll: () -> Unit, onClearSelection: () -> Unit) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.sm, vertical = spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onSelectAll) {
            Text(text = "Select All", style = PhotxTheme.typography.labelLarge, color = colors.primary)
        }
        Text(text = "$selectedCount Selected", style = PhotxTheme.typography.titleLarge, color = colors.onBackground)
        TextButton(onClick = onClearSelection) {
            Text(text = "Cancel", style = PhotxTheme.typography.labelLarge, color = colors.primary)
        }
    }
}

@Composable
private fun PhotosDefaultTopBar(
    accountLabel: String?,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenAuth: () -> Unit,
    onRefresh: () -> Unit,
    onEnterSelectionMode: () -> Unit
) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccountStatusPill(label = accountLabel, onClick = onOpenAuth)
        Text(text = "All Photos", style = PhotxTheme.typography.titleLarge, color = colors.onBackground)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleTheme) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    contentDescription = if (isDarkTheme) "Switch to Light Theme" else "Switch to Dark Theme",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(TOP_ICON_SIZE)
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh photos",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(TOP_ICON_SIZE)
                )
            }
            Surface(
                shape = CircleShape,
                color = colors.surface,
                modifier = Modifier.clickable(onClick = onEnterSelectionMode)
            ) {
                Text(
                    text = "Select",
                    style = PhotxTheme.typography.labelMedium,
                    color = colors.primary,
                    modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs)
                )
            }
        }
    }
}

@Composable
private fun AccountStatusPill(label: String?, onClick: () -> Unit) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing
    val dotColor = if (label != null) colors.tertiary else colors.onSurfaceVariant

    Surface(
        shape = CircleShape,
        color = colors.surface,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(STATUS_DOT_SIZE)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(
                text = label ?: "Sign In",
                style = PhotxTheme.typography.labelSmall,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = spacing.xxs).width(ACCOUNT_PILL_MAX_WIDTH.coerceAtMost(84.dp))
            )
        }
    }
}

@Composable
private fun PhotosBody(
    uiState: GalleryUiState,
    pagedItems: LazyPagingItems<GalleryTimelineUiModel>,
    onRefresh: () -> Unit,
    onRequestFullAccess: () -> Unit,
    onManageSelection: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onOpenMedia: (String) -> Unit
) {
    val spacing = PhotxTheme.spacing
    val isPagingLoading = pagedItems.loadState.refresh is LoadState.Loading
    val pagingError = (pagedItems.loadState.refresh as? LoadState.Error)?.error?.localizedMessage

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMN_COUNT),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        contentPadding = PaddingValues(bottom = spacing.dockBottomClearance)
    ) {
        photosStatusItems(
            uiState = uiState,
            isPagingLoading = isPagingLoading,
            pagingError = pagingError,
            itemCount = pagedItems.itemCount,
            onRefresh = onRefresh,
            onRequestFullAccess = onRequestFullAccess,
            onManageSelection = onManageSelection
        )

        items(
            count = pagedItems.itemCount,
            key = pagedItems.itemKey { it.key },
            contentType = pagedItems.itemContentType { it.contentType },
            span = { index ->
                if (pagedItems.peek(index) is GalleryTimelineUiModel.DateHeader) {
                    GridItemSpan(maxLineSpan)
                } else {
                    GridItemSpan(1)
                }
            }
        ) { index ->
            when (val entry = pagedItems[index]) {
                is GalleryTimelineUiModel.DateHeader -> PhotxDateHeader(title = entry.title)

                is GalleryTimelineUiModel.MediaItem -> {
                    val itemId = entry.item.id
                    PhotxPhotoThumbnailTile(
                        item = entry.tile,
                        isSelected = itemId in uiState.selectedIds,
                        selectionEnabled = uiState.isSelectionMode,
                        onClick = { if (uiState.isSelectionMode) onToggleSelection(itemId) else onOpenMedia(itemId) },
                        onLongClick = { onToggleSelection(itemId) }
                    )
                }

                null -> Unit
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.photosStatusItems(
    uiState: GalleryUiState,
    isPagingLoading: Boolean,
    pagingError: String?,
    itemCount: Int,
    onRefresh: () -> Unit,
    onRequestFullAccess: () -> Unit,
    onManageSelection: () -> Unit
) {
    if (uiState.permissionState != MediaPermissionState.Full) {
        item(key = "permission_banner", span = { GridItemSpan(maxLineSpan) }) {
            PhotxPermissionBanner(
                permissionState = uiState.permissionState,
                onRequestFullAccess = onRequestFullAccess,
                onManageSelection = onManageSelection
            )
        }
    }
    val errorMsg = uiState.refreshErrorMessage ?: pagingError
    if (errorMsg != null) {
        item(key = "error_banner", span = { GridItemSpan(maxLineSpan) }) {
            PhotxErrorState(title = "Couldn't refresh gallery", message = errorMsg, onRetry = onRefresh)
        }
    }
    if ((isPagingLoading || uiState.isRefreshing) && itemCount == 0) {
        item(key = "loading_skeleton", span = { GridItemSpan(maxLineSpan) }) {
            PhotxGalleryLoadingSkeleton()
        }
    } else if (itemCount == 0 && uiState.permissionState.canScanDeviceMedia) {
        item(key = "empty_state", span = { GridItemSpan(maxLineSpan) }) {
            PhotxEmptyState(
                title = "No local photos yet",
                message = "Photos and videos in DCIM/Camera will appear here automatically.",
                primaryActionLabel = "Scan Again",
                onPrimaryAction = onRefresh
            )
        }
    }
}

private fun fullMediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun userSelectedMediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )

    else -> fullMediaPermissions()
}
