package com.example.photx.feature.viewer

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import com.example.photx.core.ui.PhotxBadgeDisplayMode
import com.example.photx.core.ui.PhotxEmptyState
import com.example.photx.core.ui.PhotxSyncStatusBadge
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MIN_ZOOM_SCALE = 1f
private const val DOUBLE_TAP_ZOOM_SCALE = 2.5f
private const val MAX_ZOOM_SCALE = 4f
private const val SHA256_PREVIEW_CHARS = 16
private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = BYTES_PER_KB * BYTES_PER_KB
private val VIEWER_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(
    "MMM d, yyyy · h:mm a",
    Locale.getDefault()
)

/**
 * Full-screen Hilt-connected media viewer with swipe, zoom, video playback, and metadata sheet.
 */
@Composable
public fun MediaViewerRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MediaViewerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MediaViewerScreen(
        uiState = uiState,
        onBack = onBack,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun MediaViewerScreen(uiState: MediaViewerUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val glass = PhotxTheme.glass
    var showControls by remember { mutableStateOf(true) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var restoreNotice by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            uiState.isLoading -> CircularProgressIndicator(
                color = PhotxTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center)
            )

            uiState.items.isEmpty() -> PhotxEmptyState(
                title = "Media unavailable",
                message = "The selected item could not be found in the local gallery index.",
                primaryActionLabel = "Back",
                onPrimaryAction = onBack,
                modifier = Modifier.align(Alignment.Center)
            )

            else -> ViewerPagerContent(
                items = uiState.items,
                initialIndex = uiState.initialIndex,
                showControls = showControls,
                showInfoSheet = showInfoSheet,
                restoreNotice = restoreNotice,
                onToggleControls = { showControls = !showControls },
                onShowInfoChange = { showInfoSheet = it },
                onRequestRestore = { restoreNotice = "Cloud restore from Telegram activates in Phase 5." },
                onBack = onBack
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerPagerContent(
    items: List<GalleryMediaItem>,
    initialIndex: Int,
    showControls: Boolean,
    showInfoSheet: Boolean,
    restoreNotice: String?,
    onToggleControls: () -> Unit,
    onShowInfoChange: (Boolean) -> Unit,
    onRequestRestore: () -> Unit,
    onBack: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size }
    )
    val currentItem = items[pagerState.currentPage]

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val pageItem = items[page]
            when (pageItem.mediaType) {
                MediaType.IMAGE -> ZoomableImagePage(item = pageItem, onTap = onToggleControls)
                MediaType.VIDEO -> VideoPlayerPage(item = pageItem, isCurrentPage = page == pagerState.currentPage)
            }
        }
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ViewerTopBar(item = currentItem, onBack = onBack, onOpenInfo = { onShowInfoChange(true) })
        }
        AnimatedVisibility(
            visible = showControls && currentItem.syncState == SyncState.CLOUD_ONLY,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            CloudRestoreBar(notice = restoreNotice, onRequestRestore = onRequestRestore)
        }
        if (showInfoSheet) {
            ModalBottomSheet(
                onDismissRequest = { onShowInfoChange(false) },
                containerColor = PhotxTheme.colorScheme.surface
            ) {
                MetadataInfoSheet(item = currentItem)
            }
        }
    }
}

@Composable
private fun ZoomableImagePage(item: GalleryMediaItem, onTap: () -> Unit) {
    var scale by remember(item.id) { mutableFloatStateOf(MIN_ZOOM_SCALE) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val updatedScale = (scale * zoomChange).coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
        scale = updatedScale
        offset = if (updatedScale > MIN_ZOOM_SCALE) offset + panChange else Offset.Zero
    }
    val modelData: Any? = item.localUri?.let(Uri::parse) ?: item.localThumbPath?.let(::File)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > MIN_ZOOM_SCALE) {
                            scale = MIN_ZOOM_SCALE
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_ZOOM_SCALE
                        }
                    }
                )
            }
            .transformable(state = transformState, canPan = { scale > MIN_ZOOM_SCALE }),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(modelData)
                .build(),
            contentDescription = item.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

