package com.example.photx.core.domain.backup

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome

/**
 * Request to upload an already-staged local file to Saved Messages as an uncompressed document.
 */
public data class TelegramBackupUploadRequest(
    public val stagedFilePath: String,
    public val fileName: String,
    public val mimeType: String,
    public val expectedByteCount: Long,
    public val metadata: PhotxBackupMetadata,
    public val thumbnailFilePath: String? = null
) {
    init {
        require(stagedFilePath.isNotBlank()) { "stagedFilePath must not be blank." }
        require(fileName.isNotBlank()) { "fileName must not be blank." }
        require(mimeType.isNotBlank()) { "mimeType must not be blank." }
        require(expectedByteCount > 0L) { "expectedByteCount must be positive." }
    }
}

/**
 * Server-confirmed Telegram Saved Messages document backup metadata.
 *
 * [messageId] is strictly the final server-assigned TDLib message ID (`UpdateMessageSendSucceeded`),
 * never a temporary local message identifier.
 */
public data class TelegramConfirmedBackup(
    public val chatId: Long,
    public val messageId: Long,
    public val telegramFileId: Int? = null,
    public val telegramThumbFileId: Int? = null,
    public val metadata: PhotxBackupMetadata,
    public val fileName: String,
    public val byteCount: Long,
    public val mimeType: String = "application/octet-stream",
    public val dateSeconds: Long = 0L
) {
    init {
        require(chatId != 0L) { "Telegram chatId must not be zero." }
        require(messageId > 0L) { "Confirmed Telegram messageId must be positive, got $messageId." }
    }
}

/**
 * Explicit three-way outcome of sending a staged document to Telegram Saved Messages.
 */
public sealed interface TelegramUploadResult {
    /** Server confirmed delivery via `UpdateMessageSendSucceeded` with a final [TelegramConfirmedBackup]. */
    public data class Confirmed(public val backup: TelegramConfirmedBackup) : TelegramUploadResult

    /** Explicit rejection before remote acceptance (safe to mark FAILED without reconciliation). */
    public data class DefiniteFailure(public val error: AppError) : TelegramUploadResult

    /**
     * Delivery outcome is unknown (timeout, network drop, or client closure after local submission).
     * Callers MUST reconcile Saved Messages using the persisted correlation token before retrying.
     */
    public data class Uncertain(public val reason: String, public val error: AppError? = null) : TelegramUploadResult
}

/**
 * Outcome of searching Saved Messages for an already-accepted upload by correlation token.
 */
public sealed interface TelegramReconciliationResult {
    public data class Matched(public val backup: TelegramConfirmedBackup) : TelegramReconciliationResult

    public data object NotFound : TelegramReconciliationResult

    public data class Ambiguous(public val candidateCount: Int, public val reason: String) :
        TelegramReconciliationResult

    public data class Failed(public val error: AppError) : TelegramReconciliationResult
}

/**
 * Single page of validated `#photx v1` backups discovered from Telegram Saved Messages.
 */
public data class TelegramDiscoveryPage(
    public val backups: List<TelegramConfirmedBackup>,
    public val nextFromMessageId: Long,
    public val ignoredNonPhotxCount: Int = 0
)

/**
 * Genuine byte-level progress reported by TDLib during an on-demand original download.
 */
public data class CloudDownloadProgress(public val downloadedBytes: Long, public val totalBytes: Long)

/**
 * Request to download and verify the full-resolution original document for a `CLOUD_ONLY` item.
 */
public data class TelegramOriginalDownloadRequest(
    public val chatId: Long,
    public val messageId: Long,
    public val correlationToken: String,
    public val expectedSha256: String,
    public val expectedFileName: String? = null,
    public val expectedByteCount: Long? = null,
    public val destinationPath: String
)

/**
 * Outcome of validating, downloading, and SHA-256 verifying a Telegram original document.
 */
public sealed interface TelegramOriginalDownloadResult {
    public data class Verified(public val filePath: String, public val sha256: String, public val byteCount: Long) :
        TelegramOriginalDownloadResult

    public data class IntegrityFailed(public val reason: String) : TelegramOriginalDownloadResult

    public data class Failed(public val error: AppError) : TelegramOriginalDownloadResult
}

/**
 * Domain-facing gateway for uploading staged media documents to Saved Messages, reconciling
 * uncertain uploads, discovering cloud-only backups, and downloading thumbnails without exposing
 * TDLib types to domain or worker layers.
 */
public interface TelegramBackupGateway {
    public suspend fun sendStagedDocument(request: TelegramBackupUploadRequest): TelegramUploadResult

    public suspend fun findBackupByCorrelationToken(
        correlationToken: String,
        expectedSha256: String,
        expectedFileName: String? = null,
        expectedByteCount: Long? = null
    ): TelegramReconciliationResult

    public suspend fun discoverBackedUpDocuments(
        fromMessageId: Long = 0L,
        limit: Int = 50
    ): Outcome<TelegramDiscoveryPage, AppError> = Outcome.Success(TelegramDiscoveryPage(emptyList(), 0L, 0))

    public suspend fun downloadThumbnail(telegramThumbFileId: Int, destinationPath: String): Outcome<String, AppError> =
        Outcome.Failure(AppError.Io("Thumbnail download not supported."))

    public suspend fun resolveThumbnailFileId(chatId: Long, messageId: Long): Outcome<Int?, AppError> =
        Outcome.Success(null)

    public suspend fun downloadVerifiedOriginalDocument(
        request: TelegramOriginalDownloadRequest,
        onProgress: (CloudDownloadProgress) -> Unit = {}
    ): TelegramOriginalDownloadResult =
        TelegramOriginalDownloadResult.Failed(AppError.Io("Original document download not supported."))
}
