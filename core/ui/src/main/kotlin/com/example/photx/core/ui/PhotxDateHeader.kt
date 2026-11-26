package com.example.photx.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.photx.core.designsystem.PhotxTheme

/**
 * Full-span chronological section header (e.g., `Today`, `Yesterday`, `November 2026`).
 */
@Composable
public fun PhotxDateHeader(title: String, modifier: Modifier = Modifier, itemCount: Int? = null) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm)
            .semantics { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = PhotxTheme.typography.titleMedium,
            color = colors.onBackground
        )
        if (itemCount != null) {
            Text(
                text = itemCount.toString(),
                style = PhotxTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}
