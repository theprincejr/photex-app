package com.example.photx.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.example.photx.core.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotxThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `given dark and light photx themes_when composed_then exposes exact tokens and sync colors`() {
        var resolvedDarkBg: Long = 0L
        var resolvedLightPrimary: Long = 0L

        composeRule.setContent {
            Column {
                PhotxTheme(darkTheme = true) {
                    resolvedDarkBg = PhotxTheme.colorScheme.background.value.toLong()
                    ThemeTokenProbe(tag = "dark-probe", text = "Dark Photos")
                }
                PhotxTheme(darkTheme = false) {
                    resolvedLightPrimary = PhotxTheme.colorScheme.primary.value.toLong()
                    ThemeTokenProbe(tag = "light-probe", text = "Light Photos")
                }
            }
        }

        composeRule.onNodeWithTag("dark-probe").assertIsDisplayed()
        composeRule.onNodeWithTag("light-probe").assertIsDisplayed()
        composeRule.onNodeWithText("Dark Photos").assertIsDisplayed()
        composeRule.onNodeWithText("Light Photos").assertIsDisplayed()
        assertEquals(PhotxDarkColorScheme.background.value.toLong(), resolvedDarkBg)
        assertEquals(PhotxLightColorScheme.primary.value.toLong(), resolvedLightPrimary)
    }

    @Composable
    private fun ThemeTokenProbe(tag: String, text: String) {
        val syncColors = PhotxTheme.syncColors
        assertEquals(6, SyncState.entries.map { syncColors.forState(it) }.size)
        assertEquals(PhotxSpacing(), PhotxTheme.spacing)
        assertEquals(PhotxRadii(), PhotxTheme.radii)
        Text(
            text = text,
            style = PhotxTheme.typography.titleLarge,
            color = PhotxTheme.colorScheme.onBackground,
            modifier = Modifier.testTag(tag)
        )
    }
}
