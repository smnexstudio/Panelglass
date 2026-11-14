package com.smnexstudio.panelglass.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smnexstudio.panelglass.core.model.AppTheme
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.ModelState
import com.smnexstudio.panelglass.core.model.SfxMode
import com.smnexstudio.panelglass.core.ui.AppLocale
import com.smnexstudio.panelglass.core.ui.CardDivider
import com.smnexstudio.panelglass.core.ui.SearchField
import com.smnexstudio.panelglass.core.ui.SectionLabel
import com.smnexstudio.panelglass.core.ui.TokenCard
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.uiName
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The Settings hub: a search field and one row per page, grouped as Translation, Reading & Studio and App. Each row
 * says what its page holds now ("Japanese → English · Replace"), so most people never need to open it. [selected] is
 * the page shown beside the hub on a wide window. The search [query] is held by the screen, so Back can clear it and a
 * page opened from the results returns to them.
 */
@Composable
internal fun SettingsHub(
    vm: SettingsViewModel, selected: SettingsPage?, query: String, onQuery: (String) -> Unit, onOpen: (SettingsPage) -> Unit,
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val downloading = (vm.modelStates.values.map { it.collectAsStateWithLifecycle().value } +
        vm.mangaOcrState.collectAsStateWithLifecycle().value + vm.lamaState.collectAsStateWithLifecycle().value)
        .any { it is ModelState.Downloading }
    val appLang = remember { AppLocale.current(context) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Text(
            stringResource(UiR.string.settings_title), style = MaterialTheme.typography.headlineSmall, color = Tokens.Ink,
            modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 0.dp),
        )
        SearchField(
            query, onQuery, stringResource(UiR.string.set_search_hint), onGo = {},
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
        )

        if (query.isNotBlank()) {
            val hits = searchSettings(query, settingsIndex(vm))
            if (hits.isEmpty()) {
                Text(
                    stringResource(UiR.string.set_search_none, query.trim()), style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.InkSoft, modifier = Modifier.padding(start = 32.dp, end = 24.dp, top = 20.dp),
                )
            } else {
                SectionLabel(stringResource(UiR.string.set_search_results))
                TokenCard(Modifier.padding(horizontal = 16.dp)) {
                    hits.forEachIndexed { i, hit ->
                        if (i > 0) CardDivider()
                        Column(Modifier.fillMaxWidth().clickable { onOpen(hit.page) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(hit.label, style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink)
                            Text(pageTitle(hit.page), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
                        }
                    }
                }
            }
            return@Column
        }

        val model = if (s.engineId.hasModel) s.modelFor(s.engineId).takeIf { it.isNotBlank() } else null
        val sfx = stringResource(if (s.sfxMode == SfxMode.SKIP) UiR.string.studio_sfx_keep else UiR.string.studio_sfx_replace)
        val theme = if (s.appTheme == AppTheme.DEFAULT) stringResource(UiR.string.theme_default) else s.appTheme.label

        SectionLabel(stringResource(UiR.string.section_translation))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            HubRow(Icons.Outlined.AutoAwesome, "engine", pageTitle(SettingsPage.ENGINE),
                listOfNotNull(s.engineId.uiName(), model).joinToString(" · "), selected == SettingsPage.ENGINE) { onOpen(SettingsPage.ENGINE) }
            CardDivider()
            HubRow(Icons.Outlined.Translate, "languages", pageTitle(SettingsPage.LANGUAGES),
                stringResource(UiR.string.set_page_languages_sub, s.defaultSourceLang.uiName(), s.defaultTargetLang.uiName(), sfx),
                selected == SettingsPage.LANGUAGES) { onOpen(SettingsPage.LANGUAGES) }
            CardDivider()
            HubRow(Icons.Outlined.Download, "models", pageTitle(SettingsPage.MODELS), stringResource(UiR.string.set_page_models_sub),
                selected == SettingsPage.MODELS, badge = if (downloading) stringResource(UiR.string.set_badge_downloading) else null) { onOpen(SettingsPage.MODELS) }
        }

        SectionLabel(stringResource(UiR.string.set_group_reading))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            HubRow(Icons.AutoMirrored.Filled.ViewList, "reader", pageTitle(SettingsPage.READER), stringResource(UiR.string.set_page_reader_sub),
                selected == SettingsPage.READER) { onOpen(SettingsPage.READER) }
            CardDivider()
            HubRow(Icons.Filled.AutoStories, "studio", pageTitle(SettingsPage.STUDIO), stringResource(UiR.string.set_page_studio_sub),
                selected == SettingsPage.STUDIO) { onOpen(SettingsPage.STUDIO) }
            CardDivider()
            HubRow(Icons.Outlined.Storage, "storage", pageTitle(SettingsPage.STORAGE),
                storage?.let { stringResource(UiR.string.set_page_storage_sub, Formatter.formatShortFileSize(context, it.total)) }
                    ?: stringResource(UiR.string.set_measuring),
                selected == SettingsPage.STORAGE) { onOpen(SettingsPage.STORAGE) }
        }

        SectionLabel(stringResource(UiR.string.set_group_app))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            HubRow(Icons.Outlined.Palette, "appearance", pageTitle(SettingsPage.APPEARANCE), "$theme · ${nativeName(appLang)}",
                selected == SettingsPage.APPEARANCE) { onOpen(SettingsPage.APPEARANCE) }
            CardDivider()
            HubRow(Icons.Outlined.Info, "about", pageTitle(SettingsPage.ABOUT), stringResource(UiR.string.update_version, vm.appVersion),
                selected == SettingsPage.ABOUT) { onOpen(SettingsPage.ABOUT) }
        }
    }
}

