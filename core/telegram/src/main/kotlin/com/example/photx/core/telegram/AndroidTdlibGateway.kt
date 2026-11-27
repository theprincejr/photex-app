package com.example.photx.core.telegram

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * Production Android JNI bridge to TDLib's [Client] and [TdApi].
 */
@Suppress("TooManyFunctions")
internal class AndroidTdlibGateway :
    TdlibGateway,
    TdlibBackupTransport {
    private val clientLock = Any()
    private var activeClient: Client? = null
    private val _sendUpdates = MutableSharedFlow<TdlibSendUpdate>(extraBufferCapacity = SEND_UPDATE_BUFFER_CAPACITY)
    override val sendUpdates: Flow<TdlibSendUpdate> = _sendUpdates.asSharedFlow()
    private val fileUpdates = MutableSharedFlow<TdlibFileUpdate>(extraBufferCapacity = FILE_UPDATE_BUFFER_CAPACITY)

    override fun start(
        onAuthEvent: (TdlibAuthEvent) -> Unit,
        onFatalError: (AppError) -> Unit
    ): Outcome<Unit, AppError> = synchronized(clientLock) {
        if (activeClient != null) {
            return Outcome.Success(Unit)
        }

        try {
            configureLogVerbosity()
            val updateHandler = Client.ResultHandler { obj ->
                when (obj) {
                    is TdApi.UpdateAuthorizationState -> {
                        val event = TdlibAuthStateMapper.toAuthEvent(obj.authorizationState)
                        if (event is TdlibAuthEvent.Closed) {
                            synchronized(clientLock) {
                                activeClient = null
                            }
                        }
                        onAuthEvent(event)
                    }

                    is TdApi.UpdateMessageSendSucceeded -> {
                        mapSendSucceeded(obj)?.let(_sendUpdates::tryEmit)
                    }

                    is TdApi.UpdateMessageSendFailed -> {
                        val mappedError = TdlibAuthStateMapper.mapTdError(
                            code = obj.error?.code ?: 0,
                            message = obj.error?.message.orEmpty()
                        )
                        _sendUpdates.tryEmit(
                            TdlibSendUpdate.Failed(oldMessageId = obj.oldMessageId, error = mappedError)
                        )
                    }

                    is TdApi.UpdateFile -> {
                        mapFileUpdate(obj.file)?.let(fileUpdates::tryEmit)
                    }
                }
            }
            val exceptionHandler = Client.ExceptionHandler { cause ->
                onFatalError(
                    AppError.Unexpected(
                        message = cause.message ?: "Unexpected TDLib background callback failure.",
                        cause = cause
                    )
                )
            }
            activeClient = Client.create(updateHandler, exceptionHandler, exceptionHandler)
            Outcome.Success(Unit)
        } catch (error: UnsatisfiedLinkError) {
            Outcome.Failure(
                AppError.Io(
                    message = "Failed to load native TDLib library (libtdjni.so).",
                    cause = error
                )
            )
        }
    }

    override suspend fun sendTdlibParameters(config: TdlibParametersConfig): Outcome<Unit, AppError> {
        val query = TdApi.SetTdlibParameters(
            config.useTestDc,
            config.databaseDirectory,
            config.filesDirectory,
            ByteArray(0),
            config.useFileDatabase,
            config.useChatInfoDatabase,
            config.useMessageDatabase,
            false,
            config.apiId,
            config.apiHash,
            config.systemLanguageCode,
            config.deviceModel,
            config.systemVersion,
            config.applicationVersion
        )
        return sendExpectingOk(query)
    }

    override suspend fun sendPhoneNumber(phoneNumber: String): Outcome<Unit, AppError> {
        val settings = TdApi.PhoneNumberAuthenticationSettings(
            false,
            false,
            false,
            false,
            false,
            null,
            emptyArray()
        )
        return sendExpectingOk(TdApi.SetAuthenticationPhoneNumber(phoneNumber, settings))
    }

    override suspend fun sendAuthenticationCode(code: String): Outcome<Unit, AppError> =
        sendExpectingOk(TdApi.CheckAuthenticationCode(code))

    override suspend fun sendPassword(password: String): Outcome<Unit, AppError> =
        sendExpectingOk(TdApi.CheckAuthenticationPassword(password))

    override suspend fun sendLogOut(): Outcome<Unit, AppError> = sendExpectingOk(TdApi.LogOut())

    override suspend fun fetchCurrentUserLabel(): Outcome<String, AppError> =
        sendQuery<TdApi.User, String>(TdApi.GetMe()) { user ->
            Outcome.Success(TdlibAuthStateMapper.formatAccountDisplayLabel(user))
        }

    override suspend fun getCurrentUserId(): Outcome<Long, AppError> =
        sendQuery<TdApi.User, Long>(TdApi.GetMe()) { user ->
            Outcome.Success(user.id)
        }

    override suspend fun getSavedMessagesChatId(userId: Long): Outcome<Long, AppError> =
        sendQuery<TdApi.Chat, Long>(TdApi.CreatePrivateChat(userId, false)) { chat ->
            Outcome.Success(chat.id)
        }

    override suspend fun submitDocumentMessage(
        chatId: Long,
        stagedFilePath: String,
        caption: String,
        thumbnailFilePath: String?
    ): Outcome<Long, AppError> {
        val inputThumb = thumbnailFilePath?.let(::prepareJpegInputThumbnail)
        val inputDocument = TdApi.InputDocument(
            TdApi.InputFileLocal(stagedFilePath),
            inputThumb,
            true
        )
        val content = TdApi.InputMessageDocument(inputDocument, TdApi.FormattedText(caption, null))
        val request = TdApi.SendMessage(chatId, null, null, null, null, content)
        return sendQuery<TdApi.Message, Long>(request) { tempMessage ->
            Outcome.Success(tempMessage.id)
        }
    }

    private fun prepareJpegInputThumbnail(thumbPath: String): TdApi.InputThumbnail? {
        val sourceFile = File(thumbPath)
        val bitmap = if (sourceFile.exists() && sourceFile.length() > 0L) {
            BitmapFactory.decodeFile(thumbPath)
        } else {
            null
        }
        return bitmap?.let { decoded ->
            try {
                val jpegFile = if (thumbPath.endsWith(".jpg", ignoreCase = true) ||
                    thumbPath.endsWith(".jpeg", ignoreCase = true)
                ) {
                    sourceFile
                } else {
                    File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}.tg.jpg").also { out ->
                        FileOutputStream(out).use { stream ->
                            decoded.compress(Bitmap.CompressFormat.JPEG, JPEG_THUMB_QUALITY, stream)
                        }
                    }
                }
                TdApi.InputThumbnail(TdApi.InputFileLocal(jpegFile.absolutePath), decoded.width, decoded.height)
            } finally {
                decoded.recycle()
            }
        }
    }

    override suspend fun searchSavedMessagesDocuments(
        chatId: Long,
        senderUserId: Long,
        query: String,
        fromMessageId: Long,
        limit: Int
    ): Outcome<TdlibSearchPage, AppError> {
        val request = TdApi.SearchChatMessages(
            chatId,
            null,
            query,
            // Telegram rejects senderId in private-chat SearchChatMessages with INPUT_FILTER_INVALID;
            // sender is checked in validateCandidate.
            null,
            fromMessageId,
            0,
            limit,
            TdApi.SearchMessagesFilterDocument()
        )
        return sendQuery<TdApi.FoundChatMessages, TdlibSearchPage>(request) { found ->
            Outcome.Success(
                TdlibSearchPage(
                    messages = found.messages.orEmpty().mapNotNull(::toCandidateDocument),
                    nextFromMessageId = found.nextFromMessageId
                )
            )
        }
    }

    override suspend fun fetchSavedMessagesHistoryPage(
        chatId: Long,
        fromMessageId: Long,
        limit: Int
    ): Outcome<TdlibSearchPage, AppError> {
        val request = TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false)
        return sendQuery<TdApi.Messages, TdlibSearchPage>(request) { page ->
            val rawMessages = page.messages.orEmpty()
            val nextCursor = rawMessages.lastOrNull()?.id?.takeIf { it != fromMessageId } ?: 0L
            Outcome.Success(
                TdlibSearchPage(
                    messages = rawMessages.mapNotNull(::toCandidateDocument),
                    nextFromMessageId = nextCursor
                )
            )
        }
    }

    override suspend fun downloadTelegramFile(fileId: Int): Outcome<String, AppError> {
        val request = TdApi.DownloadFile(fileId, THUMBNAIL_DOWNLOAD_PRIORITY, 0L, 0L, true)
        return sendQuery<TdApi.File, String>(request) { file ->
            val localPath = file.local?.path
            if (file.local?.isDownloadingCompleted == true && !localPath.isNullOrBlank()) {
                Outcome.Success(localPath)
            } else {
                Outcome.Failure(AppError.Io("TDLib file download did not complete for fileId=$fileId."))
            }
        }
    }

    override suspend fun resolveMessageThumbnailFileId(chatId: Long, messageId: Long): Outcome<Int?, AppError> {
        val request = TdApi.GetMessage(chatId, messageId)
        return sendQuery<TdApi.Message, Int?>(request) { msg ->
            val doc = (msg.content as? TdApi.MessageDocument)?.document
            Outcome.Success(doc?.thumbnail?.file?.id)
        }
    }

    override suspend fun getMessageDocument(chatId: Long, messageId: Long): Outcome<TdlibCandidateDocument, AppError> {
        val request = TdApi.GetMessage(chatId, messageId)
        return sendQuery<TdApi.Message, TdlibCandidateDocument>(request) { msg ->
            val candidate = toCandidateDocument(msg)
            if (candidate != null) {
                Outcome.Success(candidate)
            } else {
                Outcome.Failure(AppError.Validation("Telegram message $messageId does not contain a document."))
            }
        }
    }

    override suspend fun downloadTelegramFileWithProgress(
        fileId: Int,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Outcome<String, AppError> = coroutineScope {
        val updateJob = launch {
            fileUpdates
                .filter { it.fileId == fileId }
                .collect { update ->
                    onProgress(update.downloadedBytes, update.totalBytes)
                }
        }
        try {
            val startOutcome = sendQuery<TdApi.File, TdlibFileUpdate>(
                TdApi.DownloadFile(fileId, ORIGINAL_DOWNLOAD_PRIORITY, 0L, 0L, false)
            ) { file ->
                val mapped = mapFileUpdate(file)
                if (mapped != null) {
                    Outcome.Success(mapped)
                } else {
                    Outcome.Failure(AppError.Io("TDLib returned null file for fileId=$fileId."))
                }
            }
            val initial = when (startOutcome) {
                is Outcome.Failure -> return@coroutineScope startOutcome
                is Outcome.Success -> startOutcome.value
            }
            onProgress(initial.downloadedBytes, initial.totalBytes)
            val initialPath = initial.localPath
            if (initial.isDownloadingCompleted && !initialPath.isNullOrBlank()) {
                return@coroutineScope Outcome.Success(initialPath)
            }
            val terminal = fileUpdates
                .filter { it.fileId == fileId && (it.isDownloadingCompleted || !it.isDownloadingActive) }
                .first()
            val finalPath = terminal.localPath
            if (terminal.isDownloadingCompleted && !finalPath.isNullOrBlank()) {
                Outcome.Success(finalPath)
            } else {
                Outcome.Failure(AppError.Io("TDLib download did not complete for fileId=$fileId."))
            }
        } catch (cancelled: CancellationException) {
            cancelTelegramDownload(fileId)
            throw cancelled
        } finally {
            updateJob.cancel()
        }
    }

    private fun cancelTelegramDownload(fileId: Int) {
        val client = synchronized(clientLock) { activeClient } ?: return
        client.send(TdApi.CancelDownloadFile(fileId, false)) { /* Best-effort cancellation. */ }
    }

    private fun mapFileUpdate(file: TdApi.File?): TdlibFileUpdate? {
        file ?: return null
        val total = file.size.takeIf { it > 0L } ?: file.expectedSize
        val downloaded = file.local?.downloadedSize ?: 0L
        return TdlibFileUpdate(
            fileId = file.id,
            downloadedBytes = downloaded,
            totalBytes = total,
            isDownloadingCompleted = file.local?.isDownloadingCompleted == true,
            isDownloadingActive = file.local?.isDownloadingActive == true,
            localPath = file.local?.path
        )
    }

    private fun mapSendSucceeded(update: TdApi.UpdateMessageSendSucceeded): TdlibSendUpdate.Succeeded? {
        val msg = update.message
        val docContent = msg?.content as? TdApi.MessageDocument
        val doc = docContent?.document
        return if (msg != null && doc != null) {
            TdlibSendUpdate.Succeeded(
                oldMessageId = update.oldMessageId,
                chatId = msg.chatId,
                messageId = msg.id,
                telegramFileId = doc.document?.id,
                telegramThumbFileId = doc.thumbnail?.file?.id,
                fileName = doc.fileName.orEmpty(),
                byteCount = doc.document?.size ?: 0L,
                caption = docContent.caption?.text
            )
        } else {
            null
        }
    }

    private fun toCandidateDocument(message: TdApi.Message?): TdlibCandidateDocument? {
        val docContent = message?.content as? TdApi.MessageDocument
        val doc = docContent?.document
        return if (message != null && doc != null) {
            TdlibCandidateDocument(
                chatId = message.chatId,
                messageId = message.id,
                senderUserId = (message.senderId as? TdApi.MessageSenderUser)?.userId,
                telegramFileId = doc.document?.id,
                telegramThumbFileId = doc.thumbnail?.file?.id,
                fileName = doc.fileName.orEmpty(),
                byteCount = doc.document?.size ?: 0L,
                caption = docContent.caption?.text,
                isSendingPending = message.sendingState != null,
                mimeType = doc.mimeType.orEmpty().ifBlank { "application/octet-stream" },
                dateSeconds = message.date.toLong()
            )
        } else {
            null
        }
    }

    private fun configureLogVerbosity() {
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(LOG_VERBOSITY_ERROR_ONLY))
        } catch (_: Client.ExecutionException) {
            // Non-fatal: continue with TDLib's default verbosity if synchronous log setup fails.
        }
    }

    private suspend fun sendExpectingOk(query: TdApi.Function<TdApi.Ok>): Outcome<Unit, AppError> =
        sendQuery<TdApi.Ok, Unit>(query) { Outcome.Success(Unit) }

    private suspend inline fun <reified T : TdApi.Object, R> sendQuery(
        query: TdApi.Function<*>,
        crossinline transform: (T) -> Outcome<R, AppError>
    ): Outcome<R, AppError> {
        val client = synchronized(clientLock) { activeClient }
            ?: return Outcome.Failure(AppError.Unexpected("TDLib client is not initialized."))

        return suspendCancellableCoroutine { continuation ->
            client.send(query) { result ->
                if (!continuation.isActive) return@send
                val mapped: Outcome<R, AppError> = when (result) {
                    is T -> transform(result)

                    is TdApi.Error -> Outcome.Failure(
                        TdlibAuthStateMapper.mapTdError(result.code, result.message.orEmpty())
                    )

                    else -> Outcome.Failure(
                        AppError.Unexpected("Unexpected TDLib response type for ${query.javaClass.simpleName}.")
                    )
                }
                continuation.resume(mapped)
            }
        }
    }

    private companion object {
        const val LOG_VERBOSITY_ERROR_ONLY = 1
        const val SEND_UPDATE_BUFFER_CAPACITY = 64
        const val FILE_UPDATE_BUFFER_CAPACITY = 128
        const val THUMBNAIL_DOWNLOAD_PRIORITY = 16
        const val ORIGINAL_DOWNLOAD_PRIORITY = 24
        const val JPEG_THUMB_QUALITY = 85
    }
}
