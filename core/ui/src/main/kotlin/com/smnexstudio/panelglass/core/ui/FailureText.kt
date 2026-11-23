package com.smnexstudio.panelglass.core.ui

import android.content.Context
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.NetworkKind

/**
 * What a failed call says, in the UI's language: the same words in Settings' Try box, the reader's bar and the key
 * sheet, and specific about the cause (busy, too slow, no secure connection, model not offered) so the user knows
 * whether to wait, pick another model or fix a setting. [packNames] names ML Kit language tags for
 * [EngineFailure.PackMissing]; [inReader] words an overload for a screen that translates as it scrolls.
 *
 * The provider's own reason is appended as it came (it is the provider's text, never ours).
 */
fun EngineFailure.message(context: Context, packNames: (List<String>) -> String, inReader: Boolean = false): String {
    val name = engine.uiName(context)
    fun plain(res: Int) = context.getString(res, name)
    fun reasoned(res: Int, reason: String) =
        plain(res).let { if (reason.isEmpty()) it else context.getString(R.string.error_with_reason, it, reason) }
    return when (this) {
        is EngineFailure.MissingKey -> plain(R.string.error_needs_key)
        is EngineFailure.ModelMissing -> context.getString(R.string.error_model_missing)
        is EngineFailure.PackMissing -> context.getString(R.string.error_pack_missing, packNames(tags))
        is EngineFailure.QuotaExceeded -> plain(R.string.error_quota)
        is EngineFailure.RateLimited -> plain(R.string.error_rate_limited)
        is EngineFailure.Overloaded ->
            if (inReader) plain(R.string.error_overloaded_scroll) else reasoned(R.string.error_overloaded, reason)
        is EngineFailure.Malformed -> plain(R.string.error_malformed)
        is EngineFailure.Network -> plain(when (kind) {
            NetworkKind.TIMEOUT -> R.string.error_too_long
            NetworkKind.SECURE -> R.string.error_secure
            NetworkKind.UNREACHABLE -> R.string.error_network
        })
        is EngineFailure.Unavailable -> when {
            isModelNotFound -> plain(R.string.error_model_not_found)
            // The pipeline's watchdog: a stage ran past its limit.
            reason.startsWith("Watchdog") -> plain(R.string.error_too_long)
            else -> reasoned(R.string.error_unavailable, reason)
        }
    }
}