@Composable
private fun VideoPlayerPage(item: GalleryMediaItem, isCurrentPage: Boolean) {
    val uri = item.localUri?.let(Uri::parse)
    if (uri == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Original video is cloud-only",
                style = PhotxTheme.typography.bodyLarge,
                color = PhotxTheme.glass.onScrim
            )
        }
        return
    }

    var videoViewRef by remember(item.id) { mutableStateOf<VideoView?>(null) }
    LaunchedEffect(isCurrentPage, videoViewRef) {
        val view = videoViewRef ?: return@LaunchedEffect
        if (isCurrentPage) {
            view.start()
        } else if (view.isPlaying) {
            view.pause()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(uri)
                setOnPreparedListener { mp ->
                    mp.isLooping = false
                    if (isCurrentPage) start()
                }
                videoViewRef = this
            }
        },
        update = { view ->
            if (!isCurrentPage && view.isPlaying) {
                view.pause()
            }
        }
    )
}

@Composable
private fun ViewerTopBar(item: GalleryMediaItem, onBack: () -> Unit, onOpenInfo: () -> Unit) {
    val glass = PhotxTheme.glass
    val spacing = PhotxTheme.spacing
    val formattedDate = remember(item.takenAt) {
        VIEWER_DATE_FORMATTER.format(Instant.ofEpochMilli(item.takenAt).atZone(ZoneId.systemDefault()))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(glass.scrimContainer)
            .statusBarsPadding()
            .padding(horizontal = spacing.xs, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = glass.onScrim
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
            Text(text = formattedDate, style = PhotxTheme.typography.titleSmall, color = glass.onScrim)
            Spacer(modifier = Modifier.height(spacing.xxs))
            PhotxSyncStatusBadge(
                syncState = item.syncState,
                displayMode = PhotxBadgeDisplayMode.ALL,
                showLabel = true
            )
        }
        IconButton(onClick = onOpenInfo) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = "Media info",
                tint = glass.onScrim
            )
        }
    }
}

@Composable
private fun CloudRestoreBar(notice: String?, onRequestRestore: () -> Unit) {
    val spacing = PhotxTheme.spacing
    val syncColors = PhotxTheme.syncColors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (notice != null) {
            Surface(shape = CircleShape, color = PhotxTheme.glass.scrimContainer) {
                Text(
                    text = notice,
                    style = PhotxTheme.typography.labelMedium,
                    color = PhotxTheme.glass.onScrim,
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs)
                )
            }
            Spacer(modifier = Modifier.height(spacing.sm))
        }
        Button(
            onClick = onRequestRestore,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = syncColors.ctaContainer,
                contentColor = syncColors.onCtaContainer
            )
        ) {
            Icon(imageVector = Icons.Rounded.CloudDownload, contentDescription = null)
            Spacer(modifier = Modifier.padding(horizontal = spacing.xxs))
            Text(text = "Restore Original", style = PhotxTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun MetadataInfoSheet(item: GalleryMediaItem) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        Text(text = item.fileName, style = PhotxTheme.typography.titleLarge, color = colors.onSurface)
        MetadataRow(label = "Type", value = item.mimeType)
        MetadataRow(label = "Resolution", value = "${item.width} × ${item.height}")
        MetadataRow(label = "Size", value = formatBytes(item.sizeBytes))
        MetadataRow(label = "Sync State", value = item.syncState.name)
        MetadataRow(
            label = "SHA-256",
            value = item.sha256?.take(SHA256_PREVIEW_CHARS)?.plus("…") ?: "Pending Phase 3"
        )
        Spacer(modifier = Modifier.height(spacing.md))
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    val colors = PhotxTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = PhotxTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(text = value, style = PhotxTheme.typography.bodyMedium, color = colors.onSurface)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= BYTES_PER_MB -> String.format(Locale.US, "%.2f MB", bytes / BYTES_PER_MB)
    bytes >= BYTES_PER_KB -> String.format(Locale.US, "%.1f KB", bytes / BYTES_PER_KB)
    else -> "$bytes B"
}
