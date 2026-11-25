package com.example.photx.feature.auth.di

import com.example.photx.core.domain.auth.AuthRepository
import com.example.photx.core.domain.auth.InitializeAuthUseCase
import com.example.photx.core.domain.auth.LogOutUseCase
import com.example.photx.core.domain.auth.ObserveAuthStateUseCase
import com.example.photx.core.domain.auth.SubmitAuthCodeUseCase
import com.example.photx.core.domain.auth.SubmitPasswordUseCase
import com.example.photx.core.domain.auth.SubmitPhoneNumberUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Provides pure-Kotlin domain auth use cases for `:feature:auth` without coupling `:core:domain`
 * to Hilt or Android DI annotations.
 */
@Module
@InstallIn(SingletonComponent::class)
public object AuthDomainModule {
    @Provides
    public fun provideObserveAuthStateUseCase(authRepository: AuthRepository): ObserveAuthStateUseCase =
        ObserveAuthStateUseCase(authRepository)

    @Provides
    public fun provideInitializeAuthUseCase(authRepository: AuthRepository): InitializeAuthUseCase =
        InitializeAuthUseCase(authRepository)

    @Provides
    public fun provideSubmitPhoneNumberUseCase(authRepository: AuthRepository): SubmitPhoneNumberUseCase =
        SubmitPhoneNumberUseCase(authRepository)

    @Provides
    public fun provideSubmitAuthCodeUseCase(authRepository: AuthRepository): SubmitAuthCodeUseCase =
        SubmitAuthCodeUseCase(authRepository)

    @Provides
    public fun provideSubmitPasswordUseCase(authRepository: AuthRepository): SubmitPasswordUseCase =
        SubmitPasswordUseCase(authRepository)

    @Provides
    public fun provideLogOutUseCase(authRepository: AuthRepository): LogOutUseCase = LogOutUseCase(authRepository)
}
