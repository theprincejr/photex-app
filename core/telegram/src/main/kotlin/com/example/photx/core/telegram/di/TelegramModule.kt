package com.example.photx.core.telegram.di

import android.content.Context
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.domain.auth.AuthRepository
import com.example.photx.core.domain.backup.TelegramBackupGateway
import com.example.photx.core.telegram.AndroidTdlibConfigProvider
import com.example.photx.core.telegram.AndroidTdlibGateway
import com.example.photx.core.telegram.DefaultAuthRepository
import com.example.photx.core.telegram.TdlibBackupGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency bindings exposing `:core:telegram`'s TDLib-backed [AuthRepository]
 * and [TelegramBackupGateway] backed by the same singleton TDLib client session.
 */
@Module
@InstallIn(SingletonComponent::class)
public object TelegramModule {
    @Provides
    @Singleton
    internal fun provideAndroidTdlibGateway(): AndroidTdlibGateway = AndroidTdlibGateway()

    @Provides
    @Singleton
    internal fun provideAuthRepository(
        @ApplicationContext context: Context,
        tdlibGateway: AndroidTdlibGateway,
        dispatcherProvider: DispatcherProvider
    ): AuthRepository = DefaultAuthRepository(
        gateway = tdlibGateway,
        configProvider = AndroidTdlibConfigProvider(context),
        dispatcherProvider = dispatcherProvider
    )

    @Provides
    @Singleton
    internal fun provideTelegramBackupGateway(
        tdlibGateway: AndroidTdlibGateway,
        dispatcherProvider: DispatcherProvider
    ): TelegramBackupGateway = TdlibBackupGateway(
        transport = tdlibGateway,
        dispatcherProvider = dispatcherProvider
    )
}
