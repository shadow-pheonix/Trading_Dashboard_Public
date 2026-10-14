package com.shadowpheonix.tradingdashboard

import android.Manifest
import android.content.pm.PackageManager
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : FragmentActivity() {
    private val dashboard: DashboardViewModel by viewModels()
    private lateinit var alertStore: AlertStore
    private var alertsEnabled by mutableStateOf(true)
    private var alertHistory by mutableStateOf<Map<String, List<DashboardAlert>>>(emptyMap())
    private var alertStatus by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private val alertPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && alertsEnabled) startAlerts() else {
            alertsEnabled = false
            alertStore.enabled(false)
            AlertService.stop(this)
        }
    }
    private val lock: AppLockViewModel by viewModels()
    private val updates: AppUpdateViewModel by viewModels()
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) installUpdate() else updates.permissionDenied()
    }
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var lockStore: AppLockStore
    private var lockEnabled by mutableStateOf(true)
    private var lockError by mutableStateOf<String?>(null)
    private val keyguard get() = getSystemService(KeyguardManager::class.java)
    private val credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val attempt = lock.credentialAttempt
        lock.credentialAttempt = null
        if (attempt != null) {
            if (result.resultCode == Activity.RESULT_OK) lock.session.succeeded(attempt)
            else lock.session.failed(attempt, "Unlock cancelled. Tap Unlock to try again.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alertStore = AlertStore(this)
        alertsEnabled = alertStore.enabled()
        handleAlertIntent(intent)
        lockStore = AppLockStore(this)
        lockEnabled = try { lockStore.load() } catch (_: Exception) { true }
        lock.session.configure(lockEnabled)
        lock.session.opened(taskId)
        updates.opened(taskId)
        enableEdgeToEdge()
        // Reattach to the existing prompt after rotation without starting another request.
        biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val attempt = lock.biometricAttempt ?: return
                lock.biometricAttempt = null
                lock.session.succeeded(attempt)
            }

            override fun onAuthenticationFailed() {
                lock.biometricAttempt?.let { lock.session.rejected(it) }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val attempt = lock.biometricAttempt ?: return
                lock.biometricAttempt = null
                if (Build.VERSION.SDK_INT < 30 && errorCode in setOf(
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT, BiometricPrompt.ERROR_HW_UNAVAILABLE,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_NO_BIOMETRICS,
                    )) confirmCredential(attempt)
                else lock.session.failed(attempt, "Unlock cancelled or unavailable. Tap Unlock to try again.")
            }
        })
        onBackPressedDispatcher.addCallback(this) {
            lock.session.close()
            lock.biometricAttempt = null
            lock.credentialAttempt = null
            biometricPrompt.cancelAuthentication()
            finish()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                lock.session.whileUnlocked {
                    coroutineScope {
                        launch { dashboard.streamWhileVisible() }
                        launch { updates.monitor() }
                        launch {
                            dashboard.state.map { it.connections }.distinctUntilChanged().collect { connections ->
                                if (connections.isNotEmpty() && alertsEnabled) {
                                    if (!notificationPermissionGranted() && !alertStore.prompted()) {
                                        alertStore.prompted(true)
                                        if (Build.VERSION.SDK_INT >= 33) alertPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else if (notificationPermissionGranted()) startAlerts()
                                } else if (connections.isEmpty()) AlertService.stop(this@MainActivity)
                            }
                        }
                        launch {
                            while (true) {
                                val connections = dashboard.state.value.connections
                                alertHistory = withContext(Dispatchers.IO) {
                                    connections.associate { it.id to try { alertStore.load(it.id).history } catch (_: Exception) { emptyList() } }
                                }
                                alertStatus = connections.map { it.label to if (!notificationPermissionGranted()) "Notifications disabled in Android Settings" else alertStore.status(it.id) }
                                delay(3000)
                            }
                        }
                    }
                }
            }
        }
        val appearance = try { AppearanceStore(this).load() } catch (_: Exception) { Appearance.System }
        setContent {
            val state by lock.session.state.collectAsStateWithLifecycle()
            val updateState by updates.state.collectAsStateWithLifecycle()
            if (state.unlocked) DashboardApp(dashboard, lockEnabled, lockError, ::changeAppLock,
                updateState, updates::check, ::installUpdate, updates::later,
                alertsEnabled, ::changeAlerts, alertStatus, alertHistory)
            else AppLockedScreen(state, appearance, ::authenticate, ::configureScreenLock)
        }
    }

    private fun notificationPermissionGranted() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun startAlerts() {
        try { AlertService.start(this) } catch (_: Exception) {
            alertStatus = listOf("Alerts" to "Could not start background receiver · reopen the app")
        }
    }
    private fun changeAlerts(enabled: Boolean) {
        alertsEnabled = enabled
        alertStore.enabled(enabled)
        if (!enabled) AlertService.stop(this)
        else if (notificationPermissionGranted()) startAlerts()
        else if (Build.VERSION.SDK_INT >= 33) {
            alertStore.prompted(true)
            alertPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlertIntent(intent)
    }
    private fun handleAlertIntent(value: Intent?) {
        if (value?.hasExtra("alertEvent") == true) {
            dashboard.alertInbox.open(AlertTarget(value.getStringExtra("alertConnection"), value.getStringExtra("alertEvent")))
        }
    }

    private fun installUpdate() {
        updates.requestInstall()
        if (!packageManager.canRequestPackageInstalls()) {
            try { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { updates.permissionDenied() }
            return
        }
        lifecycleScope.launch {
            try {
                val file = updates.installFile()
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.updates", file)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) { updates.installError() }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (lock.autoPrompt) {
            lock.autoPrompt = false
            authenticate()
        }
    }

    private fun authenticate() {
        if (!lockEnabled) return
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return
        val method = deviceUnlockMethod(
            Build.VERSION.SDK_INT, keyguard.isDeviceSecure,
            BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS,
        )
        if (method == DeviceUnlockMethod.Setup) {
            lock.session.explain("Set up your phone's screen lock to unlock Trading Dashboard.")
            return
        }
        val attempt = lock.session.begin() ?: return
        if (method == DeviceUnlockMethod.Credential) {
            confirmCredential(attempt)
            return
        }
        lock.biometricAttempt = attempt
        try {
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Trading Dashboard")
                .setSubtitle("Use your fingerprint or phone screen lock")
                .setAllowedAuthenticators(if (method == DeviceUnlockMethod.Combined) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG)
            if (method == DeviceUnlockMethod.Biometric) builder.setNegativeButtonText("Use screen lock")
            biometricPrompt.authenticate(builder.build())
        } catch (_: Exception) {
            lock.biometricAttempt = null
            lock.session.failed(attempt, "Android authentication is unavailable. Tap Unlock to retry.")
        }
    }

    private fun changeAppLock(enabled: Boolean) {
        if (enabled && !keyguard.isDeviceSecure) {
            lockError = "Set up a screen lock in Android Settings before enabling fingerprint unlock."
            return
        }
        try {
            lockStore.save(enabled)
            lockEnabled = enabled
            lock.session.configure(enabled)
            lockError = null
        } catch (_: Exception) {
            lockError = "Fingerprint unlock setting could not be saved. Try again."
        }
    }

    @Suppress("DEPRECATION") // Android 8–10: the combined authenticator is unsupported.
    private fun confirmCredential(attempt: Long) {
        try {
            val intent = keyguard.createConfirmDeviceCredentialIntent(
                "Unlock Trading Dashboard", "Confirm your phone's PIN, pattern or password",
            ) ?: throw IllegalStateException("No secure screen lock")
            lock.credentialAttempt = attempt
            credentialLauncher.launch(intent)
        } catch (_: Exception) {
            lock.credentialAttempt = null
            lock.session.failed(attempt, "Screen lock authentication is unavailable. Set up your phone's screen lock.")
        }
    }

    private fun configureScreenLock() {
        try {
            lock.autoPrompt = true
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } catch (_: Exception) {
            lock.autoPrompt = false
            lock.session.explain("Open your phone's Settings and enable a secure screen lock.")
        }
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) updates.closed()
        lock.session.activityDestroyed(isFinishing, isChangingConfigurations)
        if (!isChangingConfigurations) {
            lock.biometricAttempt = null
            lock.credentialAttempt = null
            if (::biometricPrompt.isInitialized) biometricPrompt.cancelAuthentication()
        }
        super.onDestroy()
    }
}
