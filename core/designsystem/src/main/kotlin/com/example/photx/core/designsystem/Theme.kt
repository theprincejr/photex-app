package com.example.photx.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Top-level PhotX Material 3 theme provider enforcing the Botanical Orchid palette across
 * Obsidian Dark and Pure Light modes without automatic device dynamic-colour overrides.
 */
@Composable
public fun PhotxTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) PhotxDarkColorScheme else PhotxLightColorScheme
    val syncColors = if (darkTheme) PhotxDarkSyncColors else PhotxLightSyncColors
    val glassTokens = if (darkTheme) PhotxDarkGlassTokens else PhotxLightGlassTokens

    CompositionLocalProvider(
        LocalPhotxSyncColors provides syncColors,
        LocalPhotxGlassTokens provides glassTokens,
        LocalPhotxSpacing provides PhotxSpacing(),
        LocalPhotxRadii provides PhotxRadii()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PhotxTypography,
            shapes = PhotxShapes,
            content = content
        )
    }
}

/**
 * Typed accessors for PhotX design-system tokens inside `@Composable` scopes.
 */
public object PhotxTheme {
    public val colorScheme: ColorScheme
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme

    public val typography: Typography
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography

    public val shapes: Shapes
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes

    public val syncColors: PhotxSyncColors
        @Composable
        @ReadOnlyComposable
        get() = LocalPhotxSyncColors.current

    public val glass: PhotxGlassTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalPhotxGlassTokens.current

    public val spacing: PhotxSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalPhotxSpacing.current

    public val radii: PhotxRadii
        @Composable
        @ReadOnlyComposable
        get() = LocalPhotxRadii.current
}
