package com.example.photx.core.telegram

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome

internal class FakeTdlibGateway : TdlibGateway {
    private var authEventHandler: ((TdlibAuthEvent) -> Unit)? = null
    private var fatalErrorHandler: ((AppError) -> Unit)? = null

    var startCalls: Int = 0
        private set
    val sentParameters: MutableList<TdlibParametersConfig> = mutableListOf()
    val sentPhoneNumbers: MutableList<String> = mutableListOf()
    val sentCodes: MutableList<String> = mutableListOf()
    val sentPasswords: MutableList<String> = mutableListOf()
    var logOutCalls: Int = 0
        private set

    var startResult: Outcome<Unit, AppError> = Outcome.Success(Unit)
    var sendParametersResult: Outcome<Unit, AppError> = Outcome.Success(Unit)
    var sendPhoneResult: Outcome<Unit, AppError> = Outcome.Success(Unit)
    var sendCodeResult: Outcome<Unit, AppError> = Outcome.Success(Unit)
    var sendPasswordResult: Outcome<Unit, AppError> = Outcome.Success(Unit)
    var sendLogOutResult: Outcome<Unit, AppError> = Outcome.Success(Unit)
    var fetchUserLabelResult: Outcome<String, AppError> = Outcome.Success("@photx_user")

    override fun start(
        onAuthEvent: (TdlibAuthEvent) -> Unit,
        onFatalError: (AppError) -> Unit
    ): Outcome<Unit, AppError> {
        startCalls += 1
        authEventHandler = onAuthEvent
        fatalErrorHandler = onFatalError
        return startResult
    }

    override suspend fun sendTdlibParameters(config: TdlibParametersConfig): Outcome<Unit, AppError> {
        sentParameters += config
        return sendParametersResult
    }

    override suspend fun sendPhoneNumber(phoneNumber: String): Outcome<Unit, AppError> {
        sentPhoneNumbers += phoneNumber
        return sendPhoneResult
    }

    override suspend fun sendAuthenticationCode(code: String): Outcome<Unit, AppError> {
        sentCodes += code
        return sendCodeResult
    }

    override suspend fun sendPassword(password: String): Outcome<Unit, AppError> {
        sentPasswords += password
        return sendPasswordResult
    }

    override suspend fun sendLogOut(): Outcome<Unit, AppError> {
        logOutCalls += 1
        return sendLogOutResult
    }

    override suspend fun fetchCurrentUserLabel(): Outcome<String, AppError> = fetchUserLabelResult

    fun emitAuthEvent(event: TdlibAuthEvent) {
        authEventHandler?.invoke(event)
    }

    fun emitFatalError(error: AppError) {
        fatalErrorHandler?.invoke(error)
    }
}
