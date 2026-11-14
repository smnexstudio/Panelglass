package com.smnexstudio.panelglass.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smnexstudio.panelglass.core.data.update.ReleaseInfo
import com.smnexstudio.panelglass.core.engine.local.DeviceMemory
import com.smnexstudio.panelglass.core.engine.local.LocalModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.widthIn
import com.smnexstudio.panelglass.core.ui.ChoiceSheet
import com.smnexstudio.panelglass.core.data.export.ExportFolders
import com.smnexstudio.panelglass.core.model.CustomProvider
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.FontRole
import com.smnexstudio.panelglass.core.model.ModelState
import com.smnexstudio.panelglass.core.model.QwenBackend
import com.smnexstudio.panelglass.core.model.SfxMode
import com.smnexstudio.panelglass.core.model.TranslationNotes
import com.smnexstudio.panelglass.core.ui.ActionRow
import com.smnexstudio.panelglass.core.ui.AppLocale
import com.smnexstudio.panelglass.core.ui.CardDivider
import com.smnexstudio.panelglass.core.ui.CheckDisc
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.SectionLabel
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.ToggleCardRow
import com.smnexstudio.panelglass.core.ui.TokenCard
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.ValueRow
import com.smnexstudio.panelglass.core.ui.fonts.FontField
import com.smnexstudio.panelglass.core.ui.languageName
import com.smnexstudio.panelglass.core.ui.shortName
import com.smnexstudio.panelglass.core.ui.tileTint
import com.smnexstudio.panelglass.core.ui.uiName
import kotlinx.coroutines.delay
import com.smnexstudio.panelglass.core.ui.R as UiR

/** One page of Settings, by [page]; [onBack] is null beside the hub on a wide window. */
@Composable
internal fun SettingsPageContent(
    page: SettingsPage, vm: SettingsViewModel, sheets: SettingsSheets, onBack: (() -> Unit)?, onOpen: (SettingsPage) -> Unit,
    onManageFonts: () -> Unit,
) {
    val context = LocalContext.current
    when (page) {
        SettingsPage.HUB -> Unit
        SettingsPage.ENGINE -> EnginePage(vm, sheets, onBack)
        SettingsPage.LANGUAGES -> LanguagesPage(vm, sheets, onBack)
        SettingsPage.MODELS -> ModelsPage(vm, sheets, onBack)
        SettingsPage.READER -> ReaderPage(vm, sheets, onBack)
        SettingsPage.STUDIO -> StudioPage(vm, onBack, onManageFonts)
        SettingsPage.STORAGE -> StoragePage(vm, sheets, onBack, onOpen)
        SettingsPage.APPEARANCE -> AppearancePage(vm, sheets, onBack)
        SettingsPage.ABOUT -> AboutPage(vm, onBack, onOpen)
        SettingsPage.LICENCES -> LegalPage(pageTitle(page), LegalDoc.LICENCES, onBack) { openUrl(context, it) }
        SettingsPage.PRIVACY -> LegalPage(pageTitle(page), LegalDoc.PRIVACY, onBack) { openUrl(context, it) }
    }
}

// ---- engine & keys ---------------------------------------------------------------------------

/**
 * Every engine as a row: the free ones on this phone first, then the cloud ones with the user's key. Only the check
 * disc selects. Tapping a cloud engine's tile or name opens its setup below it (one at a time) without selecting it:
 * its model and Custom AI's address, read-only; Edit opens the key sheet, which checks the address and asks before a
 * saved key goes to a new host.
 */
