package com.example.photx.core.telegram.di

import android.content.Context
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.domain.auth.AuthRepository
import com.example.photx.core.telegram.AndroidTdlibConfigProvider
import com.example.photx.core.telegram.AndroidTdlibGateway
import com.example.photx.core.telegram.DefaultAuthRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency bindings exposing `:core:telegram`'s TDLib-backed [AuthRepository]
 * without leaking TDLib types across module boundaries.
 */
@Module
@InstallIn(SingletonComponent::class)
public object TelegramModule {
    @Provides
    @Singleton
    public fun provideAuthRepository(
        @ApplicationContext context: Context,
        dispatcherProvider: DispatcherProvider
    ): AuthRepository = DefaultAuthRepository(
        gateway = AndroidTdlibGateway(),
        configProvider = AndroidTdlibConfigProvider(context),
        dispatcherProvider = dispatcherProvider
    )
}
