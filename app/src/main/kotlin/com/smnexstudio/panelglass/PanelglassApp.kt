package com.smnexstudio.panelglass

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Bundle
import com.smnexstudio.panelglass.core.ui.AppLocale
import com.smnexstudio.panelglass.core.engine.local.LlmCompare
import com.smnexstudio.panelglass.core.engine.local.LocalEngines
import com.smnexstudio.panelglass.core.engine.local.ModelStores
import com.smnexstudio.panelglass.core.engine.local.LocalModel
import com.smnexstudio.panelglass.core.engine.mt.LanguagePackStore
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.smnexstudio.panelglass.core.ocr.MangaOcrRecognizer
import com.smnexstudio.panelglass.core.render.StudioFonts
import com.smnexstudio.panelglass.feature.studio.fonts.UserFontStore
import com.smnexstudio.panelglass.core.ocr.LamaInpainter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PanelglassApp : Application() {
    @Inject lateinit var locals: LocalEngines
    @Inject lateinit var mangaOcr: MangaOcrRecognizer
    @Inject lateinit var modelStores: ModelStores
    @Inject lateinit var packs: LanguagePackStore
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var userFonts: UserFontStore
    @Inject lateinit var lama: LamaInpainter

    /** The UI language picked in Settings (English by default), before Android 13; [AppLocale]. */
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onCreate() {
        super.onCreate()
        AppLocale.init(this)
        StudioFonts.init(this)
        userFonts.start()
        // Debug: `files/debug-llm-compare` present -> translate fixed lines with every installed on-device model.
        if (LlmCompare.trigger(this) != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { LlmCompare.run(this@PanelglassApp, locals, modelStores) }
        }
        startupDownloads()
        // Back on screen: a model busy when the UI was hidden is kept for the reader again (LocalEngines.onHidden).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = locals.onVisible()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * Google Translate is the default engine, so its Japanese, Korean and Chinese packs (~30 MB each; English is built
     * in) are fetched at first start, over any network, and retried at each start until they are all there. Every
     * other language is downloaded only when the user asks (Settings, or the Download a missing pack offers).
     *
     * Someone who installed before Google Translate became the default and never picked an engine was on Qwen: when
     * the Qwen model is on the phone, that choice is written down so the new default does not switch them.
     */
    private fun startupDownloads() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (!settingsRepo.hasEngineChoice() && modelStores[LocalModel.QWEN15].state.value !is ModelState.Missing) {
                settingsRepo.setEngine(EngineId.QWEN15_LOCAL)
            }
            if (!settingsRepo.automaticPacksDone() && packs.ensureAutomatic()) settingsRepo.setAutomaticPacksDone()
        }
    }

    /**
     * The on-device models (the LiteRT-LM translator, 1.6–2.6 GB, up to ~2.8 GB locked on the GPU; manga-ocr, ~140 MB
     * of sessions) are what put a device on the low-memory-killer threshold. Under pressure, or once the UI is hidden,
     * drop whichever is idle; both reload lazily on the next call, so nothing is lost but a few seconds. A translator
     * busy when the UI is hidden goes shortly after its last call (docs/MEMORY_USAGE.md).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            android.util.Log.i("PanelglassApp", "onTrimMemory $level: releasing idle models")
            mangaOcr.releaseIfIdle()
            lama.releaseIfIdle()
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) locals.onHidden() else locals.releaseIfIdle()
        }
    }
}