@Composable
private fun EnginePage(vm: SettingsViewModel, sheets: SettingsSheets, onBack: (() -> Unit)?) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val packs by vm.packState.collectAsStateWithLifecycle()
    val tryState by vm.tryState.collectAsStateWithLifecycle()
    var openCloud by rememberSaveable { mutableStateOf<EngineId?>(null) }
    SettingsPageFrame(pageTitle(SettingsPage.ENGINE), onBack) {
        SectionLabel(stringResource(UiR.string.set_engines_device))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            val all = com.smnexstudio.panelglass.core.engine.mt.LanguagePackStore.ALL_TAGS
            EngineOption(EngineId.GOOGLE, s.engineId == EngineId.GOOGLE,
                stringResource(UiR.string.packs_count, all.count { packs.has(it) }, all.size), onSelect = { vm.selectEngine(EngineId.GOOGLE) },
                trailing = { TextAction(stringResource(UiR.string.packs_manage)) { sheets.packs = true } })
            for (m in vm.localModels) {
                val state by vm.modelStates.getValue(m).collectAsStateWithLifecycle()
                CardDivider()
                EngineOption(m.engineId, s.engineId == m.engineId, localSubtitle(m, state), onSelect = { vm.selectEngine(m.engineId) },
                    trailing = {
                        when (state) {
                            ModelState.Missing -> TextAction(stringResource(UiR.string.action_download)) { vm.downloadModel(m) }
                            is ModelState.Failed -> TextAction(stringResource(UiR.string.action_retry)) { vm.downloadModel(m) }
                            is ModelState.Downloading -> TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft) { vm.cancelDownload(m) }
                            is ModelState.Ready -> StatusChip(stringResource(UiR.string.set_ready))
                        }
                    },
                    below = if (state is ModelState.Downloading) ({
                        val d = state as ModelState.Downloading
                        val fraction = if (d.total > 0) (d.bytes.toFloat() / d.total).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { fraction }, color = Tokens.Yellow, trackColor = Tokens.Border, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    }) else null)
            }
        }

        SectionLabel(stringResource(UiR.string.set_engines_cloud))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            val cloud = vm.engines.filter { it.needsKey }
            cloud.forEachIndexed { i, e ->
                if (i > 0) CardDivider()
                val selected = s.engineId == e
                val hasKey = e in keys
                val open = openCloud == e
                EngineOption(e, selected,
                    when (e) {
                        EngineId.GEMINI -> stringResource(UiR.string.set_engine_gemini_sub)
                        // A named provider says which, and the model when one is set: "DeepSeek · deepseek-chat".
                        EngineId.CUSTOM -> CustomProvider.of(s.customBaseUrl).takeIf { it.isPreset }
                            ?.let { p -> listOf(p.displayName, s.modelFor(e)).filter { it.isNotBlank() }.joinToString(" · ") }
                            ?: stringResource(UiR.string.set_engine_custom_sub)
                        else -> s.modelFor(e)
                    },
                    onSelect = { vm.selectEngine(e) },
                    onOpen = { openCloud = if (open) null else e },
                    trailing = {
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { sheets.keyFor = e }.padding(4.dp)) {
                            StatusChip(stringResource(if (hasKey) UiR.string.set_key_saved else UiR.string.set_key_missing), good = hasKey)
                        }
                        Icon(
                            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null,
                            tint = Tokens.InkFaint, modifier = Modifier.size(22.dp),
                        )
                    },
                    below = if (!open) null else ({ CloudDetails(e, s, hasKey) { sheets.keyFor = e } }),
                )
            }
        }

        SectionLabel(stringResource(UiR.string.settings_try))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            TryBox(
                tryState, onInput = { vm.setTryInput(it) }, onRun = { vm.tryTranslate() }, onAddKey = { sheets.keyFor = it },
                onUseOnDevice = { vm.selectEngine(EngineId.GOOGLE) }, onDownloadPacks = { vm.downloadPacksAndTry(it) },
            )
        }
        PageNote(stringResource(UiR.string.settings_keys_note))
    }
}

/** An on-device model's line under its name, by its download state. */
@Composable
private fun localSubtitle(m: LocalModel, state: ModelState): String = when (state) {
    ModelState.Missing -> stringResource(UiR.string.set_engine_local_missing, mb((m.bytes)))
    is ModelState.Downloading -> stringResource(
        UiR.string.set_engine_local_downloading,
        if (state.total > 0) (state.bytes * 100 / state.total).toInt() else 0,
    )
    is ModelState.Ready -> stringResource(UiR.string.picker_local_ready)
    is ModelState.Failed -> stringResource(UiR.string.model_failed, "LiteRT-LM", downloadText(state.reason))
}

/**
 * One engine: the check disc (the only thing that selects it, labelled with the engine's name for TalkBack), its tile,
 * its name and line, a [trailing] action, and [below] when it is open. With [onOpen], tile and name open or close
 * [below]; without, they do nothing.
 */
