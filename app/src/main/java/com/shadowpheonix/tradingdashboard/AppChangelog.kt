package com.shadowpheonix.tradingdashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class ChangelogCategory(val title: String) {
    NewFeatures("New features"), Improvements("Improvements"), BugFixes("Bug fixes"),
}

internal data class ChangelogChange(val category: ChangelogCategory, val description: String)

// Replace this list with the changes included in each release. The installed
// package supplies the version; release workflow version numbers are dynamic.
internal val appChangelog = listOf(
    ChangelogChange(ChangelogCategory.Improvements,
        "P&L milestones say Rose above or Dropped below the signed level, with matching direction icons in notifications and alert history."),
    ChangelogChange(ChangelogCategory.BugFixes,
        "Reconnecting or recovering unavailable P&L starts quietly. Old pending P&L milestones no longer replay as fresh crossings; the 15-minute per-level cooldown remains."),
    ChangelogChange(ChangelogCategory.BugFixes,
        "Export diagnostics saves a timestamped ZIP directly to Downloads and shows its filename. Incomplete exports are removed; the Android share sheet is no longer used."),
)

@Composable
internal fun AppChangelogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.NewReleases, contentDescription = null) },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What’s new")
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ChangelogCategory.entries.forEach { category ->
                    val changes = appChangelog.filter { it.category == category }
                    if (changes.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(category.title, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                            changes.forEach { change ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("•", style = MaterialTheme.typography.bodyMedium)
                                    Text(change.description, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}
