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
import com.smnexstudio.panelglass.core.model.CastSheet
import com.smnexstudio.panelglass.core.model.ContextMode
import com.smnexstudio.panelglass.core.model.DialogueContext
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.FreeTextMode
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.QwenBackend
import com.smnexstudio.panelglass.core.model.RetentionLimits
import com.smnexstudio.panelglass.core.model.SavedTranslations
import com.smnexstudio.panelglass.core.model.Settings
import com.smnexstudio.panelglass.core.model.SfxMode
import com.smnexstudio.panelglass.core.model.TranslationNotes
import com.smnexstudio.panelglass.core.model.UserModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Font ids never contain a space; one separates them in the stored list. */
    private val FAV_SEP = " "

    /** The user's own model library is JSON; an unknown field from a newer version is ignored. */
    private val json = Json { ignoreUnknownKeys = true }

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
        val translationNotes = stringPreferencesKey("translationNotes")
        /** How much of the conversation around a bubble the reader sends to the model. */
        val dialogueContext = stringPreferencesKey("dialogueContext")
        /** The user's character sheets, one per website host: a JSON object of host to text. */
        val castSheets = stringPreferencesKey("castSheets")
        /** The screens of overlays kept around the viewport; 0 means Auto. */
        val overlayScreens = intPreferencesKey("overlayScreens")
        /** The decoded memory the overlays may use, in MB; 0 means Auto. */
        val overlayMemoryMb = intPreferencesKey("overlayMemoryMb")
        /** How long a finished translation is kept on disk. */
        val savedTranslations = stringPreferencesKey("savedTranslations")
        /** The disk the saved translations may use, in MB. */
        val savedTranslationsMb = intPreferencesKey("savedTranslationsMb")
        val updateCheckedAt = longPreferencesKey("updateCheckedAt")
        val autoUpdateCheck = booleanPreferencesKey("autoUpdateCheck")
        /** The user's own OpenAI-compatible server for the "Custom AI" engine; an address, not a secret. */
        val customBaseUrl = stringPreferencesKey("customBaseUrl")
        /** The user's own LiteRT-LM models, as a JSON array. */
        val userModels = stringPreferencesKey("userModels")
        /** The id of the user's own model the "Offline model" engine runs. */
        val activeUserModel = stringPreferencesKey("activeUserModel")
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
            dialogueContext = p[K.dialogueContext]?.let { enumOrNull<DialogueContext>(it) } ?: d.dialogueContext,
            overlayScreens = RetentionLimits.clampScreens(p[K.overlayScreens] ?: d.overlayScreens),
            overlayMemoryMb = RetentionLimits.clampOverlayMb(p[K.overlayMemoryMb] ?: d.overlayMemoryMb),
            savedTranslations = p[K.savedTranslations]?.let { enumOrNull<SavedTranslations>(it) } ?: d.savedTranslations,
            savedTranslationsMb = RetentionLimits.clampStorageMb(p[K.savedTranslationsMb] ?: d.savedTranslationsMb),
            autoUpdateCheck = p[K.autoUpdateCheck] ?: d.autoUpdateCheck,
            customBaseUrl = p[K.customBaseUrl] ?: d.customBaseUrl,
            activeUserModelId = p[K.activeUserModel] ?: d.activeUserModelId,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setDefaultSourceLang(v: Lang) { dataStore.edit { it[K.src] = v.name } }
    suspend fun setDefaultTargetLang(v: Lang) { dataStore.edit { it[K.tgt] = v.name } }
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

    /** How much of the conversation around a bubble the reader sends to the model. */
    suspend fun setDialogueContext(v: DialogueContext) { dataStore.edit { it[K.dialogueContext] = v.name } }

    /** The screens of overlays kept around the viewport; 0 means Auto. */
    suspend fun setOverlayScreens(v: Int) { dataStore.edit { it[K.overlayScreens] = RetentionLimits.clampScreens(v) } }

    /** The decoded memory the overlays may use, in MB; 0 means Auto. */
    suspend fun setOverlayMemoryMb(v: Int) { dataStore.edit { it[K.overlayMemoryMb] = RetentionLimits.clampOverlayMb(v) } }

    /** How long a finished translation is kept on disk. */
    suspend fun setSavedTranslations(v: SavedTranslations) { dataStore.edit { it[K.savedTranslations] = v.name } }

    /** The disk the saved translations may use, in MB. */
    suspend fun setSavedTranslationsMb(v: Int) { dataStore.edit { it[K.savedTranslationsMb] = RetentionLimits.clampStorageMb(v) } }

    /** The character sheet of one website host, or an empty string when there is none. */
    suspend fun castSheetFor(host: String): String = castSheets(dataStore.data.first())[host.lowercase()].orEmpty()

    /** The character sheet of one website host, and every change to it. */
    fun castSheetFlow(host: String): Flow<String> =
        dataStore.data.map { p -> castSheets(p)[host.lowercase()].orEmpty() }

    /**
     * The user's character sheet for one website host.
     *
     * One sheet per website is a deliberate simplification: the reader's series key is too coarse to hold one per
     * series, and a host is what the user actually thinks in.
     */
    suspend fun setCastSheet(host: String, text: String) {
        val key = host.lowercase()
        val clean = CastSheet.clean(text)
        dataStore.edit { p ->
            // The map keeps the order the sheets were written in, so the oldest are the first.
            val sheets = LinkedHashMap(castSheets(p))
            if (clean.isBlank()) sheets.remove(key) else sheets[key] = clean
            val kept = if (sheets.size > MAX_CAST_HOSTS) {
                LinkedHashMap(sheets.entries.drop(sheets.size - MAX_CAST_HOSTS).associate { it.key to it.value })
            } else sheets
            p[K.castSheets] = json.encodeToString(castSheetsSerializer, kept)
        }
    }

    /** Every stored sheet; a damaged file gives none rather than crashing the app. */
    private fun castSheets(p: Preferences): Map<String, String> {
        val raw = p[K.castSheets] ?: return emptyMap()
        return runCatching {
            json.decodeFromString(castSheetsSerializer, raw)
        }.getOrDefault(emptyMap())
    }

    /** The reader's "Translate page" target, an ML Kit language tag; English until the user picks another. */
    suspend fun pageTranslateTarget(): String = dataStore.data.first()[K.pageTarget] ?: "en"
    suspend fun setPageTranslateTarget(tag: String) { dataStore.edit { it[K.pageTarget] = tag } }

    /** The Studio's last export format (an enum name the Studio owns); null until the first choice. */
    suspend fun studioExportFormat(): String? = dataStore.data.first()[K.studioExportFormat]
    suspend fun setStudioExportFormat(name: String) { dataStore.edit { it[K.studioExportFormat] = name } }

    suspend fun studioExportQuality(): String? = dataStore.data.first()[K.studioExportQuality]
    suspend fun setStudioExportQuality(name: String) { dataStore.edit { it[K.studioExportQuality] = name } }

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

    /**
     * The user's own on-device models, as saved. Corrupt JSON (an older version, a partial write) reads as an empty
     * library rather than crashing the settings screen.
     */
    val userModels: Flow<List<UserModel>> = dataStore.data.map { p ->
        val raw = p[K.userModels] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<UserModel>>(raw) }.getOrDefault(emptyList())
    }

    /** Saves the user's own model library. */
    suspend fun saveUserModels(list: List<UserModel>) {
        val raw = if (list.isEmpty()) "" else json.encodeToString(list)
        dataStore.edit { p -> if (raw.isEmpty()) p.remove(K.userModels) else p[K.userModels] = raw }
    }

    /** The model the "Offline model" engine runs; blank clears it. */
    suspend fun setActiveUserModel(id: String) {
        dataStore.edit { p -> id.trim().let { if (it.isEmpty()) p.remove(K.activeUserModel) else p[K.activeUserModel] = it } }
    }

    /** The active user's own model, or null when none is chosen (or it is gone from the library). */
    suspend fun activeUserModel(): UserModel? =
        userModels.first().firstOrNull { it.id == current().activeUserModelId }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    companion object {
        /** How many websites keep a character sheet. */
        const val MAX_CAST_HOSTS = 200

        /** The sheets, as a JSON object of host to text. */
        private val castSheetsSerializer = MapSerializer(String.serializer(), String.serializer())
    }
}
