package com.smnexstudio.panelglass.feature.studio.translate

import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.engine.local.LocalModelUse
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.feature.studio.MemoryDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When a Studio run gives way: the pure rules (heat, battery, charger, network, the reader, the user, in that order)
 * and the gate that applies them between pages, waking at once for the user and the reader and looking at the phone
 * again after a while for the rest.
 */
class RunGateTest {
    private val gemini = EngineId.GEMINI
    private val qwen = EngineId.QWEN15_LOCAL
    private val google = EngineId.GOOGLE

    private fun reason(
        d: DeviceState = DeviceState(), engine: EngineId = gemini, user: Boolean = false, charging: Boolean = false, reader: Boolean = false,
    ) = RunRules.reason(d, engine, user, charging, reader)

    @Test fun aHealthyPhoneNeverWaits() {
        for (e in listOf(gemini, qwen, google)) assertNull(reason(engine = e))
    }

    @Test fun theUserFirstThenHeat() {
        assertEquals(PauseReason.USER, reason(DeviceState(severeThermal = true, online = false), user = true))
        assertEquals(PauseReason.HOT, reason(DeviceState(severeThermal = true, batterySaver = true)))
    }

    /** Battery Saver and a low battery pause a run, unless the phone is charging. */
    @Test fun aLowOrSavingBatteryWaitsUnlessCharging() {
        assertEquals(PauseReason.BATTERY_SAVER, reason(DeviceState(batterySaver = true)))
        assertEquals(PauseReason.BATTERY_LOW, reason(DeviceState(batteryPercent = RunRules.LOW_BATTERY - 1)))
        assertNull(reason(DeviceState(batteryPercent = RunRules.LOW_BATTERY)), "at the threshold it runs")
        assertNull(reason(DeviceState(batterySaver = true, batteryPercent = 5, charging = true)))
    }

    /** "Only while charging" is for the on-device models, and off by default. */
    @Test fun onlyWhileChargingHoldsOnDeviceRunsOnly() {
        assertEquals(PauseReason.CHARGER, reason(engine = qwen, charging = true))
        assertEquals(PauseReason.CHARGER, reason(engine = EngineId.GEMMA4_LOCAL, charging = true))
        assertNull(reason(engine = gemini, charging = true), "a cloud engine does not wait for the charger")
        assertNull(reason(engine = google, charging = true), "nor does Google Translate")
        assertNull(reason(DeviceState(charging = true), engine = qwen, charging = true))
        assertNull(reason(engine = qwen), "off: an on-device run goes on without a charger")
    }

    /** Only an engine that needs the network waits for it. */
    @Test fun offlineHoldsCloudEnginesOnly() {
        assertEquals(PauseReason.OFFLINE, reason(DeviceState(online = false), engine = gemini))
        assertNull(reason(DeviceState(online = false), engine = qwen))
        assertNull(reason(DeviceState(online = false), engine = google), "ML Kit translates on the device")
    }

    /** The reader using the on-device model holds an on-device run; a cloud run does not share the model. */
    @Test fun theReaderHoldsOnDeviceRunsOnly() {
        assertEquals(PauseReason.READER, reason(engine = qwen, reader = true))
        assertNull(reason(engine = gemini, reader = true))
    }

    // ---- the gate -----------------------------------------------------------------------------------------------

    private var device = DeviceState()
    private val use = LocalModelUse()
    private val gate = StudioRunGate(object : DeviceProbe { override fun now() = device }, SettingsRepository(MemoryDataStore()), use)
        .apply { pollMs = 30 }

    @Test fun anOpenGateReturnsAtOnceWithoutReporting() = runBlocking {
        val seen = mutableListOf<PauseReason?>()
        gate.await(gemini) { seen += it }
        assertTrue(seen.isEmpty())
    }

    /** The user's pause holds the run until Resume, reported going in and out. */
    @Test fun theUsersPauseHoldsUntilResume() = runBlocking {
        val seen = mutableListOf<PauseReason?>()
        gate.setUserPaused(true)
        val waiting = async(Dispatchers.Default) { gate.await(gemini) { synchronized(seen) { seen += it } } }
        withTimeout(5_000) { while (synchronized(seen) { seen.isEmpty() }) delay(10) }
        assertEquals(listOf(PauseReason.USER), synchronized(seen) { seen.toList() })
        gate.setUserPaused(false)
        withTimeout(5_000) { waiting.await() }
        assertEquals(listOf(PauseReason.USER, null), seen)
    }

    /** A hot phone is looked at again until it cools; the reason changes are reported as they come. */
    @Test fun heatIsWaitedOutAndReasonsFollowThePhone() = runBlocking {
        val seen = mutableListOf<PauseReason?>()
        device = DeviceState(severeThermal = true)
        val waiting = async(Dispatchers.Default) { gate.await(gemini) { synchronized(seen) { seen += it } } }
        withTimeout(5_000) { while (synchronized(seen) { seen.isEmpty() }) delay(10) }
        device = DeviceState(batterySaver = true)
        withTimeout(5_000) { while (synchronized(seen) { PauseReason.BATTERY_SAVER !in seen }) delay(10) }
        device = DeviceState()
        withTimeout(5_000) { waiting.await() }
        assertEquals(PauseReason.HOT, seen.first())
        assertTrue(PauseReason.BATTERY_SAVER in seen)
        assertNull(seen.last())
    }

    @Test fun theReaderLeavingWakesAnOnDeviceRun() = runBlocking {
        use.reader.value = true
        gate.pollMs = 60_000 // only the reader's change can end this wait in time
        val waiting = async(Dispatchers.Default) { gate.await(qwen) {} }
        delay(100)
        use.reader.value = false
        withTimeout(5_000) { waiting.await() }
    }
}
