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
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.photx.core.designsystem.PhotxTheme

/**
 * Recoverable error card with a retry CTA using high-contrast error container tokens.
 */
@Composable
public fun PhotxErrorState(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "Retry"
) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        shape = RoundedCornerShape(PhotxTheme.radii.banner),
        color = colors.errorContainer
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = colors.onErrorContainer
                )
                Text(
                    text = title,
                    style = PhotxTheme.typography.titleMedium,
                    color = colors.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = message,
                style = PhotxTheme.typography.bodyMedium,
                color = colors.onErrorContainer
            )
            Spacer(modifier = Modifier.height(spacing.md))
            Button(
                onClick = onRetry,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.error,
                    contentColor = colors.onError
                )
            ) {
                Text(text = retryLabel, style = PhotxTheme.typography.labelLarge)
            }
        }
    }
}