@Composable
private fun EngineOption(
    e: EngineId, selected: Boolean, subtitle: String, onSelect: () -> Unit, onOpen: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {}, below: (@Composable () -> Unit)? = null,
) {
    val name = e.uiName()
    Column(Modifier.fillMaxWidth().background(if (below != null) Tokens.YellowTint.copy(alpha = 0.45f) else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) { CheckDisc(selected) }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
                    .padding(start = 2.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(e.tileTint), contentAlignment = Alignment.Center) {
                    Text(e.shortName, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 13.sp, color = Color(0xFF0A1019))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 15.sp, color = Tokens.Ink)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(top = 2.dp))
                }
            }
            trailing()
        }
        if (below != null) Box(Modifier.fillMaxWidth().padding(start = 62.dp, end = 14.dp, bottom = 14.dp)) { below() }
    }
}

/** A cloud engine's setup, read-only; [onEdit] opens the key sheet, where it is changed and checked. */
@Composable
private fun CloudDetails(e: EngineId, s: com.smnexstudio.panelglass.core.model.Settings, hasKey: Boolean, onEdit: () -> Unit) {
    val notSet = stringResource(UiR.string.set_not_set)
    Column(Modifier.fillMaxWidth()) {
        if (e == EngineId.CUSTOM && s.customBaseUrl.isNotBlank()) {
            val p = CustomProvider.of(s.customBaseUrl)
            DetailLine(stringResource(UiR.string.key_provider), if (p.isPreset) p.displayName else stringResource(UiR.string.key_provider_custom))
        }
        if (e == EngineId.CUSTOM) DetailLine(stringResource(UiR.string.key_base_url), s.customBaseUrl.ifBlank { notSet })
        if (e.hasModel) DetailLine(stringResource(UiR.string.key_model), s.modelFor(e).ifBlank { notSet })
        DetailLine(stringResource(UiR.string.set_api_key), stringResource(if (hasKey) UiR.string.set_key_saved else UiR.string.set_key_missing))
        if (e == EngineId.CUSTOM) Text(
            stringResource(UiR.string.set_engine_custom_note), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            TextAction(stringResource(UiR.string.action_edit), onClick = onEdit)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.W600, color = Tokens.InkSoft)
        Text(
            value, style = MaterialTheme.typography.bodyMedium, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(Tokens.Card)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

// ---- languages, notes & sound effects --------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagesPage(vm: SettingsViewModel, sheets: SettingsSheets, onBack: (() -> Unit)?) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val recent by vm.recentPairs.collectAsStateWithLifecycle()
    val glossary by vm.glossarySummary.collectAsStateWithLifecycle()
    SettingsPageFrame(stringResource(UiR.string.set_page_languages_short), onBack) {
        SectionLabel(stringResource(UiR.string.set_languages))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                LanguageButton(stringResource(UiR.string.lang_translate_from), s.defaultSourceLang.uiName(), Modifier.weight(1f)) { sheets.src = true }
                val canSwap = vm.canSwap(s)
                IconButton(onClick = { vm.swapLanguages() }, enabled = canSwap, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(if (canSwap) Tokens.Yellow else Tokens.Border), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = stringResource(UiR.string.set_swap), tint = Tokens.Ink)
                    }
                }
                LanguageButton(stringResource(UiR.string.lang_translate_to), s.defaultTargetLang.uiName(), Modifier.weight(1f)) { sheets.tgt = true }
            }
            if (recent.isNotEmpty()) {
                CardDivider()
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(stringResource(UiR.string.set_recent_pairs), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((src, tgt) in recent) {
                            val current = src == s.defaultSourceLang && tgt == s.defaultTargetLang
                            Text(
                                "${src.uiName()} → ${tgt.uiName()}", fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 13.sp,
                                color = if (current) Tokens.Card else Tokens.Ink,
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (current) Tokens.Ink else Tokens.Bg)
                                    .selectable(selected = current, role = Role.RadioButton) { vm.setLanguages(src, tgt) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        PageNote(stringResource(UiR.string.set_auto_detect_note))

        SectionLabel(stringResource(UiR.string.settings_notes))
        TokenCard(Modifier.padding(horizontal = 16.dp)) { NotesEditor(s.translationNotes, vm) }
        Spacer(Modifier.height(10.dp))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            ActionRow(
                stringResource(UiR.string.set_glossary_title),
                stringResource(UiR.string.set_glossary_summary, glossary.series, glossary.terms), action = null,
            )
        }
        PageNote(stringResource(UiR.string.settings_notes_note))

        SectionLabel(stringResource(UiR.string.studio_role_sfx))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                SegmentedChoice(
                    listOf(SfxMode.SKIP, SfxMode.REPLACE), s.sfxMode,
                    label = { stringResource(if (it == SfxMode.SKIP) UiR.string.studio_sfx_keep else UiR.string.studio_sfx_replace) },
                    onPick = { vm.setSfxMode(it) },
                )
                Text(
                    stringResource(if (s.sfxMode == SfxMode.SKIP) UiR.string.studio_sfx_keep_note else UiR.string.set_sfx_replace_note),
                    style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun LanguageButton(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).background(Tokens.Bg).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 12.sp, color = Tokens.InkSoft)
        Text(value, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 16.sp, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Settings' notes, edited in place: saved half a second after typing stops and when the page closes; Reset puts the
 * shipped default back.
 */
@Composable
private fun NotesEditor(saved: String, vm: SettingsViewModel) {
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    val text = draft ?: saved
    LaunchedEffect(draft) {
        val d = draft ?: return@LaunchedEffect
        delay(500)
        vm.setTranslationNotes(d)
    }
    val latest by rememberUpdatedState(draft)
    DisposableEffect(Unit) { onDispose { latest?.let { vm.setTranslationNotes(it) } } }
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Text(stringResource(UiR.string.set_notes_who), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text, onValueChange = { draft = it.take(TranslationNotes.MAX_CHARS) },
            placeholder = { Text(stringResource(UiR.string.settings_notes_hint)) }, minLines = 3, maxLines = 8,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Tokens.Ink, unfocusedBorderColor = Tokens.Border, focusedTextColor = Tokens.Ink, unfocusedTextColor = Tokens.Ink,
                cursorColor = Tokens.Ink, focusedPlaceholderColor = Tokens.InkFaint, unfocusedPlaceholderColor = Tokens.InkFaint,
            ),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction(stringResource(UiR.string.set_notes_reset)) { draft = null; vm.resetTranslationNotes() }
            Spacer(Modifier.weight(1f))
            Text("${text.length} / ${TranslationNotes.MAX_CHARS}", style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
        }
    }
}

// ---- models & downloads ----------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelsPage(vm: SettingsViewModel, sheets: SettingsSheets, onBack: (() -> Unit)?) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val mangaOcr by vm.mangaOcrState.collectAsStateWithLifecycle()
    val lama by vm.lamaState.collectAsStateWithLifecycle()
    val packs by vm.packState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadStorage() }
    SettingsPageFrame(pageTitle(SettingsPage.MODELS), onBack) {
        storage?.let { st ->
            val sizes = vm.localModels.map { m -> m.label to ((vm.modelStates.getValue(m).collectAsStateWithLifecycle().value as? ModelState.Ready)?.sizeBytes ?: 0L) } +
                listOf(stringResource(UiR.string.model_manga_ocr) to ((mangaOcr as? ModelState.Ready)?.sizeBytes ?: 0L),
                    stringResource(UiR.string.model_lama) to ((lama as? ModelState.Ready)?.sizeBytes ?: 0L),
                    stringResource(UiR.string.packs_title) to st.packs)
            val used = st.models + st.packs
            val colors = listOf(Tokens.YellowDeep, Tokens.SkyDeep, Tokens.Orange, Tokens.Ink, Tokens.InkSoft)
            val shown = sizes.filter { it.second > 0 }
            TokenCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(UiR.string.set_on_phone), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 15.sp, color = Tokens.Ink, modifier = Modifier.weight(1f))
                        Text(
                            st.free?.let { stringResource(UiR.string.set_used_free, Formatter.formatShortFileSize(context, used), Formatter.formatShortFileSize(context, it)) }
                                ?: Formatter.formatShortFileSize(context, used),
                            fontWeight = FontWeight.W700, fontSize = 13.sp, color = Tokens.Ink,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    UsageBar(shown.mapIndexed { i, (_, b) -> (if (used > 0) b.toFloat() / used else 0f) to colors[i % colors.size] }, Tokens.Bg)
                    if (shown.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            shown.forEachIndexed { i, (name, b) -> LegendItem(colors[i % colors.size], "$name ${Formatter.formatShortFileSize(context, b)}") }
                        }
                    }
                }
            }
        }

        // Every model and language-pack download follows it; one already queued keeps the networks it was given.
        TokenCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            ToggleCardRow(
                stringResource(UiR.string.set_wifi_only), s.downloadWifiOnly,
                secondary = stringResource(UiR.string.set_wifi_only_note),
            ) { vm.setDownloadWifiOnly(it) }
        }

        SectionLabel(stringResource(UiR.string.set_models_translation))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            vm.localModels.forEachIndexed { i, m ->
                if (i > 0) CardDivider()
                val state by vm.modelStates.getValue(m).collectAsStateWithLifecycle()
                ModelRow(
                    title = m.label, name = "LiteRT-LM", sizeMb = (m.bytes / 1_048_576).toInt(), state = state,
                    missingNote = if (m.minRamGb > 0) stringResource(UiR.string.model_needs_ram, m.minRamGb) else null,
                    onDownload = { vm.downloadModel(m) }, onCancel = { vm.cancelDownload(m) }, onDelete = { vm.deleteModel(m) },
                )
                if (m == LocalModel.QWEN15) QwenBackendChoice(s.qwenBackend, vm)
            }
        }

        SectionLabel(stringResource(UiR.string.set_models_reading))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            ModelRow(
                stringResource(UiR.string.model_manga_ocr), "manga-ocr", 140, mangaOcr,
                readyNote = stringResource(UiR.string.model_manga_ready), missingNote = stringResource(UiR.string.model_manga_missing),
                onDownload = { vm.downloadMangaOcr() }, onCancel = { vm.cancelMangaOcr() }, onDelete = { vm.deleteMangaOcr() },
            )
            CardDivider()
            // LaMa needs 4 GB of RAM: on a smaller phone the row says so instead of offering the download.
            if (vm.lamaFits) ModelRow(
                stringResource(UiR.string.model_lama), "LaMa", 208, lama,
                readyNote = stringResource(UiR.string.model_lama_ready), missingNote = stringResource(UiR.string.model_lama_missing),
                onDownload = { vm.downloadLama() }, onCancel = { vm.cancelLama() }, onDelete = { vm.deleteLama() },
            ) else ActionRow(stringResource(UiR.string.model_lama), stringResource(UiR.string.model_lama_needs_ram, 4), null)
        }

        SectionLabel(stringResource(UiR.string.packs_title))
        TokenCard(Modifier.padding(horizontal = 16.dp)) { PacksRow(packs, onManage = { sheets.packs = true }) }
        PageNote(stringResource(UiR.string.set_models_checked))
    }
}

