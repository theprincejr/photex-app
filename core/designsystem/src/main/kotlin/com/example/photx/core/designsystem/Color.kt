package com.example.photx.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.photx.core.model.SyncState

/**
 * Approved Botanical Orchid colour palette tokens for PhotX.
 *
 * Intentionally excludes device dynamic-colour overrides so the Obsidian Dark (`#000000` /
 * `#15101F` / `#C77DFF`) and Pure Light (`#FFFFFF` / `#F5F2F8` / `#8228C8`) visual identity
 * remains consistent across all Android versions.
 */
public object PhotxPalette {
    // Obsidian Dark tokens
    public val DarkBackground: Color = Color(0xFF000000)
    public val DarkSurface: Color = Color(0xFF15101F)
    public val DarkSurfaceContainer: Color = Color(0xFF1C1529)
    public val DarkSurfaceContainerHigh: Color = Color(0xFF231A33)
    public val DarkSurfaceVariant: Color = Color(0xFF1F182D)
    public val DarkPrimary: Color = Color(0xFFC77DFF)
    public val DarkOnPrimary: Color = Color(0xFF240046)
    public val DarkPrimaryContainer: Color = Color(0xFF2D1645)
    public val DarkOnPrimaryContainer: Color = Color(0xFFE9D5FF)
    public val DarkSecondary: Color = Color(0xFFB59CD6)
    public val DarkOnSecondary: Color = Color(0xFF211333)
    public val DarkSecondaryContainer: Color = Color(0xFF332349)
    public val DarkOnSecondaryContainer: Color = Color(0xFFE7DAFA)
    public val DarkTertiary: Color = Color(0xFF34D399)
    public val DarkOnTertiary: Color = Color(0xFF003824)
    public val DarkTertiaryContainer: Color = Color(0xFF124B36)
    public val DarkOnTertiaryContainer: Color = Color(0xFFA7F3D0)
    public val DarkOnSurface: Color = Color(0xFFFFFFFF)
    public val DarkOnSurfaceVariant: Color = Color(0xFF9E95AD)
    public val DarkOutline: Color = Color(0xFF56486E)
    public val DarkOutlineVariant: Color = Color(0xFF2B223C)
    public val DarkError: Color = Color(0xFFFF6B8B)
    public val DarkOnError: Color = Color(0xFF380012)
    public val DarkErrorContainer: Color = Color(0xFF5C1024)
    public val DarkOnErrorContainer: Color = Color(0xFFFFD9E0)
    public val DarkCtaContainer: Color = Color(0xFF8E35D9)
    public val DarkOnCtaContainer: Color = Color(0xFFFFFFFF)

    // Pure Light tokens
    public val LightBackground: Color = Color(0xFFFFFFFF)
    public val LightSurface: Color = Color(0xFFF5F2F8)
    public val LightSurfaceContainer: Color = Color(0xFFEDE7F4)
    public val LightSurfaceContainerHigh: Color = Color(0xFFE5DCEF)
    public val LightSurfaceVariant: Color = Color(0xFFEDE7F4)
    public val LightPrimary: Color = Color(0xFF8228C8)
    public val LightOnPrimary: Color = Color(0xFFFFFFFF)
    public val LightPrimaryContainer: Color = Color(0xFFF3E8FF)
    public val LightOnPrimaryContainer: Color = Color(0xFF4A0E78)
    public val LightSecondary: Color = Color(0xFF6B4F8C)
    public val LightOnSecondary: Color = Color(0xFFFFFFFF)
    public val LightSecondaryContainer: Color = Color(0xFFEDE2FA)
    public val LightOnSecondaryContainer: Color = Color(0xFF28163F)
    public val LightTertiary: Color = Color(0xFF15803D)
    public val LightOnTertiary: Color = Color(0xFFFFFFFF)
    public val LightTertiaryContainer: Color = Color(0xFFDCFCE7)
    public val LightOnTertiaryContainer: Color = Color(0xFF14532D)
    public val LightOnSurface: Color = Color(0xFF09070D)
    public val LightOnSurfaceVariant: Color = Color(0xFF5F576B)
    public val LightOutline: Color = Color(0xFF7B708A)
    public val LightOutlineVariant: Color = Color(0xFFE2DAE9)
    public val LightError: Color = Color(0xFFC81E4B)
    public val LightOnError: Color = Color(0xFFFFFFFF)
    public val LightErrorContainer: Color = Color(0xFFFFE4EA)
    public val LightOnErrorContainer: Color = Color(0xFF660822)

