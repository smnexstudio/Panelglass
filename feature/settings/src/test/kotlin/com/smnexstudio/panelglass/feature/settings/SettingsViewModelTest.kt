package com.smnexstudio.panelglass.feature.settings

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import com.smnexstudio.panelglass.core.model.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Settings' engine choice: explicit, never switched behind the user's back (CLAUDE.md › Engines). A keyed engine without
 * a key opens its sheet and leaves the selection alone; saving the key there completes it. The HTTP client fails every
 * request, so nothing here can reach a provider. Robolectric (JUnit 4 via vintage); the main dispatcher is unconfined.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var rig: SettingsRig
    private val settings get() = rig.settings
    private val registry get() = rig.registry
    private val attempted get() = rig.attempted

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        rig = SettingsRig(context)
    }

    @After fun tearDown() {
        rig.close()
        Dispatchers.resetMain()
    }

    private fun vm() = rig.vm()

    private suspend fun engineBecomes(id: EngineId) = withTimeout(5_000) { settings.settings.first { it.engineId == id } }

    @Test fun aKeyedEngineWithoutAKeyOpensItsSheetAndKeepsTheSelection() = runBlocking<Unit> {
        val vm = vm()
        vm.selectEngine(EngineId.GEMINI).join()
        assertEquals(EngineId.GEMINI, vm.needsKey.value)
        assertEquals(EngineId.GOOGLE, settings.current().engineId)
    }

    @Test fun savingTheKeyFromThatSheetCompletesTheSelection() = runBlocking<Unit> {
        val vm = vm()
        vm.selectEngine(EngineId.GEMINI).join()
        vm.saveEngineConfig(EngineId.GEMINI, "invented-key", "gemini-flash-latest")
        engineBecomes(EngineId.GEMINI)
        assertTrue(registry.hasKey(EngineId.GEMINI))
        assertEquals("gemini-flash-latest", settings.current().modelFor(EngineId.GEMINI))
        assertNull(vm.needsKey.value)
    }

    /** Closing the sheet first (or only changing the model) is not a choice of engine. */
    @Test fun aSheetClosedWithoutAKeySelectsNothing() = runBlocking<Unit> {
        val vm = vm()
        vm.selectEngine(EngineId.GEMINI).join()
        vm.clearNeedsKey()
        vm.saveEngineConfig(EngineId.GEMINI, null, "gemini-flash-latest")
        withTimeout(5_000) { settings.settings.first { it.models[EngineId.GEMINI] == "gemini-flash-latest" } }
        assertEquals(EngineId.GOOGLE, settings.current().engineId)
    }

    @Test fun anEngineThatNeedsNoKeyIsSelectedAtOnce() = runBlocking<Unit> {
        val vm = vm()
        vm.selectEngine(EngineId.QWEN15_LOCAL).join()
        assertEquals(EngineId.QWEN15_LOCAL, settings.current().engineId)
        assertNull(vm.needsKey.value)
    }

    /** The selection is left alone; it resolves to MissingKey until a key is saved again. */
    @Test fun removingAKeyKeepsTheSelection() = runBlocking<Unit> {
        val vm = vm()
        registry.setKey(EngineId.GEMINI, "invented-key")
        vm.selectEngine(EngineId.GEMINI).join()
        engineBecomes(EngineId.GEMINI)
        vm.removeKey(EngineId.GEMINI)
        withTimeout(5_000) { while (registry.hasKey(EngineId.GEMINI)) kotlinx.coroutines.yield() }
        assertEquals(EngineId.GEMINI, settings.current().engineId)
    }

    @Test fun plannedEnginesAndModelsThisPhoneCannotHoldAreNotOffered() {
        val am = context.getSystemService(ActivityManager::class.java)
        shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = 4L shl 30 })
        val small = vm().engines
        assertTrue(small.all { it.offered })
        assertFalse("Gemma 4 needs 8 GB", EngineId.GEMMA4_LOCAL in small)
        assertTrue(EngineId.GOOGLE in small && EngineId.GEMINI in small && EngineId.QWEN15_LOCAL in small)
        shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = 12L shl 30 })
        assertTrue(EngineId.GEMMA4_LOCAL in vm().engines)
    }

    /** The Try box with a keyed engine and no key: a typed failure, no request, no other engine. */
    @Test fun theTryBoxWithoutAKeyFailsTypedWithoutARequest() = runBlocking<Unit> {
        settings.setEngine(EngineId.GEMINI)
        val vm = vm()
        vm.setTryInput("invented line")
        vm.tryTranslate()
        val s = withTimeout(5_000) { vm.tryState.first { !it.running && it.failure != null } }
        assertEquals(EngineFailure.MissingKey(EngineId.GEMINI), s.failure)
        assertTrue(attempted.isEmpty())
    }

    @Test fun theTryBoxTranslatesWithTheSelectedOnDeviceEngine() = runBlocking<Unit> {
        val vm = vm()
        vm.setTryInput("invented line")
        vm.tryTranslate()
        val s = withTimeout(5_000) { vm.tryState.first { !it.running && it.output != null } }
        assertEquals("EN(invented line)", s.output)
        assertTrue(s.via!!.endsWith(" ms"))
        assertTrue("Google Translate is on-device", attempted.isEmpty())
    }

    @Test fun swapTradesTheLanguagesAndRemembersThePair() = runBlocking<Unit> {
        val vm = vm()
        vm.setLanguages(Lang.JA, Lang.EN).join()
        vm.swapLanguages().join()
        assertEquals(Lang.EN, settings.current().defaultSourceLang)
        assertEquals(Lang.JA, settings.current().defaultTargetLang)
        assertEquals(listOf(Lang.EN to Lang.JA, Lang.JA to Lang.EN), settings.recentPairs.first())
    }

    @Test fun aTargetTheReaderCannotReadIsNotSwappedIn() = runBlocking<Unit> {
        val vm = vm()
        vm.setLanguages(Lang.JA, Lang.HI).join()
        assertFalse(vm.canSwap(settings.current()))
        vm.swapLanguages().join()
        assertEquals(Lang.JA, settings.current().defaultSourceLang)
        assertEquals(Lang.HI, settings.current().defaultTargetLang)
    }

    @Test fun updatingTheBlockListsSaysWhetherItWorked() = runBlocking<Unit> {
        val vm = vm()
        vm.updateBlockLists()
        withTimeout(5_000) { vm.blockListUpdate.first { it == BlockListUpdate.DONE } }
        rig.blockListsUpdate = false
        vm.updateBlockLists()
        withTimeout(5_000) { vm.blockListUpdate.first { it == BlockListUpdate.FAILED } }
        assertEquals(2, rig.blockListUpdates)
    }

    @Test fun theGlossaryIsCountedAcrossSeries() = runBlocking<Unit> {
        val vm = vm()
        rig.glossary.upsert(GlossaryEntry(seriesKey = "a.example/one", source = "Aki", target = "Aki"))
        rig.glossary.upsert(GlossaryEntry(seriesKey = "a.example/one", source = "Mori", target = "Forest"))
        rig.glossary.upsert(GlossaryEntry(seriesKey = "b.example/two", source = "Sora", target = "Sky"))
        val summary = withTimeout(5_000) { vm.glossarySummary.first { it.terms == 3 } }
        assertEquals(2, summary.series)
    }

    @Test fun storageIsMeasuredFromThePhone() = runBlocking<Unit> {
        val vm = vm()
        vm.loadStorage().join()
        val st = withTimeout(5_000) { vm.storage.first { it != null } }!!
        assertEquals(0L, st.cache)
        assertEquals(st.models + st.packs + st.studio + st.cache, st.total)
        assertEquals(st.packCount * StorageSummary.PACK_BYTES, st.packs)
    }
}
