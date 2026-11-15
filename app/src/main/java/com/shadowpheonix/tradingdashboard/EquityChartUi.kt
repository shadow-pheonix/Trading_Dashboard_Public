package com.shadowpheonix.tradingdashboard

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal fun EquityChartScreen(snapshot: DashboardSnapshot, view: ChartView, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val activity = LocalActivity.current
    val previousOrientation = rememberSaveable { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    LaunchedEffect(activity) { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
    DisposableEffect(activity) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (activity?.isChangingConfigurations == false) activity.requestedOrientation = previousOrientation
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.displayCutout)) {
        CurveCard(snapshot, fullscreen = true, initialView = view, onClose = onBack)
    }
}