    // Translucent dock and viewer scrim tokens
    public val DarkDockContainer: Color = Color(0xE6181224)
    public val DarkDockBorder: Color = Color(0x33D8B4FE)
    public val DarkDockActiveTab: Color = Color(0x2EC77DFF)
    public val LightDockContainer: Color = Color(0xE6FFFFFF)
    public val LightDockBorder: Color = Color(0xFFE2DAE9)
    public val LightDockActiveTab: Color = Color(0x218228C8)
    public val ViewerScrimContainer: Color = Color(0xA6000000)
    public val ViewerOnScrim: Color = Color(0xFFFFFFFF)
}

/**
 * PhotX Obsidian Dark Material 3 [ColorScheme].
 */
public val PhotxDarkColorScheme: ColorScheme = darkColorScheme(
    primary = PhotxPalette.DarkPrimary,
    onPrimary = PhotxPalette.DarkOnPrimary,
    primaryContainer = PhotxPalette.DarkPrimaryContainer,
    onPrimaryContainer = PhotxPalette.DarkOnPrimaryContainer,
    secondary = PhotxPalette.DarkSecondary,
    onSecondary = PhotxPalette.DarkOnSecondary,
    secondaryContainer = PhotxPalette.DarkSecondaryContainer,
    onSecondaryContainer = PhotxPalette.DarkOnSecondaryContainer,
    tertiary = PhotxPalette.DarkTertiary,
    onTertiary = PhotxPalette.DarkOnTertiary,
    tertiaryContainer = PhotxPalette.DarkTertiaryContainer,
    onTertiaryContainer = PhotxPalette.DarkOnTertiaryContainer,
    background = PhotxPalette.DarkBackground,
    onBackground = PhotxPalette.DarkOnSurface,
    surface = PhotxPalette.DarkSurface,
    onSurface = PhotxPalette.DarkOnSurface,
    surfaceVariant = PhotxPalette.DarkSurfaceVariant,
    onSurfaceVariant = PhotxPalette.DarkOnSurfaceVariant,
    surfaceContainerLowest = PhotxPalette.DarkBackground,
    surfaceContainerLow = PhotxPalette.DarkSurface,
    surfaceContainer = PhotxPalette.DarkSurfaceContainer,
    surfaceContainerHigh = PhotxPalette.DarkSurfaceContainerHigh,
    surfaceContainerHighest = PhotxPalette.DarkPrimaryContainer,
    surfaceBright = PhotxPalette.DarkSurfaceContainerHigh,
    surfaceDim = PhotxPalette.DarkBackground,
    surfaceTint = PhotxPalette.DarkPrimary,
    inverseSurface = PhotxPalette.LightSurface,
    inverseOnSurface = PhotxPalette.LightOnSurface,
    inversePrimary = PhotxPalette.LightPrimary,
    outline = PhotxPalette.DarkOutline,
    outlineVariant = PhotxPalette.DarkOutlineVariant,
    scrim = Color.Black,
    error = PhotxPalette.DarkError,
    onError = PhotxPalette.DarkOnError,
    errorContainer = PhotxPalette.DarkErrorContainer,
    onErrorContainer = PhotxPalette.DarkOnErrorContainer
)

/**
 * PhotX Pure Light Material 3 [ColorScheme].
 */
