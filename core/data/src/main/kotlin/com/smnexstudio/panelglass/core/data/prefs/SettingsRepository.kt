package com.smnexstudio.panelglass.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.smnexstudio.panelglass.core.model.AppTheme
import com.smnexstudio.panelglass.core.model.BubbleFont
import com.smnexstudio.panelglass.core.model.ContextMode
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.FreeTextMode
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.QwenBackend
import com.smnexstudio.panelglass.core.model.Settings
import com.smnexstudio.panelglass.core.model.SfxMode
import com.smnexstudio.panelglass.core.model.TranslationNotes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Font ids never contain a space; one separates them in the stored list. */
    private val FAV_SEP = " "
    /** Language pairs Settings › Languages offers again. */
    private val MAX_RECENT_PAIRS = 4
    /** From then To picked within this time count as one change of pair. */
    private val PAIR_EDIT_WINDOW_MS = 60_000L

    private object K {
        val src = stringPreferencesKey("defaultSourceLang")
        val tgt = stringPreferencesKey("defaultTargetLang")
        val engine = stringPreferencesKey("engineId")
        val adBlock = booleanPreferencesKey("adBlockDefault")
        /** Before the Studio's fonts: a [BubbleFont] name, read only while [readerFont] is unset. */
        val font = stringPreferencesKey("bubbleFont")
        /** A Studio font id; "" is Auto. */
        val readerFont = stringPreferencesKey("readerFont")
        val sfx = stringPreferencesKey("sfxMode")
        val free = stringPreferencesKey("freeTextMode")
        val quality = intPreferencesKey("patchQuality")
        val translateOnOpen = booleanPreferencesKey("translateOnOpen")
        val blockListUpdatedAt = longPreferencesKey("blockListUpdatedAt")
        val contextMode = stringPreferencesKey("contextMode")
        val theme = stringPreferencesKey("appTheme")
        val pageTarget = stringPreferencesKey("pageTranslateTarget")
        val studioExportFormat = stringPreferencesKey("studioExportFormat")
        val studioExportQuality = stringPreferencesKey("studioExportQuality")
        val studioExportContent = stringPreferencesKey("studioExportContent")
        val fontFavourites = stringPreferencesKey("fontFavourites")
        val qwenBackend = stringPreferencesKey("qwenBackend")
        val automaticPacks = booleanPreferencesKey("automaticPacksDone")
        val automaticModels = booleanPreferencesKey("automaticModelsDone")
        val notificationsAsked = booleanPreferencesKey("notificationsAsked")
        val downloadWifiOnly = booleanPreferencesKey("downloadWifiOnly")
        val firstDownloadsChosen = booleanPreferencesKey("firstDownloadsChosen")
        val translationNotes = stringPreferencesKey("translationNotes")
        val updateCheckedAt = longPreferencesKey("updateCheckedAt")
        val autoUpdateCheck = booleanPreferencesKey("autoUpdateCheck")
        val studioOnlyWhileCharging = booleanPreferencesKey("studioOnlyWhileCharging")
        val studioExportFolder = stringPreferencesKey("studioExportFolder")
        val studioRunChapter = longPreferencesKey("studioRunChapter")
        /** The user's own OpenAI-compatible server for the "Custom AI" engine; an address, not a secret. */
        val customBaseUrl = stringPreferencesKey("customBaseUrl")
        /** The language pairs last set in Settings, newest first (`JA>EN KO>EN`). */
        val recentPairs = stringPreferencesKey("recentPairs")
        /** When From or To was last changed alone ([setSource] / [setTarget]). */
        val pairEditedAt = longPreferencesKey("pairEditedAt")
        fun model(id: EngineId) = stringPreferencesKey("model_" + id.name)
        fun profile(id: EngineId, model: String) = stringPreferencesKey("profile_" + id.name + "_" + model)
    }

    val settings: Flow<Settings> = dataStore.data.map { p ->
        val d = Settings()
        Settings(
            defaultSourceLang = p[K.src]?.let { enumOrNull<Lang>(it) } ?: d.defaultSourceLang,
            defaultTargetLang = p[K.tgt]?.let { enumOrNull<Lang>(it) } ?: d.defaultTargetLang,
            engineId = p[K.engine]?.let { enumOrNull<EngineId>(it) } ?: d.engineId,
            models = EngineId.entries.mapNotNull { id -> p[K.model(id)]?.let { id to it } }.toMap(),
            contextMode = p[K.contextMode]?.let { enumOrNull<ContextMode>(it) } ?: d.contextMode,
            adBlockDefault = p[K.adBlock] ?: d.adBlockDefault,
            // "" is Auto, a choice like any other: only a phone that never chose (no key) reads the font saved before.
            readerFontId = when (val id = p[K.readerFont]) {
                null -> p[K.font]?.let { BubbleFont.fromName(it)?.fontId } ?: d.readerFontId
                else -> id.ifEmpty { null }
            },
            sfxMode = p[K.sfx]?.let { enumOrNull<SfxMode>(it) } ?: d.sfxMode,
            freeTextMode = p[K.free]?.let { enumOrNull<FreeTextMode>(it) } ?: d.freeTextMode,
            patchQuality = p[K.quality] ?: d.patchQuality,
            translateOnOpen = p[K.translateOnOpen] ?: d.translateOnOpen,
            blockListUpdatedAt = p[K.blockListUpdatedAt] ?: d.blockListUpdatedAt,
            appTheme = p[K.theme]?.let { AppTheme.fromId(it) } ?: d.appTheme,
            qwenBackend = p[K.qwenBackend]?.let { enumOrNull<QwenBackend>(it) } ?: d.qwenBackend,
            translationNotes = p[K.translationNotes] ?: d.translationNotes,
            autoUpdateCheck = p[K.autoUpdateCheck] ?: d.autoUpdateCheck,
            studioOnlyWhileCharging = p[K.studioOnlyWhileCharging] ?: d.studioOnlyWhileCharging,
            studioExportFolder = p[K.studioExportFolder]?.takeIf { it.isNotBlank() },
            downloadWifiOnly = p[K.downloadWifiOnly] ?: d.downloadWifiOnly,
            customBaseUrl = p[K.customBaseUrl] ?: d.customBaseUrl,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setDefaultSourceLang(v: Lang) { dataStore.edit { it[K.src] = v.name } }
    suspend fun setDefaultTargetLang(v: Lang) { dataStore.edit { it[K.tgt] = v.name } }

    /**
     * Sets both languages in one write and remembers the pair at the front of [recentPairs] (Settings › Languages:
     * From / To, the swap button, a recent pair). A pair with the same language on both sides is not remembered.
     */
    suspend fun setLanguages(src: Lang, tgt: Lang) {
        dataStore.edit { p ->
            p[K.src] = src.name
            p[K.tgt] = tgt.name
            if (src != tgt) p[K.recentPairs] = pairsText(listOf(src to tgt) + pairs(p[K.recentPairs]).filter { it != src to tgt })
            p.remove(K.pairEditedAt) // a whole pair chosen at once: the next one-sided pick starts a new entry
        }
    }

    /** Up to [MAX_RECENT_PAIRS] language pairs, newest first; unknown or broken entries are skipped. */
    val recentPairs: Flow<List<Pair<Lang, Lang>>> = dataStore.data.map { pairs(it[K.recentPairs]) }

    /** The clock for [setSource] / [setTarget]; swapped in tests. */
    internal var now: () -> Long = System::currentTimeMillis

    /** Sets From alone; see [setOneSide]. */
    suspend fun setSource(v: Lang) = setOneSide(src = v, tgt = null)

    /** Sets To alone; see [setOneSide]. */
    suspend fun setTarget(v: Lang) = setOneSide(src = null, tgt = v)

    /**
     * One side of the pair, read and written in one edit, so two quick picks never undo each other. Changing From
     * then To (JA>EN to KO>FR) passes through KO>EN, which the user never chose: a one-sided change within
     * [PAIR_EDIT_WINDOW_MS] of the last one replaces the pair that one added instead of adding another.
     */
    private suspend fun setOneSide(src: Lang?, tgt: Lang?) {
        dataStore.edit { p ->
            val s = src ?: p[K.src]?.let { enumOrNull<Lang>(it) } ?: Settings().defaultSourceLang
            val t = tgt ?: p[K.tgt]?.let { enumOrNull<Lang>(it) } ?: Settings().defaultTargetLang
            p[K.src] = s.name
            p[K.tgt] = t.name
            val at = now()
            val recent = pairs(p[K.recentPairs])
            val continuing = at - (p[K.pairEditedAt] ?: Long.MIN_VALUE / 2) in 0..PAIR_EDIT_WINDOW_MS
            val kept = if (continuing) recent.drop(1) else recent
            if (s != t) p[K.recentPairs] = pairsText(listOf(s to t) + kept.filter { it != s to t })
            p[K.pairEditedAt] = at
        }
    }

    private fun pairs(raw: String?): List<Pair<Lang, Lang>> = raw.orEmpty().split(' ').mapNotNull { item ->
        val (a, b) = item.split('>').takeIf { it.size == 2 } ?: return@mapNotNull null
        val src = enumOrNull<Lang>(a) ?: return@mapNotNull null
        val tgt = enumOrNull<Lang>(b) ?: return@mapNotNull null
        src to tgt
    }.distinct().take(MAX_RECENT_PAIRS)

    private fun pairsText(list: List<Pair<Lang, Lang>>) = list.take(MAX_RECENT_PAIRS).joinToString(" ") { "${it.first.name}>${it.second.name}" }
    suspend fun setEngine(v: EngineId) { dataStore.edit { it[K.engine] = v.name } }
    suspend fun setAdBlockDefault(v: Boolean) { dataStore.edit { it[K.adBlock] = v } }
    /** The reader's font, a Studio font id; null for Auto. */
    suspend fun setReaderFont(id: String?) { dataStore.edit { it[K.readerFont] = id.orEmpty() } }
    suspend fun setBlockListUpdatedAt(v: Long) { dataStore.edit { it[K.blockListUpdatedAt] = v } }
    suspend fun setAppTheme(v: AppTheme) { dataStore.edit { it[K.theme] = v.id } }
    suspend fun setQwenBackend(v: QwenBackend) { dataStore.edit { it[K.qwenBackend] = v.name } }
    /** When GitHub was last asked for a newer release (the daily check). */
    suspend fun updateCheckedAt(): Long = dataStore.data.first()[K.updateCheckedAt] ?: 0L
    suspend fun setUpdateCheckedAt(v: Long) { dataStore.edit { it[K.updateCheckedAt] = v } }

    suspend fun setAutoUpdateCheck(v: Boolean) { dataStore.edit { it[K.autoUpdateCheck] = v } }

    /** How the reader treats sound effects; read since the start, but nothing could set it before. */
    suspend fun setSfxMode(v: SfxMode) { dataStore.edit { it[K.sfx] = v.name } }
    suspend fun setTranslationNotes(v: String) {
        dataStore.edit { it[K.translationNotes] = v.trim().take(TranslationNotes.MAX_CHARS) }
    }

    /** The reader's "Translate page" target, an ML Kit language tag; English until the user picks another. */
    suspend fun pageTranslateTarget(): String = dataStore.data.first()[K.pageTarget] ?: "en"
    suspend fun setPageTranslateTarget(tag: String) { dataStore.edit { it[K.pageTarget] = tag } }
    /** [pageTranslateTarget] as it changes (Settings › Reader). */
    val pageTranslateTargetFlow: Flow<String> = dataStore.data.map { it[K.pageTarget] ?: "en" }

    /** The Studio's last export format (an enum name the Studio owns); null until the first choice. */
    suspend fun studioExportFormat(): String? = dataStore.data.first()[K.studioExportFormat]
    suspend fun setStudioExportFormat(name: String) { dataStore.edit { it[K.studioExportFormat] = name } }

    suspend fun studioExportQuality(): String? = dataStore.data.first()[K.studioExportQuality]
    suspend fun setStudioExportQuality(name: String) { dataStore.edit { it[K.studioExportQuality] = name } }

    suspend fun setStudioOnlyWhileCharging(v: Boolean) { dataStore.edit { it[K.studioOnlyWhileCharging] = v } }

    /** The Studio's default export folder (a persisted tree URI); null goes back to `Download/Panelglass`. */
    suspend fun setStudioExportFolder(uri: String?) {
        dataStore.edit { p -> if (uri.isNullOrBlank()) p.remove(K.studioExportFolder) else p[K.studioExportFolder] = uri }
    }

    /**
     * The chapter a Studio run is working on, kept until the run ends, so a run the system stopped (the app killed)
     * carries on at the next start; null when none is.
     */
    suspend fun studioRunChapter(): Long? = dataStore.data.first()[K.studioRunChapter]
    suspend fun setStudioRunChapter(id: Long?) {
        dataStore.edit { if (id == null) it.remove(K.studioRunChapter) else it[K.studioRunChapter] = id }
    }

    /** What the Studio last exported: the translated pages or the clean ones (`ExportContent` name). */
    suspend fun studioExportContent(): String? = dataStore.data.first()[K.studioExportContent]
    suspend fun setStudioExportContent(name: String) { dataStore.edit { it[K.studioExportContent] = name } }

    /** The Studio's favourite fonts, in the order they were starred (ids shared by bundled and user fonts). */
    val fontFavourites: Flow<List<String>> = dataStore.data.map { p -> favourites(p[K.fontFavourites]) }

    /** Stars [id], or unstars it when it is a favourite already. */
    suspend fun toggleFontFavourite(id: String) {
        dataStore.edit { p ->
            val list = favourites(p[K.fontFavourites])
            p[K.fontFavourites] = (if (id in list) list - id else list + id).joinToString(FAV_SEP)
        }
    }

    private fun favourites(raw: String?): List<String> = raw?.split(FAV_SEP)?.filter { it.isNotBlank() }.orEmpty()

    /** Whether the user (or a launch intent) ever chose an engine; without one the default in [Settings] applies. */
    suspend fun hasEngineChoice(): Boolean = dataStore.data.first()[K.engine] != null

    /**
     * Whether the Japanese, Korean and Chinese Google Translate packs were fetched after install. Set once they are,
     * so a pack the user deletes afterwards is not downloaded again at the next start.
     */
    suspend fun automaticPacksDone(): Boolean = dataStore.data.first()[K.automaticPacks] == true
    suspend fun setAutomaticPacksDone() { dataStore.edit { it[K.automaticPacks] = true } }

    /** manga-ocr and LaMa were queued once after install; a model the user deletes later is not fetched again. */
    suspend fun automaticModelsDone(): Boolean = dataStore.data.first()[K.automaticModels] == true
    suspend fun setAutomaticModelsDone() { dataStore.edit { it[K.automaticModels] = true } }

    /** The notification permission was asked for at first open (Android 13+); it is not asked again at every start. */
    suspend fun notificationsAsked(): Boolean = dataStore.data.first()[K.notificationsAsked] == true
    suspend fun setNotificationsAsked() { dataStore.edit { it[K.notificationsAsked] = true } }

    /** Models and language packs over Wi-Fi only ([Settings.downloadWifiOnly]). */
    suspend fun setDownloadWifiOnly(v: Boolean) { dataStore.edit { it[K.downloadWifiOnly] = v } }

    /**
     * The first-open setup was answered (what to download, over which network). Until then nothing is fetched at
     * start: the user sees the list and its size first.
     */
    suspend fun firstDownloadsChosen(): Boolean = dataStore.data.first()[K.firstDownloadsChosen] == true
    suspend fun setFirstDownloadsChosen() { dataStore.edit { it[K.firstDownloadsChosen] = true } }

    /**
     * What a provider learned about one of its models (for Gemini, the request options it accepts), kept across runs so
     * the first call after launch does not re-learn it. Opaque here; model id and option name only, never a key.
     */
    suspend fun requestProfile(id: EngineId, model: String): String? = dataStore.data.first()[K.profile(id, model)]
    suspend fun setRequestProfile(id: EngineId, model: String, profile: String) { dataStore.edit { it[K.profile(id, model)] = profile } }

    /** Model id for a provider that hosts several; blank clears it so the provider's default applies again. */
    suspend fun setModel(id: EngineId, model: String) {
        dataStore.edit { p -> model.trim().let { if (it.isEmpty()) p.remove(K.model(id)) else p[K.model(id)] = it } }
    }

    /**
     * The user's own OpenAI-compatible server for the "Custom AI" engine. An address, not a secret, so it lives here
     * and not in the key store. Blank clears it; the address is stored as given (the Settings screen normalizes it).
     */
    suspend fun setCustomBaseUrl(v: String) {
        dataStore.edit { p -> v.trim().let { if (it.isEmpty()) p.remove(K.customBaseUrl) else p[K.customBaseUrl] = it } }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
