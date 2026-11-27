package com.example.photx.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.domain.gallery.BackupExecutionState
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.model.SyncState
import com.example.photx.core.ui.PhotxBadgeDisplayMode
import com.example.photx.core.ui.PhotxEmptyState
import com.example.photx.core.ui.PhotxErrorState
import com.example.photx.core.ui.PhotxPhotoThumbnailTile
import com.example.photx.core.ui.PhotxSyncStatusBadge

private const val SEARCH_COLUMN_COUNT = 4

/**
 * Backup summary screen reflecting live Room synchronization counts and Telegram account state.
 */
@Composable
public fun BackupRoute(
    onOpenAuth: () -> Unit,
    onRetryScan: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BackupScreen(
        uiState = uiState,
        onOpenAuth = onOpenAuth,
        onRetryScan = onRetryScan,
        onDiscoverCloudMedia = viewModel::discoverCloudMedia,
        modifier = modifier
    )
}

@Composable
public fun BackupScreen(
    uiState: GalleryUiState,
    onOpenAuth: () -> Unit,
    onRetryScan: () -> Unit,
    modifier: Modifier = Modifier,
    onDiscoverCloudMedia: () -> Unit = {}
) {
    val colors = PhotxTheme.colorScheme
    val syncColors = PhotxTheme.syncColors
    val spacing = PhotxTheme.spacing
    val summary = uiState.backupSummary

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = spacing.md,
            end = spacing.md,
            top = spacing.md,
            bottom = spacing.dockBottomClearance
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item {
            Text(
                text = "Backup",
                style = PhotxTheme.typography.headlineMedium,
                color = colors.onBackground
            )
        }

        item {
            BackupAccountCard(
                accountLabel = uiState.accountLabel,
                totalCount = summary.totalCount,
                onOpenAuth = onOpenAuth,
                onDiscoverCloudMedia = onDiscoverCloudMedia
            )
        }

        val err = uiState.refreshErrorMessage ?: uiState.backupErrorMessage
        if (err != null) {
            item { PhotxErrorState(title = "Sync status warning", message = err, onRetry = onRetryScan) }
        }

        if (summary.totalCount == 0) {
            item {
                PhotxEmptyState(
                    title = "No indexed media",
                    message = "Index local photos first, then select items in Photos to queue a backup."
                )
            }
        } else {
            item { BackupCountsCard(summary = summary) }
            item { BackupExecutionStatusCard(executionState = uiState.backupExecutionState) }
            if (!summary.hasAnyBackupActivity) {
                item {
                    PhotxEmptyState(
                        title = "No backups queued yet",
                        message = "Open Photos, tap Select, choose local media, and tap Back Up to start."
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupAccountCard(
    accountLabel: String?,
    totalCount: Int,
    onOpenAuth: () -> Unit,
    onDiscoverCloudMedia: () -> Unit
) {
    val colors = PhotxTheme.colorScheme
    val syncColors = PhotxTheme.syncColors
    val spacing = PhotxTheme.spacing
    Surface(shape = RoundedCornerShape(PhotxTheme.radii.banner), color = colors.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
            Text(
                text = accountLabel?.let { "Connected as $it" } ?: "Telegram Not Connected",
                style = PhotxTheme.typography.titleMedium,
                color = colors.onSurface
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = "Total local & indexed media: $totalCount",
                style = PhotxTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(spacing.md))
            Button(
                onClick = if (accountLabel == null) onOpenAuth else onDiscoverCloudMedia,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = syncColors.ctaContainer,
                    contentColor = syncColors.onCtaContainer
                )
            ) {
                Text(
                    text = if (accountLabel == null) "Sign In to Telegram" else "Sync Cloud Catalogue",
                    style = PhotxTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun BackupExecutionStatusCard(executionState: BackupExecutionState) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing
    val visuals = when (executionState) {
        is BackupExecutionState.Idle -> ExecutionCardVisuals(
            title = "Engine Idle",
            detail = "No active background upload scheduled.",
            container = colors.surface,
            content = colors.onSurface
        )

        is BackupExecutionState.Queued -> ExecutionCardVisuals(
            title = "Backup Queued",
            detail = "Waiting for network constraints to start sequential upload.",
            container = colors.surfaceVariant,
            content = colors.onSurface
        )

        is BackupExecutionState.Running -> ExecutionCardVisuals(
            title = "Backup Running",
            detail = executionState.activeMediaId?.let {
                "Uploading 1 file sequentially (item $it)."
            } ?: "Uploading queued media sequentially (1 file at a time).",
            container = colors.surfaceVariant,
            content = colors.onSurface
        )

        is BackupExecutionState.WaitingForReconciliation -> ExecutionCardVisuals(
            title = "Waiting for Reconciliation",
            detail = "${executionState.uncertainMediaIds.size} uncertain upload(s) must be verified in " +
                "Saved Messages before any new upload can run.",
            container = colors.surfaceVariant,
            content = colors.primary
        )

        is BackupExecutionState.Failed -> ExecutionCardVisuals(
            title = "Backup Attention Needed",
            detail = executionState.reason ?: "One or more uploads failed before completion.",
            container = colors.errorContainer,
            content = colors.onErrorContainer
        )

        is BackupExecutionState.Completed -> ExecutionCardVisuals(
            title = "Backup Completed",
            detail = "All queued uploads finished.",
            container = colors.surface,
            content = colors.onSurface
        )
    }

    Surface(shape = RoundedCornerShape(PhotxTheme.radii.banner), color = visuals.container) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
            Text(text = visuals.title, style = PhotxTheme.typography.titleMedium, color = visuals.content)
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(text = visuals.detail, style = PhotxTheme.typography.bodyMedium, color = visuals.content)
        }
    }
}

private data class ExecutionCardVisuals(
    val title: String,
    val detail: String,
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color
)

@Composable
private fun BackupCountsCard(summary: BackupSummaryUiState) {
    val spacing = PhotxTheme.spacing
    val colors = PhotxTheme.colorScheme
    Surface(shape = RoundedCornerShape(PhotxTheme.radii.banner), color = colors.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            SyncCountRow(state = SyncState.LOCAL_ONLY, count = summary.localOnlyCount)
            SyncCountRow(state = SyncState.QUEUED, count = summary.queuedCount)
            SyncCountRow(state = SyncState.UPLOADING, count = summary.uploadingCount)
            SyncCountRow(state = SyncState.SYNCED, count = summary.syncedCount)
            SyncCountRow(state = SyncState.CLOUD_ONLY, count = summary.cloudOnlyCount)
            SyncCountRow(state = SyncState.FAILED, count = summary.failedCount)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "Pending Reconciliation",
                    style = PhotxTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                Text(
                    text = summary.waitingForReconciliationCount.toString(),
                    style = PhotxTheme.typography.titleMedium,
                    color = colors.onSurface
                )
            }
        }
    }
}

@Composable
private fun SyncCountRow(state: SyncState, count: Int) {
    val colors = PhotxTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PhotxSyncStatusBadge(
            syncState = state,
            displayMode = PhotxBadgeDisplayMode.ALL,
            showLabel = true
        )
        Text(
            text = count.toString(),
            style = PhotxTheme.typography.titleMedium,
            color = colors.onSurface
        )
    }
}

/**
 * Search screen filtering Room-indexed local media by filename, MIME type, or sync state.
 */
@Composable
public fun SearchRoute(
    onOpenMedia: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()

    SearchScreen(
        query = uiState.searchQuery,
        results = results,
        onQueryChange = viewModel::updateSearchQuery,
        onOpenMedia = onOpenMedia,
        modifier = modifier
    )
}

@Composable
public fun SearchScreen(
    query: String,
    results: List<GalleryMediaItem>,
    onQueryChange: (String) -> Unit,
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
        Text(
            text = "Search",
            style = PhotxTheme.typography.headlineMedium,
            color = colors.onBackground,
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.md)
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search filename, video, cloud_only…") },
            leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(imageVector = Icons.Rounded.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md)
        )
        Spacer(modifier = Modifier.height(spacing.md))
        SearchResultsBody(query = query, results = results, onOpenMedia = onOpenMedia)
    }
}

@Composable
private fun SearchResultsBody(query: String, results: List<GalleryMediaItem>, onOpenMedia: (String) -> Unit) {
    val spacing = PhotxTheme.spacing
    when {
        query.isBlank() -> PhotxEmptyState(
            title = "Search your library",
            message = "Filter local photos and videos by filename, type, or sync status.",
            icon = Icons.Rounded.Search
        )

        results.isEmpty() -> PhotxEmptyState(
            title = "No matching items",
            message = "No indexed media matched \"$query\".",
            icon = Icons.Rounded.Search
        )

        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(SEARCH_COLUMN_COUNT),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            contentPadding = PaddingValues(bottom = spacing.dockBottomClearance)
        ) {
            items(results, key = { it.id }) { item ->
                PhotxPhotoThumbnailTile(item = item.toTileUiModel(), onClick = { onOpenMedia(item.id) })
            }
        }
    }
}
