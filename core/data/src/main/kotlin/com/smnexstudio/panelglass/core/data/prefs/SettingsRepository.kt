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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object K {
        val src = stringPreferencesKey("defaultSourceLang")
        val tgt = stringPreferencesKey("defaultTargetLang")
        val engine = stringPreferencesKey("engineId")
        val adBlock = booleanPreferencesKey("adBlockDefault")
        val font = stringPreferencesKey("bubbleFont")
        val sfx = stringPreferencesKey("sfxMode")
        val free = stringPreferencesKey("freeTextMode")
        val quality = intPreferencesKey("patchQuality")
        val translateOnOpen = booleanPreferencesKey("translateOnOpen")
        val blockListUpdatedAt = longPreferencesKey("blockListUpdatedAt")
        val contextMode = stringPreferencesKey("contextMode")
        val theme = stringPreferencesKey("appTheme")
        val pageTarget = stringPreferencesKey("pageTranslateTarget")
        val qwenBackend = stringPreferencesKey("qwenBackend")
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
            bubbleFont = p[K.font]?.let { BubbleFont.fromName(it) } ?: d.bubbleFont,
            sfxMode = p[K.sfx]?.let { enumOrNull<SfxMode>(it) } ?: d.sfxMode,
            freeTextMode = p[K.free]?.let { enumOrNull<FreeTextMode>(it) } ?: d.freeTextMode,
            patchQuality = p[K.quality] ?: d.patchQuality,
            translateOnOpen = p[K.translateOnOpen] ?: d.translateOnOpen,
            blockListUpdatedAt = p[K.blockListUpdatedAt] ?: d.blockListUpdatedAt,
            appTheme = p[K.theme]?.let { AppTheme.fromId(it) } ?: d.appTheme,
            qwenBackend = p[K.qwenBackend]?.let { enumOrNull<QwenBackend>(it) } ?: d.qwenBackend,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setDefaultSourceLang(v: Lang) { dataStore.edit { it[K.src] = v.name } }
    suspend fun setDefaultTargetLang(v: Lang) { dataStore.edit { it[K.tgt] = v.name } }
    suspend fun setEngine(v: EngineId) { dataStore.edit { it[K.engine] = v.name } }
    suspend fun setAdBlockDefault(v: Boolean) { dataStore.edit { it[K.adBlock] = v } }
    suspend fun setBubbleFont(v: BubbleFont) { dataStore.edit { it[K.font] = v.name } }
    suspend fun setBlockListUpdatedAt(v: Long) { dataStore.edit { it[K.blockListUpdatedAt] = v } }
    suspend fun setAppTheme(v: AppTheme) { dataStore.edit { it[K.theme] = v.id } }
    suspend fun setQwenBackend(v: QwenBackend) { dataStore.edit { it[K.qwenBackend] = v.name } }

    /** The reader's "Translate page" target, an ML Kit language tag; English until the user picks another. */
    suspend fun pageTranslateTarget(): String = dataStore.data.first()[K.pageTarget] ?: "en"
    suspend fun setPageTranslateTarget(tag: String) { dataStore.edit { it[K.pageTarget] = tag } }

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

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
