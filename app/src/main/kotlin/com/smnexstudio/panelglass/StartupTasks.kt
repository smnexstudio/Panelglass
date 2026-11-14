package com.smnexstudio.panelglass

import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.model.EngineId

/**
 * What [PanelglassApp] settles at every start, apart from the Application so it is unit-tested:
 *
 * - Someone who installed before Google Translate became the default and never picked an engine was on Qwen: when
 *   the Qwen model is on the phone, that choice is written down so the new default does not switch them.
 * - Google Translate's Japanese, Korean and Chinese packs are fetched until they are all there once; after that a pack
 *   the user deletes is not fetched again. Offline, the fetch fails and the next start tries again.
 * - None of the downloads below starts before the first-open setup is answered: the user sees what is missing, its
 *   size, and picks Wi-Fi only or mobile data too ([SettingsRepository.firstDownloadsChosen]).
 * - manga-ocr (Japanese reading) and LaMa (the Studio's cleanup, on phones with the memory for it) are queued with
 *   DownloadManager until both are queued or on the phone once; the system finishes them across restarts. A model the
 *   user deletes afterwards is not fetched again.
 */
internal class StartupTasks(
    private val settings: SettingsRepository,
    private val qwenOnPhone: () -> Boolean,
    private val fetchAutomaticPacks: suspend () -> Boolean,
    private val fetchAutomaticModels: suspend () -> Boolean,
) {
    suspend fun run() {
        if (!settings.hasEngineChoice() && qwenOnPhone()) settings.setEngine(EngineId.QWEN15_LOCAL)
        // Nothing is fetched before the first-open setup is answered (what, how big, which network: MainActivity).
        if (!settings.firstDownloadsChosen()) return
        // The models first: queuing returns at once, while the pack fetch waits for the packs to finish.
        if (!settings.automaticModelsDone() && fetchAutomaticModels()) settings.setAutomaticModelsDone()
        if (!settings.automaticPacksDone() && fetchAutomaticPacks()) settings.setAutomaticPacksDone()
    }
}
