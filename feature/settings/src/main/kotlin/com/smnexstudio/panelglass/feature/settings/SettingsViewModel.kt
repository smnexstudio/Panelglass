package com.smnexstudio.panelglass.feature.settings

import androidx.lifecycle.ViewModel
import com.smnexstudio.panelglass.core.model.TranslationNotes
import com.smnexstudio.panelglass.core.data.block.BlockListUpdater
import com.smnexstudio.panelglass.core.data.db.GlossarySummary
import com.smnexstudio.panelglass.core.data.repo.GlossaryRepository
import com.smnexstudio.panelglass.core.data.cache.PatchCache
import androidx.lifecycle.viewModelScope
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.HistoryRepository
import com.smnexstudio.panelglass.core.data.repo.MangaStorage
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.engine.EngineRegistry
import com.smnexstudio.panelglass.core.engine.EngineResolution
import com.smnexstudio.panelglass.core.engine.llm.GeminiEngine
import com.smnexstudio.panelglass.core.model.ModelState
import com.smnexstudio.panelglass.core.model.SfxMode
import com.smnexstudio.panelglass.core.data.update.AppUpdates
import android.content.Context
import com.smnexstudio.panelglass.core.engine.local.DeviceMemory
import com.smnexstudio.panelglass.core.engine.local.LocalModel
import com.smnexstudio.panelglass.core.engine.local.ModelStores
import dagger.hilt.android.qualifiers.ApplicationContext
import com.smnexstudio.panelglass.core.engine.mt.LanguagePackStore
import com.smnexstudio.panelglass.core.ocr.MangaOcrStore
import com.smnexstudio.panelglass.core.ocr.LamaStore
import com.smnexstudio.panelglass.core.engine.mt.PackState
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.CustomEndpoint
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.QwenBackend
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.Settings
import com.smnexstudio.panelglass.core.model.TranslateRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import android.net.Uri
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.smnexstudio.panelglass.core.data.export.ExportFolders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.smnexstudio.panelglass.core.ui.R as UiR
import com.smnexstudio.panelglass.core.ui.uiName
import javax.inject.Inject

/** "Hello, how are you?" in [this] language: the Try box's starting text for that source language. */
val Lang.trySample: String
    get() = when (this) {
        Lang.JA -> "こんにちは、お元気ですか？"
        Lang.KO -> "안녕하세요, 어떻게 지내세요?"
        Lang.ZH -> "你好，你好吗？"
        Lang.ZH_TW -> "你好，你好嗎？"
        Lang.EN -> "Hello, how are you?"
        Lang.ES -> "Hola, ¿cómo estás?"
        Lang.FR -> "Bonjour, comment allez-vous ?"
        Lang.DE -> "Hallo, wie geht es dir?"
        Lang.IT -> "Ciao, come stai?"
        Lang.PT -> "Olá, como vai você?"
        Lang.RU -> "Привет, как дела?"
        Lang.ID -> "Halo, apa kabar?"
        Lang.VI -> "Xin chào, bạn khỏe không?"
        Lang.TH -> "สวัสดี คุณสบายดีไหม"
        Lang.AR -> "مرحبًا، كيف حالك؟"
        Lang.HI -> "नमस्ते, आप कैसे हैं?"
    }

/** State of the "Try a translation" box. */
data class TryState(
    val input: String = Lang.JA.trySample,
    val running: Boolean = false,
    val output: String? = null,
    val via: String? = null,
    val failure: EngineFailure? = null,
)

/**
 * Where Panelglass's space goes (Settings › Storage, Models). Read from the phone, except [packs] (an estimate); the
 * summary itself is null until the Studio and the cache are first measured.
 */
data class StorageSummary(
    /** Downloaded models: the on-device translators, manga-ocr and LaMa. */
    val models: Long = 0,
    /** ML Kit language packs: ML Kit keeps their files, so this is ~30 MB per pack, not a measurement. */
    val packs: Long = 0,
    val packCount: Int = 0,
    val studio: Long = 0,
    val cache: Long = 0,
    /** Free space on the phone's storage; null when it cannot be read. */
    val free: Long? = null,
) {
    val total: Long get() = models + packs + studio + cache

    companion object {
        /** What one ML Kit language pack takes, roughly (the ML Kit documentation's figure). */
        const val PACK_BYTES = 30L * 1024 * 1024
    }
}