/** Qwen's backend as three choices, with what the current one means on this phone. */
@Composable
private fun QwenBackendChoice(current: QwenBackend, vm: SettingsViewModel) {
    val gpu = stringResource(UiR.string.qwen_backend_gpu); val cpu = stringResource(UiR.string.qwen_backend_cpu)
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
        Text(stringResource(UiR.string.qwen_backend), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
        Spacer(Modifier.height(6.dp))
        SegmentedChoice(
            QwenBackend.entries, current,
            label = { when (it) { QwenBackend.AUTO -> stringResource(UiR.string.set_backend_auto); QwenBackend.GPU -> gpu; QwenBackend.CPU -> cpu } },
            onPick = { vm.setQwenBackend(it) },
        )
        Text(
            when (current) {
                QwenBackend.AUTO -> stringResource(UiR.string.qwen_backend_auto, if (vm.qwenAutoOnCpu) cpu else gpu) + " · " +
                    stringResource(UiR.string.qwen_backend_auto_note, DeviceMemory.AUTO_GPU_MIN_GB)
                QwenBackend.GPU -> stringResource(UiR.string.qwen_backend_gpu_note)
                QwenBackend.CPU -> stringResource(UiR.string.qwen_backend_cpu_note)
            },
            style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ---- reader ----------------------------------------------------------------------------------

@Composable
private fun ReaderPage(vm: SettingsViewModel, sheets: SettingsSheets, onBack: (() -> Unit)?) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val pageTarget by vm.pageTarget.collectAsStateWithLifecycle()
    val update by vm.blockListUpdate.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadStorage() }
    SettingsPageFrame(pageTitle(SettingsPage.READER), onBack) {
        SectionLabel(stringResource(UiR.string.set_lettering))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                FontField(s.readerFontId, s.defaultTargetLang, FontRole.DIALOGUE) { sheets.font = true }
                Text(stringResource(UiR.string.settings_font_note), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(top = 8.dp))
            }
        }

        SectionLabel(stringResource(UiR.string.set_page_translate_section))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            ValueRow(stringResource(UiR.string.set_page_translate_into), packName(pageTarget), onClick = { sheets.pageTarget = true })
        }
        PageNote(stringResource(UiR.string.set_page_translate_note))

        SectionLabel(stringResource(UiR.string.section_browsing))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            ToggleCardRow(stringResource(UiR.string.block_ads), s.adBlockDefault, secondary = stringResource(UiR.string.set_block_ads_note)) { vm.setAdBlock(it) }
            CardDivider()
            ActionRow(
                stringResource(UiR.string.set_block_lists),
                when (update) {
                    BlockListUpdate.RUNNING -> stringResource(UiR.string.set_updating)
                    BlockListUpdate.FAILED -> stringResource(UiR.string.set_update_failed)
                    else -> blockListAge(s.blockListUpdatedAt)
                },
                action = if (update == BlockListUpdate.RUNNING) null else stringResource(UiR.string.set_update_now),
                onAction = { vm.updateBlockLists() },
                below = if (update == BlockListUpdate.RUNNING) ({
                    LinearProgressIndicator(color = Tokens.Yellow, trackColor = Tokens.Border, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp))
                }) else null,
            )
        }

        SectionLabel(stringResource(UiR.string.set_history_section))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            ActionRow(stringResource(UiR.string.settings_history), stringResource(UiR.string.settings_history_clear), stringResource(UiR.string.action_clear), Tokens.Error, onAction = { vm.clearHistory() })
            CardDivider()
            ActionRow(
                stringResource(UiR.string.set_cache_title),
                stringResource(UiR.string.set_cache_note, storage?.let { Formatter.formatShortFileSize(context, it.cache) } ?: "…"),
                stringResource(UiR.string.action_clear), Tokens.Error, onAction = { vm.clearPageCache() },
            )
        }
    }
}

