package com.example.photx.core.telegram

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome

/**
 * Internal gateway isolating TDLib JNI [org.drinkless.tdlib.Client] calls from repository logic
 * so authentication orchestration can be unit-tested on the pure JVM.
 */
internal interface TdlibGateway {
    fun start(onAuthEvent: (TdlibAuthEvent) -> Unit, onFatalError: (AppError) -> Unit): Outcome<Unit, AppError>

    suspend fun sendTdlibParameters(config: TdlibParametersConfig): Outcome<Unit, AppError>

    suspend fun sendPhoneNumber(phoneNumber: String): Outcome<Unit, AppError>

    suspend fun sendAuthenticationCode(code: String): Outcome<Unit, AppError>

    suspend fun sendPassword(password: String): Outcome<Unit, AppError>

    suspend fun sendLogOut(): Outcome<Unit, AppError>

    suspend fun fetchCurrentUserLabel(): Outcome<String, AppError>
}