/** A page's name, as its hub row and its header show it. */
@Composable
internal fun pageTitle(page: SettingsPage): String = when (page) {
    SettingsPage.HUB -> stringResource(UiR.string.settings_title)
    SettingsPage.ENGINE -> stringResource(UiR.string.set_page_engine)
    SettingsPage.LANGUAGES -> stringResource(UiR.string.set_page_languages)
    SettingsPage.MODELS -> stringResource(UiR.string.set_page_models)
    SettingsPage.READER -> stringResource(UiR.string.nav_sites)
    SettingsPage.STUDIO -> stringResource(UiR.string.nav_studio)
    SettingsPage.STORAGE -> stringResource(UiR.string.settings_storage)
    SettingsPage.APPEARANCE -> stringResource(UiR.string.set_page_appearance)
    SettingsPage.ABOUT -> stringResource(UiR.string.set_page_about)
    SettingsPage.LICENCES -> stringResource(UiR.string.set_licences)
    SettingsPage.PRIVACY -> stringResource(UiR.string.set_privacy)
}

/** Everything the hub's search can find: each page's title and the rows on it, in the UI's language. */
@Composable
private fun settingsIndex(vm: SettingsViewModel): List<SearchHit> {
    fun hits(page: SettingsPage, labels: List<String>) = labels.map { SearchHit(page, it) }
    @Composable fun s(id: Int) = stringResource(id)
    return hits(SettingsPage.ENGINE, listOf(
        pageTitle(SettingsPage.ENGINE), s(UiR.string.settings_translation_model), s(UiR.string.settings_try),
        s(UiR.string.key_base_url), s(UiR.string.key_model), s(UiR.string.set_api_key),
    ) + vm.engines.map { it.uiName() } + EngineId.GOOGLE.uiName()) +
        hits(SettingsPage.LANGUAGES, listOf(
            pageTitle(SettingsPage.LANGUAGES), s(UiR.string.lang_source), s(UiR.string.lang_target), s(UiR.string.set_recent_pairs),
            s(UiR.string.settings_notes), s(UiR.string.set_glossary_title), s(UiR.string.studio_role_sfx),
        )) +
        hits(SettingsPage.MODELS, listOf(
            pageTitle(SettingsPage.MODELS), s(UiR.string.qwen_backend), s(UiR.string.model_manga_ocr), s(UiR.string.model_lama),
            s(UiR.string.packs_title), s(UiR.string.set_wifi_only),
        ) + vm.localModels.map { it.label }) +
        hits(SettingsPage.READER, listOf(
            pageTitle(SettingsPage.READER), s(UiR.string.settings_fonts), s(UiR.string.set_page_translate_into), s(UiR.string.block_ads),
            s(UiR.string.set_block_lists), s(UiR.string.settings_history), s(UiR.string.set_cache_title),
        )) +
        hits(SettingsPage.STUDIO, listOf(
            pageTitle(SettingsPage.STUDIO), s(UiR.string.studio_only_charging), s(UiR.string.set_export_folder), s(UiR.string.set_my_fonts),
        )) +
        hits(SettingsPage.STORAGE, listOf(pageTitle(SettingsPage.STORAGE), s(UiR.string.settings_studio_storage), s(UiR.string.set_cache_title))) +
        hits(SettingsPage.APPEARANCE, listOf(pageTitle(SettingsPage.APPEARANCE), s(UiR.string.settings_theme), s(UiR.string.settings_app_language))) +
        hits(SettingsPage.ABOUT, listOf(
            pageTitle(SettingsPage.ABOUT), s(UiR.string.update_check), s(UiR.string.update_auto), s(UiR.string.set_source),
            s(UiR.string.set_report),
        )) +
        hits(SettingsPage.LICENCES, listOf(s(UiR.string.set_licences))) +
        hits(SettingsPage.PRIVACY, listOf(s(UiR.string.set_privacy)))
}
