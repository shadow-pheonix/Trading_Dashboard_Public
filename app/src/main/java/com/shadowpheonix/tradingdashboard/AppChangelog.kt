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
    ChangelogChange(ChangelogCategory.BugFixes,
        "Pinching changes candle spacing without stretching unlocked price or MACD scales, including at the candle zoom limit. Drag inside a manual pane to move it; drag its right axis to change vertical scale."),
    ChangelogChange(ChangelogCategory.Improvements,
        "Drag full-screen charts beyond the latest candle into empty future time. The time axis continues without invented candles or MACD; Follow latest returns to the live view."),
    ChangelogChange(ChangelogCategory.Improvements,
        "Full-screen charts leave breathing room between the latest candle and the right price axis in portrait and landscape. Candles, MACD, entry arrows and touch inspection stay aligned."),
    ChangelogChange(ChangelogCategory.Improvements,
        "Stop prices appear as red numbers on the right price axis, averaging Wallet A+B within each model. Labels no longer cover the stop lines; single-wallet charts show their actual stop price."),
    ChangelogChange(ChangelogCategory.Improvements,
        "All full-screen BTC and model charts can zoom out to 256 candles in portrait and landscape. Pinch, time-axis drag and zoom buttons share the 12–256 range."),
    ChangelogChange(ChangelogCategory.NewFeatures,
        "Current drawdown replaces Average trade in Analytics."),
    ChangelogChange(ChangelogCategory.Improvements,
        "Both drawdown cards always use lifetime real closed trades for the selected models, combining Wallet A+B. Each shows its percentage first and the actual closed-trade dollar loss from that same interval below."),
    ChangelogChange(ChangelogCategory.BugFixes,
        "Legacy real trades with missing leverage can use recorded entry capital for drawdown calculations. Missing history or capital remains unavailable."),
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
