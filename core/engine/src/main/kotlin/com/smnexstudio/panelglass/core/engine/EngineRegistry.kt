package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.secure.SecureKeyStore
import com.smnexstudio.panelglass.core.engine.llm.ClaudeEngine
import com.smnexstudio.panelglass.core.engine.llm.DeepSeekEngine
import com.smnexstudio.panelglass.core.engine.llm.GeminiEngine
import com.smnexstudio.panelglass.core.engine.llm.OpenAiEngine
import com.smnexstudio.panelglass.core.engine.llm.OpenRouterEngine
import com.smnexstudio.panelglass.core.engine.local.LocalEngines
import com.smnexstudio.panelglass.core.engine.mt.DeepLEngine
import com.smnexstudio.panelglass.core.engine.mt.GoogleTranslateEngine
import com.smnexstudio.panelglass.core.engine.mt.PapagoEngine
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.TranslateRequest
import com.smnexstudio.panelglass.core.model.TranslatedItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Either a usable engine or a typed failure the UI can act on. */
sealed class EngineResolution {
    class Ready(val engine: TranslationEngine) : EngineResolution()
    class Failed(val failure: EngineFailure) : EngineResolution()
}

/** One translation call's outcome, with the engine that answered and how long it took. */
class TranslationOutcome(val engine: EngineId, val items: List<TranslatedItem>, val elapsedMs: Long)

/**
 * The provider the user selected in Settings, checked against the key store. The choice is explicit and never
 * changed behind the user's back: a selected provider without a key is a [EngineFailure.MissingKey] the UI turns
 * into "add key", not a silent switch to something else. Only the on-device model needs nothing.
 */
@Singleton
class EngineRegistry @Inject constructor(
    locals: LocalEngines, gemini: GeminiEngine, claude: ClaudeEngine, openAi: OpenAiEngine,
    deepL: DeepLEngine, papago: PapagoEngine, deepSeek: DeepSeekEngine, google: GoogleTranslateEngine, openRouter: OpenRouterEngine,
    private val keys: SecureKeyStore,
    private val settings: SettingsRepository,
) : EngineResolver {
    private val engines: Map<EngineId, TranslationEngine> =
        (listOf(gemini, claude, openAi, deepL, papago, deepSeek, google, openRouter) + locals.all).associateBy { it.id }

    val all: List<TranslationEngine> get() = EngineId.entries.mapNotNull { engines[it] }

    operator fun get(id: EngineId): TranslationEngine = engines.getValue(id)

    fun observeKeys(): Flow<Set<EngineId>> = keys.observeKeyPresence()
    suspend fun hasKey(id: EngineId): Boolean = keys.get(id) != null
    suspend fun setKey(id: EngineId, key: String?) = keys.set(id, key)

    /** The engine chosen in Settings. */
    override suspend fun resolveSelected(): EngineResolution = resolve(settings.current().engineId)

    override suspend fun resolve(id: EngineId): EngineResolution {
        val engine = engines[id] ?: return EngineResolution.Failed(EngineFailure.Unavailable(id, "Unknown engine"))
        // A planned engine (still stored from an older version, or pinned by a launch intent) is not offered: say so
        // rather than switch to another provider behind the user's back.
        if (!id.offered) return EngineResolution.Failed(EngineFailure.Unavailable(id, "Not offered yet; pick Gemini, Google Translate or an on-device model"))
        if (id.needsKey && keys.get(id) == null) return EngineResolution.Failed(EngineFailure.MissingKey(id))
        if (id.hasModel && settings.current().modelFor(id).isBlank()) {
            return EngineResolution.Failed(EngineFailure.Unavailable(id, "No model configured"))
        }
        return EngineResolution.Ready(engine)
    }

    /** Translates with the selected engine; rate limits back off and retry, everything else surfaces typed. */
    suspend fun translate(req: TranslateRequest): TranslationOutcome {
        val engine = when (val r = resolveSelected()) {
            is EngineResolution.Ready -> r.engine
            is EngineResolution.Failed -> throw EngineException(r.failure)
        }
        val started = System.currentTimeMillis()
        var attempt = 0
        while (true) {
            try {
                return TranslationOutcome(engine.id, engine.translate(req), System.currentTimeMillis() - started)
            } catch (e: EngineException) {
                val wait = EngineRetry.delayFor(e.failure, ++attempt) ?: throw e
                delay(wait)
            }
        }
    }
}

/** The part of the registry other layers depend on; lets tests substitute fakes. */
interface EngineResolver {
    suspend fun resolveSelected(): EngineResolution
    suspend fun resolve(id: EngineId): EngineResolution
}
