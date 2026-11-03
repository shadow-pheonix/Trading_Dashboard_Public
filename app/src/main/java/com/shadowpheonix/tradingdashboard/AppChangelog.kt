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
    ChangelogChange(ChangelogCategory.NewFeatures,
        "Open the main BTC chart full screen with all three models’ labelled entry arrows and stop-loss lines. Toggle all overlays together; the price chart uses the entire plot without MACD."),
    ChangelogChange(ChangelogCategory.NewFeatures,
        "Receive alerts when combined open P&L crosses positive or negative $1,000 levels within each instance, including re-crossings. Current P&L appears in the alert; instance notification controls still apply."),
    ChangelogChange(ChangelogCategory.NewFeatures,
        "Drag the right-hand price or MACD axis to set a manual vertical scale, then pan and pinch freely. Double-tap an axis or tap Auto to restore automatic scaling."),
    ChangelogChange(ChangelogCategory.Improvements,
        "Automatic price scaling now fits the visible candles without zooming out for distant stop-loss lines. Drag the time axis to zoom, or use Follow latest to resume live scrolling."),
    ChangelogChange(ChangelogCategory.BugFixes,
        "Refreshing keeps the current chart visible while fetching new data. Stable time slots and axis widths prevent extra jumps when live samples or trade markers become unavailable."),
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
