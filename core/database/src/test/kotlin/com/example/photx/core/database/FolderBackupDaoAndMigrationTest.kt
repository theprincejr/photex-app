package com.example.photx.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.QueueOrigin
import com.example.photx.core.model.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderBackupDaoAndMigrationTest {
    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var db: PhotxDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhotxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `migration 2 to 3 preserves existing media rows and adds folder preferences table`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = tempFolder.newFile("photx-v2-to-v3.db")
        seedV2Database(dbFile.absolutePath)

        val migrated = Room.databaseBuilder(context, PhotxDatabase::class.java, dbFile.absolutePath)
            .addMigrations(PhotxDatabaseMigrations.MIGRATION_1_2, PhotxDatabaseMigrations.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val existing = migrated.mediaDao().getById("v2-item-1")
            assertNotNull(existing)
            assertEquals(SyncState.SYNCED, existing?.syncState)
            assertEquals(QueueOrigin.MANUAL, existing?.queueOrigin)
            assertNull(existing?.folderId)
            assertTrue(migrated.folderBackupDao().getAllPreferences().isEmpty())
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `insertFoldersIfAbsent defaults disabled and never overwrites enabled state`() = runBlocking {
        val dao = db.folderBackupDao()
        val initial = FolderBackupPreferenceEntity(
            folderId = "external_primary:101",
            displayName = "Orchid",
            relativePath = "Pictures/Orchid",
            volumeName = "external_primary",
            autoBackupEnabled = false,
            discoveredAt = 1_000L,
            updatedAt = 1_000L
        )
        dao.insertFoldersIfAbsent(listOf(initial))
        assertFalse(dao.isFolderAutoBackupEnabled("external_primary:101") ?: true)

        dao.setAutoBackupEnabled("external_primary:101", enabled = true, updatedAt = 2_000L)
        dao.insertFoldersIfAbsent(listOf(initial.copy(autoBackupEnabled = false)))
        assertTrue(dao.isFolderAutoBackupEnabled("external_primary:101") == true)
    }

    @Test
    fun `queueEligibleFolderItems and cancelUnstartedFolderAutoItems respect origin and states`() = runBlocking {
        val folderA = "external_primary:101"
        db.folderBackupDao().insertFoldersIfAbsent(
            listOf(
                FolderBackupPreferenceEntity(
                    folderId = folderA,
                    displayName = "Orchid",
                    relativePath = "Pictures/Orchid",
                    volumeName = "external_primary"
                )
            )
        )
        val mediaDao = db.mediaDao()
        val syncDao = db.syncStateDao()

        seedItems(
            listOf(
                item("local-1", folderA, SyncState.LOCAL_ONLY, QueueOrigin.MANUAL),
                item("failed-definite", folderA, SyncState.FAILED, QueueOrigin.MANUAL, uncertain = false),
                item("failed-uncertain", folderA, SyncState.FAILED, QueueOrigin.MANUAL, uncertain = true),
                item("manual-queued", folderA, SyncState.QUEUED, QueueOrigin.MANUAL),
                item("uploading-1", folderA, SyncState.UPLOADING, QueueOrigin.FOLDER_AUTO),
                item("synced-1", folderA, SyncState.SYNCED, QueueOrigin.FOLDER_AUTO),
                item("cloud-1", folderA, SyncState.CLOUD_ONLY, QueueOrigin.MANUAL, localUri = null)
            )
        )

        val queuedCount = syncDao.queueEligibleFolderItems(folderA)
        assertEquals(2, queuedCount)
        assertEquals(SyncState.QUEUED, mediaDao.getById("local-1")?.syncState)
        assertEquals(QueueOrigin.FOLDER_AUTO, mediaDao.getById("local-1")?.queueOrigin)
        assertEquals(SyncState.QUEUED, mediaDao.getById("failed-definite")?.syncState)
        assertEquals(QueueOrigin.FOLDER_AUTO, mediaDao.getById("failed-definite")?.queueOrigin)
        assertEquals(SyncState.FAILED, mediaDao.getById("failed-uncertain")?.syncState)
        assertEquals(QueueOrigin.MANUAL, mediaDao.getById("manual-queued")?.queueOrigin)

        val cancelledCount = syncDao.cancelUnstartedFolderAutoItems(folderA)
        assertEquals(2, cancelledCount)
        assertEquals(SyncState.LOCAL_ONLY, mediaDao.getById("local-1")?.syncState)
        assertEquals(SyncState.LOCAL_ONLY, mediaDao.getById("failed-definite")?.syncState)
        assertEquals(SyncState.QUEUED, mediaDao.getById("manual-queued")?.syncState)
        assertEquals(SyncState.UPLOADING, mediaDao.getById("uploading-1")?.syncState)
        assertEquals(SyncState.SYNCED, mediaDao.getById("synced-1")?.syncState)
        assertEquals(SyncState.CLOUD_ONLY, mediaDao.getById("cloud-1")?.syncState)

        val summary = db.folderBackupDao().observeFolderSummaries().first().single()
        assertEquals(7, summary.totalCount)
        assertEquals(2, summary.backedUpCount)
        assertEquals(2, summary.queuedOrUploadingCount)
        assertEquals(2, summary.eligibleLocalCount)
    }

    @Test
    fun `enqueueManualBackup promotes FOLDER_AUTO queued item to MANUAL`() = runBlocking {
        seedItems(
            listOf(item("auto-1", "external_primary:101", SyncState.QUEUED, QueueOrigin.FOLDER_AUTO))
        )
        val result = db.syncStateDao().enqueueManualBackup("auto-1")
        assertTrue(result is MediaStateMutationResult.Applied)
        assertEquals(QueueOrigin.MANUAL, db.mediaDao().getById("auto-1")?.queueOrigin)
    }

    private fun seedItems(items: List<MediaItemEntity>) {
        val writable = db.openHelper.writableDatabase
        items.forEach { entry ->
            writable.execSQL(
                "INSERT INTO media_items (id, local_uri, media_type, file_name, mime_type, size_bytes, " +
                    "width, height, duration_ms, taken_at, sync_state, upload_attempt_uncertain, " +
                    "folder_id, queue_origin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    entry.id,
                    entry.localUri,
                    entry.mediaType.name,
                    entry.fileName,
                    entry.mimeType,
                    entry.sizeBytes,
                    entry.width,
                    entry.height,
                    entry.durationMs,
                    entry.takenAt,
                    entry.syncState.name,
                    if (entry.uploadAttemptUncertain) 1 else 0,
                    entry.folderId,
                    entry.queueOrigin.name
                )
            )
        }
    }

    private fun item(
        id: String,
        folderId: String,
        syncState: SyncState,
        origin: QueueOrigin,
        uncertain: Boolean = false,
        localUri: String? = "content://media/$id"
    ): MediaItemEntity = MediaItemEntity(
        id = id,
        localUri = localUri,
        mediaType = MediaType.IMAGE,
        fileName = "$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        width = 100,
        height = 100,
        durationMs = 0L,
        takenAt = 1_000L,
        syncState = syncState,
        uploadAttemptUncertain = uncertain,
        folderId = folderId,
        queueOrigin = origin
    )

    private fun seedV2Database(path: String) {
        val sqlite = SQLiteDatabase.openOrCreateDatabase(path, null)
        sqlite.execSQL(
            "CREATE TABLE IF NOT EXISTS media_items (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "local_uri TEXT, " +
                "local_thumb_path TEXT, " +
                "media_type TEXT NOT NULL, " +
                "file_name TEXT NOT NULL, " +
                "mime_type TEXT NOT NULL, " +
                "size_bytes INTEGER NOT NULL, " +
                "width INTEGER NOT NULL, " +
                "height INTEGER NOT NULL, " +
                "duration_ms INTEGER NOT NULL, " +
                "taken_at INTEGER NOT NULL, " +
                "sha256 TEXT, " +
                "sync_state TEXT NOT NULL, " +
                "telegram_chat_id INTEGER, " +
                "telegram_message_id INTEGER, " +
                "telegram_file_id INTEGER, " +
                "telegram_thumb_file_id INTEGER, " +
                "upload_correlation_token TEXT, " +
                "upload_attempt_uncertain INTEGER NOT NULL DEFAULT 0, " +
                "dedup_canonical_id TEXT" +
                ")"
        )
        createV2Indices(sqlite)
        sqlite.execSQL(
            "CREATE TABLE IF NOT EXISTS backup_execution_lease (" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "owner_worker_id TEXT, " +
                "active_media_id TEXT, " +
                "acquired_at_ms INTEGER NOT NULL, " +
                "expires_at_ms INTEGER NOT NULL, " +
                "fencing_token INTEGER NOT NULL DEFAULT 0" +
                ")"
        )
        sqlite.execSQL(
            "INSERT INTO media_items (id, local_uri, media_type, file_name, mime_type, size_bytes, " +
                "width, height, duration_ms, taken_at, sync_state) " +
                "VALUES ('v2-item-1', 'content://media/1', 'IMAGE', 'a.jpg', 'image/jpeg', 100, 10, 10, 0, 1, 'SYNCED')"
        )
        sqlite.execSQL("PRAGMA user_version = 2")
        sqlite.close()
    }

    private fun createV2Indices(sqlite: SQLiteDatabase) {
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS idx_media_taken_at_id ON media_items (taken_at, id)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS idx_media_sha256 ON media_items (sha256)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS idx_media_sync_state ON media_items (sync_state)")
        sqlite.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_media_telegram_msg " +
                "ON media_items (telegram_chat_id, telegram_message_id)"
        )
        sqlite.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_media_local_uri ON media_items (local_uri)")
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_media_correlation_token ON media_items (upload_correlation_token)"
        )
    }
}
