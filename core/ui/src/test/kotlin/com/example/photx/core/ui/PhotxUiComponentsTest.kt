package com.example.photx.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotxUiComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `given navigation dock_when tapping destination_then selects and emits callback`() {
        var selected = PhotxDockDestination.PHOTOS
        composeRule.setContent {
            PhotxTheme {
                PhotxNavigationDock(
                    selectedDestination = selected,
                    onSelectDestination = { selected = it }
                )
            }
        }

        composeRule.onNodeWithText("Photos").assertIsSelected()
        composeRule.onNodeWithText("Albums").performClick()
        assertEquals(PhotxDockDestination.ALBUMS, selected)
        composeRule.onNodeWithText("Backup").performClick()
        assertEquals(PhotxDockDestination.BACKUP, selected)
        composeRule.onNodeWithText("Search").performClick()
        assertEquals(PhotxDockDestination.SEARCH, selected)
    }

    @Test
    fun `given smart vs all badge mode_when rendering sync states_then filters routine states in smart mode`() {
        composeRule.setContent {
            PhotxTheme {
                Column {
                    PhotxSyncStatusBadge(syncState = SyncState.LOCAL_ONLY, displayMode = PhotxBadgeDisplayMode.SMART)
                    PhotxSyncStatusBadge(syncState = SyncState.SYNCED, displayMode = PhotxBadgeDisplayMode.SMART)
                    PhotxSyncStatusBadge(syncState = SyncState.CLOUD_ONLY, displayMode = PhotxBadgeDisplayMode.SMART)
                    PhotxSyncStatusBadge(
                        syncState = SyncState.SYNCED,
                        displayMode = PhotxBadgeDisplayMode.ALL,
                        showLabel = true
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("On Device").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Cloud Only").assertIsDisplayed()
        composeRule.onNodeWithText("Backed Up").assertIsDisplayed()
    }

    @Test
    fun `given video tile with selection and date header_when rendered_then displays duration and selection`() {
        var clicked = false
        composeRule.setContent {
            PhotxTheme {
                Column {
                    PhotxDateHeader(title = "Today", itemCount = 12)
                    PhotxPhotoThumbnailTile(
                        item = PhotxMediaTileUiModel(
                            id = "vid-1",
                            localThumbPath = null,
                            mediaType = MediaType.VIDEO,
                            syncState = SyncState.FAILED,
                            durationMs = 74_000L,
                            contentDescription = "Sample video"
                        ),
                        isSelected = true,
                        selectionEnabled = true,
                        onClick = { clicked = true }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("12").assertIsDisplayed()
        composeRule.onNodeWithText("1:14").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Selected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Failed").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sample video").performClick()
        assertEquals(true, clicked)
    }

    @Test
    fun `given album card skeleton empty error and permission states_when rendered_then callbacks fire`() {
        var albumClicks = 0
        var fullAccessClicks = 0
        var manageClicks = 0
        var emptyActionClicks = 0
        var retryClicks = 0

        composeRule.setContent {
            PhotxTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    PhotxAlbumCard(
                        album = PhotxAlbumCardUiModel(id = "cam", title = "Camera", itemCount = 42),
                        onClick = { albumClicks += 1 }
                    )
                    PhotxGalleryLoadingSkeleton(columns = 4, rows = 1)
                    PhotxPermissionBanner(
                        permissionState = MediaPermissionState.Partial(
                            imagesGranted = false,
                            videosGranted = false,
                            userSelectedOnly = true
                        ),
                        onRequestFullAccess = {},
                        onManageSelection = { manageClicks += 1 }
                    )
                    PhotxPermissionBanner(
                        permissionState = MediaPermissionState.Denied,
                        onRequestFullAccess = { fullAccessClicks += 1 },
                        onManageSelection = { manageClicks += 1 }
                    )
                    PhotxEmptyState(
                        title = "No photos yet",
                        message = "Take a photo to get started.",
                        primaryActionLabel = "Refresh",
                        onPrimaryAction = { emptyActionClicks += 1 }
                    )
                    PhotxErrorState(
                        title = "Scan failed",
                        message = "Storage unavailable.",
                        onRetry = { retryClicks += 1 }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Camera").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("42 items").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Loading gallery items").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Manage").performScrollTo().performClick()
        composeRule.onNodeWithText("Allow Full Access").performScrollTo().performClick()
        composeRule.onNodeWithText("Select Photos").performScrollTo().performClick()
        composeRule.onNodeWithText("Refresh").performScrollTo().performClick()
        composeRule.onNodeWithText("Retry").performScrollTo().performClick()

        assertEquals(1, albumClicks)
        assertEquals(1, fullAccessClicks)
        assertEquals(2, manageClicks)
        assertEquals(1, emptyActionClicks)
        assertEquals(1, retryClicks)
        assertEquals("0:00", formatVideoDuration(null))
        assertEquals("0:14", formatVideoDuration(14_000L))
    }
}
