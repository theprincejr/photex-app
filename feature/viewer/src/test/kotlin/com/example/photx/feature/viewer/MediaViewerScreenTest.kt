package com.example.photx.feature.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaViewerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `given cloud only item_when opening info sheet and restore_then displays metadata and notice`() {
        val item = GalleryMediaItem(
            id = "cloud-1",
            localUri = null,
            localThumbPath = null,
            mediaType = MediaType.IMAGE,
            fileName = "orchid_macro.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 2_097_152L,
            width = 4032,
            height = 3024,
            durationMs = 0L,
            takenAt = 1_764_150_000_000L,
            syncState = SyncState.CLOUD_ONLY
        )

        var restoreRequested = false
        composeRule.setContent {
            PhotxTheme {
                MediaViewerScreen(
                    uiState = MediaViewerUiState(
                        items = listOf(item),
                        initialIndex = 0,
                        isLoading = false,
                        restoreStates = mapOf(
                            item.id to CloudRestoreUiState.Completed(
                                restoredUri = "content://media/external/images/media/1",
                                fileName = item.fileName
                            )
                        )
                    ),
                    onBack = {},
                    onRestoreToDevice = { restoreRequested = true }
                )
            }
        }

        composeRule.onNodeWithText("Cloud Only").assertIsDisplayed()
        composeRule.onNodeWithText("Restored to device: orchid_macro.jpg").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Media info").performClick()
        composeRule.onNodeWithText("orchid_macro.jpg").assertIsDisplayed()
        composeRule.onNodeWithText("4032 × 3024").assertIsDisplayed()
        composeRule.onNodeWithText("2.00 MB").assertIsDisplayed()
    }
}
