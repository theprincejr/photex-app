package com.example.photx.core.telegram

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.Locale

internal interface TdlibConfigProvider {
    fun createParametersConfig(): TdlibParametersConfig
}

internal class AndroidTdlibConfigProvider(
    private val context: Context,
    private val apiId: Int = BuildConfig.TELEGRAM_API_ID,
    private val apiHash: String = BuildConfig.TELEGRAM_API_HASH
) : TdlibConfigProvider {
    override fun createParametersConfig(): TdlibParametersConfig {
        val tdlibRoot = File(context.filesDir, "tdlib")
        val databaseDir = File(tdlibRoot, "db").apply { mkdirs() }
        val filesDir = File(tdlibRoot, "files").apply { mkdirs() }

        return TdlibParametersConfig(
            apiId = apiId,
            apiHash = apiHash,
            databaseDirectory = databaseDir.absolutePath,
            filesDirectory = filesDir.absolutePath,
            systemLanguageCode = Locale.getDefault().toLanguageTag().ifBlank { DEFAULT_LANGUAGE_CODE },
            deviceModel = Build.MODEL.orEmpty().ifBlank { DEFAULT_DEVICE_MODEL },
            systemVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { DEFAULT_SYSTEM_VERSION },
            applicationVersion = resolveAppVersionName(context)
        )
    }

    private fun resolveAppVersionName(appContext: Context): String = try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        packageInfo.versionName?.takeIf { it.isNotBlank() } ?: DEFAULT_APP_VERSION
    } catch (_: PackageManager.NameNotFoundException) {
        DEFAULT_APP_VERSION
    }

    private companion object {
        const val DEFAULT_LANGUAGE_CODE = "en"
        const val DEFAULT_DEVICE_MODEL = "Android"
        const val DEFAULT_SYSTEM_VERSION = "Android"
        const val DEFAULT_APP_VERSION = "0.1.0"
    }
}
