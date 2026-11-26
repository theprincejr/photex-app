package com.example.photx.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.model.SyncState

private val BADGE_COMPACT_SIZE = 22.dp
private val BADGE_ICON_SIZE = 13.dp
private val BADGE_DOT_SIZE = 6.dp
private val BADGE_PILL_HORIZONTAL_PADDING = 8.dp
private val BADGE_PILL_VERTICAL_PADDING = 4.dp

/**
 * Controls badge visibility on gallery grid tiles (`GALLERY_DESIGN_BLUEPRINT.md`).
 *
 * [SMART] keeps grid tiles >= 95% unobstructed by hiding badges for routine `LOCAL_ONLY`
 * and `SYNCED` items while surfacing actionable/transfer states (`QUEUED`, `UPLOADING`,
 * `CLOUD_ONLY`, `FAILED`). [ALL] displays every state.
 */
public enum class PhotxBadgeDisplayMode {
    SMART,
    ALL
}

/**
 * Semantic synchronization status badge rendered using [PhotxTheme.syncColors].
 */
@Composable
public fun PhotxSyncStatusBadge(
    syncState: SyncState,
    modifier: Modifier = Modifier,
    displayMode: PhotxBadgeDisplayMode = PhotxBadgeDisplayMode.SMART,
    showLabel: Boolean = false
) {
    val shouldRender = showLabel || displayMode == PhotxBadgeDisplayMode.ALL || syncState.isActionableInSmartMode()
    if (!shouldRender) {
        return
    }

    val colorPair = PhotxTheme.syncColors.forState(syncState)
    val label = syncState.displayLabel()

    if (showLabel) {
        Row(
            modifier = modifier
                .clip(CircleShape)
                .background(colorPair.container)
                .padding(horizontal = BADGE_PILL_HORIZONTAL_PADDING, vertical = BADGE_PILL_VERTICAL_PADDING)
                .semantics(mergeDescendants = true) { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhotxTheme.spacing.xs)
        ) {
            Box(
                modifier = Modifier
                    .size(BADGE_DOT_SIZE)
                    .clip(CircleShape)
                    .background(colorPair.indicator)
            )
            Text(
                text = label,
                style = PhotxTheme.typography.labelSmall,
                color = colorPair.onContainer
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(BADGE_COMPACT_SIZE)
                .clip(CircleShape)
                .background(colorPair.container)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = syncState.icon(),
                contentDescription = null,
                tint = colorPair.onContainer,
                modifier = Modifier.size(BADGE_ICON_SIZE)
            )
        }
    }
}

private fun SyncState.isActionableInSmartMode(): Boolean = when (this) {
    SyncState.QUEUED,
    SyncState.UPLOADING,
    SyncState.CLOUD_ONLY,
    SyncState.FAILED -> true

    SyncState.LOCAL_ONLY,
    SyncState.SYNCED -> false
}

private fun SyncState.displayLabel(): String = when (this) {
    SyncState.LOCAL_ONLY -> "On Device"
    SyncState.QUEUED -> "Queued"
    SyncState.UPLOADING -> "Uploading"
    SyncState.SYNCED -> "Backed Up"
    SyncState.CLOUD_ONLY -> "Cloud Only"
    SyncState.FAILED -> "Failed"
}

private fun SyncState.icon(): ImageVector = when (this) {
    SyncState.LOCAL_ONLY -> Icons.Rounded.PhoneAndroid
    SyncState.QUEUED -> Icons.Rounded.Schedule
    SyncState.UPLOADING -> Icons.Rounded.CloudUpload
    SyncState.SYNCED -> Icons.Rounded.CheckCircle
    SyncState.CLOUD_ONLY -> Icons.Rounded.CloudDone
    SyncState.FAILED -> Icons.Rounded.ErrorOutline
}
