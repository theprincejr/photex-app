package com.example.photx.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.example.photx.core.model.SyncState
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotxDesignSystemTokensTest {
    @Test
    fun `given approved dark and light palettes_when inspecting core and extended m3 roles_then match spec`() {
        assertEquals(Color(0xFF000000), PhotxDarkColorScheme.background)
        assertEquals(Color(0xFF15101F), PhotxDarkColorScheme.surface)
        assertEquals(Color(0xFFC77DFF), PhotxDarkColorScheme.primary)
        assertEquals(PhotxPalette.LightSurface, PhotxDarkColorScheme.inverseSurface)
        assertEquals(PhotxPalette.LightOnSurface, PhotxDarkColorScheme.inverseOnSurface)
        assertEquals(PhotxPalette.LightPrimary, PhotxDarkColorScheme.inversePrimary)
        assertEquals(PhotxPalette.DarkPrimary, PhotxDarkColorScheme.surfaceTint)
        assertEquals(Color.Black, PhotxDarkColorScheme.scrim)

        assertEquals(Color(0xFFFFFFFF), PhotxLightColorScheme.background)
        assertEquals(Color(0xFFF5F2F8), PhotxLightColorScheme.surface)
        assertEquals(Color(0xFF8228C8), PhotxLightColorScheme.primary)
        assertEquals(PhotxPalette.DarkSurface, PhotxLightColorScheme.inverseSurface)
        assertEquals(PhotxPalette.DarkOnSurface, PhotxLightColorScheme.inverseOnSurface)
        assertEquals(PhotxPalette.DarkPrimary, PhotxLightColorScheme.inversePrimary)
        assertEquals(PhotxPalette.LightPrimary, PhotxLightColorScheme.surfaceTint)
        assertEquals(Color.Black, PhotxLightColorScheme.scrim)
    }

    @Test
    fun `given dark and light color schemes_when checking wcag contrast_then all m3 and sync pairs pass`() {
        var assertionCount = 0
        listOf(
            "dark" to (PhotxDarkColorScheme to PhotxDarkSyncColors),
            "light" to (PhotxLightColorScheme to PhotxLightSyncColors)
        ).forEach { (themeName, pair) ->
            val (scheme, syncColors) = pair
            assertionCount += verifyMaterialRolesContrast(themeName, scheme, syncColors)
            assertionCount += verifySyncStateContrast(themeName, syncColors)
        }
        assertEquals(56, assertionCount)
    }

    @Test
    fun `given translucent glass and scrim tokens_when layered over photos_then text and graphics pass contrast`() {
        var textAssertionCount = 0
        var graphicAssertionCount = 0
        val photoExtremes = listOf("dark-photo" to Color.Black, "light-photo" to Color.White)

        listOf(
            Triple("dark", PhotxDarkColorScheme, PhotxDarkGlassTokens),
            Triple("light", PhotxLightColorScheme, PhotxLightGlassTokens)
        ).forEach { (themeName, scheme, glass) ->
            photoExtremes.forEach { (photoLabel, photoBg) ->
                // Exact layered compositing order: photograph -> dockContainer -> activeTabContainer -> foreground.
                val compositedDock = glass.dockContainer.compositeOver(photoBg)
                val compositedActiveTab = glass.activeTabContainer.compositeOver(compositedDock)
                val compositedScrim = glass.scrimContainer.compositeOver(photoBg)

                textAssertionCount += verifyGlassTextContrast(
                    prefix = "$themeName $photoLabel",
                    scheme = scheme,
                    glass = glass,
                    compositedDock = compositedDock,
                    compositedActiveTab = compositedActiveTab,
                    compositedScrim = compositedScrim
                )
                graphicAssertionCount += verifyGlassGraphicalContrast(
                    prefix = "$themeName $photoLabel",
                    scheme = scheme,
                    glass = glass,
                    compositedDock = compositedDock,
                    compositedActiveTab = compositedActiveTab,
                    compositedScrim = compositedScrim
                )
            }
        }
        assertEquals(16, textAssertionCount)
        assertEquals(16, graphicAssertionCount)
    }

    private fun verifyGlassTextContrast(
        prefix: String,
        scheme: ColorScheme,
        glass: PhotxGlassTokens,
        compositedDock: Color,
        compositedActiveTab: Color,
        compositedScrim: Color
    ): Int {
        assertContrastAtLeast("$prefix text dock/onSurface", scheme.onSurface, compositedDock)
        assertContrastAtLeast("$prefix text dock/onSurfaceVariant", scheme.onSurfaceVariant, compositedDock)
        assertContrastAtLeast("$prefix text activeTab/onSurface", scheme.onSurface, compositedActiveTab)
        assertContrastAtLeast("$prefix text scrim/onScrim", glass.onScrim, compositedScrim)
        return 4
    }

    private fun verifyGlassGraphicalContrast(
        prefix: String,
        scheme: ColorScheme,
        glass: PhotxGlassTokens,
        compositedDock: Color,
        compositedActiveTab: Color,
        compositedScrim: Color
    ): Int {
        assertContrastAtLeast(
            "$prefix icon dock/primary",
            scheme.primary,
            compositedDock,
            minimum = UI_COMPONENT_CONTRAST_MIN
        )
        assertContrastAtLeast(
            "$prefix icon dock/onSurfaceVariant",
            scheme.onSurfaceVariant,
            compositedDock,
            minimum = UI_COMPONENT_CONTRAST_MIN
        )
        assertContrastAtLeast(
            "$prefix icon activeTab/primary",
            scheme.primary,
            compositedActiveTab,
            minimum = UI_COMPONENT_CONTRAST_MIN
        )
        assertContrastAtLeast(
            "$prefix icon scrim/onScrim",
            glass.onScrim,
            compositedScrim,
            minimum = UI_COMPONENT_CONTRAST_MIN
        )
        return 4
    }

    @Test
    fun `given spacing radii and typography tokens_then match approved blueprint values`() {
        val spacing = PhotxSpacing()
        val radii = PhotxRadii()
        assertEquals(2.dp, spacing.xxs)
        assertEquals(16.dp, spacing.md)
        assertEquals(96.dp, spacing.dockBottomClearance)
        assertEquals(0.dp, radii.gridTile)
        assertEquals(16.dp, radii.albumCard)
        assertEquals(999.dp, radii.pill)
        assertEquals("tnum", PhotxTypography.labelSmall.fontFeatureSettings)
    }

    private fun verifyMaterialRolesContrast(themeName: String, scheme: ColorScheme, syncColors: PhotxSyncColors): Int {
        val pairs = listOf(
            "$themeName primary/onPrimary" to (scheme.onPrimary to scheme.primary),
            "$themeName primaryContainer/onPrimaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
            "$themeName secondary/onSecondary" to (scheme.onSecondary to scheme.secondary),
            "$themeName secondaryContainer/onSecondaryContainer" to
                (scheme.onSecondaryContainer to scheme.secondaryContainer),
            "$themeName tertiary/onTertiary" to (scheme.onTertiary to scheme.tertiary),
            "$themeName tertiaryContainer/onTertiaryContainer" to
                (scheme.onTertiaryContainer to scheme.tertiaryContainer),
            "$themeName background/onBackground" to (scheme.onBackground to scheme.background),
            "$themeName background/onSurfaceVariant" to (scheme.onSurfaceVariant to scheme.background),
            "$themeName surface/onSurface" to (scheme.onSurface to scheme.surface),
            "$themeName surface/onSurfaceVariant" to (scheme.onSurfaceVariant to scheme.surface),
            "$themeName surfaceVariant/onSurfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
            "$themeName inverseSurface/inverseOnSurface" to (scheme.inverseOnSurface to scheme.inverseSurface),
            "$themeName inverseSurface/inversePrimary" to (scheme.inversePrimary to scheme.inverseSurface),
            "$themeName error/onError" to (scheme.onError to scheme.error),
            "$themeName errorContainer/onErrorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
            "$themeName ctaContainer/onCtaContainer" to (syncColors.onCtaContainer to syncColors.ctaContainer)
        )
        pairs.forEach { (label, colors) ->
            assertContrastAtLeast(label, colors.first, colors.second)
        }
        return pairs.size
    }

    private fun verifySyncStateContrast(themeName: String, syncColors: PhotxSyncColors): Int {
        var count = 0
        SyncState.entries.forEach { state ->
            val syncPair = syncColors.forState(state)
            assertContrastAtLeast("$themeName sync[$state] text", syncPair.onContainer, syncPair.container)
            assertContrastAtLeast(
                "$themeName sync[$state] indicator",
                syncPair.indicator,
                syncPair.container,
                minimum = UI_COMPONENT_CONTRAST_MIN
            )
            count += 2
        }
        return count
    }

    private fun assertContrastAtLeast(
        label: String,
        foreground: Color,
        background: Color,
        minimum: Float = AA_TEXT_CONTRAST_MIN
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("Expected $label contrast >= $minimum but was $ratio", ratio >= minimum)
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val effectiveBg = if (background.alpha < 1f) background.compositeOver(Color.Black) else background
        val effectiveFg = if (foreground.alpha < 1f) foreground.compositeOver(effectiveBg) else foreground
        val l1 = effectiveFg.luminance()
        val l2 = effectiveBg.luminance()
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private companion object {
        const val AA_TEXT_CONTRAST_MIN = 4.5f
        const val UI_COMPONENT_CONTRAST_MIN = 3.0f
    }
}