public val PhotxLightColorScheme: ColorScheme = lightColorScheme(
    primary = PhotxPalette.LightPrimary,
    onPrimary = PhotxPalette.LightOnPrimary,
    primaryContainer = PhotxPalette.LightPrimaryContainer,
    onPrimaryContainer = PhotxPalette.LightOnPrimaryContainer,
    secondary = PhotxPalette.LightSecondary,
    onSecondary = PhotxPalette.LightOnSecondary,
    secondaryContainer = PhotxPalette.LightSecondaryContainer,
    onSecondaryContainer = PhotxPalette.LightOnSecondaryContainer,
    tertiary = PhotxPalette.LightTertiary,
    onTertiary = PhotxPalette.LightOnTertiary,
    tertiaryContainer = PhotxPalette.LightTertiaryContainer,
    onTertiaryContainer = PhotxPalette.LightOnTertiaryContainer,
    background = PhotxPalette.LightBackground,
    onBackground = PhotxPalette.LightOnSurface,
    surface = PhotxPalette.LightSurface,
    onSurface = PhotxPalette.LightOnSurface,
    surfaceVariant = PhotxPalette.LightSurfaceVariant,
    onSurfaceVariant = PhotxPalette.LightOnSurfaceVariant,
    surfaceContainerLowest = PhotxPalette.LightBackground,
    surfaceContainerLow = PhotxPalette.LightSurface,
    surfaceContainer = PhotxPalette.LightSurfaceContainer,
    surfaceContainerHigh = PhotxPalette.LightSurfaceContainerHigh,
    surfaceContainerHighest = PhotxPalette.LightPrimaryContainer,
    surfaceBright = PhotxPalette.LightBackground,
    surfaceDim = PhotxPalette.LightSurfaceContainerHigh,
    surfaceTint = PhotxPalette.LightPrimary,
    inverseSurface = PhotxPalette.DarkSurface,
    inverseOnSurface = PhotxPalette.DarkOnSurface,
    inversePrimary = PhotxPalette.DarkPrimary,
    outline = PhotxPalette.LightOutline,
    outlineVariant = PhotxPalette.LightOutlineVariant,
    scrim = Color.Black,
    error = PhotxPalette.LightError,
    onError = PhotxPalette.LightOnError,
    errorContainer = PhotxPalette.LightErrorContainer,
    onErrorContainer = PhotxPalette.LightOnErrorContainer
)

/**
 * Accessible colour triplet for rendering a [SyncState] pill, badge, or summary chip.
 */
@Immutable
public data class SyncColorPair(public val container: Color, public val onContainer: Color, public val indicator: Color)

/**
 * Semantic synchronization colours covering all six [SyncState] values plus high-contrast CTA fills.
 */
@Immutable
public data class PhotxSyncColors(
    public val localOnly: SyncColorPair,
    public val queued: SyncColorPair,
    public val uploading: SyncColorPair,
    public val synced: SyncColorPair,
    public val cloudOnly: SyncColorPair,
    public val failed: SyncColorPair,
    public val ctaContainer: Color,
    public val onCtaContainer: Color
) {
    /** Returns the semantic [SyncColorPair] corresponding to [syncState]. */
    public fun forState(syncState: SyncState): SyncColorPair = when (syncState) {
        SyncState.LOCAL_ONLY -> localOnly
        SyncState.QUEUED -> queued
        SyncState.UPLOADING -> uploading
        SyncState.SYNCED -> synced
        SyncState.CLOUD_ONLY -> cloudOnly
        SyncState.FAILED -> failed
    }
}

