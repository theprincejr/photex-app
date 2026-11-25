package com.example.photx.di

import com.example.photx.core.common.AppError
import com.example.photx.core.common.DefaultDispatcherProvider
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.domain.auth.AuthRepository
import com.example.photx.core.domain.auth.AuthState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-level dependency bindings.
 *
 * `AuthRepository` temporarily binds [PendingTdlibAuthRepository] while TDLib binary adoption
 * (`:core:telegram`) awaits supply-chain verification (`gh attestation verify` or first-party build).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    @Singleton
    fun provideAuthRepository(): AuthRepository = PendingTdlibAuthRepository()
}

private class PendingTdlibAuthRepository : AuthRepository {
    private val pendingError = AppError.Validation(
        "Telegram backend (:core:telegram) is paused until TDLib supply-chain verification is approved."
    )
    private val stateFlow = MutableStateFlow<AuthState>(AuthState.Error(pendingError))

    override val authState: StateFlow<AuthState> = stateFlow.asStateFlow()

    override suspend fun initialize(): Outcome<Unit, AppError> = Outcome.Failure(pendingError)

    override suspend fun submitPhoneNumber(phoneNumber: String): Outcome<Unit, AppError> = Outcome.Failure(pendingError)

    override suspend fun submitAuthenticationCode(code: String): Outcome<Unit, AppError> = Outcome.Failure(pendingError)

    override suspend fun submitPassword(password: String): Outcome<Unit, AppError> = Outcome.Failure(pendingError)

    override suspend fun logOut(): Outcome<Unit, AppError> = Outcome.Failure(pendingError)
}
