package com.smnexstudio.panelglass.core.ui

import com.smnexstudio.panelglass.core.model.CustomEndpoint
import com.smnexstudio.panelglass.core.model.EngineId

/** A configuration can be saved only when its required fields are usable. */
internal fun apiKeyCanSave(
    engine: EngineId,
    hasKey: Boolean,
    key: String,
    model: String,
    baseUrl: String,
): Boolean = (key.isNotBlank() || hasKey) &&
    (!engine.hasModel || model.isNotBlank()) &&
    (engine != EngineId.CUSTOM || CustomEndpoint.normalize(baseUrl) != null)
