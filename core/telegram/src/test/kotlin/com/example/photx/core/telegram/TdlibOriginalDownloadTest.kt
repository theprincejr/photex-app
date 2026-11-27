package com.example.photx.core.telegram

import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.domain.backup.CloudDownloadProgress
import com.example.photx.core.domain.backup.PhotxBackupMetadata
import com.example.photx.core.domain.backup.PhotxBackupMetadataCodec
import com.example.photx.core.domain.backup.TelegramOriginalDownloadRequest
import com.example.photx.core.domain.backup.TelegramOriginalDownloadResult
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TdlibOriginalDownloadTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val main: CoroutineDispatcher = testDispatcher
        override val mainImmediate: CoroutineDispatcher = testDispatcher
    }

    private val token = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

    private class FakeTransport(
        private val userId: Long = 42L,
        private val chatId: Long = 42L,
        var liveMessage: Outcome<TdlibCandidateDocument, AppError> = Outcome.Failure(AppError.Io("Missing")),
        var downloadSourceFile: File? = null
    ) : TdlibBackupTransport {
        val downloadedFileIds = mutableListOf<Int>()
        override val sendUpdates: Flow<TdlibSendUpdate> = emptyFlow()

        override suspend fun getCurrentUserId(): Outcome<Long, AppError> = Outcome.Success(userId)

        override suspend fun getSavedMessagesChatId(userId: Long): Outcome<Long, AppError> = Outcome.Success(chatId)

        override suspend fun submitDocumentMessage(
            chatId: Long,
            stagedFilePath: String,
            caption: String,
            thumbnailFilePath: String?
        ): Outcome<Long, AppError> = Outcome.Failure(AppError.Io("Not used"))

        override suspend fun searchSavedMessagesDocuments(
            chatId: Long,
            senderUserId: Long,
            query: String,
            fromMessageId: Long,
            limit: Int
        ): Outcome<TdlibSearchPage, AppError> = Outcome.Success(TdlibSearchPage(emptyList(), 0L))

        override suspend fun fetchSavedMessagesHistoryPage(
            chatId: Long,
            fromMessageId: Long,
            limit: Int
        ): Outcome<TdlibSearchPage, AppError> = Outcome.Success(TdlibSearchPage(emptyList(), 0L))

        override suspend fun downloadTelegramFile(fileId: Int): Outcome<String, AppError> {
            downloadedFileIds.add(fileId)
            return downloadSourceFile?.let { Outcome.Success(it.absolutePath) }
                ?: Outcome.Failure(AppError.Io("Incomplete download"))
        }

        override suspend fun getMessageDocument(
            chatId: Long,
            messageId: Long
        ): Outcome<TdlibCandidateDocument, AppError> = liveMessage

        override suspend fun downloadTelegramFileWithProgress(
            fileId: Int,
            onProgress: (Long, Long) -> Unit
        ): Outcome<String, AppError> {
            downloadedFileIds.add(fileId)
            val src = downloadSourceFile ?: return Outcome.Failure(AppError.Io("Incomplete download"))
            val size = src.length()
            onProgress(size / 2, size)
            onProgress(size, size)
            return Outcome.Success(src.absolutePath)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `given valid live message_when downloading original_then resolves doc id verifies sha256 and promotes`() =
        runTest {
            val payload = "original-image-bytes-123456".toByteArray()
            val expectedSha = sha256Hex(payload)
            val caption = (
                PhotxBackupMetadataCodec.encode(
                    PhotxBackupMetadata(expectedSha, token)
                ) as Outcome.Success
                ).value
            val sourceFile = tempFolder.newFile("td_doc.bin").apply { writeBytes(payload) }
            val destFile = File(tempFolder.root, "cache/item.orig")

            val transport = FakeTransport(
                liveMessage = Outcome.Success(
                    TdlibCandidateDocument(
                        chatId = 42L,
                        messageId = 9001L,
                        senderUserId = 42L,
                        telegramFileId = 7777,
                        telegramThumbFileId = 6666,
                        fileName = "item.staged",
                        byteCount = payload.size.toLong(),
                        caption = caption,
                        isSendingPending = false
                    )
                ),
                downloadSourceFile = sourceFile
            )
            val gateway = TdlibBackupGateway(transport, dispatchers)
            val progressEvents = mutableListOf<CloudDownloadProgress>()

            val result = gateway.downloadVerifiedOriginalDocument(
                TelegramOriginalDownloadRequest(
                    chatId = 42L,
                    messageId = 9001L,
                    correlationToken = token,
                    expectedSha256 = expectedSha,
                    expectedFileName = "photo.jpg",
                    expectedByteCount = payload.size.toLong(),
                    destinationPath = destFile.absolutePath
                ),
                onProgress = progressEvents::add
            )

            assertTrue(result is TelegramOriginalDownloadResult.Verified)
            assertEquals(listOf(7777), transport.downloadedFileIds)
            assertFalse(transport.downloadedFileIds.contains(6666))
            assertTrue(destFile.exists())
            assertEquals(payload.toList(), destFile.readBytes().toList())
            assertTrue(progressEvents.isNotEmpty())
            assertEquals(0, destFile.parentFile!!.listFiles()!!.count { it.name.contains(".part.") })
        }

    @Test
    fun `given wrong sender or token in live message_when downloading_then rejects before download`() = runTest {
        val payload = "bytes".toByteArray()
        val expectedSha = sha256Hex(payload)
        val caption = (
            PhotxBackupMetadataCodec.encode(
                PhotxBackupMetadata(expectedSha, token)
            ) as Outcome.Success
            ).value
        val destFile = File(tempFolder.root, "cache/reject.orig")

        val transport = FakeTransport(
            liveMessage = Outcome.Success(
                TdlibCandidateDocument(
                    chatId = 42L,
                    messageId = 9001L,
                    senderUserId = 999L, // wrong sender
                    telegramFileId = 7777,
                    telegramThumbFileId = 6666,
                    fileName = "photo.jpg",
                    byteCount = payload.size.toLong(),
                    caption = caption,
                    isSendingPending = false
                )
            )
        )
        val gateway = TdlibBackupGateway(transport, dispatchers)

        val result = gateway.downloadVerifiedOriginalDocument(
            TelegramOriginalDownloadRequest(
                chatId = 42L,
                messageId = 9001L,
                correlationToken = token,
                expectedSha256 = expectedSha,
                expectedFileName = "photo.jpg",
                expectedByteCount = payload.size.toLong(),
                destinationPath = destFile.absolutePath
            )
        )

        assertTrue(result is TelegramOriginalDownloadResult.Failed)
        assertTrue(transport.downloadedFileIds.isEmpty())
        assertFalse(destFile.exists())
    }

    @Test
    fun `given sha256 mismatch_when downloading original_then rejects and cleans part file`() = runTest {
        val expectedBytes = "good-bytes".toByteArray()
        val expectedSha = sha256Hex(expectedBytes)
        val corruptedBytes = "bad!-bytes".toByteArray()
        val caption = (
            PhotxBackupMetadataCodec.encode(
                PhotxBackupMetadata(expectedSha, token)
            ) as Outcome.Success
            ).value
        val sourceFile = tempFolder.newFile("corrupt.bin").apply { writeBytes(corruptedBytes) }
        val destFile = File(tempFolder.root, "cache/corrupt.orig")

        val transport = FakeTransport(
            liveMessage = Outcome.Success(
                TdlibCandidateDocument(
                    chatId = 42L,
                    messageId = 9001L,
                    senderUserId = 42L,
                    telegramFileId = 7777,
                    telegramThumbFileId = 6666,
                    fileName = "photo.jpg",
                    byteCount = expectedBytes.size.toLong(),
                    caption = caption,
                    isSendingPending = false
                )
            ),
            downloadSourceFile = sourceFile
        )
        val gateway = TdlibBackupGateway(transport, dispatchers)

        val result = gateway.downloadVerifiedOriginalDocument(
            TelegramOriginalDownloadRequest(
                chatId = 42L,
                messageId = 9001L,
                correlationToken = token,
                expectedSha256 = expectedSha,
                expectedFileName = "photo.jpg",
                expectedByteCount = expectedBytes.size.toLong(),
                destinationPath = destFile.absolutePath
            )
        )

        assertTrue(result is TelegramOriginalDownloadResult.IntegrityFailed)
        assertFalse(destFile.exists())
        assertEquals(0, destFile.parentFile?.listFiles()?.count { it.name.contains(".part.") } ?: 0)
    }

    @Test
    fun `given incomplete download_when downloading original_then fails without leaving dest file`() = runTest {
        val expectedBytes = "good-bytes".toByteArray()
        val expectedSha = sha256Hex(expectedBytes)
        val caption = (
            PhotxBackupMetadataCodec.encode(
                PhotxBackupMetadata(expectedSha, token)
            ) as Outcome.Success
            ).value
        val destFile = File(tempFolder.root, "cache/incomplete.orig")

        val transport = FakeTransport(
            liveMessage = Outcome.Success(
                TdlibCandidateDocument(
                    chatId = 42L,
                    messageId = 9001L,
                    senderUserId = 42L,
                    telegramFileId = 7777,
                    telegramThumbFileId = 6666,
                    fileName = "photo.jpg",
                    byteCount = expectedBytes.size.toLong(),
                    caption = caption,
                    isSendingPending = false
                )
            ),
            downloadSourceFile = null
        )
        val gateway = TdlibBackupGateway(transport, dispatchers)

        val result = gateway.downloadVerifiedOriginalDocument(
            TelegramOriginalDownloadRequest(
                chatId = 42L,
                messageId = 9001L,
                correlationToken = token,
                expectedSha256 = expectedSha,
                expectedFileName = "photo.jpg",
                expectedByteCount = expectedBytes.size.toLong(),
                destinationPath = destFile.absolutePath
            )
        )

        assertTrue(result is TelegramOriginalDownloadResult.Failed)
        assertFalse(destFile.exists())
    }
}
