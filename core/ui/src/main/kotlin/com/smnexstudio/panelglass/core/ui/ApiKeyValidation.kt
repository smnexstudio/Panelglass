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
): Boolean = (!engine.needsKey || key.isNotBlank() || hasKey) &&
    (!engine.hasModel || model.isNotBlank()) &&
    (engine != EngineId.CUSTOM || CustomEndpoint.normalize(baseUrl) != null) &&
    (engine != EngineId.CUSTOM_LAN || CustomEndpoint.normalize(baseUrl, true) != null)

/**
 * The (saved, new) hosts when saving the "Custom AI" sheet would send the saved key to a server other than the one it
 * was entered for: the address now names another host and no new key was typed. Null otherwise. The sheet then asks
 * whether to keep the key (the user fixed a typo in the host) or enter a new one; it never deletes the key itself.
 */
internal fun keyHostChange(
    engine: EngineId,
    hasKey: Boolean,
    key: String,
    savedBaseUrl: String,
    baseUrl: String,
): Pair<String, String>? {
    if (engine !in setOf(EngineId.CUSTOM, EngineId.CUSTOM_LAN) || !hasKey || key.isNotBlank()) return null
    val saved = CustomEndpoint.host(savedBaseUrl, engine == EngineId.CUSTOM_LAN) ?: return null
    val now = CustomEndpoint.host(baseUrl, engine == EngineId.CUSTOM_LAN) ?: return null
    return if (saved == now) null else saved to now
}
