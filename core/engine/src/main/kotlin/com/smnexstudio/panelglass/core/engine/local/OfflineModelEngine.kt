package com.smnexstudio.panelglass.core.engine.local

import com.smnexstudio.panelglass.core.engine.TranslationEngine
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TranslateRequest
import com.smnexstudio.panelglass.core.model.TranslatedItem
import com.smnexstudio.panelglass.core.model.UserModel

/**
 * The "Offline model" engine: it runs whichever model of the user's own library is active.
 *
 * The engine itself holds no runtime: the active model's [LocalLlmEngine] does, and that engine is shared with the
 * built-in models — loading one releases the others, so the phone never holds two at once.
 */
class OfflineModelEngine(
    private val engines: LocalEngines,
    private val stores: ModelStores,
) : TranslationEngine {
    override val id = EngineId.LOCAL_USER
    override val acceptsImages = false
    override val usesContext = true

    /**
     * The model to run. Wired by [LocalEngines.bindSettings]; without it (unit tests) there is no active model.
     */
    var activeModel: suspend () -> UserModel? = { null }

    /** The engine of the active model, or a typed failure when there is none or its file is not there yet. */
    private suspend fun target(): LocalLlmEngine {
        val m = activeModel() ?: throw EngineException(EngineFailure.ModelMissing(id))
        if (!stores.forUser(m).isReady) throw EngineException(EngineFailure.ModelMissing(id))
        return engines.forUser(m)
    }

    override suspend fun translate(req: TranslateRequest): List<TranslatedItem> = target().translate(req)

    override suspend fun warmUp(src: Lang, tgt: Lang) {
        runCatching { target() }.getOrNull()?.warmUp(src, tgt)
    }
}
