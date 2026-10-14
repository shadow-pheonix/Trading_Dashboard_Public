package com.shadowpheonix.tradingdashboard

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

enum class Appearance(val label: String) {
    Light("Light"), Dark("Dark"), System("Use device setting");

    fun isDark(systemDark: Boolean): Boolean = this == Dark || (this == System && systemDark)

    companion object {
        fun fromSaved(value: String?): Appearance = entries.find { it.name == value } ?: Light
    }
}

// A separate, non-secret preference; changing appearance never rewrites dashboard keys.
interface AppearancePreferences {
    fun read(): String?
    fun write(value: String): Boolean
}

class AppearanceStore(private val preferences: AppearancePreferences) {
    constructor(context: Context) : this(object : AppearancePreferences {
        private val prefs = context.getSharedPreferences("dashboard_appearance", Context.MODE_PRIVATE)
        override fun read(): String? = prefs.getString("theme", null)
        override fun write(value: String): Boolean = prefs.edit().putString("theme", value).commit()
    })

    fun load(): Appearance = Appearance.fromSaved(preferences.read())
    fun save(value: Appearance) { check(preferences.write(value.name)) }
}

internal fun dashboardColors(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFF9DB8FF), onPrimary = Color(0xFF122C67),
    primaryContainer = Color(0xFF243E70), onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF6DD6AE), onSecondary = Color(0xFF003826),
    secondaryContainer = Color(0xFF154C3A), onSecondaryContainer = Color(0xFFA6F0D1),
    tertiary = Color(0xFFB5C8F0), onTertiary = Color(0xFF21324D),
    tertiaryContainer = Color(0xFF344966), onTertiaryContainer = Color(0xFFDCE8FF),
    error = Color(0xFFFFA7B7), onError = Color(0xFF650022),
    errorContainer = Color(0xFF6D2438), onErrorContainer = Color(0xFFFFDCE3),
    background = Color(0xFF0C1422), onBackground = Color(0xFFE5EDFA),
    surface = Color(0xFF152033), onSurface = Color(0xFFE5EDFA),
    surfaceVariant = Color(0xFF202E44), onSurfaceVariant = Color(0xFFABBCD2),
    outline = Color(0xFF7C8EA8), outlineVariant = Color(0xFF304058),
    inverseSurface = Color(0xFFE5EDFA), inverseOnSurface = Color(0xFF152033), inversePrimary = Color(0xFF315CDB),
    surfaceDim = Color(0xFF0C1422), surfaceBright = Color(0xFF304058), surfaceContainerLowest = Color(0xFF08101B),
    surfaceContainer = Color(0xFF19263B), surfaceContainerHigh = Color(0xFF203049),
    surfaceContainerHighest = Color(0xFF293B54), surfaceContainerLow = Color(0xFF111C2D),
) else lightColorScheme(
    primary = Color(0xFF315CDB), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE6FF), onPrimaryContainer = Color(0xFF142C65),
    secondary = Color(0xFF06764F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F6ED), onSecondaryContainer = Color(0xFF003D2A),
    tertiary = Color(0xFF40577D), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE8FF), onTertiaryContainer = Color(0xFF21324D),
    error = Color(0xFFBE3654), onError = Color.White,
    errorContainer = Color(0xFFFCE3EA), onErrorContainer = Color(0xFF660522),
    background = Color(0xFFF2F5FA), onBackground = Color(0xFF14233B),
    surface = Color.White, onSurface = Color(0xFF14233B),
    surfaceVariant = Color(0xFFEBF0F7), onSurfaceVariant = Color(0xFF5B697F),
    outline = Color(0xFF66748A), outlineVariant = Color(0xFFE4EAF2),
    inverseSurface = Color(0xFF263249), inverseOnSurface = Color(0xFFF3F6FA), inversePrimary = Color(0xFFADC2FF),
    surfaceDim = Color(0xFFE0E5ED), surfaceBright = Color.White, surfaceContainerLowest = Color.White,
    surfaceContainer = Color(0xFFF0F3FA), surfaceContainerHigh = Color(0xFFEBF0F7),
    surfaceContainerHighest = Color(0xFFE4EAF2), surfaceContainerLow = Color(0xFFF8FAFD),
)

@Composable
internal fun DashboardTheme(appearance: Appearance = Appearance.Light, content: @Composable () -> Unit) {
    val colors = dashboardColors(appearance.isDark(isSystemInDarkTheme()))
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}