public val PhotxDarkSyncColors: PhotxSyncColors = PhotxSyncColors(
    localOnly = SyncColorPair(
        container = PhotxPalette.DarkSurfaceContainerHigh,
        onContainer = Color(0xFFDCD4EA),
        indicator = Color(0xFFB5AAC7)
    ),
    queued = SyncColorPair(
        container = PhotxPalette.DarkPrimaryContainer,
        onContainer = PhotxPalette.DarkOnPrimaryContainer,
        indicator = Color(0xFFD8B4FE)
    ),
    uploading = SyncColorPair(
        container = PhotxPalette.DarkCtaContainer,
        onContainer = PhotxPalette.DarkOnCtaContainer,
        indicator = PhotxPalette.DarkOnPrimaryContainer
    ),
    synced = SyncColorPair(
        container = PhotxPalette.DarkTertiaryContainer,
        onContainer = PhotxPalette.DarkOnTertiaryContainer,
        indicator = PhotxPalette.DarkTertiary
    ),
    cloudOnly = SyncColorPair(
        container = Color(0xFF6D28D9),
        onContainer = Color(0xFFFFFFFF),
        indicator = PhotxPalette.DarkOnPrimaryContainer
    ),
    failed = SyncColorPair(
        container = Color(0xFF7F1D3A),
        onContainer = PhotxPalette.DarkOnErrorContainer,
        indicator = PhotxPalette.DarkError
    ),
    ctaContainer = PhotxPalette.DarkCtaContainer,
    onCtaContainer = PhotxPalette.DarkOnCtaContainer
)

public val PhotxLightSyncColors: PhotxSyncColors = PhotxSyncColors(
    localOnly = SyncColorPair(
        container = PhotxPalette.LightSurfaceContainer,
        onContainer = Color(0xFF2E263B),
        indicator = PhotxPalette.LightOnSurfaceVariant
    ),
    queued = SyncColorPair(
        container = PhotxPalette.LightPrimaryContainer,
        onContainer = PhotxPalette.LightOnPrimaryContainer,
        indicator = PhotxPalette.LightPrimary
    ),
    uploading = SyncColorPair(
        container = PhotxPalette.LightPrimary,
        onContainer = PhotxPalette.LightOnPrimary,
        indicator = PhotxPalette.LightPrimaryContainer
    ),
    synced = SyncColorPair(
        container = PhotxPalette.LightTertiaryContainer,
        onContainer = PhotxPalette.LightOnTertiaryContainer,
        indicator = PhotxPalette.LightTertiary
    ),
    cloudOnly = SyncColorPair(
        container = Color(0xFF6D28D9),
        onContainer = Color(0xFFFFFFFF),
        indicator = PhotxPalette.LightPrimaryContainer
    ),
    failed = SyncColorPair(
        container = PhotxPalette.LightErrorContainer,
        onContainer = Color(0xFF7F1D3A),
        indicator = PhotxPalette.LightError
    ),
    ctaContainer = PhotxPalette.LightPrimary,
    onCtaContainer = PhotxPalette.LightOnPrimary
)

/**
 * Translucent surface tokens for the floating unified pill dock and media-viewer overlays.
 */
@Immutable
public data class PhotxGlassTokens(
    public val dockContainer: Color,
    public val dockBorder: Color,
    public val activeTabContainer: Color,
    public val scrimContainer: Color,
    public val onScrim: Color
)

public val PhotxDarkGlassTokens: PhotxGlassTokens = PhotxGlassTokens(
    dockContainer = PhotxPalette.DarkDockContainer,
    dockBorder = PhotxPalette.DarkDockBorder,
    activeTabContainer = PhotxPalette.DarkDockActiveTab,
    scrimContainer = PhotxPalette.ViewerScrimContainer,
    onScrim = PhotxPalette.ViewerOnScrim
)

public val PhotxLightGlassTokens: PhotxGlassTokens = PhotxGlassTokens(
    dockContainer = PhotxPalette.LightDockContainer,
    dockBorder = PhotxPalette.LightDockBorder,
    activeTabContainer = PhotxPalette.LightDockActiveTab,
    scrimContainer = PhotxPalette.ViewerScrimContainer,
    onScrim = PhotxPalette.ViewerOnScrim
)

internal val LocalPhotxSyncColors = staticCompositionLocalOf { PhotxDarkSyncColors }
internal val LocalPhotxGlassTokens = staticCompositionLocalOf { PhotxDarkGlassTokens }
