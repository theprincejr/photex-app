package com.example.photx.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing density tokens for PhotX layouts and grids (`GALLERY_DESIGN_BLUEPRINT.md`).
 */
@Immutable
public data class PhotxSpacing(
    public val xxs: Dp = 2.dp,
    public val xs: Dp = 4.dp,
    public val sm: Dp = 8.dp,
    public val md: Dp = 16.dp,
    public val lg: Dp = 24.dp,
    public val xl: Dp = 32.dp,
    public val dockBottomClearance: Dp = 96.dp
)

/**
 * Corner-radius tokens for grid tiles (`0.dp`), album cards (`16.dp`), sheets (`24.dp`), and pills.
 */
@Immutable
public data class PhotxRadii(
    public val gridTile: Dp = 0.dp,
    public val banner: Dp = 16.dp,
    public val albumCard: Dp = 16.dp,
    public val sheet: Dp = 24.dp,
    public val pill: Dp = 999.dp
)

/**
 * Standard Material 3 [Shapes] configuration for PhotX surfaces.
 */
public val PhotxShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

internal val LocalPhotxSpacing = staticCompositionLocalOf { PhotxSpacing() }
internal val LocalPhotxRadii = staticCompositionLocalOf { PhotxRadii() }
