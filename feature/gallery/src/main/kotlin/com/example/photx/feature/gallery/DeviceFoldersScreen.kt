package com.example.photx.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.domain.gallery.DeviceFolderSummary
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import com.example.photx.core.ui.PhotxBadgeDisplayMode
import com.example.photx.core.ui.PhotxEmptyState
import com.example.photx.core.ui.PhotxMediaTileUiModel
import com.example.photx.core.ui.PhotxPhotoThumbnailTile

private val FOLDER_THUMB_SIZE = 56.dp

/**
 * Route for choosing which Android device media folders PhotX backs up automatically.
 */
@Composable
public fun DeviceFoldersRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val folders by viewModel.deviceFolders.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DeviceFoldersScreen(
        folders = folders,
        feedbackMessage = uiState.backupFeedbackMessage,
        errorMessage = uiState.backupErrorMessage,
        onToggleFolder = viewModel::toggleFolderAutoBackup,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
public fun DeviceFoldersScreen(
    folders: List<DeviceFolderSummary>,
    feedbackMessage: String?,
    errorMessage: String?,
    onToggleFolder: (folderId: String, enabled: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing
    var pendingEnableFolder by remember { mutableStateOf<DeviceFolderSummary?>(null) }

    pendingEnableFolder?.let { folder ->
        EnableFolderBackupDialog(
            folder = folder,
            onConfirm = {
                onToggleFolder(folder.folderId, true)
                pendingEnableFolder = null
            },
            onDismiss = { pendingEnableFolder = null }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        item { DeviceFoldersHeader(onBack = onBack) }
        item { DeviceFoldersBanner(enabledCount = folders.count { it.autoBackupEnabled }) }
        val statusMessage = errorMessage ?: feedbackMessage
        if (statusMessage != null) {
            item { FolderFeedbackBanner(message = statusMessage, isError = errorMessage != null) }
        }
        if (folders.isEmpty()) {
            item {
                PhotxEmptyState(
                    title = "No device folders found",
                    message = "Grant media access and refresh Photos to discover folders on this device."
                )
            }
        } else {
            items(folders, key = { it.folderId }) { folder ->
                DeviceFolderRow(
                    folder = folder,
                    onCheckedChange = { checked ->
                        if (checked && folder.eligibleLocalCount > 0) {
                            pendingEnableFolder = folder
                        } else {
                            onToggleFolder(folder.folderId, checked)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DeviceFoldersHeader(onBack: () -> Unit) {
    val colors = PhotxTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = colors.onBackground
            )
        }
        Text(
            text = "Device folders",
            style = PhotxTheme.typography.headlineMedium,
            color = colors.onBackground
        )
    }
}

@Composable
private fun DeviceFoldersBanner(enabledCount: Int) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing
    Surface(shape = RoundedCornerShape(PhotxTheme.radii.banner), color = colors.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
            Text(
                text = if (enabledCount == 0) {
                    "Automatic folder backup is off"
                } else {
                    "$enabledCount folder(s) backing up automatically"
                },
                style = PhotxTheme.typography.titleMedium,
                color = colors.onSurface
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = "Turn on any folder to back up existing and newly added photos and videos " +
                    "to Telegram Saved Messages.",
                style = PhotxTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FolderFeedbackBanner(message: String, isError: Boolean) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing
    Surface(
        shape = RoundedCornerShape(PhotxTheme.radii.banner),
        color = if (isError) colors.errorContainer else colors.surfaceVariant
    ) {
        Text(
            text = message,
            style = PhotxTheme.typography.bodyMedium,
            color = if (isError) colors.onErrorContainer else colors.onSurface,
            modifier = Modifier.fillMaxWidth().padding(spacing.md)
        )
    }
}

@Composable
private fun DeviceFolderRow(folder: DeviceFolderSummary, onCheckedChange: (Boolean) -> Unit) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing
    Surface(shape = RoundedCornerShape(PhotxTheme.radii.banner), color = colors.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FolderCoverThumbnail(folder = folder)
            Spacer(modifier = Modifier.width(spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.displayName,
                    style = PhotxTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${folder.totalCount} item(s) • ${folder.backedUpCount} backed up",
                    style = PhotxTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                Text(
                    text = folderStatusLabel(folder),
                    style = PhotxTheme.typography.labelLarge,
                    color = if (folder.autoBackupEnabled) {
                        PhotxTheme.syncColors.ctaContainer
                    } else {
                        colors.onSurfaceVariant
                    }
                )
            }
            Spacer(modifier = Modifier.width(spacing.sm))
            Switch(
                checked = folder.autoBackupEnabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun FolderCoverThumbnail(folder: DeviceFolderSummary) {
    val colors = PhotxTheme.colorScheme
    val thumbPath = folder.coverThumbPath
    Box(
        modifier = Modifier
            .size(FOLDER_THUMB_SIZE)
            .clip(RoundedCornerShape(PhotxTheme.radii.gridTile))
            .background(colors.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (!thumbPath.isNullOrBlank()) {
            PhotxPhotoThumbnailTile(
                item = PhotxMediaTileUiModel(
                    id = folder.folderId,
                    localThumbPath = thumbPath,
                    mediaType = MediaType.IMAGE,
                    syncState = SyncState.LOCAL_ONLY,
                    contentDescription = folder.displayName
                ),
                onClick = {},
                badgeDisplayMode = PhotxBadgeDisplayMode.SMART,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = folder.displayName,
                tint = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EnableFolderBackupDialog(folder: DeviceFolderSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Back up ${folder.displayName}?") },
        text = {
            Text(
                text = "${folder.eligibleLocalCount} existing item(s) in this folder will be queued " +
                    "for backup to Telegram Saved Messages, and new items will be backed up automatically."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Turn on")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun folderStatusLabel(folder: DeviceFolderSummary): String = when {
    folder.autoBackupEnabled && folder.queuedOrUploadingCount > 0 ->
        "Automatically backing up (${folder.queuedOrUploadingCount} queued)"

    folder.autoBackupEnabled -> "New items will be backed up automatically"

    folder.backedUpCount > 0 -> "Backup paused"

    else -> "Back up this folder"
}
