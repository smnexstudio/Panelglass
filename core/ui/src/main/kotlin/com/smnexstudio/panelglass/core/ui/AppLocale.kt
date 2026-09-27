package com.smnexstudio.panelglass.core.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's UI language, picked in Settings. English until the user picks another; it never follows the phone's
 * language. On Android 13+ it is the system's per-app language ([LocaleManager], also under Settings › Apps ›
 * Panelglass › Language); before that the app applies it itself: [wrap] in `attachBaseContext` of the application
 * and the activity. The choice is also kept in plain preferences, because `attachBaseContext` cannot wait for DataStore.
 */
object AppLocale {
    /** The UI languages: the `values-*` folders and `locales_config.xml`. English first. */
    val tags = listOf("en", "ja", "ko", "zh-Hans", "zh-Hant", "es", "fr", "de", "it", "pt", "ru", "id", "vi", "th", "ar", "hi")

    private const val PREFS = "app_locale"
    private const val KEY = "tag"
    private const val DEFAULT = "en"

    /** The current UI language, one of [tags]. */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val set = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (!set.isEmpty) return normalize(set[0])
        }
        return stored(context)
    }

    /** At app start: on Android 13+ an app with no language of its own is put on the saved one (English at first). */
    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val lm = context.getSystemService(LocaleManager::class.java)
        if (lm.applicationLocales.isEmpty) lm.applicationLocales = LocaleList.forLanguageTags(stored(context))
        else save(context, normalize(lm.applicationLocales[0]))
    }

    /** Switches the UI to [tag]; the activity is recreated in the new language. */
    fun set(activity: Activity, tag: String) {
        save(activity, tag)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
            return
        }
        // The application's resources too: view models format their messages with the application context.
        val app = activity.applicationContext
        val config = Configuration(app.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) }
        @Suppress("DEPRECATION") app.resources.updateConfiguration(config, app.resources.displayMetrics)
        Locale.setDefault(Locale.forLanguageTag(tag))
        activity.recreate()
    }

    /** For `attachBaseContext` before Android 13: [base] in the saved language. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val locale = Locale.forLanguageTag(stored(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(config)
    }

    /** Before Android 13 a configuration change (rotation) resets the default locale to the phone's; put ours back. */
    fun reapplyDefault(context: Context) {
        if (Build.VERSION.SDK_INT < 33) Locale.setDefault(Locale.forLanguageTag(stored(context)))
    }

    private fun stored(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it in tags } ?: DEFAULT

    private fun save(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /** A locale as one of [tags]: "ja-JP" → "ja", "zh-TW" → "zh-Hant", Java's old "in" → "id". */
    fun normalize(locale: Locale): String {
        val lang = locale.language.let { if (it == "in") "id" else it }
        if (lang == "zh") {
            val traditional = locale.script == "Hant" || (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))
            return if (traditional) "zh-Hant" else "zh-Hans"
        }
        return lang.takeIf { it in tags } ?: DEFAULT
    }
}
