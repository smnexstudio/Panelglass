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
import com.smnexstudio.panelglass.core.model.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.smnexstudio.panelglass.core.ocr.MangaOcrRecognizer
import com.smnexstudio.panelglass.core.render.StudioFonts
import com.smnexstudio.panelglass.feature.studio.fonts.UserFontStore
import com.smnexstudio.panelglass.core.data.download.DownloadNetwork
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.ocr.LamaInpainter
import com.smnexstudio.panelglass.core.ocr.LamaStore
import com.smnexstudio.panelglass.core.ocr.MangaOcrStore
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
    @Inject lateinit var mangaOcrStore: MangaOcrStore
    @Inject lateinit var lamaStore: LamaStore
    @Inject lateinit var systemDownloads: SystemDownloads

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
        followDownloadNetwork()
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

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Google Translate is the default engine, so its Japanese, Korean and Chinese packs (~30 MB each; English is built
     * in) are fetched after the first-open setup, and retried at each start until they are all there. Every other
     * language is downloaded only when the user asks (Settings, or the Download a missing pack offers). manga-ocr
     * (~140 MB) and, on a phone with 4 GB or more, LaMa (~208 MB) are queued then too: they read Japanese bubbles and
     * clean Studio pages far better than without them. The setup ([FirstDownloads], shown by `MainActivity`) lists
     * them with their size and asks Wi-Fi only or mobile data too; [startupDownloads] runs again once it is answered.
     * [StartupTasks] also keeps Qwen for someone who was on it before the default changed.
     */
    fun startupDownloads() {
        val tasks = StartupTasks(
            settingsRepo,
            qwenOnPhone = { modelStores[LocalModel.QWEN15].state.value !is ModelState.Missing },
            fetchAutomaticPacks = { packs.ensureAutomatic() },
            fetchAutomaticModels = {
                // Queued, not awaited: DownloadManager carries on across restarts. Done only when each is really
                // queued or on the phone: a leftover file still being hashed queues nothing, and is retried next start.
                mangaOcrStore.download()
                if (lamaStore.fitsThisPhone) lamaStore.download()
                mangaOcrStore.queuedOrReady() && (!lamaStore.fitsThisPhone || lamaStore.queuedOrReady())
            },
        )
        startupScope.launch {
            // The network choice is in place before anything is queued, then follows the setting as it changes.
            DownloadNetwork.wifiOnly = settingsRepo.current().downloadWifiOnly
            // Mobile data allowed: an ML Kit pack request left waiting for Wi-Fi (asked before the change, the app
            // closed since) would hold the new one back, so it goes first.
            if (!DownloadNetwork.wifiOnly) systemDownloads.dropWaiting(MLKIT_PACKS_URL)
            tasks.run()
        }
    }

    /**
     * Keeps [DownloadNetwork] in step with Settings › Models & downloads › Download over Wi-Fi only. The system keeps
     * the networks a download was queued with, so a change also re-queues every download still waiting with nothing
     * received (turning Wi-Fi only off while off Wi-Fi then starts them on mobile data at once); one already receiving
     * goes on as it was queued, so no progress is thrown away.
     */
    private fun followDownloadNetwork() {
        startupScope.launch {
            var first = true
            settingsRepo.settings.map { it.downloadWifiOnly }.distinctUntilChanged().collect { wifiOnly ->
                DownloadNetwork.wifiOnly = wifiOnly
                if (first) { first = false; return@collect }
                requeueWaiting(mangaOcrStore.state.value, mangaOcrStore::cancel, mangaOcrStore::download)
                requeueWaiting(lamaStore.state.value, lamaStore::cancel, lamaStore::download)
                for (store in modelStores.all) requeueWaiting(store.state.value, store::cancel, store::download)
                // ML Kit joins a new request to its waiting one, which keeps the old networks: remove that first.
                systemDownloads.dropWaiting(MLKIT_PACKS_URL)
                packs.retryPending()
            }
        }
    }

    /** Where ML Kit's translation packs come from (its own requests, seen in the system's download list). */
    private val MLKIT_PACKS_URL = "/edgedl/translate/"

    /** Cancels and queues again a download that has received nothing yet (not one being checked or receiving). */
    private fun requeueWaiting(state: ModelState, cancel: () -> Unit, download: () -> Unit) {
        if (state !is ModelState.Downloading || state.bytes > 0 || state.note == SystemDownloads.CHECKING) return
        cancel()
        download()
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
