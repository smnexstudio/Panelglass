package com.smnexstudio.panelglass.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.HistoryRepository
import com.smnexstudio.panelglass.core.engine.EngineRegistry
import com.smnexstudio.panelglass.core.engine.EngineResolution
import com.smnexstudio.panelglass.core.engine.llm.GeminiEngine
import com.smnexstudio.panelglass.core.model.ModelState
import com.smnexstudio.panelglass.core.model.BubbleFont
import android.content.Context
import com.smnexstudio.panelglass.core.engine.local.DeviceMemory
import com.smnexstudio.panelglass.core.engine.local.LocalModel
import com.smnexstudio.panelglass.core.engine.local.ModelStores
import dagger.hilt.android.qualifiers.ApplicationContext
import com.smnexstudio.panelglass.core.engine.mt.LanguagePackStore
import com.smnexstudio.panelglass.core.ocr.MangaOcrStore
import com.smnexstudio.panelglass.core.engine.mt.PackState
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.QwenBackend
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.Settings
import com.smnexstudio.panelglass.core.model.TranslateRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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
) : ViewModel() {
    val settings: StateFlow<Settings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val keys: StateFlow<Set<EngineId>> = registry.observeKeys().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    /** On-device models this phone can hold (Gemma 4 E2B needs 8 GB of RAM); the rest are never shown. */
    val localModels: List<LocalModel> = LocalModel.entries.filter { DeviceMemory.meets(context, it.minRamGb) }
    val modelStates: Map<LocalModel, StateFlow<ModelState>> = localModels.associateWith { modelStores[it].state }
    /** Engines of on-device models this phone cannot hold. */
    val hiddenEngines: Set<EngineId> = LocalModel.entries.filter { it !in localModels }.map { it.engineId }.toSet()
    val mangaOcrState: StateFlow<ModelState> = mangaOcrStore.state
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
    /** What Automatic picks on this phone (by its RAM), shown next to the choice. */
    val qwenAutoOnCpu: Boolean = DeviceMemory.qwenOnCpu(context, QwenBackend.AUTO)
    fun setFont(v: BubbleFont) = viewModelScope.launch { repo.setBubbleFont(v) }
    fun setTheme(t: com.smnexstudio.panelglass.core.model.AppTheme) = viewModelScope.launch { repo.setAppTheme(t) }
    fun clearHistory() = viewModelScope.launch { history.clear() }

    // ---- translation --------------------------------------------------------------------------
    fun setSrc(l: Lang) = viewModelScope.launch { repo.setDefaultSourceLang(l) }
    fun setTgt(l: Lang) = viewModelScope.launch { repo.setDefaultTargetLang(l) }

    /** Explicit provider choice. A keyed engine without a key is not selected; the key sheet is requested instead. */
    fun selectEngine(id: EngineId) = viewModelScope.launch {
        if (id.needsKey && !registry.hasKey(id)) { _needsKey.value = id; return@launch }
        repo.setEngine(id)
    }

    fun clearNeedsKey() { _needsKey.value = null }

    /**
     * Saves what the engine sheet holds: a new key (null = keep the saved one) and, for multi-model providers,
     * the model id. Completing the sheet for the engine the user was trying to select completes that selection.
     */
    fun saveEngineConfig(id: EngineId, key: String?, model: String?) {
        // Read the pending selection now: the sheet dismisses (and clears it) right after calling us.
        val completesSelection = _needsKey.value == id && (key != null || id in keys.value)
        _needsKey.value = null
        viewModelScope.launch {
            if (key != null) registry.setKey(id, key)
            if (model != null && id.hasModel) repo.setModel(id, model)
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
        if (engine != EngineId.GEMINI) { _modelChoices.value = ModelChoices(); return }
        modelsJob?.cancel()
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

    fun downloadPacks() = packStore.downloadAll()
    fun cancelPacks() = packStore.cancel()
    fun deletePacks() = packStore.deleteAll()

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