/** Settings › Reader › Block lists › Update. */
enum class BlockListUpdate { IDLE, RUNNING, DONE, FAILED }

/** The model dropdown's content for the key sheet: the key's own model list, fetched on demand. */
data class ModelChoices(
    val loading: Boolean = false,
    val models: List<Pair<String, String>> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val history: HistoryRepository,
    private val registry: EngineRegistry,
    private val gemini: GeminiEngine,
    @ApplicationContext private val context: Context,
    private val modelStores: ModelStores,
    private val packStore: LanguagePackStore,
    private val mangaOcrStore: MangaOcrStore,
    private val lamaStore: LamaStore,
    private val studio: StudioRepository,
    private val updates: AppUpdates,
    private val patchCache: PatchCache,
    private val glossary: GlossaryRepository,
    private val blockLists: BlockListUpdater,
) : ViewModel() {
    val settings: StateFlow<Settings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val keys: StateFlow<Set<EngineId>> = registry.observeKeys().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    /** On-device models this phone can hold (Gemma 4 E2B needs 8 GB of RAM); the rest are never shown. */
    val localModels: List<LocalModel> = LocalModel.entries.filter { DeviceMemory.meets(context, it.minRamGb) }
    val modelStates: Map<LocalModel, StateFlow<ModelState>> = localModels.associateWith { modelStores[it].state }
    /** Engines of on-device models this phone cannot hold. */
    val hiddenEngines: Set<EngineId> = LocalModel.entries.filter { it !in localModels }.map { it.engineId }.toSet()
    val mangaOcrState: StateFlow<ModelState> = mangaOcrStore.state
    /** LaMa, the Studio's cleanup model; offered only on phones with the memory for it. */
    val lamaState: StateFlow<ModelState> = lamaStore.state
    val lamaFits: Boolean get() = lamaStore.fitsThisPhone
    val packState: StateFlow<PackState> = packStore.state
    val engines: List<EngineId> get() = EngineId.entries.filter { it.offered && it !in hiddenEngines }

    private val _tryState = MutableStateFlow(TryState())
    val tryState: StateFlow<TryState> = _tryState

    /** Set when the user picked a keyed engine that has no key yet; the screen opens the key sheet for it. */
    private val _needsKey = MutableStateFlow<EngineId?>(null)
    val needsKey: StateFlow<EngineId?> = _needsKey

    private var tryJob: Job? = null

    init {
        modelStores.all.forEach { it.refresh() }; packStore.refresh()
        // The Try box speaks the source language: its sample follows Settings' source language (on open and on every
        // change). Text the user typed is theirs and stays; only an empty box or another language's sample is replaced.
        viewModelScope.launch {
            repo.settings.map { it.defaultSourceLang }.distinctUntilChanged().collect { src ->
                _tryState.update { t ->
                    val untouched = t.input.isBlank() || Lang.entries.any { it.trySample == t.input }
                    if (untouched && t.input != src.trySample) t.copy(input = src.trySample, output = null, via = null, failure = null) else t
                }
            }
        }
    }

    // ---- browsing / storage ------------------------------------------------------------------
    fun setAdBlock(v: Boolean) = viewModelScope.launch { repo.setAdBlockDefault(v) }
    /** Takes effect at Qwen's next call: a model already loaded on the other backend is reloaded then. */
    fun setQwenBackend(v: QwenBackend) = viewModelScope.launch { repo.setQwenBackend(v) }
    fun setStudioOnlyWhileCharging(v: Boolean) = viewModelScope.launch { repo.setStudioOnlyWhileCharging(v) }
    /** Models and language packs over Wi-Fi only; the app keeps the download code in step (`PanelglassApp`). */
    fun setDownloadWifiOnly(v: Boolean) = viewModelScope.launch { repo.setDownloadWifiOnly(v) }

    /**
     * The Studio's default export folder as the row shows it: the picked folder's own name, or null for
     * `Download/Panelglass`. A folder whose name cannot be read (permission gone) shows the last part of its path.
     */
    val exportFolderName: StateFlow<String?> = settings.map { it.studioExportFolder }.distinctUntilChanged()
        .map { uri -> uri?.let { withContext(Dispatchers.IO) { ExportFolders.name(context, it) } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Set when the folder just picked could not be kept (the provider refused a lasting permission). */
    val exportFolderRefused = MutableStateFlow(false)

    /**
     * Exports go to [tree] (a folder the system picker returned) from now on, for every manga without its own folder;
     * null goes back to Downloads. The folder's permission is kept across restarts first; one that cannot be kept is
     * refused and the setting is left as it was. A permission given up is not released: a manga may use that folder.
     */
    fun setExportFolder(tree: Uri?) = viewModelScope.launch {
        if (tree != null) {
            val kept = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.isSuccess
            }
            exportFolderRefused.value = !kept
            if (!kept) return@launch
        } else exportFolderRefused.value = false
        repo.setStudioExportFolder(tree?.toString())
    }

    fun setSfxMode(v: SfxMode) = viewModelScope.launch { repo.setSfxMode(v) }

    /** Settings › About: the installed version, and a check for a newer release (shown by the app-wide dialog). */
    val appVersion: String get() = updates.currentVersion
    fun checkForUpdates() = updates.checkNow()
    fun setAutoUpdateCheck(v: Boolean) = viewModelScope.launch { repo.setAutoUpdateCheck(v) }
    /** What Automatic picks on this phone (by its RAM), shown next to the choice. */
    val qwenAutoOnCpu: Boolean = DeviceMemory.qwenOnCpu(context, QwenBackend.AUTO)
    /** The reader's font, a Studio font id; null for Auto (the target language's comic font). */
    fun setReaderFont(id: String?) = viewModelScope.launch { repo.setReaderFont(id) }
    /** Notes for the AI engines on every series. */
    fun setTranslationNotes(v: String) = viewModelScope.launch { repo.setTranslationNotes(v) }
    /** The Studio's favourite fonts, shared with the reader's font picker. */
    val fontFavourites: StateFlow<List<String>> = repo.fontFavourites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun toggleFontFavourite(id: String) = viewModelScope.launch { repo.toggleFontFavourite(id) }
    fun setTheme(t: com.smnexstudio.panelglass.core.model.AppTheme) = viewModelScope.launch { repo.setAppTheme(t) }
    fun clearHistory() = viewModelScope.launch { history.clear() }

    /** What each Studio manga takes on the phone; null until measured (it walks the page files). */
    val studioStorage = MutableStateFlow<List<MangaStorage>?>(null)
    /** Deletes the manga with its chapters, pages and their files; exported files are not touched. */
    fun deleteStudioManga(m: Manga) = viewModelScope.launch {
        studio.deleteManga(m)
        storageJob?.join() // a measurement started before the delete would show the manga again
        loadStorage()
    }

    // ---- translation --------------------------------------------------------------------------
    /** One side, read and written in one edit (two quick picks never undo each other); see [SettingsRepository.setSource]. */
    fun setSrc(l: Lang) = viewModelScope.launch { repo.setSource(l) }
    fun setTgt(l: Lang) = viewModelScope.launch { repo.setTarget(l) }
    fun setLanguages(src: Lang, tgt: Lang) = viewModelScope.launch { repo.setLanguages(src, tgt) }

    /** The pairs set lately, newest first; Settings › Languages offers them as chips. */
    val recentPairs: StateFlow<List<Pair<Lang, Lang>>> = repo.recentPairs.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether From and To can trade places: the target must be a language the reader can read (a source choice). */
    fun canSwap(s: Settings): Boolean = s.defaultTargetLang in Lang.sourceChoices && s.defaultSourceLang in Lang.targetChoices

    fun swapLanguages() = viewModelScope.launch {
        val s = repo.current()
        if (canSwap(s)) repo.setLanguages(s.defaultTargetLang, s.defaultSourceLang)
    }

    /** Settings' notes back to the shipped default (register, honorifics, pronouns, short lines). */
    fun resetTranslationNotes() = viewModelScope.launch { repo.setTranslationNotes(TranslationNotes.DEFAULT) }

    /** Series with a glossary and their terms, counted together. */
    val glossarySummary: StateFlow<GlossarySummary> =
        glossary.observeSummary().stateIn(viewModelScope, SharingStarted.Eagerly, GlossarySummary(0, 0))

    /** The reader's "Translate page" target, an ML Kit tag. */
    val pageTarget: StateFlow<String> = repo.pageTranslateTargetFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "en")
    fun setPageTarget(tag: String) = viewModelScope.launch { repo.setPageTranslateTarget(tag) }

    private val _blockListUpdate = MutableStateFlow(BlockListUpdate.IDLE)
    val blockListUpdate: StateFlow<BlockListUpdate> = _blockListUpdate
    fun updateBlockLists() {
        if (_blockListUpdate.value == BlockListUpdate.RUNNING) return
        viewModelScope.launch {
            _blockListUpdate.value = BlockListUpdate.RUNNING
            _blockListUpdate.value = if (blockLists.updateNow()) BlockListUpdate.DONE else BlockListUpdate.FAILED
        }
    }

    // ---- storage --------------------------------------------------------------------------------
    /** The Studio's pages and the page cache, as last measured ([loadStorage]); null until then. */
    private val measured = MutableStateFlow<Pair<Long, Long>?>(null)
    private var storageJob: Job? = null

    /**
     * What Panelglass takes. Models and packs follow their stores as they change (a download finishing or a model
     * deleted on the Models page shows at once); the Studio and the cache are as [loadStorage] last measured them.
     */
    val storage: StateFlow<StorageSummary?> = combine(
        measured,
        combine((modelStates.values + mangaOcrState + lamaState).toList()) { states -> states.sumOf { (it as? ModelState.Ready)?.sizeBytes ?: 0L } },
        packState,
    ) { m, models, packs ->
        m ?: return@combine null
        val packCount = packs.downloaded.count { it != LanguagePackStore.BUILT_IN }
        StorageSummary(
            models = models,
            packs = packCount * StorageSummary.PACK_BYTES,
            packCount = packCount,
            studio = m.first,
            cache = m.second,
            free = runCatching { android.os.StatFs(context.filesDir.path).availableBytes }.getOrNull(),
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Measures the Studio's pages (it walks every page file) and the page cache. One measurement at a time: a page
     * opened while one runs shares it, so two never race to the screen.
     */
    fun loadStorage(): Job = storageJob?.takeIf { it.isActive } ?: viewModelScope.launch {
        val studioList = studio.storage()
        studioStorage.value = studioList
        measured.value = studioList.sumOf { it.bytes } to patchCache.sizeBytes()
    }.also { storageJob = it }

    /** Drops every cached translated page; the reader makes them again as pages are read. */
    fun clearPageCache() = viewModelScope.launch {
        patchCache.clear()
        storageJob?.join()
        loadStorage()
    }

    /** Explicit provider choice. A keyed engine without a key is not selected; the key sheet is requested instead. */
    fun selectEngine(id: EngineId) = viewModelScope.launch {
        if (id.needsKey && !registry.hasKey(id)) { _needsKey.value = id; return@launch }
        repo.setEngine(id)
    }

    fun clearNeedsKey() { _needsKey.value = null }

    /**
     * Saves what the engine sheet holds: a new key (null = keep the saved one), for multi-model providers the model
     * id, and for the "Custom AI" engine its server address. Completing the sheet for the engine the user was trying
     * to select completes that selection. One coroutine, so the three values land in order and never race.
     */
    fun saveEngineConfig(id: EngineId, key: String?, model: String?, baseUrl: String? = null) {
        // Validate before changing the key, model or pending engine selection.
        val normalizedBaseUrl = if (id == EngineId.CUSTOM) {
            CustomEndpoint.normalize(baseUrl ?: return) ?: return
        } else null
        // Read the pending selection now: the sheet dismisses (and clears it) right after calling us.
        val completesSelection = _needsKey.value == id && (key != null || id in keys.value)
        _needsKey.value = null
        viewModelScope.launch {
            if (key != null) registry.setKey(id, key)
            if (model != null && id.hasModel) repo.setModel(id, model)
            if (normalizedBaseUrl != null) repo.setCustomBaseUrl(normalizedBaseUrl)
            if (completesSelection) repo.setEngine(id)
        }
    }

    /** Removes the key; the selection is left alone and resolves to MissingKey until a key is saved again. */
    fun removeKey(id: EngineId) {
        _needsKey.value = null
        viewModelScope.launch { registry.setKey(id, null) }
    }

    private val _modelChoices = MutableStateFlow(ModelChoices())
    val modelChoices: StateFlow<ModelChoices> = _modelChoices
    private var modelsJob: Job? = null

    /**
     * Fills the model dropdown from the provider's own list, with [typedKey] (a key typed but not saved yet) or the
     * saved key. Only Gemini offers a listing today; for other engines the field stays free text.
     */
    fun loadModels(engine: EngineId, typedKey: String?) {
        modelsJob?.cancel()
        if (engine != EngineId.GEMINI) { _modelChoices.value = ModelChoices(); return }
        modelsJob = viewModelScope.launch {
            _modelChoices.update { it.copy(loading = true, error = null) }
            _modelChoices.value = try {
                ModelChoices(models = gemini.listModels(typedKey).map { it.id to it.displayName })
            } catch (e: EngineException) {
                ModelChoices(error = when (val f = e.failure) {
                    is EngineFailure.MissingKey -> context.getString(UiR.string.key_models_error_key)
                    is EngineFailure.Network -> context.getString(UiR.string.key_models_error_network, EngineId.GEMINI.displayName)
                    is EngineFailure.Unavailable -> context.getString(UiR.string.key_models_error).let { if (f.reason.isEmpty()) it else context.getString(UiR.string.error_with_reason, it, f.reason) }
                    else -> context.getString(UiR.string.key_models_error)
                })
            }
        }
    }

    fun downloadModel(m: LocalModel) = modelStores[m].download()
    fun cancelDownload(m: LocalModel) = modelStores[m].cancel()
    fun deleteModel(m: LocalModel) = modelStores[m].delete()

    fun downloadMangaOcr() = mangaOcrStore.download()
    fun cancelMangaOcr() = mangaOcrStore.cancel()
    fun deleteMangaOcr() = mangaOcrStore.delete()
    fun downloadLama() = lamaStore.download()
    fun cancelLama() = lamaStore.cancel()
    fun deleteLama() = lamaStore.delete()

    fun downloadPack(tag: String) = packStore.download(listOf(tag))
    fun deletePack(tag: String) = packStore.delete(tag)

    /** The Try box's "Download": fetch the missing packs, then run the same text again. */
    fun downloadPacksAndTry(tags: List<String>) {
        tryJob?.cancel()
        tryJob = viewModelScope.launch {
            _tryState.update { it.copy(running = true, failure = null) }
            val ok = packStore.downloadNow(tags)
            _tryState.update { it.copy(running = false) }
            if (ok) tryTranslate()
            else _tryState.update { it.copy(failure = EngineFailure.Unavailable(EngineId.GOOGLE, context.getString(UiR.string.pack_failed))) }
        }
    }

    fun setTryInput(s: String) = _tryState.update { it.copy(input = s) }

    fun tryTranslate() {
        val text = _tryState.value.input.trim()
        if (text.isEmpty()) return
        tryJob?.cancel()
        tryJob = viewModelScope.launch {
            _tryState.update { it.copy(running = true, output = null, via = null, failure = null) }
            val s = settings.value
            when (val r = registry.resolveSelected()) {
                is EngineResolution.Failed -> { _tryState.update { it.copy(running = false, failure = r.failure) }; return@launch }
                is EngineResolution.Ready -> Unit
            }
            val req = TranslateRequest(listOf(RegionItem(0, "try", RegionKind.ENCLOSED, text)), s.defaultSourceLang, s.defaultTargetLang)
            try {
                val out = registry.translate(req)
                val via = listOfNotNull(out.engine.uiName(context), s.modelFor(out.engine).takeIf { out.engine.hasModel }, "${out.elapsedMs} ms").joinToString(" · ")
                _tryState.update { it.copy(running = false, output = out.items.firstOrNull()?.text ?: "", via = via) }
            } catch (e: EngineException) {
                _tryState.update { it.copy(running = false, failure = e.failure) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _tryState.update { it.copy(running = false, failure = EngineFailure.Unavailable(s.engineId)) }
            }
        }
    }
}
