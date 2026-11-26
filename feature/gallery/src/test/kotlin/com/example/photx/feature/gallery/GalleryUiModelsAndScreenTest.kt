package com.example.photx.feature.gallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GalleryUiModelsAndScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `given indexed items_when deriving albums backup counts and search_then computes accurately`() {
        val items = listOf(
            sampleItem(id = "1", fileName = "IMG_001.jpg", syncState = SyncState.LOCAL_ONLY),
            sampleItem(
                id = "2",
                fileName = "VID_002.mp4",
                mediaType = MediaType.VIDEO,
                mimeType = "video/mp4",
                syncState = SyncState.SYNCED
            ),
            sampleItem(id = "3", fileName = "IMG_003.jpg", localUri = null, syncState = SyncState.CLOUD_ONLY),
            sampleItem(id = "4", fileName = "IMG_004.jpg", syncState = SyncState.FAILED)
        )

        val albums = deriveAlbums(items)
        assertEquals(
            listOf(
                GalleryAlbumId.RECENTS,
                GalleryAlbumId.CAMERA,
                GalleryAlbumId.VIDEOS,
                GalleryAlbumId.CLOUD_ONLY,
                GalleryAlbumId.FAILED
            ),
            albums.map {
                it.first
            }
        )
        assertEquals(1, filterItemsForAlbum(items, GalleryAlbumId.VIDEOS).size)

        val summary = computeBackupSummary(items = items, totalCount = 4)
        assertEquals(4, summary.totalCount)
        assertEquals(1, summary.localOnlyCount)
        assertEquals(1, summary.syncedCount)
        assertEquals(1, summary.cloudOnlyCount)
        assertEquals(1, summary.failedCount)

        assertEquals(1, filterSearchItems(items, "vid_002").size)
        assertEquals(1, filterSearchItems(items, "cloud_only").size)
        assertEquals(0, filterSearchItems(items, "   ").size)

        val today = LocalDate.of(2026, 11, 26)
        assertEquals("Today", formatDateHeaderTitle(today, today))
        assertEquals("Yesterday", formatDateHeaderTitle(today.minusDays(1), today))
    }

    @Test
    fun `given albums screen_when rendered_then displays real counts and handles click`() {
        val items = listOf(
            sampleItem(id = "1", fileName = "beach_sunset.jpg", syncState = SyncState.SYNCED)
        )
        var openedAlbum: GalleryAlbumId? = null

        composeRule.setContent {
            PhotxTheme {
                AlbumsScreen(
                    albums = deriveAlbums(items),
                    onOpenAlbum = { openedAlbum = it }
                )
            }
        }
        composeRule.onNodeWithText("Recents").assertIsDisplayed().performClick()
        assertEquals(GalleryAlbumId.RECENTS, openedAlbum)
    }

    @Test
    fun `given backup screen_when rendered_then displays real counts and sign in cta`() {
        val items = listOf(
            sampleItem(id = "1", fileName = "beach_sunset.jpg", syncState = SyncState.SYNCED)
        )
        var signInClicked = false

        composeRule.setContent {
            PhotxTheme {
                BackupScreen(
                    uiState = GalleryUiState(
                        permissionState = MediaPermissionState.Full,
                        totalIndexedCount = 1,
                        backupSummary = computeBackupSummary(items, 1)
                    ),
                    onOpenAuth = { signInClicked = true },
                    onRetryScan = {}
                )
            }
        }
        composeRule.onNodeWithText("Sign In to Telegram").performClick()
        composeRule.onNodeWithText("Backed Up").assertIsDisplayed()
        assertEquals(true, signInClicked)
    }

    @Test
    fun `given search screen_when rendered_then displays query and matching items`() {
        val items = listOf(
            sampleItem(id = "1", fileName = "beach_sunset.jpg", syncState = SyncState.SYNCED)
        )

        composeRule.setContent {
            PhotxTheme {
                SearchScreen(
                    query = "beach",
                    results = items,
                    onQueryChange = {},
                    onOpenMedia = {}
                )
            }
        }
        composeRule.onNodeWithText("beach").assertIsDisplayed()
    }

    private fun sampleItem(
        id: String,
        fileName: String,
        mediaType: MediaType = MediaType.IMAGE,
        mimeType: String = "image/jpeg",
        localUri: String? = "content://media/external/images/media/$id",
        syncState: SyncState = SyncState.LOCAL_ONLY
    ) = GalleryMediaItem(
        id = id,
        localUri = localUri,
        localThumbPath = null,
        mediaType = mediaType,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = 2048L,
        width = 1080,
        height = 1920,
        durationMs = 0L,
        takenAt = 1_764_150_000_000L,
        syncState = syncState
    )
}
