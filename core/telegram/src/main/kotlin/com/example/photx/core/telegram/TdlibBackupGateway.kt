package com.example.photx.core.telegram

import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.domain.backup.BackupMetadataDecodeResult
import com.example.photx.core.domain.backup.CloudDownloadProgress
import com.example.photx.core.domain.backup.PhotxBackupMetadata
import com.example.photx.core.domain.backup.PhotxBackupMetadataCodec
import com.example.photx.core.domain.backup.TelegramBackupGateway
import com.example.photx.core.domain.backup.TelegramBackupUploadRequest
import com.example.photx.core.domain.backup.TelegramConfirmedBackup
import com.example.photx.core.domain.backup.TelegramDiscoveryPage
import com.example.photx.core.domain.backup.TelegramOriginalDownloadRequest
import com.example.photx.core.domain.backup.TelegramOriginalDownloadResult
import com.example.photx.core.domain.backup.TelegramReconciliationResult
import com.example.photx.core.domain.backup.TelegramUploadResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TDLib-backed [TelegramBackupGateway] sending staged media as uncompressed documents to the
 * authenticated user's Saved Messages and reconciling uncertain sends via `#photx v1` metadata.
 */
@Suppress("TooManyFunctions")
internal class TdlibBackupGateway(
    private val transport: TdlibBackupTransport,
    private val dispatcherProvider: DispatcherProvider,
    private val confirmationTimeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS
) : TelegramBackupGateway {
    private val originalDownloadMutex = Mutex()
    override suspend fun sendStagedDocument(request: TelegramBackupUploadRequest): TelegramUploadResult =
        withContext(dispatcherProvider.io) {
            val preflightError = validatePreflight(request)
            if (preflightError != null) {
                return@withContext TelegramUploadResult.DefiniteFailure(preflightError)
            }
            val captionOutcome = PhotxBackupMetadataCodec.encode(request.metadata)
            val caption = when (captionOutcome) {
                is Outcome.Success -> captionOutcome.value
                is Outcome.Failure -> return@withContext TelegramUploadResult.DefiniteFailure(captionOutcome.error)
            }
            val chatId = when (val chatOutcome = resolveSavedMessagesContext()) {
                is Outcome.Success -> chatOutcome.value.second
                is Outcome.Failure -> return@withContext TelegramUploadResult.DefiniteFailure(chatOutcome.error)
            }
            executeSendAndAwaitServerConfirmation(chatId = chatId, request = request, caption = caption)
        }

    override suspend fun findBackupByCorrelationToken(
        correlationToken: String,
        expectedSha256: String,
        expectedFileName: String?,
        expectedByteCount: Long?
    ): TelegramReconciliationResult = withContext(dispatcherProvider.io) {
        if (!PhotxBackupMetadata.isValidCorrelationToken(correlationToken)) {
            return@withContext TelegramReconciliationResult.Failed(
                AppError.Validation("Invalid correlation token for Telegram reconciliation.")
            )
        }
        val (userId, chatId) = when (val ctx = resolveSavedMessagesContext()) {
            is Outcome.Success -> ctx.value
            is Outcome.Failure -> return@withContext TelegramReconciliationResult.Failed(ctx.error)
        }
        searchAllPagesForMatch(
            userId = userId,
            chatId = chatId,
            correlationToken = correlationToken,
            expectedSha256 = expectedSha256,
            expectedFileName = expectedFileName,
            expectedByteCount = expectedByteCount
        )
    }

    override suspend fun discoverBackedUpDocuments(
        fromMessageId: Long,
        limit: Int
    ): Outcome<TelegramDiscoveryPage, AppError> = withContext(dispatcherProvider.io) {
        val (userId, chatId) = when (val ctx = resolveSavedMessagesContext()) {
            is Outcome.Success -> ctx.value
            is Outcome.Failure -> return@withContext Outcome.Failure(ctx.error)
        }
        val pageOutcome = transport.fetchSavedMessagesHistoryPage(
            chatId = chatId,
            fromMessageId = fromMessageId,
            limit = limit.coerceIn(1, MAX_DISCOVERY_PAGE_SIZE)
        )
        when (pageOutcome) {
            is Outcome.Failure -> Outcome.Failure(pageOutcome.error)

            is Outcome.Success -> {
                val validBackups = mutableListOf<TelegramConfirmedBackup>()
                var ignoredCount = 0
                pageOutcome.value.messages.forEach { candidate ->
                    val parsed = parseDiscoveredCandidate(candidate, chatId, userId)
                    if (parsed != null) validBackups.add(parsed) else ignoredCount += 1
                }
                Outcome.Success(
                    TelegramDiscoveryPage(
                        backups = validBackups,
                        nextFromMessageId = pageOutcome.value.nextFromMessageId,
                        ignoredNonPhotxCount = ignoredCount
                    )
                )
            }
        }
    }

    override suspend fun downloadThumbnail(
        telegramThumbFileId: Int,
        destinationPath: String
    ): Outcome<String, AppError> = withContext(dispatcherProvider.io) {
        if (telegramThumbFileId <= 0 || destinationPath.isBlank()) {
            return@withContext Outcome.Failure(
                AppError.Validation("Valid telegramThumbFileId and destinationPath are required.")
            )
        }
        when (val downloaded = transport.downloadTelegramFile(telegramThumbFileId)) {
            is Outcome.Failure -> Outcome.Failure(downloaded.error)
            is Outcome.Success -> copyAtomicallyToDestination(downloaded.value, destinationPath)
        }
    }

    override suspend fun resolveThumbnailFileId(chatId: Long, messageId: Long): Outcome<Int?, AppError> =
        withContext(dispatcherProvider.io) {
            if (chatId == 0L || messageId <= 0L) {
                Outcome.Success(null)
            } else {
                transport.resolveMessageThumbnailFileId(chatId, messageId)
            }
        }

    override suspend fun downloadVerifiedOriginalDocument(
        request: TelegramOriginalDownloadRequest,
        onProgress: (CloudDownloadProgress) -> Unit
    ): TelegramOriginalDownloadResult = withContext(dispatcherProvider.io) {
        if (request.chatId == 0L || request.messageId <= 0L || request.destinationPath.isBlank()) {
            return@withContext TelegramOriginalDownloadResult.Failed(
                AppError.Validation("Valid chatId, messageId, and destinationPath are required.")
            )
        }
        originalDownloadMutex.withLock {
            val (userId, savedChatId) = when (val ctx = resolveSavedMessagesContext()) {
                is Outcome.Success -> ctx.value
                is Outcome.Failure -> return@withLock TelegramOriginalDownloadResult.Failed(ctx.error)
            }
            if (request.chatId != savedChatId) {
                return@withLock TelegramOriginalDownloadResult.Failed(
                    AppError.Validation("Remote chat ID does not match Saved Messages.")
                )
            }
            val candidate = when (val msgOutcome = transport.getMessageDocument(request.chatId, request.messageId)) {
                is Outcome.Failure -> return@withLock TelegramOriginalDownloadResult.Failed(msgOutcome.error)
                is Outcome.Success -> msgOutcome.value
            }
            val validated = validateCandidate(
                candidate = candidate,
                expectedChatId = savedChatId,
                expectedUserId = userId,
                correlationToken = request.correlationToken,
                expectedSha256 = request.expectedSha256,
                expectedFileName = request.expectedFileName,
                expectedByteCount = request.expectedByteCount
            ) ?: return@withLock TelegramOriginalDownloadResult.Failed(
                AppError.Validation("Live Telegram message failed #photx v1 ownership/metadata validation.")
            )
            val liveDocFileId = validated.telegramFileId?.takeIf { it > 0 }
                ?: return@withLock TelegramOriginalDownloadResult.Failed(
                    AppError.Io("Live Telegram message has no downloadable document file ID.")
                )
            val downloadedPath = when (
                val dl = transport.downloadTelegramFileWithProgress(liveDocFileId) { downloaded, total ->
                    onProgress(CloudDownloadProgress(downloaded, total))
                }
            ) {
                is Outcome.Failure -> return@withLock TelegramOriginalDownloadResult.Failed(dl.error)
                is Outcome.Success -> dl.value
            }
            stageAndVerifyOriginal(
                sourcePath = downloadedPath,
                destinationPath = request.destinationPath,
                expectedSha256 = request.expectedSha256,
                expectedByteCount = request.expectedByteCount
            )
        }
    }

    private suspend fun stageAndVerifyOriginal(
        sourcePath: String,
        destinationPath: String,
        expectedSha256: String,
        expectedByteCount: Long?
    ): TelegramOriginalDownloadResult {
        val source = File(sourcePath)
        if (!source.isFile || !source.canRead() || source.length() <= 0L) {
            return TelegramOriginalDownloadResult.Failed(
                AppError.Io("Downloaded Telegram file is missing or unreadable.")
            )
        }
        val dest = File(destinationPath)
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "${dest.name}.part.${System.nanoTime()}")
        var promoted = false
        return try {
            val (totalBytes, actualSha256) = streamCopyWithSha256(source, part)
            val result = promoteVerifiedOriginal(
                part = part,
                dest = dest,
                actualSha256 = actualSha256,
                totalBytes = totalBytes,
                expectedSha256 = expectedSha256,
                expectedByteCount = expectedByteCount
            )
            promoted = result is TelegramOriginalDownloadResult.Verified
            result
        } catch (error: IOException) {
            TelegramOriginalDownloadResult.Failed(AppError.Io("Failed to stage downloaded original.", cause = error))
        } finally {
            if (!promoted) part.delete()
        }
    }

    private suspend fun streamCopyWithSha256(source: File, part: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var totalBytes = 0L
        FileInputStream(source).use { input ->
            FileOutputStream(part).use { output ->
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    totalBytes += read
                }
                output.fd.sync()
            }
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        return totalBytes to actualSha256
    }

    private fun promoteVerifiedOriginal(
        part: File,
        dest: File,
        actualSha256: String,
        totalBytes: Long,
        expectedSha256: String,
        expectedByteCount: Long?
    ): TelegramOriginalDownloadResult {
        val expectedSize = expectedByteCount?.takeIf { it > 0L }
        val sizeMismatch = totalBytes <= 0L || (expectedSize != null && totalBytes != expectedSize)
        return when {
            sizeMismatch -> TelegramOriginalDownloadResult.IntegrityFailed(
                "Downloaded byte count mismatch ($totalBytes != $expectedByteCount)."
            )

            !actualSha256.equals(expectedSha256, ignoreCase = true) -> TelegramOriginalDownloadResult.IntegrityFailed(
                "SHA-256 verification failed for downloaded original."
            )

            else -> {
                if (dest.exists()) dest.delete()
                if (part.renameTo(dest)) {
                    TelegramOriginalDownloadResult.Verified(dest.absolutePath, actualSha256, totalBytes)
                } else {
                    TelegramOriginalDownloadResult.Failed(
                        AppError.Io("Unable to promote verified original cache file.")
                    )
                }
            }
        }
    }

    private fun parseDiscoveredCandidate(
        candidate: TdlibCandidateDocument,
        expectedChatId: Long,
        expectedUserId: Long
    ): TelegramConfirmedBackup? {
        val valid = isCandidateEligible(candidate, expectedChatId, expectedUserId)
        val decoded = if (valid) PhotxBackupMetadataCodec.decode(candidate.caption) else null
        return if (decoded is BackupMetadataDecodeResult.Valid && candidate.byteCount > 0L) {
            TelegramConfirmedBackup(
                chatId = candidate.chatId,
                messageId = candidate.messageId,
                telegramFileId = candidate.telegramFileId,
                telegramThumbFileId = candidate.telegramThumbFileId,
                metadata = decoded.metadata,
                fileName = candidate.fileName.ifBlank { "telegram_${candidate.messageId}" },
                byteCount = candidate.byteCount,
                mimeType = candidate.mimeType,
                dateSeconds = candidate.dateSeconds
            )
        } else {
            null
        }
    }

    private fun copyAtomicallyToDestination(sourcePath: String, destinationPath: String): Outcome<String, AppError> =
        try {
            val source = File(sourcePath)
            val dest = File(destinationPath)
            dest.parentFile?.mkdirs()
            val part = File(dest.parentFile, "${dest.name}.part.${System.nanoTime()}")
            source.copyTo(part, overwrite = true)
            if (dest.exists()) dest.delete()
            if (part.renameTo(dest)) {
                Outcome.Success(dest.absolutePath)
            } else {
                part.delete()
                Outcome.Failure(AppError.Io("Unable to finalize downloaded thumbnail."))
            }
        } catch (error: IOException) {
            Outcome.Failure(AppError.Io("Failed to cache downloaded thumbnail.", cause = error))
        }

    private fun validatePreflight(request: TelegramBackupUploadRequest): AppError? {
        val stagedFile = File(request.stagedFilePath)
        return when {
            !stagedFile.isFile ->
                AppError.Io("Staged upload file does not exist: ${stagedFile.name}")

            stagedFile.length() != request.expectedByteCount ->
                AppError.Io(
                    "Staged upload file size mismatch (${stagedFile.length()} != ${request.expectedByteCount})."
                )

            else -> null
        }
    }

    private suspend fun resolveSavedMessagesContext(): Outcome<Pair<Long, Long>, AppError> {
        val userOutcome = transport.getCurrentUserId()
        return when (userOutcome) {
            is Outcome.Failure -> Outcome.Failure(userOutcome.error)

            is Outcome.Success -> {
                val userId = userOutcome.value
                val chatOutcome = transport.getSavedMessagesChatId(userId)
                when {
                    chatOutcome is Outcome.Failure -> Outcome.Failure(chatOutcome.error)

                    chatOutcome is Outcome.Success && userId > 0L && chatOutcome.value != 0L ->
                        Outcome.Success(userId to chatOutcome.value)

                    else -> Outcome.Failure(
                        AppError.Unauthorized("Authenticated Saved Messages context is unavailable.")
                    )
                }
            }
        }
    }

    private suspend fun executeSendAndAwaitServerConfirmation(
        chatId: Long,
        request: TelegramBackupUploadRequest,
        caption: String
    ): TelegramUploadResult = coroutineScope {
        val expectedTempId = CompletableDeferred<Long>()
        val updateWaiter = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(confirmationTimeoutMs) {
                transport.sendUpdates.firstOrNull { update ->
                    val tempId = expectedTempId.await()
                    update.oldMessageId == tempId
                }
            }
        }
        val tempId = when (
            val submitOutcome = transport.submitDocumentMessage(
                chatId = chatId,
                stagedFilePath = request.stagedFilePath,
                caption = caption,
                thumbnailFilePath = request.thumbnailFilePath
            )
        ) {
            is Outcome.Success -> submitOutcome.value

            is Outcome.Failure -> {
                updateWaiter.cancel()
                return@coroutineScope TelegramUploadResult.DefiniteFailure(submitOutcome.error)
            }
        }
        if (tempId == 0L) {
            updateWaiter.cancel()
            return@coroutineScope TelegramUploadResult.Uncertain(
                reason = "TDLib returned zero temporary message ID; Saved Messages reconciliation required."
            )
        }
        expectedTempId.complete(tempId)

        when (val update = updateWaiter.await()) {
            is TdlibSendUpdate.Succeeded -> mapSucceededUpdate(update, chatId, request)

            is TdlibSendUpdate.Failed -> TelegramUploadResult.DefiniteFailure(update.error)

            null -> TelegramUploadResult.Uncertain(
                reason = "Timed out or disconnected before TDLib confirmed server message ID."
            )
        }
    }

    private fun mapSucceededUpdate(
        update: TdlibSendUpdate.Succeeded,
        expectedChatId: Long,
        request: TelegramBackupUploadRequest
    ): TelegramUploadResult = if (update.messageId <= 0L || update.chatId != expectedChatId) {
        TelegramUploadResult.Uncertain(
            reason = "TDLib confirmation had unexpected chat (${update.chatId}) or messageId (${update.messageId})."
        )
    } else {
        TelegramUploadResult.Confirmed(
            TelegramConfirmedBackup(
                chatId = update.chatId,
                messageId = update.messageId,
                telegramFileId = update.telegramFileId,
                telegramThumbFileId = update.telegramThumbFileId,
                metadata = request.metadata,
                fileName = update.fileName.ifBlank { request.fileName },
                byteCount = update.byteCount.takeIf { it > 0L } ?: request.expectedByteCount
            )
        )
    }

    private suspend fun searchAllPagesForMatch(
        userId: Long,
        chatId: Long,
        correlationToken: String,
        expectedSha256: String,
        expectedFileName: String?,
        expectedByteCount: Long?
    ): TelegramReconciliationResult {
        val searchResult = scanPagesForMatch(
            userId = userId,
            chatId = chatId,
            correlationToken = correlationToken,
            expectedSha256 = expectedSha256,
            expectedFileName = expectedFileName,
            expectedByteCount = expectedByteCount
        ) { cursor ->
            transport.searchSavedMessagesDocuments(
                chatId = chatId,
                senderUserId = userId,
                query = correlationToken,
                fromMessageId = cursor,
                limit = SEARCH_PAGE_SIZE
            )
        }
        if (searchResult !is TelegramReconciliationResult.NotFound) {
            return searchResult
        }
        // Telegram's server-side SearchChatMessages may not match hyphenated UUID tokens immediately;
        // fall back to scanning recent Saved Messages history pages.
        return scanPagesForMatch(
            userId = userId,
            chatId = chatId,
            correlationToken = correlationToken,
            expectedSha256 = expectedSha256,
            expectedFileName = expectedFileName,
            expectedByteCount = expectedByteCount
        ) { cursor ->
            transport.fetchSavedMessagesHistoryPage(
                chatId = chatId,
                fromMessageId = cursor,
                limit = SEARCH_PAGE_SIZE
            )
        }
    }

    private suspend fun scanPagesForMatch(
        userId: Long,
        chatId: Long,
        correlationToken: String,
        expectedSha256: String,
        expectedFileName: String?,
        expectedByteCount: Long?,
        fetchPage: suspend (cursorMessageId: Long) -> Outcome<TdlibSearchPage, AppError>
    ): TelegramReconciliationResult {
        val matches = mutableListOf<TelegramConfirmedBackup>()
        var cursorMessageId = 0L
        for (pageIndex in 0 until MAX_SEARCH_PAGES) {
            val page = when (val pageOutcome = fetchPage(cursorMessageId)) {
                is Outcome.Success -> pageOutcome.value
                is Outcome.Failure -> return TelegramReconciliationResult.Failed(pageOutcome.error)
            }
            page.messages.forEach { candidate ->
                validateCandidate(
                    candidate = candidate,
                    expectedChatId = chatId,
                    expectedUserId = userId,
                    correlationToken = correlationToken,
                    expectedSha256 = expectedSha256,
                    expectedFileName = expectedFileName,
                    expectedByteCount = expectedByteCount
                )?.let(matches::add)
            }
            if (page.nextFromMessageId == 0L || page.nextFromMessageId == cursorMessageId) {
                break
            }
            cursorMessageId = page.nextFromMessageId
        }
        return when (matches.size) {
            0 -> TelegramReconciliationResult.NotFound

            1 -> TelegramReconciliationResult.Matched(matches.single())

            else -> TelegramReconciliationResult.Ambiguous(
                candidateCount = matches.size,
                reason = "Multiple Saved Messages documents matched correlation token '$correlationToken'."
            )
        }
    }

    private fun validateCandidate(
        candidate: TdlibCandidateDocument,
        expectedChatId: Long,
        expectedUserId: Long,
        correlationToken: String,
        expectedSha256: String,
        expectedFileName: String?,
        expectedByteCount: Long?
    ): TelegramConfirmedBackup? {
        val decoded = if (isCandidateEligible(candidate, expectedChatId, expectedUserId)) {
            PhotxBackupMetadataCodec.decode(candidate.caption) as? BackupMetadataDecodeResult.Valid
        } else {
            null
        }
        val metadata = decoded?.metadata?.takeIf {
            it.correlationToken == correlationToken && it.sha256 == expectedSha256
        }
        val fileNameMatches = expectedFileName == null ||
            candidate.fileName.isBlank() ||
            candidate.fileName == expectedFileName ||
            candidate.fileName.endsWith(".staged")
        val sizeMatches = expectedByteCount == null ||
            candidate.byteCount <= 0L ||
            candidate.byteCount == expectedByteCount
        return if (metadata != null && fileNameMatches && sizeMatches) {
            TelegramConfirmedBackup(
                chatId = candidate.chatId,
                messageId = candidate.messageId,
                telegramFileId = candidate.telegramFileId,
                telegramThumbFileId = candidate.telegramThumbFileId,
                metadata = metadata,
                fileName = candidate.fileName.ifBlank { expectedFileName.orEmpty() },
                byteCount = candidate.byteCount
            )
        } else {
            null
        }
    }

    private fun isCandidateEligible(
        candidate: TdlibCandidateDocument,
        expectedChatId: Long,
        expectedUserId: Long
    ): Boolean = candidate.chatId == expectedChatId &&
        candidate.senderUserId == expectedUserId &&
        candidate.messageId > 0L &&
        !candidate.isSendingPending

    internal companion object {
        const val DEFAULT_CONFIRMATION_TIMEOUT_MS: Long = 300_000L
        const val SEARCH_PAGE_SIZE: Int = 50
        const val MAX_SEARCH_PAGES: Int = 10
        const val MAX_DISCOVERY_PAGE_SIZE: Int = 100
        const val STREAM_BUFFER_BYTES: Int = 65_536
    }
}
