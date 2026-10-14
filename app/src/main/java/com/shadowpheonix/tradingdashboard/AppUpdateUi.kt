package com.shadowpheonix.tradingdashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppUpdateCard(state: AppUpdateState, onCheck: () -> Unit, onInstall: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("App updates", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Installed · ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            state.downloading -> {
                Text("Downloading ${state.release?.versionName} · ${state.progress ?: 0}%")
                LinearProgressIndicator(progress = { (state.progress ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Download continues in the background.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.ready -> Button(onClick = onInstall) { Text("Install ${state.release?.versionName}") }
            state.checking -> Text("Checking for updates…")
            else -> Text("New versions download automatically when you open the app.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = onCheck, enabled = !state.checking && !state.downloading) { Text("Check for updates") }
    }
}

@Composable
fun AppUpdatePrompt(state: AppUpdateState, onInstall: () -> Unit, onLater: () -> Unit) {
    if (state.ready && state.prompt) AlertDialog(onDismissRequest = onLater,
        title = { Text("Update ready") },
        text = { Text("Trading Dashboard ${state.release?.versionName} has downloaded. Install it now, or get a reminder next time you open the app.") },
        confirmButton = { TextButton(onClick = onInstall) { Text("Install now") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } })
}
