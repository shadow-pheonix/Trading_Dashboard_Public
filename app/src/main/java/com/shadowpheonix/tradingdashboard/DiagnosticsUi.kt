package com.shadowpheonix.tradingdashboard

import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun DiagnosticsCard() {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Diagnostics", fontWeight = FontWeight.Bold)
        Text("Share recent app, connection and chart logs to help investigate issues. Keys and server addresses are excluded.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Last 24 hours · stored privately on this phone", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true; result = null
                val metrics = context.resources.displayMetrics
                val summary = mapOf(DiagnosticField.Version to BuildConfig.VERSION_CODE.toLong(),
                    DiagnosticField.Sdk to Build.VERSION.SDK_INT.toLong(), DiagnosticField.Width to config.screenWidthDp.toLong(),
                    DiagnosticField.Height to config.screenHeightDp.toLong(), DiagnosticField.Orientation to config.orientation.toLong(),
                    DiagnosticField.Density to metrics.densityDpi.toLong(), DiagnosticField.FontScale to (config.fontScale * 100).toLong())
                scope.launch {
                    try {
                        val file = File(context.cacheDir, "diagnostics-exports/Trading-Dashboard-diagnostics.zip")
                        withContext(Dispatchers.IO) { AppDiagnostics.export(file, summary) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("Diagnostics", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                        result = "Diagnostics ready to share."
                    } catch (_: Exception) { result = "Could not export diagnostics. Try again." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Please wait…" else "Export diagnostics") }
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            AppDiagnostics.clear()
                            File(context.cacheDir, "diagnostics-exports/Trading-Dashboard-diagnostics.zip").delete()
                        }
                        result = "Diagnostics cleared."
                    } catch (_: Exception) { result = "Could not clear diagnostics. Try again." }
                    finally { busy = false }
                }
            }) { Text("Clear") }
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
