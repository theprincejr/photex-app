package com.example.photx.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.photx.core.designsystem.PhotxTheme

private val EMPTY_ICON_CONTAINER_SIZE = 56.dp
private val EMPTY_ICON_SIZE = 28.dp

/**
 * Calm empty-state card used across Photos, Albums, Backup, and Search when no items match.
 */
@Composable
public fun PhotxEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.PhotoLibrary,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    val colors = PhotxTheme.colorScheme
    val syncColors = PhotxTheme.syncColors
    val spacing = PhotxTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(EMPTY_ICON_CONTAINER_SIZE),
            shape = CircleShape,
            color = colors.primaryContainer
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier
                    .padding(spacing.md)
                    .size(EMPTY_ICON_SIZE)
            )
        }
        Spacer(modifier = Modifier.height(spacing.md))
        Text(
            text = title,
            style = PhotxTheme.typography.titleLarge,
            color = colors.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = message,
            style = PhotxTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (primaryActionLabel != null && onPrimaryAction != null) {
            Spacer(modifier = Modifier.height(spacing.md))
            Button(
                onClick = onPrimaryAction,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = syncColors.ctaContainer,
                    contentColor = syncColors.onCtaContainer
                )
            ) {
                Text(text = primaryActionLabel, style = PhotxTheme.typography.labelLarge)
            }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(modifier = Modifier.height(spacing.sm))
            OutlinedButton(onClick = onSecondaryAction, shape = CircleShape) {
                Text(text = secondaryActionLabel, style = PhotxTheme.typography.labelLarge)
            }
        }
    }
}
