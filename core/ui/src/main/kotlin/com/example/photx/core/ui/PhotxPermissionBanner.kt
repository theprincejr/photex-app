package com.example.photx.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.domain.gallery.MediaPermissionState

/**
 * Permission guidance banner for [MediaPermissionState.Partial] and [MediaPermissionState.Denied].
 *
 * Renders nothing when [MediaPermissionState.Full] is active.
 */
@Composable
public fun PhotxPermissionBanner(
    permissionState: MediaPermissionState,
    onRequestFullAccess: () -> Unit,
    onManageSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (permissionState) {
        MediaPermissionState.Full -> return

        is MediaPermissionState.Partial -> PartialAccessBanner(
            partial = permissionState,
            onManageSelection = onManageSelection,
            modifier = modifier
        )

        MediaPermissionState.Denied -> DeniedAccessCard(
            onRequestFullAccess = onRequestFullAccess,
            onManageSelection = onManageSelection,
            modifier = modifier
        )
    }
}

@Composable
private fun PartialAccessBanner(
    partial: MediaPermissionState.Partial,
    onManageSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing
    val bannerMessage = if (partial.userSelectedOnly) {
        "Only selected photos are visible"
    } else {
        "Only partial media access is granted"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        shape = RoundedCornerShape(PhotxTheme.radii.banner),
        color = colors.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = bannerMessage,
                style = PhotxTheme.typography.bodyMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onManageSelection) {
                Text(
                    text = "Manage",
                    style = PhotxTheme.typography.labelLarge,
                    color = colors.primary
                )
            }
        }
    }
}

@Composable
private fun DeniedAccessCard(
    onRequestFullAccess: () -> Unit,
    onManageSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PhotxTheme.colorScheme
    val syncColors = PhotxTheme.syncColors
    val spacing = PhotxTheme.spacing

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        shape = RoundedCornerShape(PhotxTheme.radii.banner),
        color = colors.surface
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Icon(
                    imageVector = Icons.Rounded.LockOpen,
                    contentDescription = null,
                    tint = colors.primary
                )
                Text(
                    text = "Photo access needed",
                    style = PhotxTheme.typography.titleMedium,
                    color = colors.onSurface
                )
            }
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = "Allow PhotX to index your local photos and videos offline, with optional Telegram backup.",
                style = PhotxTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Button(
                    onClick = onRequestFullAccess,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = syncColors.ctaContainer,
                        contentColor = syncColors.onCtaContainer
                    )
                ) {
                    Text(text = "Allow Full Access", style = PhotxTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = onManageSelection, shape = CircleShape) {
                    Text(text = "Select Photos", style = PhotxTheme.typography.labelLarge)
                }
            }
        }
    }
}
