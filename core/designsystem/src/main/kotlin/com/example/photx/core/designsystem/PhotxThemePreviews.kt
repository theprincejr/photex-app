package com.example.photx.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.photx.core.model.SyncState

private const val PREVIEW_CHIPS_PER_ROW = 3
private val PREVIEW_SWATCH_HEIGHT = 36.dp
private val PREVIEW_CHIP_HORIZONTAL_PADDING = 10.dp
private val PREVIEW_DOT_SIZE = 6.dp
private val PREVIEW_BORDER_WIDTH = 1.dp

@Composable
private fun DesignSystemShowcase() {
    val colors = PhotxTheme.colorScheme
    val syncColors = PhotxTheme.syncColors
    val spacing = PhotxTheme.spacing

    Surface(color = colors.background) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Text(text = "All Photos", style = PhotxTheme.typography.titleLarge, color = colors.onBackground)
            Text(
                text = "Botanical Orchid Tokens",
                style = PhotxTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                TokenSwatch(
                    label = "Background",
                    color = colors.background,
                    borderColor = colors.outlineVariant,
                    modifier = Modifier.weight(1f)
                )
                TokenSwatch(label = "Surface", color = colors.surface, modifier = Modifier.weight(1f))
                TokenSwatch(label = "Primary", color = colors.primary, modifier = Modifier.weight(1f))
                TokenSwatch(label = "CTA", color = syncColors.ctaContainer, modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(spacing.xs))
            SyncState.entries.chunked(PREVIEW_CHIPS_PER_ROW).forEach { rowStates ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs), modifier = Modifier.fillMaxWidth()) {
                    rowStates.forEach { state ->
                        SyncTokenChip(state = state, pair = syncColors.forState(state))
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenSwatch(label: String, color: Color, modifier: Modifier = Modifier, borderColor: Color? = null) {
    val swatchShape = RoundedCornerShape(PhotxTheme.spacing.sm)
    val boxModifier = Modifier
        .fillMaxWidth()
        .height(PREVIEW_SWATCH_HEIGHT)
        .clip(swatchShape)
        .background(color)
        .then(
            if (borderColor != null) {
                Modifier.border(width = PREVIEW_BORDER_WIDTH, color = borderColor, shape = swatchShape)
            } else {
                Modifier
            }
        )
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = boxModifier)
        Text(text = label, style = PhotxTheme.typography.labelSmall, color = PhotxTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SyncTokenChip(state: SyncState, pair: SyncColorPair) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(pair.container)
            .padding(horizontal = PREVIEW_CHIP_HORIZONTAL_PADDING, vertical = PhotxTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhotxTheme.spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(PREVIEW_DOT_SIZE)
                .clip(CircleShape)
                .background(pair.indicator)
        )
        Text(text = state.name, style = PhotxTheme.typography.labelSmall, color = pair.onContainer)
    }
}

@Preview(name = "Obsidian Dark", showBackground = true)
@Composable
private fun PhotxThemeDarkPreview() {
    PhotxTheme(darkTheme = true) {
        DesignSystemShowcase()
    }
}

@Preview(name = "Pure Light", showBackground = true)
@Composable
private fun PhotxThemeLightPreview() {
    PhotxTheme(darkTheme = false) {
        DesignSystemShowcase()
    }
}
