package com.example.photx.core.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.photx.core.designsystem.PhotxTheme

private const val DEFAULT_SKELETON_COLUMNS = 4
private const val DEFAULT_SKELETON_ROWS = 3
private const val SKELETON_PULSE_MIN_ALPHA = 0.45f
private const val SKELETON_PULSE_MAX_ALPHA = 0.9f
private const val SKELETON_PULSE_DURATION_MS = 900
private val SKELETON_HEADER_WIDTH = 120.dp
private val SKELETON_HEADER_HEIGHT = 16.dp

/**
 * Tonal loading skeleton matching PhotX's 4-column `1:1` `2.dp` gutter grid geometry.
 */
@Composable
public fun PhotxGalleryLoadingSkeleton(
    modifier: Modifier = Modifier,
    columns: Int = DEFAULT_SKELETON_COLUMNS,
    rows: Int = DEFAULT_SKELETON_ROWS
) {
    val colors = PhotxTheme.colorScheme
    val spacing = PhotxTheme.spacing
    val transition = rememberInfiniteTransition(label = "gallerySkeleton")
    val pulseAlpha by transition.animateFloat(
        initialValue = SKELETON_PULSE_MIN_ALPHA,
        targetValue = SKELETON_PULSE_MAX_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SKELETON_PULSE_DURATION_MS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gallerySkeletonAlpha"
    )
    val placeholderColor = colors.surfaceContainerHigh.copy(alpha = pulseAlpha)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Loading gallery items" },
        verticalArrangement = Arrangement.spacedBy(spacing.xxs)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = spacing.md, vertical = spacing.sm)
                .width(SKELETON_HEADER_WIDTH)
                .height(SKELETON_HEADER_HEIGHT)
                .clip(RoundedCornerShape(spacing.xs))
                .background(placeholderColor)
        )
        repeat(rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xxs)
            ) {
                repeat(columns) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(placeholderColor)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(spacing.sm))
    }
}