// ---- studio ----------------------------------------------------------------------------------

@Composable
private fun StudioPage(vm: SettingsViewModel, onBack: (() -> Unit)?, onManageFonts: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    SettingsPageFrame(pageTitle(SettingsPage.STUDIO), onBack) {
        SectionLabel(stringResource(UiR.string.set_studio_background))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            ToggleCardRow(
                stringResource(UiR.string.studio_only_charging), s.studioOnlyWhileCharging,
                secondary = stringResource(UiR.string.studio_only_charging_note),
            ) { vm.setStudioOnlyWhileCharging(it) }
            CardDivider()
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(UiR.string.set_studio_waits), fontWeight = FontWeight.W700, fontSize = 13.sp, color = Tokens.Ink)
                WaitRule(Icons.Outlined.Thermostat, stringResource(UiR.string.set_wait_hot))
                WaitRule(Icons.Outlined.BatterySaver, stringResource(UiR.string.set_wait_battery))
                WaitRule(Icons.Outlined.WifiOff, stringResource(UiR.string.set_wait_network))
                WaitRule(Icons.AutoMirrored.Outlined.MenuBook, stringResource(UiR.string.set_wait_reader))
            }
        }

        SectionLabel(stringResource(UiR.string.set_export))
        val folderName by vm.exportFolderName.collectAsStateWithLifecycle()
        val folderRefused by vm.exportFolderRefused.collectAsStateWithLifecycle()
        var choosingFolder by rememberSaveable { mutableStateOf(false) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> if (tree != null) vm.setExportFolder(tree) }
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().clickable { choosingFolder = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(UiR.string.set_export_folder), style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink)
                    Text(stringResource(UiR.string.set_export_folder_note), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
                }
                // Downloads is a path on the phone, the same in every language; a picked folder shows its own name.
                Text(
                    if (s.studioExportFolder == null) ExportFolders.DOWNLOADS_DIR else folderName.orEmpty(),
                    fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 13.sp, color = Tokens.SkyDeep,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp).widthIn(max = 180.dp),
                )
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Tokens.InkFaint, modifier = Modifier.size(20.dp))
            }
            if (folderRefused) Text(
                stringResource(UiR.string.studio_export_folder_refused), style = MaterialTheme.typography.bodySmall, color = Tokens.Error,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }
        if (choosingFolder) {
            val downloads = stringResource(UiR.string.studio_export_downloads)
            val pick = stringResource(UiR.string.studio_export_choose_folder)
            ChoiceSheet(
                stringResource(UiR.string.studio_export_change_folder), listOf(false, true), s.studioExportFolder != null,
                render = { if (it) pick else downloads },
                onPick = { picked -> if (picked) runCatching { picker.launch(null) } else vm.setExportFolder(null) },
                onDismiss = { choosingFolder = false },
            )
        }

        SectionLabel(stringResource(UiR.string.settings_fonts))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onManageFonts).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(UiR.string.set_my_fonts), style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink)
                    Text(stringResource(UiR.string.set_my_fonts_note), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Tokens.InkFaint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun WaitRule(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Tokens.InkSoft, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = Tokens.Ink)
    }
}

