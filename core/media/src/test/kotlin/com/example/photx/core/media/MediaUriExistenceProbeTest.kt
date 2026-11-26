package com.example.photx.core.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import androidx.test.core.app.ApplicationProvider
import com.example.photx.core.common.DispatcherProvider
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaUriExistenceProbeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class ProbeProvider : ContentProvider() {
        var existingFile: File? = null
        var throwSecurity: Boolean = false
        var rowPresent: Boolean = true

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            if (throwSecurity) throw SecurityException("Revoked")
            return MatrixCursor(arrayOf(BaseColumns._ID)).apply {
                if (rowPresent) addRow(arrayOf<Any?>(1L))
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (throwSecurity) throw SecurityException("Revoked")
            val file = existingFile ?: throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
    }

    private fun testDispatchers(scheduler: TestCoroutineScheduler): DispatcherProvider {
        val dispatcher = StandardTestDispatcher(scheduler)
        return object : DispatcherProvider {
            override val main: CoroutineDispatcher = dispatcher
            override val mainImmediate: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
        }
    }

    @Test
    fun `given existing missing and revoked uris_when probing_then returns exact status`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = ProbeProvider().apply {
            attachInfo(context, ProviderInfo().apply { authority = "com.example.photx.probe" })
        }
        ShadowContentResolver.registerProviderInternal("com.example.photx.probe", provider)

        val probe = AndroidMediaUriExistenceProbe(context, testDispatchers(testScheduler))
        val sampleFile = tempFolder.newFile("sample.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        provider.existingFile = sampleFile
        assertEquals(MediaUriStatus.EXISTS, probe.checkExists("content://com.example.photx.probe/media/1"))

        // Row gone from MediaStore -> CONFIRMED_MISSING
        provider.rowPresent = false
        assertEquals(MediaUriStatus.CONFIRMED_MISSING, probe.checkExists("content://com.example.photx.probe/media/1"))

        // Row present in cursor, underlying file deleted -> FileNotFoundException -> CONFIRMED_MISSING
        provider.rowPresent = true
        provider.existingFile = null
        assertEquals(MediaUriStatus.CONFIRMED_MISSING, probe.checkExists("content://com.example.photx.probe/media/1"))

        // SecurityException (e.g., partial permission / revoked access) -> ACCESS_RESTRICTED
        provider.throwSecurity = true
        assertEquals(MediaUriStatus.ACCESS_RESTRICTED, probe.checkExists("content://com.example.photx.probe/media/1"))
        assertEquals(MediaUriStatus.ACCESS_RESTRICTED, probe.checkExists(""))
    }
}
