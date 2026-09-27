package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.KeyRequirement
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TranslateRequest
import com.smnexstudio.panelglass.core.model.TranslatedItem

interface TranslationEngine {
    val id: EngineId
    val keyRequirement: KeyRequirement get() = id.keyRequirement
    /** True when the engine can read an SFX montage image in the same call as the dialogue. */
    val acceptsImages: Boolean
    /** True when the engine accepts conversation / dialogue continuity context. */
    val usesContext: Boolean get() = false

    /**
     * Translates every item; the result has exactly one entry per request item, in any order.
     * Throws [com.smnexstudio.panelglass.core.model.EngineException] with a typed failure.
     */
    suspend fun translate(req: TranslateRequest): List<TranslatedItem>

    /** Opens a connection to the provider so the first real call skips DNS + TLS. */
    suspend fun warmUp(src: Lang, tgt: Lang) {}
}
