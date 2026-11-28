package com.example.photx.core.telegram

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * TDLib asynchronous delivery update mapped out of JNI [org.drinkless.tdlib.TdApi] types for
 * pure-JVM testability of [TdlibBackupGateway].
 */
internal sealed interface TdlibSendUpdate {
    val oldMessageId: Long

    data class Succeeded(
        override val oldMessageId: Long,
        val chatId: Long,
        val messageId: Long,
        val telegramFileId: Int?,
        val telegramThumbFileId: Int?,
        val fileName: String,
        val byteCount: Long,
        val caption: String?
    ) : TdlibSendUpdate

    data class Failed(override val oldMessageId: Long, val error: AppError) : TdlibSendUpdate
}

/**
 * Candidate document returned by Saved Messages history search.
 */
internal data class TdlibCandidateDocument(
    val chatId: Long,
    val messageId: Long,
    val senderUserId: Long?,
    val telegramFileId: Int?,
    val telegramThumbFileId: Int?,
    val fileName: String,
    val byteCount: Long,
    val caption: String?,
    val isSendingPending: Boolean,
    val mimeType: String = "application/octet-stream",
    val dateSeconds: Long = 0L,
    val minithumbnailBytes: ByteArray? = null
)

/**
 * Single page from TDLib `searchChatMessages`.
 */
internal data class TdlibSearchPage(val messages: List<TdlibCandidateDocument>, val nextFromMessageId: Long)

/**
 * TDLib `UpdateFile` download snapshot mapped out of JNI types.
 */
internal data class TdlibFileUpdate(
    val fileId: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val isDownloadingCompleted: Boolean,
    val isDownloadingActive: Boolean,
    val localPath: String?
)

/**
 * Internal transport seam isolating TDLib JNI calls from [TdlibBackupGateway] orchestration.
 */
internal interface TdlibBackupTransport {
    val sendUpdates: Flow<TdlibSendUpdate>

    suspend fun getCurrentUserId(): Outcome<Long, AppError>

    suspend fun getSavedMessagesChatId(userId: Long): Outcome<Long, AppError>

    /**
     * Submits an `InputMessageDocument` to [chatId] and returns TDLib's temporary local message ID.
     *
     * Callers MUST NOT persist this temporary ID as the final server message ID.
     */
    suspend fun submitDocumentMessage(
        chatId: Long,
        stagedFilePath: String,
        caption: String,
        thumbnailFilePath: String? = null
    ): Outcome<Long, AppError>

    suspend fun searchSavedMessagesDocuments(
        chatId: Long,
        senderUserId: Long,
        query: String,
        fromMessageId: Long,
        limit: Int
    ): Outcome<TdlibSearchPage, AppError>

    suspend fun fetchSavedMessagesHistoryPage(
        chatId: Long,
        fromMessageId: Long,
        limit: Int
    ): Outcome<TdlibSearchPage, AppError>

    suspend fun downloadTelegramFile(fileId: Int): Outcome<String, AppError>

    suspend fun resolveMessageThumbnailFileId(chatId: Long, messageId: Long): Outcome<Int?, AppError> =
        Outcome.Success(null)

    suspend fun getMessageDocument(chatId: Long, messageId: Long): Outcome<TdlibCandidateDocument, AppError> =
        Outcome.Failure(AppError.Io("Live message lookup not supported."))

    suspend fun downloadTelegramFileWithProgress(
        fileId: Int,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Outcome<String, AppError> = downloadTelegramFile(fileId)
}
