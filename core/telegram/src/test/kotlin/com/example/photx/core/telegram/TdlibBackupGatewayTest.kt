package com.example.photx.core.telegram

import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.domain.backup.PhotxBackupMetadata
import com.example.photx.core.domain.backup.PhotxBackupMetadataCodec
import com.example.photx.core.domain.backup.TelegramBackupUploadRequest
import com.example.photx.core.domain.backup.TelegramReconciliationResult
import com.example.photx.core.domain.backup.TelegramUploadResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TdlibBackupGatewayTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sha = "deadbeef".repeat(8)
    private val token = "corr-token-123"
    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Test
    fun `given staged file_when send succeeds_then returns final server message id not temp id`() = runTest {
        val stagedFile = tempFolder.newFile("photo.staged").apply { writeBytes(ByteArray(128)) }
        val transport = FakeTdlibBackupTransport(userId = 42L, savedChatId = 42L)
        transport.onSubmit = { _, _, _ ->
            transport.emitUpdate(
                TdlibSendUpdate.Succeeded(
                    oldMessageId = 1048576L,
                    chatId = 42L,
                    messageId = 9001L,
                    telegramFileId = 77,
                    telegramThumbFileId = 88,
                    fileName = "IMG_001.jpg",
                    byteCount = 128L,
                    caption = null
                )
            )
            Outcome.Success(1048576L)
        }
        val gateway = TdlibBackupGateway(transport, dispatchers, confirmationTimeoutMs = 1_000L)
        val metadata = PhotxBackupMetadata(sha256 = sha, correlationToken = token)
        val result = gateway.sendStagedDocument(
            TelegramBackupUploadRequest(
                stagedFilePath = stagedFile.absolutePath,
                fileName = "IMG_001.jpg",
                mimeType = "image/jpeg",
                expectedByteCount = 128L,
                metadata = metadata
            )
        )

        assertTrue(result is TelegramUploadResult.Confirmed)
        val confirmed = (result as TelegramUploadResult.Confirmed).backup
        assertEquals(42L, confirmed.chatId)
        assertEquals(9001L, confirmed.messageId)
        assertEquals(77, confirmed.telegramFileId)
        assertEquals(stagedFile.absolutePath, transport.lastSubmittedPath)
        assertEquals(
            (PhotxBackupMetadataCodec.encode(metadata) as Outcome.Success).value,
            transport.lastSubmittedCaption
        )
    }

    @Test
    fun `given missing staged file or tdlib send failure_when sending_then returns DefiniteFailure`() = runTest {
        val transport = FakeTdlibBackupTransport(userId = 42L, savedChatId = 42L)
        val gateway = TdlibBackupGateway(transport, dispatchers, confirmationTimeoutMs = 200L)
        val metadata = PhotxBackupMetadata(sha256 = sha, correlationToken = token)

        val missingResult = gateway.sendStagedDocument(
            TelegramBackupUploadRequest(
                stagedFilePath = "${tempFolder.root.absolutePath}/missing.staged",
                fileName = "missing.jpg",
                mimeType = "image/jpeg",
                expectedByteCount = 64L,
                metadata = metadata
            )
        )
        assertTrue(missingResult is TelegramUploadResult.DefiniteFailure)
        assertEquals(0, transport.submitCount)

        val stagedFile = tempFolder.newFile("fail.staged").apply { writeBytes(ByteArray(64)) }
        transport.onSubmit = { _, _, _ -> Outcome.Failure(AppError.Network("400: FILE_PARTS_INVALID", false)) }
        val tdErrorResult = gateway.sendStagedDocument(
            TelegramBackupUploadRequest(stagedFile.absolutePath, "fail.jpg", "image/jpeg", 64L, metadata)
        )
        assertTrue(tdErrorResult is TelegramUploadResult.DefiniteFailure)
    }

    @Test
    fun `given timeout after temp id_when sending_then returns Uncertain and new instance recovers via token`() =
        runTest {
            val stagedFile = tempFolder.newFile("uncertain.staged").apply { writeBytes(ByteArray(64)) }
            val transport = FakeTdlibBackupTransport(userId = 42L, savedChatId = 42L)
            transport.onSubmit = { _, _, _ -> Outcome.Success(2097152L) }
            val metadata = PhotxBackupMetadata(sha256 = sha, correlationToken = token)
            val expectedCaption = (PhotxBackupMetadataCodec.encode(metadata) as Outcome.Success).value

            val preCrashGateway = TdlibBackupGateway(transport, dispatchers, confirmationTimeoutMs = 50L)
            val sendOutcome = preCrashGateway.sendStagedDocument(
                TelegramBackupUploadRequest(stagedFile.absolutePath, "u.jpg", "image/jpeg", 64L, metadata)
            )
            assertTrue(sendOutcome is TelegramUploadResult.Uncertain)
            assertEquals(1, transport.submitCount)

            // Simulate process restart with a fresh gateway instance reconciling Saved Messages.
            transport.searchPages = listOf(
                TdlibSearchPage(
                    messages = listOf(
                        TdlibCandidateDocument(
                            chatId = 42L,
                            messageId = 9999L,
                            senderUserId = 42L,
                            telegramFileId = 501,
                            telegramThumbFileId = null,
                            fileName = "u.jpg",
                            byteCount = 64L,
                            caption = expectedCaption,
                            isSendingPending = false
                        )
                    ),
                    nextFromMessageId = 0L
                )
            )
            val restartedGateway = TdlibBackupGateway(transport, dispatchers, confirmationTimeoutMs = 1_000L)
            val recon = restartedGateway.findBackupByCorrelationToken(token, sha, "u.jpg", 64L)
            assertTrue(recon is TelegramReconciliationResult.Matched)
            assertEquals(9999L, (recon as TelegramReconciliationResult.Matched).backup.messageId)
            assertEquals(1, transport.submitCount)
        }

    @Test
    fun `given invalid or ambiguous candidates_when reconciling_then rejects invalid and flags ambiguous`() = runTest {
        val transport = FakeTdlibBackupTransport(userId = 42L, savedChatId = 42L)
        val metadata = PhotxBackupMetadata(sha256 = sha, correlationToken = token)
        val validCaption = (PhotxBackupMetadataCodec.encode(metadata) as Outcome.Success).value
        transport.searchPages = listOf(
            TdlibSearchPage(
                messages = listOf(
                    // Wrong chat
                    TdlibCandidateDocument(999L, 1L, 42L, 1, null, "a.jpg", 64L, validCaption, false),
                    // Wrong sender
                    TdlibCandidateDocument(42L, 2L, 888L, 2, null, "a.jpg", 64L, validCaption, false),
                    // Still sending
                    TdlibCandidateDocument(42L, 3L, 42L, 3, null, "a.jpg", 64L, validCaption, true),
                    // Ordinary document
                    TdlibCandidateDocument(42L, 4L, 42L, 4, null, "a.jpg", 64L, "normal doc", false)
                ),
                nextFromMessageId = 0L
            )
        )
        val gateway = TdlibBackupGateway(transport, dispatchers)
        assertEquals(
            TelegramReconciliationResult.NotFound,
            gateway.findBackupByCorrelationToken(token, sha, "a.jpg", 64L)
        )

        transport.searchPages = listOf(
            TdlibSearchPage(
                messages = listOf(
                    TdlibCandidateDocument(42L, 10L, 42L, 10, null, "a.jpg", 64L, validCaption, false),
                    TdlibCandidateDocument(42L, 11L, 42L, 11, null, "a.jpg", 64L, validCaption, false)
                ),
                nextFromMessageId = 0L
            )
        )
        val ambiguous = gateway.findBackupByCorrelationToken(token, sha, "a.jpg", 64L)
        assertTrue(ambiguous is TelegramReconciliationResult.Ambiguous)
        assertEquals(2, (ambiguous as TelegramReconciliationResult.Ambiguous).candidateCount)
    }

    private class FakeTdlibBackupTransport(private val userId: Long, private val savedChatId: Long) :
        TdlibBackupTransport {
        private val updatesFlow = MutableSharedFlow<TdlibSendUpdate>(extraBufferCapacity = 16)
        override val sendUpdates: Flow<TdlibSendUpdate> = updatesFlow

        var submitCount: Int = 0
        var lastSubmittedPath: String? = null
        var lastSubmittedCaption: String? = null
        var lastSubmittedThumbPath: String? = null
        var onSubmit: (Long, String, String) -> Outcome<Long, AppError> = { _, _, _ -> Outcome.Success(1L) }
        var searchPages: List<TdlibSearchPage> = emptyList()
        val downloadedFileIds = mutableListOf<Int>()
        var downloadSourcePath: String? = null

        fun emitUpdate(update: TdlibSendUpdate) {
            updatesFlow.tryEmit(update)
        }

        override suspend fun getCurrentUserId(): Outcome<Long, AppError> = Outcome.Success(userId)

        override suspend fun getSavedMessagesChatId(userId: Long): Outcome<Long, AppError> =
            Outcome.Success(savedChatId)

        override suspend fun submitDocumentMessage(
            chatId: Long,
            stagedFilePath: String,
            caption: String,
            thumbnailFilePath: String?
        ): Outcome<Long, AppError> {
            submitCount += 1
            lastSubmittedPath = stagedFilePath
            lastSubmittedCaption = caption
            lastSubmittedThumbPath = thumbnailFilePath
            return onSubmit(chatId, stagedFilePath, caption)
        }

        override suspend fun searchSavedMessagesDocuments(
            chatId: Long,
            senderUserId: Long,
            query: String,
            fromMessageId: Long,
            limit: Int
        ): Outcome<TdlibSearchPage, AppError> =
            Outcome.Success(searchPages.firstOrNull() ?: TdlibSearchPage(emptyList(), 0L))

        override suspend fun fetchSavedMessagesHistoryPage(
            chatId: Long,
            fromMessageId: Long,
            limit: Int
        ): Outcome<TdlibSearchPage, AppError> =
            Outcome.Success(searchPages.firstOrNull() ?: TdlibSearchPage(emptyList(), 0L))

        override suspend fun downloadTelegramFile(fileId: Int): Outcome<String, AppError> {
            downloadedFileIds.add(fileId)
            val path = downloadSourcePath
            return if (path != null) Outcome.Success(path) else Outcome.Failure(AppError.Io("Missing file"))
        }
    }

    @Test
    fun `given saved messages history_when discovering and downloading thumb_then ignores ordinary docs`() = runTest {
        val transport = FakeTdlibBackupTransport(userId = 42L, savedChatId = 42L)
        val metadata = PhotxBackupMetadata(sha256 = sha, correlationToken = token)
        val validCaption = (PhotxBackupMetadataCodec.encode(metadata) as Outcome.Success).value
        transport.searchPages = listOf(
            TdlibSearchPage(
                messages = listOf(
                    TdlibCandidateDocument(42L, 100L, 42L, 700, 701, "backed.jpg", 2048L, validCaption, false),
                    TdlibCandidateDocument(42L, 99L, 42L, 800, 801, "invoice.pdf", 512L, "Receipt", false)
                ),
                nextFromMessageId = 0L
            )
        )
        val thumbSrc = tempFolder.newFile("td_thumb.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        transport.downloadSourcePath = thumbSrc.absolutePath
        val thumbDest = tempFolder.newFolder("cache").resolve("cached_thumb.jpg")

        val gateway = TdlibBackupGateway(transport, dispatchers)
        val discovered = gateway.discoverBackedUpDocuments()
        assertTrue(discovered is Outcome.Success)
        val page = (discovered as Outcome.Success).value
        assertEquals(1, page.backups.size)
        assertEquals(1, page.ignoredNonPhotxCount)
        assertEquals(701, page.backups.single().telegramThumbFileId)

        val thumbOutcome = gateway.downloadThumbnail(701, thumbDest.absolutePath)
        assertTrue(thumbOutcome is Outcome.Success)
        assertEquals(listOf(701), transport.downloadedFileIds)
        assertTrue(thumbDest.isFile && thumbDest.length() == 3L)
    }
}
