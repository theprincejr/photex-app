package com.example.photx.core.telegram

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * Production Android JNI bridge to TDLib's [Client] and [TdApi].
 */
internal class AndroidTdlibGateway : TdlibGateway {
    private val clientLock = Any()
    private var activeClient: Client? = null

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
                if (obj is TdApi.UpdateAuthorizationState) {
                    val event = TdlibAuthStateMapper.toAuthEvent(obj.authorizationState)
                    if (event is TdlibAuthEvent.Closed) {
                        synchronized(clientLock) {
                            activeClient = null
                        }
                    }
                    onAuthEvent(event)
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
    }
}
