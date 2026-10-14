package com.shadowpheonix.tradingdashboard

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class AppLockState(
    val unlocked: Boolean = false,
    val authenticating: Boolean = false,
    val message: String? = null,
)

enum class DeviceUnlockMethod { Combined, Biometric, Credential, Setup }

// Strong biometric + credential is supported together only from Android 11.
fun deviceUnlockMethod(api: Int, secure: Boolean, strongBiometricAvailable: Boolean): DeviceUnlockMethod = when {
    !secure -> DeviceUnlockMethod.Setup
    api >= 30 -> DeviceUnlockMethod.Combined
    strongBiometricAvailable -> DeviceUnlockMethod.Biometric
    else -> DeviceUnlockMethod.Credential
}

class AppUnlockSession {
    private val mutable = MutableStateFlow(AppLockState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var pending: Long? = null
    private var task: Int? = null
    private var enabled = true

    fun configure(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            generation++
            pending = null
            mutable.value = AppLockState(unlocked = true)
        }
        // Enabling inside an unlocked app takes effect on its next close/open.
    }

    fun opened(taskId: Int) {
        // Also catches task removal when Android skips the old activity's onDestroy.
        if (task != null && task != taskId) close()
        task = taskId
    }

    fun begin(): Long? {
        if (state.value.unlocked || state.value.authenticating) return null
        pending = ++generation
        mutable.value = AppLockState(authenticating = true)
        return pending
    }

    fun succeeded(attempt: Long) {
        if (pending != attempt) return
        pending = null
        mutable.value = AppLockState(unlocked = true)
    }

    fun failed(attempt: Long, message: String) {
        if (pending != attempt) return
        pending = null
        mutable.value = AppLockState(message = message)
    }

    fun rejected(attempt: Long) {
        if (pending == attempt) mutable.value = AppLockState(
            authenticating = true, message = "Fingerprint not recognized. Try again.",
        )
    }

    fun explain(message: String) {
        if (!state.value.unlocked && !state.value.authenticating) mutable.value = AppLockState(message = message)
    }

    fun close() {
        generation++
        pending = null
        mutable.value = AppLockState(unlocked = !enabled)
    }

    fun activityDestroyed(finishing: Boolean, changingConfiguration: Boolean) {
        if (changingConfiguration) return
        if (finishing) close() else pending?.let { failed(it, "Unlock to continue.") }
    }

    suspend fun whileUnlocked(action: suspend () -> Unit) {
        state.map { it.unlocked }.distinctUntilChanged().collectLatest { if (it) action() }
    }
}

// Memory only: backgrounding preserves unlock; new processes apply the saved lock preference.
internal object ProcessAppUnlock { val session = AppUnlockSession() }

class AppLockViewModel : ViewModel() {
    val session = ProcessAppUnlock.session
    var autoPrompt = true
    var biometricAttempt: Long? = null
    var credentialAttempt: Long? = null
}