// ---- storage ---------------------------------------------------------------------------------

@Composable
private fun StoragePage(vm: SettingsViewModel, sheets: SettingsSheets, onBack: (() -> Unit)?, onOpen: (SettingsPage) -> Unit) {
    val storage by vm.storage.collectAsStateWithLifecycle()
    val studioList by vm.studioStorage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadStorage() }
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)
    SettingsPageFrame(pageTitle(SettingsPage.STORAGE), onBack) {
        val st = storage
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Tokens.Ink).padding(18.dp)) {
            Text(stringResource(UiR.string.set_storage_uses), fontSize = 13.sp, color = Tokens.Card.copy(alpha = 0.75f))
            Text(st?.let { size(it.total) } ?: "…", fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 32.sp, color = Tokens.Card)
            if (st != null && st.total > 0) {
                Spacer(Modifier.height(12.dp))
                UsageBar(
                    listOf((st.models + st.packs).toFloat() / st.total to Tokens.Yellow, st.studio.toFloat() / st.total to Tokens.Sky, st.cache.toFloat() / st.total to Tokens.Card),
                    Tokens.Card.copy(alpha = 0.18f), height = 12,
                )
            }
            st?.free?.let {
                Text(stringResource(UiR.string.set_storage_free, size(it)), fontSize = 12.sp, color = Tokens.Card.copy(alpha = 0.75f), modifier = Modifier.padding(top = 10.dp))
            }
        }

        SectionLabel(stringResource(UiR.string.set_storage_breakdown))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            StorageLine(Tokens.Yellow, stringResource(UiR.string.set_storage_models),
                st?.let { stringResource(UiR.string.set_storage_packs, it.packCount, size(it.packs)) } ?: "…",
                st?.let { size(it.models + it.packs) }) { onOpen(SettingsPage.MODELS) }
            CardDivider()
            StorageLine(Tokens.Sky, stringResource(UiR.string.settings_studio_storage),
                studioList?.let { stringResource(UiR.string.set_storage_manga, it.size) } ?: "…", st?.let { size(it.studio) })
            CardDivider()
            StorageLine(Tokens.InkSoft, stringResource(UiR.string.set_cache_title), stringResource(UiR.string.set_storage_cache_note), st?.let { size(it.cache) })
        }

        StudioStorage(studioList, onDelete = { sheets.deleting = it })
        PageNote(stringResource(UiR.string.set_storage_delete_note))
    }
}

