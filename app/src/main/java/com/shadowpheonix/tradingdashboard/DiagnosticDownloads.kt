package com.shadowpheonix.tradingdashboard

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

// Runs on Dispatchers.IO. The sanitized ZIP is created privately before it is
// copied to shared Downloads; a failed export never publishes a partial file.
internal fun saveDiagnosticDownloads(context: Context, summary: Map<DiagnosticField, Long>): String {
    val name = diagnosticDownloadName()
    val temporary = File.createTempFile("diagnostics-", ".zip", context.cacheDir)
    try {
        AppDiagnostics.export(temporary, summary)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }))
            copyDiagnosticDownload(temporary, object : DiagnosticDownloadDestination {
                override fun open() = checkNotNull(resolver.openOutputStream(uri, "w"))
                override fun publish() {
                    check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
                }
                override fun discard() { resolver.delete(uri, null, null) }
            })
            // MediaStore may disambiguate names after a clock rollback or two
            // exports in the same millisecond. Show the actual saved name.
            return runCatching {
                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: name
        }
        @Suppress("DEPRECATION")
        val file = reserveDiagnosticDownload(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
        copyDiagnosticDownload(temporary, object : DiagnosticDownloadDestination {
            override fun open() = file.outputStream()
            override fun publish() { MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("application/zip"), null) }
            override fun discard() { check(!file.exists() || file.delete()) }
        })
        return file.name
    } finally { temporary.delete() }
}
