package com.smnexstudio.panelglass.core.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smnexstudio.panelglass.core.model.AppTheme
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import java.util.Locale

/**
 * A language's name in the UI's own language, from Android's locale data: "Japanese" in English, "日本語" in Japanese,
 * "japonés" → "Japonés" in Spanish. Chinese is named by script (Simplified / Traditional), which is what the two
 * entries differ by. [Lang.displayName] stays the English name the engines' prompts use.
 */
fun Lang.uiName(locale: Locale = Locale.getDefault()): String {
    val tag = when (this) {
        Lang.ZH -> "zh-Hans"
        Lang.ZH_TW -> "zh-Hant"
        else -> code
    }
    val name = Locale.forLanguageTag(tag).getDisplayName(locale)
    return if (name.isBlank() || name == tag) displayName else name.replaceFirstChar { it.titlecase(locale) }
}

/**
 * A BCP-47 tag's language name in [locale] ("ja" → "Japanese"; with `locale = Locale.forLanguageTag(tag)`, its own
 * name, "日本語"). ML Kit's single Chinese is "zh"; the tag itself if Android has no name for it.
 */
fun languageName(tag: String, locale: Locale = Locale.getDefault()): String {
    val name = Locale.forLanguageTag(tag).getDisplayName(locale)
    return if (name.isBlank()) tag else name.replaceFirstChar { it.titlecase(locale) }
}

/** An engine's name for the UI. Product names stay as they are; on-device models carry a translated "(on-device)". */
fun EngineId.uiName(context: Context): String = when (this) {
    EngineId.QWEN15_LOCAL -> context.getString(R.string.engine_on_device, "Qwen 2.5 1.5B")
    EngineId.GEMMA4_LOCAL -> context.getString(R.string.engine_on_device, "Gemma 4 E2B")
    else -> displayName
}

@Composable
fun EngineId.uiName(): String = uiName(LocalContext.current)

/** A theme's name: "Default" is a word and is translated; the others are names and are not. */
@Composable
fun AppTheme.uiName(): String = if (this == AppTheme.DEFAULT) stringResource(R.string.theme_default) else label
