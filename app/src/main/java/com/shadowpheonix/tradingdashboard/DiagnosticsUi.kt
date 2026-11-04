package com.shadowpheonix.tradingdashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
    val export: () -> Unit = {
        busy = true; result = null
        val metrics = context.resources.displayMetrics
        val summary = mapOf(DiagnosticField.Version to BuildConfig.VERSION_CODE.toLong(),
            DiagnosticField.Sdk to Build.VERSION.SDK_INT.toLong(), DiagnosticField.Width to config.screenWidthDp.toLong(),
            DiagnosticField.Height to config.screenHeightDp.toLong(), DiagnosticField.Orientation to config.orientation.toLong(),
            DiagnosticField.Density to metrics.densityDpi.toLong(), DiagnosticField.FontScale to (config.fontScale * 100).toLong())
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) { saveDiagnosticDownloads(context.applicationContext, summary) }
                result = "Saved to Downloads/$name"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { result = "Could not save diagnostics to Downloads. Check available storage and try again." }
            finally { busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) export() else {
            busy = false
            result = "Storage permission is needed to save to Downloads on Android 8–9. Tap Export diagnostics to try again."
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Diagnostics", fontWeight = FontWeight.Bold)
        Text("Save recent app, connection and chart logs as a ZIP in Downloads. Keys and server addresses are excluded.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Last 24 hours · stored privately on this phone", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    busy = true; result = null
                    permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else export()
            }) { Text(if (busy) "Please wait…" else "Export diagnostics") }
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            AppDiagnostics.clear()
                            File(context.cacheDir, "diagnostics-exports/Trading-Dashboard-diagnostics.zip").delete()
                        }
                        result = "Private diagnostics cleared. Saved ZIPs remain in Downloads."
                    } catch (_: Exception) { result = "Could not clear diagnostics. Try again." }
                    finally { busy = false }
                }
            }) { Text("Clear") }
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
