package com.smnexstudio.panelglass.feature.studio.translate

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.engine.local.LocalModelUse
import com.smnexstudio.panelglass.core.model.EngineId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Why a Studio run is waiting between pages. */
enum class PauseReason {
    /** The user paused it (the notification or the chapter screen). */
    USER,
    /** The phone reports a severe thermal state. */
    HOT,
    /** An on-device run with Settings' "only while charging" on, and no charger. */
    CHARGER,
    /** Battery Saver is on and the phone is not charging. */
    BATTERY_SAVER,
    /** The battery is below [RunRules.LOW_BATTERY] % and the phone is not charging. */
    BATTERY_LOW,
    /** A cloud engine with no network. */
    OFFLINE,
    /** The reader is translating the screen with the on-device model the run needs. */
    READER,
}

/** What the phone is like right now, as far as a run cares. */
data class DeviceState(
    val severeThermal: Boolean = false,
    val batterySaver: Boolean = false,
    /** 0–100. */
    val batteryPercent: Int = 100,
    val charging: Boolean = false,
    val online: Boolean = true,
)

/**
 * When a Studio run waits. It runs for as long as the user lets it, so it gives way to the phone: to heat, to a low
 * battery, to the reader. Pure: the conditions come in, the first reason that applies (in [PauseReason]'s order) goes
 * out, or null to carry on.
 */
object RunRules {
    const val LOW_BATTERY = 15

    fun reason(
        device: DeviceState, engine: EngineId, userPaused: Boolean, onlyWhileCharging: Boolean, readerUsesModel: Boolean,
    ): PauseReason? = when {
        userPaused -> PauseReason.USER
        device.severeThermal -> PauseReason.HOT
        engine.isLocalLlm && onlyWhileCharging && !device.charging -> PauseReason.CHARGER
        device.batterySaver && !device.charging -> PauseReason.BATTERY_SAVER
        device.batteryPercent < LOW_BATTERY && !device.charging -> PauseReason.BATTERY_LOW
        // Gemini and the other cloud engines need the network; the on-device ones (ML Kit too) do not.
        engine.needsKey && !device.online -> PauseReason.OFFLINE
        engine.isLocalLlm && readerUsesModel -> PauseReason.READER
        else -> null
    }
}

/**
 * Where a run waits between pages. [await] returns at once when nothing stops the run; otherwise it reports the reason
 * through `paused`, waits until none is left, and reports null.
 */
interface RunGate {
    suspend fun await(engine: EngineId, paused: suspend (PauseReason?) -> Unit)

    /** The user's pause (the notification's Pause, the chapter screen's), effective at the next page. */
    val userPaused: StateFlow<Boolean>
    fun setUserPaused(paused: Boolean)

    companion object {
        /** Never waits: tests, and the translator before Hilt has bound the real gate. */
        val OPEN = object : RunGate {
            override val userPaused = MutableStateFlow(false)
            override suspend fun await(engine: EngineId, paused: suspend (PauseReason?) -> Unit) = Unit
            override fun setUserPaused(paused: Boolean) = Unit
        }
    }
}

/**
 * The real gate: the user's pause, Settings' "only while charging", the reader's use of the on-device model, and the
 * phone's heat, battery and network. The phone is read when a page is about to start and, while waiting, every
 * [pollMs] (no listener stays registered between runs); the user's pause and the reader wake the wait at once.
 */
@Singleton
class StudioRunGate @Inject constructor(
    private val device: DeviceProbe,
    private val settings: SettingsRepository,
    private val localModelUse: LocalModelUse,
) : RunGate {
    private val _userPaused = MutableStateFlow(false)
    override val userPaused: StateFlow<Boolean> = _userPaused.asStateFlow()

    /** How often a waiting run looks at the phone again; tests shorten it. */
    internal var pollMs = POLL_MS

    override fun setUserPaused(paused: Boolean) { _userPaused.value = paused }

    private suspend fun reason(engine: EngineId): PauseReason? = RunRules.reason(
        device.now(), engine, _userPaused.value, settings.current().studioOnlyWhileCharging, localModelUse.reader.value,
    )

    override suspend fun await(engine: EngineId, paused: suspend (PauseReason?) -> Unit) {
        var why = reason(engine) ?: return
        while (true) {
            paused(why)
            // Woken by the user or the reader at once; heat, battery and network are looked at again after a while.
            withTimeoutOrNull(pollMs) { merge(_userPaused.drop(1), localModelUse.reader.drop(1)).first() }
            why = reason(engine) ?: break
        }
        paused(null)
    }

    companion object {
        const val POLL_MS = 10_000L
    }
}

/** The phone's state, read when asked. A seam: tests and the JVM have no thermal service or battery. */
interface DeviceProbe {
    fun now(): DeviceState
}

/** [DeviceProbe] from the system services: thermal status, the battery's sticky broadcast, the active network. */
class AndroidDeviceProbe @Inject constructor(@ApplicationContext private val context: Context) : DeviceProbe {
    override fun now(): DeviceState {
        val power = context.getSystemService(PowerManager::class.java)
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return DeviceState(
            severeThermal = (power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >= PowerManager.THERMAL_STATUS_SEVERE,
            batterySaver = power?.isPowerSaveMode == true,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else 100,
            charging = plugged,
            online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }
}