@Composable
private fun StorageLine(color: Color, title: String, subtitle: String, size: String?, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
        }
        Text(size ?: "…", fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, color = Tokens.Ink)
        if (onClick != null) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Tokens.InkFaint, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

// ---- appearance & language -------------------------------------------------------------------

@Composable
private fun AppearancePage(vm: SettingsViewModel, sheets: SettingsSheets, onBack: (() -> Unit)?) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appLang = remember { AppLocale.current(context) }
    SettingsPageFrame(pageTitle(SettingsPage.APPEARANCE), onBack) {
        SectionLabel(stringResource(UiR.string.settings_theme))
        ThemeGrid(selectedTheme = s.appTheme, onSelect = { vm.setTheme(it) }, modifier = Modifier.padding(horizontal = 16.dp))

        SectionLabel(stringResource(UiR.string.settings_app_language))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            // The current language and the first few others; the rest are one tap away in the full list.
            val shown = listOf(appLang) + AppLocale.tags.filter { it != appLang }.take(3)
            shown.forEachIndexed { i, tag ->
                if (i > 0) CardDivider()
                Row(
                    Modifier.fillMaxWidth().selectable(selected = tag == appLang, role = Role.RadioButton) { setAppLanguage(context, tag, appLang) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(nativeName(tag), style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink)
                        languageName(tag).takeIf { it != nativeName(tag) }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft) }
                    }
                    CheckDisc(tag == appLang)
                }
            }
            CardDivider()
            Row(Modifier.fillMaxWidth().clickable { sheets.appLang = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(UiR.string.set_all_languages, AppLocale.tags.size), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, color = Tokens.SkyDeep, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Tokens.InkFaint, modifier = Modifier.size(20.dp))
            }
        }
        PageNote(stringResource(UiR.string.set_app_language_note))
    }
}

