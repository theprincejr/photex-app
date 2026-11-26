package com.example.photx.core.media

import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import com.example.photx.core.common.DispatcherProvider
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.withContext

/**
 * Safely probes whether a tracked `content://` URI still resolves to an accessible local original.
 */
public interface MediaUriExistenceProbe {
    public suspend fun checkExists(localUri: String): MediaUriStatus
}

internal class AndroidMediaUriExistenceProbe(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) : MediaUriExistenceProbe {
    override suspend fun checkExists(localUri: String): MediaUriStatus = withContext(dispatcherProvider.io) {
        val parsed = runCatching { Uri.parse(localUri) }.getOrNull()
        if (parsed == null || localUri.isBlank()) {
            return@withContext MediaUriStatus.ACCESS_RESTRICTED
        }

        probeContentResolver(parsed)
    }

    private fun probeContentResolver(uri: Uri): MediaUriStatus = try {
        val hasRow = context.contentResolver.query(
            uri,
            arrayOf(BaseColumns._ID),
            null,
            null,
            null
        )?.use { cursor -> cursor.moveToFirst() } ?: false

        if (!hasRow) {
            MediaUriStatus.CONFIRMED_MISSING
        } else {
            val opened = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            if (opened) MediaUriStatus.EXISTS else MediaUriStatus.CONFIRMED_MISSING
        }
    } catch (_: FileNotFoundException) {
        MediaUriStatus.CONFIRMED_MISSING
    } catch (_: SecurityException) {
        MediaUriStatus.ACCESS_RESTRICTED
    } catch (_: IllegalArgumentException) {
        MediaUriStatus.ACCESS_RESTRICTED
    } catch (_: IOException) {
        MediaUriStatus.ACCESS_RESTRICTED
    }
}