// ---- about & updates -------------------------------------------------------------------------

/** Links the About page opens in the browser: this repository's pages (a fork's own, as updates do). */
internal object SettingsLinks {
    private val base = "https://github.com/" + ReleaseInfo.REPO
    val source: String get() = base
    fun release(version: String) = "$base/releases/tag/v$version"
    val issue: String get() = "$base/issues/new"
    /** Files on the default branch; relative links in the bundled documents resolve against it. */
    val blobBase: String get() = "$base/blob/main/"
    fun blob(path: String) = blobBase + path
}

/** Opens [url] in the browser; nothing happens when no app can. */
internal fun openUrl(context: android.content.Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { }
}

@Composable
private fun AboutPage(vm: SettingsViewModel, onBack: (() -> Unit)?, onOpen: (SettingsPage) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun open(url: String) = openUrl(context, url)
    SettingsPageFrame(pageTitle(SettingsPage.ABOUT), onBack) {
        TokenCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // The app's own logo, as on the Sites screen and the navigation rail.
                Image(
                    painter = painterResource(UiR.drawable.ic_panelglass_logo),
                    contentDescription = stringResource(UiR.string.cd_logo),
                    modifier = Modifier.size(72.dp),
                )
                // The name is never translated.
                Text("Panelglass", fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 22.sp, color = Tokens.Ink, modifier = Modifier.padding(top = 12.dp))
                Text(stringResource(UiR.string.update_version, vm.appVersion), style = MaterialTheme.typography.bodyMedium, color = Tokens.InkSoft)
                Spacer(Modifier.height(14.dp))
                PrimaryPill(stringResource(UiR.string.update_check), onClick = { vm.checkForUpdates() })
            }
        }

        SectionLabel(stringResource(UiR.string.set_updates))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            ToggleCardRow(stringResource(UiR.string.update_auto), s.autoUpdateCheck, secondary = stringResource(UiR.string.update_auto_note)) { vm.setAutoUpdateCheck(it) }
            CardDivider()
            LinkRow(stringResource(UiR.string.set_whats_new, vm.appVersion), stringResource(UiR.string.set_whats_new_note)) { open(SettingsLinks.release(vm.appVersion)) }
        }

        SectionLabel(stringResource(UiR.string.set_project))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            LinkRow(stringResource(UiR.string.set_source), "github.com/" + ReleaseInfo.REPO) { open(SettingsLinks.source) }
            CardDivider()
            LinkRow(stringResource(UiR.string.set_report), stringResource(UiR.string.set_report_note)) { open(SettingsLinks.issue) }
            CardDivider()
            LinkRow(stringResource(UiR.string.set_licences), stringResource(UiR.string.set_licences_note), external = false) { onOpen(SettingsPage.LICENCES) }
            CardDivider()
            LinkRow(stringResource(UiR.string.set_privacy), stringResource(UiR.string.set_privacy_note), external = false) { onOpen(SettingsPage.PRIVACY) }
        }
    }
}

/** A row that opens a page: in the browser when [external], else inside Settings (a chevron). */
@Composable
private fun LinkRow(title: String, subtitle: String, external: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
        }
        Icon(
            if (external) Icons.AutoMirrored.Outlined.OpenInNew else Icons.Filled.ChevronRight, contentDescription = null,
            tint = Tokens.InkFaint, modifier = Modifier.size(if (external) 18.dp else 20.dp),
        )
    }
}
