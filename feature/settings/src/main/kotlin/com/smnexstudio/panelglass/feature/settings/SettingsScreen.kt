package com.smnexstudio.panelglass.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import com.smnexstudio.panelglass.core.ui.AppLocale
import com.smnexstudio.panelglass.core.ui.languageName
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.smnexstudio.panelglass.core.engine.local.DeviceMemory
import com.smnexstudio.panelglass.core.engine.local.LocalModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.smnexstudio.panelglass.core.ui.R as UiR
import com.smnexstudio.panelglass.core.ui.uiName
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.engine.mt.GoogleTranslateEngine
import com.smnexstudio.panelglass.core.engine.mt.LanguagePackStore
import com.smnexstudio.panelglass.core.engine.mt.PackState
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.ModelState
import com.smnexstudio.panelglass.core.model.QwenBackend
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.model.BubbleFont
import com.smnexstudio.panelglass.core.ui.ActionRow
import com.smnexstudio.panelglass.core.ui.ApiKeyDialog
import com.smnexstudio.panelglass.core.ui.CardDivider
import com.smnexstudio.panelglass.core.ui.CheckDisc
import com.smnexstudio.panelglass.core.ui.ChoiceSheet
import com.smnexstudio.panelglass.core.ui.EnginePickerSheet
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.KeyedEngineState
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.SectionLabel
import com.smnexstudio.panelglass.core.ui.SparkleIcon
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.ToggleCardRow
import com.smnexstudio.panelglass.core.ui.TokenCard
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.ValueRow

import com.smnexstudio.panelglass.core.model.AppTheme
import com.smnexstudio.panelglass.core.ui.LocalAppTheme
import com.smnexstudio.panelglass.core.ui.themeBackground
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.offset

/** @param openKeyFor an `EngineId` name whose key dialog opens on arrival (the reader hands off here). */
@Composable
fun SettingsScreen(openKeyFor: String? = null, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val mangaOcr by vm.mangaOcrState.collectAsStateWithLifecycle()
    val packs by vm.packState.collectAsStateWithLifecycle()
    val tryState by vm.tryState.collectAsStateWithLifecycle()
    val needsKey by vm.needsKey.collectAsStateWithLifecycle()

    var keyDialogFor by remember { mutableStateOf<EngineId?>(null) }
    var enginePicker by remember { mutableStateOf(false) }
    var srcPicker by remember { mutableStateOf(false) }
    var tgtPicker by remember { mutableStateOf(false) }
    var appLangPicker by remember { mutableStateOf(false) }
    var backendPicker by remember { mutableStateOf(false) }
    val gpu = stringResource(UiR.string.qwen_backend_gpu); val cpu = stringResource(UiR.string.qwen_backend_cpu)
    // Automatic names what it picks on this phone ("Automatic · GPU").
    val backendNames = mapOf(
        QwenBackend.AUTO to stringResource(UiR.string.qwen_backend_auto, if (vm.qwenAutoOnCpu) cpu else gpu),
        QwenBackend.GPU to gpu, QwenBackend.CPU to cpu,
    )
    val backendNotes = mapOf(
        QwenBackend.AUTO to stringResource(UiR.string.qwen_backend_auto_note, DeviceMemory.AUTO_GPU_MIN_GB),
        QwenBackend.GPU to stringResource(UiR.string.qwen_backend_gpu_note),
        QwenBackend.CPU to stringResource(UiR.string.qwen_backend_cpu_note),
    )
    val context = LocalContext.current
    val appLang = remember { AppLocale.current(context) }

    // Picking a keyed engine without a key routes here: open the dialog, and the selection completes on save.
    LaunchedEffect(needsKey) { needsKey?.let { keyDialogFor = it } }
    LaunchedEffect(openKeyFor) { openKeyFor?.let { name -> runCatching { EngineId.valueOf(name) }.getOrNull()?.let { vm.selectEngine(it) } } }

    Column(Modifier.fillMaxSize().themeBackground(LocalAppTheme.current).verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Text(stringResource(UiR.string.settings_title), style = MaterialTheme.typography.headlineSmall, color = Tokens.Ink, modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 0.dp))

        // ---- translation: one grouped card ------------------------------------------------------
        SectionLabel(stringResource(UiR.string.section_translation))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            ValueRow(stringResource(UiR.string.lang_source), s.defaultSourceLang.uiName(), onClick = { srcPicker = true }) {
                Icon(Icons.Outlined.Translate, contentDescription = null, tint = Tokens.InkSoft, modifier = Modifier.size(20.dp))
            }
            CardDivider()
            ValueRow(stringResource(UiR.string.lang_target), s.defaultTargetLang.uiName(), onClick = { tgtPicker = true }) {
                Icon(Icons.Outlined.Language, contentDescription = null, tint = Tokens.InkSoft, modifier = Modifier.size(20.dp))
            }
            CardDivider()
            ValueRow(stringResource(UiR.string.settings_translation_model), s.engineId.uiName(), onClick = { enginePicker = true }) { SparkleIcon() }
        }

        // ---- try -------------------------------------------------------------------------------
        SectionLabel(stringResource(UiR.string.settings_try))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            TryBox(tryState, onInput = { vm.setTryInput(it) }, onRun = { vm.tryTranslate() }, onAddKey = { keyDialogFor = it }, onUseOnDevice = { vm.selectEngine(EngineId.QWEN15_LOCAL) })
        }

        // ---- app language ----------------------------------------------------------------------
        SectionLabel(stringResource(UiR.string.settings_app_language))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            ValueRow(stringResource(UiR.string.app_language), nativeName(appLang), onClick = { appLangPicker = true }) {
                Icon(Icons.Outlined.Language, contentDescription = null, tint = Tokens.InkSoft, modifier = Modifier.size(20.dp))
            }
        }

        // ---- theme grid ------------------------------------------------------------------------
        SectionLabel(stringResource(UiR.string.settings_theme))
        ThemeGrid(
            selectedTheme = s.appTheme,
            onSelect = { vm.setTheme(it) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // ---- fonts -----------------------------------------------------------------------------
        SectionLabel(stringResource(UiR.string.settings_fonts))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            BubbleFont.entries.forEachIndexed { i, f ->
                if (i > 0) CardDivider()
                FontRow(f, selected = s.bubbleFont == f) { vm.setFont(f) }
            }
        }

        // ---- models ----------------------------------------------------------------------------
        SectionLabel(stringResource(UiR.string.settings_models))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            vm.localModels.forEachIndexed { i, m ->
                if (i > 0) CardDivider()
                val state by vm.modelStates.getValue(m).collectAsState()
                ModelRow(
                    title = m.label,
                    name = "LiteRT-LM",
                    sizeMb = (m.bytes / 1_048_576).toInt(), state = state,
                    missingNote = if (m.minRamGb > 0) stringResource(UiR.string.model_needs_ram, m.minRamGb) else null,
                    onDownload = { vm.downloadModel(m) },
                    onCancel = { vm.cancelDownload(m) }, onDelete = { vm.deleteModel(m) },
                )
                if (m == LocalModel.QWEN15) {
                    CardDivider()
                    // Same shape as the model rows around it (ActionRow), plus the chevron of a row that opens choices.
                    Row(
                        Modifier.fillMaxWidth().clickable { backendPicker = true }.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(UiR.string.qwen_backend), style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink)
                            Text(backendNotes.getValue(s.qwenBackend), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(top = 2.dp))
                        }
                        Text(
                            backendNames.getValue(s.qwenBackend), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp,
                            color = Tokens.SkyDeep, maxLines = 1, modifier = Modifier.padding(start = 10.dp),
                        )
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Tokens.InkFaint, modifier = Modifier.padding(start = 2.dp).size(20.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            ModelRow(
                stringResource(UiR.string.model_manga_ocr), "manga-ocr", 140, mangaOcr,
                readyNote = stringResource(UiR.string.model_manga_ready), missingNote = stringResource(UiR.string.model_manga_missing),
                onDownload = { vm.downloadMangaOcr() }, onCancel = { vm.cancelMangaOcr() }, onDelete = { vm.deleteMangaOcr() },
            )
        }
        Spacer(Modifier.height(12.dp))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            PacksRow(packs, onDownload = { vm.downloadPacks() }, onCancel = { vm.cancelPacks() }, onDelete = { vm.deletePacks() })
        }
        Text(
            stringResource(UiR.string.settings_keys_note),
            style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(16.dp, 10.dp),
        )

        // ---- browsing / storage ----------------------------------------------------------------
        SectionLabel(stringResource(UiR.string.section_browsing))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            ToggleCardRow(stringResource(UiR.string.block_ads), s.adBlockDefault, secondary = blockListAge(s.blockListUpdatedAt)) { vm.setAdBlock(it) }
        }
        SectionLabel(stringResource(UiR.string.settings_storage))
        TokenCard(Modifier.padding(horizontal = 16.dp)) {
            ActionRow(stringResource(UiR.string.settings_history), stringResource(UiR.string.settings_history_clear), stringResource(UiR.string.action_clear), Tokens.Error, onAction = { vm.clearHistory() })
        }
    }

    if (backendPicker) ChoiceSheet(
        stringResource(UiR.string.qwen_backend), QwenBackend.entries, s.qwenBackend,
        render = { backendNames.getValue(it) },
        onPick = { vm.setQwenBackend(it) }, onDismiss = { backendPicker = false },
        subtitle = { backendNotes.getValue(it) },
    )
    if (srcPicker) ChoiceSheet(stringResource(UiR.string.lang_source), Lang.sourceChoices, s.defaultSourceLang, { it.uiName() }, onPick = { vm.setSrc(it) }, onDismiss = { srcPicker = false })
    if (tgtPicker) ChoiceSheet(stringResource(UiR.string.lang_target), Lang.targetChoices, s.defaultTargetLang, { it.uiName() }, onPick = { vm.setTgt(it) }, onDismiss = { tgtPicker = false })
    // Each language in its own name (as the system's picker does), the UI language's name for it underneath.
    if (appLangPicker) ChoiceSheet(
        stringResource(UiR.string.app_language), AppLocale.tags, appLang, { nativeName(it) },
        onPick = { tag -> if (tag != appLang) context.findActivity()?.let { AppLocale.set(it, tag) } },
        onDismiss = { appLangPicker = false },
        subtitle = { tag -> languageName(tag).takeIf { it != nativeName(tag) } },
    )
    val readyEngines = vm.localModels.filter { vm.modelStates.getValue(it).collectAsState().value is ModelState.Ready }
        .map { it.engineId }.toSet()
    if (enginePicker) EnginePickerSheet(
        selected = s.engineId,
        keyed = { e -> KeyedEngineState(hasKey = e in keys, model = s.modelFor(e)) },
        onSelect = { vm.selectEngine(it) },
        // Check disc on a keyed engine without a key: selectEngine records the pending choice, opens the dialog, and
        // saving the key completes the selection.
        onNeedsKey = { e -> enginePicker = false; vm.selectEngine(e) },
        // Name tap: the key + model dialog over the sheet; the selection is untouched.
        onConfigure = { e -> keyDialogFor = e },
        onDismiss = { enginePicker = false },
        hidden = vm.hiddenEngines,
        ready = readyEngines,
    )
    keyDialogFor?.let { engine ->
        val choices by vm.modelChoices.collectAsStateWithLifecycle()
        ApiKeyDialog(
            engine = engine,
            hasKey = engine in keys,
            currentModel = s.modelFor(engine),
            onSave = { key, model -> vm.saveEngineConfig(engine, key, model) },
            onRemove = if (engine in keys) ({ vm.removeKey(engine) }) else null,
            onDismiss = { keyDialogFor = null; vm.clearNeedsKey() },
            modelChoices = choices.models,
            modelsLoading = choices.loading,
            modelsError = choices.error,
            onLoadModels = if (engine == EngineId.GEMINI) ({ typed -> vm.loadModels(engine, typed) }) else null,
        )
    }
}

private fun nativeName(tag: String) = languageName(tag, Locale.forLanguageTag(tag))

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun blockListAge(updatedAt: Long): String {
    if (updatedAt <= 0) return stringResource(UiR.string.blocklist_none)
    val h = ((System.currentTimeMillis() - updatedAt) / 3_600_000).toInt()
    return if (h < 1) stringResource(UiR.string.blocklist_now) else pluralStringResource(UiR.plurals.blocklist_hours, h, h)
}

@Composable
private fun mb(bytes: Long): String = stringResource(UiR.string.size_mb, Math.round(bytes / 1048576.0).toInt())

/** Joins a subtitle with an optional note, " · " between them. */
private fun withNote(text: String, note: String?) = if (note == null) text else "$text · $note"

/**
 * The download layer's status notes and failure reasons ([SystemDownloads], the model stores) are English
 * constants; this shows them in the UI's language. Anything unrecognised (an HTTP code) is shown as it is.
 */
@Composable
private fun downloadText(text: String): String = when (text) {
    SystemDownloads.CHECKING -> stringResource(UiR.string.download_checking)
    SystemDownloads.CORRUPT -> stringResource(UiR.string.download_corrupt)
    "waiting for network" -> stringResource(UiR.string.download_waiting_network)
    "connection lost, retrying" -> stringResource(UiR.string.download_retrying)
    "waiting for Wi-Fi" -> stringResource(UiR.string.download_waiting_wifi)
    "paused" -> stringResource(UiR.string.download_paused)
    "Not enough storage" -> stringResource(UiR.string.download_no_space)
    "Storage unavailable" -> stringResource(UiR.string.download_no_storage)
    "Server refused the download" -> stringResource(UiR.string.download_refused)
    "Connection failed; tap Retry" -> stringResource(UiR.string.download_connection_failed)
    "Download incomplete; tap Retry" -> stringResource(UiR.string.download_incomplete)
    "Download failed" -> stringResource(UiR.string.download_failed)
    else -> text
}

// ---- model rows ------------------------------------------------------------------------------

/** Download / cancel / delete row for a downloadable model, with a progress bar while fetching. */
@Composable
private fun ModelRow(
    title: String, name: String, sizeMb: Int, state: ModelState,
    readyNote: String? = null, missingNote: String? = null,
    onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
) {
    val subtitle = when (state) {
        ModelState.Missing -> withNote(stringResource(UiR.string.model_missing, name, stringResource(UiR.string.size_mb, sizeMb)), missingNote)
        is ModelState.Downloading -> stringResource(
            UiR.string.model_downloading, name,
            state.note?.let { downloadText(it) } ?: stringResource(UiR.string.download_downloading), mb(state.bytes), mb(state.total),
        )
        is ModelState.Ready -> withNote(stringResource(UiR.string.model_ready, name, mb(state.sizeBytes)), readyNote)
        is ModelState.Failed -> stringResource(UiR.string.model_failed, name, downloadText(state.reason))
    }
    val (action, color, handler) = when (state) {
        ModelState.Missing -> Triple(stringResource(UiR.string.action_download), Tokens.SkyDeep, onDownload)
        is ModelState.Failed -> Triple(stringResource(UiR.string.action_retry), Tokens.SkyDeep, onDownload)
        is ModelState.Downloading -> Triple(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onCancel)
        is ModelState.Ready -> Triple(stringResource(UiR.string.action_delete), Tokens.Error, onDelete)
    }
    ActionRow(title, subtitle, action, color, onAction = handler, below = if (state is ModelState.Downloading) ({
        val fraction = if (state.total > 0) (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { fraction }, color = Tokens.Yellow, trackColor = Tokens.Border, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp))
    }) else null)
}

/** Google Translate (ML Kit) language packs for every supported language, ~30 MB each. */
@Composable
private fun PacksRow(p: PackState, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    val subtitle = when {
        p.downloading -> stringResource(UiR.string.packs_downloading, p.current?.let { packName(it) } ?: "", p.downloaded.size, p.total)
        p.failed != null -> stringResource(UiR.string.packs_failed, p.failed!!, p.downloaded.size, p.total)
        p.complete -> stringResource(UiR.string.packs_complete, p.total)
        p.downloaded.isEmpty() -> stringResource(UiR.string.packs_none)
        else -> stringResource(UiR.string.packs_some, p.downloaded.size, p.total)
    }
    val (action, color, handler) = when {
        p.downloading -> Triple(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onCancel)
        p.complete -> Triple(stringResource(UiR.string.action_delete), Tokens.Error, onDelete)
        p.failed != null -> Triple(stringResource(UiR.string.action_retry), Tokens.SkyDeep, onDownload)
        else -> Triple(stringResource(UiR.string.packs_download_all), Tokens.SkyDeep, onDownload)
    }
    ActionRow(stringResource(UiR.string.packs_title), subtitle, action, color, onAction = handler, below = if (p.downloading) ({
        val fraction = if (p.total > 0) p.downloaded.size.toFloat() / p.total else 0f
        LinearProgressIndicator(progress = { fraction }, color = Tokens.Yellow, trackColor = Tokens.Border, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp))
    }) else null)
}

// ---- try box ---------------------------------------------------------------------------------

@Composable
private fun TryBox(t: TryState, onInput: (String) -> Unit, onRun: () -> Unit, onAddKey: (EngineId) -> Unit, onUseOnDevice: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        OutlinedTextField(
            value = t.input, onValueChange = onInput, minLines = 2, maxLines = 5,
            label = { Text(stringResource(UiR.string.try_source_text)) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Tokens.Ink, unfocusedBorderColor = Tokens.Border, focusedTextColor = Tokens.Ink, unfocusedTextColor = Tokens.Ink,
                cursorColor = Tokens.Ink, focusedLabelColor = Tokens.InkSoft, unfocusedLabelColor = Tokens.InkFaint,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryPill(stringResource(UiR.string.try_translate), onClick = onRun, enabled = !t.running && t.input.isNotBlank())
            if (t.running) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Tokens.Ink) }
        }
        t.output?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink, modifier = Modifier.fillMaxWidth().background(Tokens.Bg, RoundedCornerShape(14.dp)).padding(12.dp))
            t.via?.let { via -> Text(stringResource(UiR.string.try_via, via), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(top = 4.dp)) }
        }
        t.failure?.let { f ->
            Spacer(Modifier.height(8.dp))
            val (msg, action) = failureText(f)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(msg, style = MaterialTheme.typography.bodySmall, color = Tokens.Error, modifier = Modifier.weight(1f))
                when (action) {
                    Action.ADD_KEY -> TextAction(stringResource(UiR.string.action_add_key), Tokens.SkyDeep) { onAddKey(f.engine) }
                    Action.USE_ON_DEVICE -> TextAction(stringResource(UiR.string.action_use_on_device), Tokens.SkyDeep, onUseOnDevice)
                    Action.NONE -> Unit
                }
            }
        }
    }
}

private enum class Action { ADD_KEY, USE_ON_DEVICE, NONE }

/** A Google Translate pack's language, named in the UI's language. */
private fun packName(tag: String): String =
    Lang.entries.firstOrNull { GoogleTranslateEngine.tag(it) == tag }?.uiName() ?: LanguagePackStore.displayName(tag)

@Composable
private fun failureText(f: EngineFailure): Pair<String, Action> {
    val name = f.engine.uiName()
    // The provider's own reason is appended as it came: it is the provider's text, not ours.
    @Composable fun withReason(res: Int, reason: String): String =
        stringResource(res, name).let { if (reason.isEmpty()) it else stringResource(UiR.string.error_with_reason, it, reason) }
    return when (f) {
        is EngineFailure.MissingKey -> stringResource(UiR.string.error_needs_key, name) to Action.ADD_KEY
        is EngineFailure.ModelMissing -> stringResource(UiR.string.error_model_missing) to Action.NONE
        is EngineFailure.QuotaExceeded -> stringResource(UiR.string.error_quota, name) to Action.USE_ON_DEVICE
        is EngineFailure.RateLimited -> stringResource(UiR.string.error_rate_limited, name) to Action.NONE
        is EngineFailure.Overloaded -> withReason(UiR.string.error_overloaded, f.reason) to Action.USE_ON_DEVICE
        is EngineFailure.Malformed -> stringResource(UiR.string.error_malformed, name) to Action.USE_ON_DEVICE
        is EngineFailure.Network -> stringResource(UiR.string.error_network, name) to Action.USE_ON_DEVICE
        is EngineFailure.Unavailable -> withReason(UiR.string.error_unavailable, f.reason) to Action.USE_ON_DEVICE
    }
}

@Composable
private fun FontRow(font: BubbleFont, selected: Boolean, onClick: () -> Unit) {
    val family = when (font) {
        BubbleFont.PLUS_JAKARTA_SANS -> Jakarta
        BubbleFont.COMING_SOON -> FontFamily(Font(com.smnexstudio.panelglass.core.ui.R.font.coming_soon))
        BubbleFont.LUCKIEST_GUY -> FontFamily(Font(com.smnexstudio.panelglass.core.ui.R.font.luckiest_guy))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) Tokens.YellowTint else Tokens.Card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = font.label,
            fontFamily = family,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Tokens.Ink,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        CheckDisc(selected)
    }
}

@Composable
private fun ThemeGrid(
    selectedTheme: AppTheme,
    onSelect: (AppTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val rows = AppTheme.entries.chunked(2)
        for (row in rows) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (theme in row) {
                    ThemePreviewCard(
                        theme = theme,
                        selected = theme == selectedTheme,
                        onClick = { onSelect(theme) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ThemePreviewCard(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTheme = LocalAppTheme.current
    val palette = when (theme) {
        AppTheme.DEFAULT -> listOf(Color(0xFFFCC336), Color(0xFFF7F5EF), Color(0xFF0A1019), Color(0xFF5BA9C7))
        AppTheme.PANEL_POP -> listOf(Color(0xFFFFD23F), Color(0xFFFF3B30), Color(0xFF2F6FED), Color(0xFF111318))
        AppTheme.SOFT_BLOOM -> listOf(Color(0xFFB7A8F0), Color(0xFFA6E3C4), Color(0xFFFFC9B3), Color(0xFF2E2140))
        AppTheme.PAPER_INK -> listOf(Color(0xFF3A4562), Color(0xFFF7F5F0), Color(0xFF211E1A), Color(0xFF6B6459))
    }

    val cardShape = RoundedCornerShape(14.dp)
    val previewShape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .clip(cardShape)
            .background(Tokens.Card)
            .then(
                if (selected) {
                    Modifier.border(2.dp, Tokens.Yellow, cardShape)
                } else {
                    Modifier.border(1.dp, Tokens.Border, cardShape)
                },
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(138.dp)
                .clip(previewShape)
                .border(1.dp, if (theme == AppTheme.PANEL_POP) Color(0xFF111318) else Tokens.Border, previewShape),
        ) {
            MiniThemeScreen(theme = theme)

            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Tokens.Yellow)
                        .then(if (currentTheme == AppTheme.PANEL_POP) Modifier.border(1.5.dp, Color(0xFF111318), CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(UiR.string.cd_selected),
                        tint = if (currentTheme == AppTheme.PANEL_POP) Color(0xFF111318) else if (currentTheme == AppTheme.SOFT_BLOOM || currentTheme == AppTheme.PAPER_INK) Color.White else Tokens.Ink,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = theme.uiName(),
                fontFamily = if (theme == AppTheme.PAPER_INK) FontFamily.Serif else Jakarta,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                palette.forEach { c ->
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(0.5.dp, Color(0x33000000), CircleShape),
                    )
                }
            }
        }

        Text(
            text = when (theme) {
                AppTheme.DEFAULT -> stringResource(UiR.string.theme_tag_default)
                AppTheme.PANEL_POP -> stringResource(UiR.string.theme_tag_panel_pop)
                AppTheme.SOFT_BLOOM -> stringResource(UiR.string.theme_tag_soft_bloom)
                AppTheme.PAPER_INK -> stringResource(UiR.string.theme_tag_paper_ink)
            },
            fontFamily = Jakarta,
            fontSize = 11.sp,
            fontWeight = FontWeight.W500,
            color = Tokens.InkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun MiniThemeScreen(theme: AppTheme) {
    when (theme) {
        AppTheme.PANEL_POP -> {
            Column(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(Color(0xFFFFF6E9))
                        val dotRadiusPx = 1.dp.toPx()
                        val stepPx = 8.dp.toPx()
                        var y = stepPx / 2
                        var row = 0
                        while (y < size.height) {
                            val xOffset = if (row % 2 == 1) stepPx / 2 else 0f
                            var x = xOffset
                            while (x < size.width) {
                                drawCircle(color = Color(0x22111318), radius = dotRadiusPx, center = Offset(x, y))
                                x += stepPx
                            }
                            y += stepPx * 0.866f
                            row++
                        }
                    }
                    .padding(horizontal = 7.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(13.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFFFFD23F))
                            .border(1.2.dp, Color(0xFF111318), RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Panelglass", fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 8.5.sp, color = Color(0xFF111318))
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(15.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White)
                        .border(1.2.dp, Color(0xFF111318), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.width(26.dp).height(2.5.dp).background(Color(0xFF8C909A), RoundedCornerShape(1.dp)))
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFFFF3B30))
                        .border(1.2.dp, Color(0xFF111318), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(stringResource(UiR.string.library_pinned).uppercase(), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 6.sp, color = Color.White)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().padding(end = 2.dp, bottom = 2.dp)) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .offset(x = 2.dp, y = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF111318)),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White)
                            .border(1.2.dp, Color(0xFF111318), RoundedCornerShape(4.dp))
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(15.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFFFF3B30))
                                .border(1.dp, Color(0xFF111318), RoundedCornerShape(3.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("R", fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 7.5.sp, color = Color.White)
                        }
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.width(34.dp).height(2.5.dp).background(Color(0xFF111318), RoundedCornerShape(1.dp)))
                            Spacer(Modifier.height(2.dp))
                            Box(Modifier.width(22.dp).height(2.dp).background(Color(0xFF4A4E58), RoundedCornerShape(1.dp)))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .background(Color(0xFFFFF6E9))
                        .border(width = 1.2.dp, color = Color(0xFF111318)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 22.dp, height = 10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFFFFD23F))
                            .border(1.dp, Color(0xFF111318), RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
        AppTheme.SOFT_BLOOM -> {
            Column(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(Color(0xFFFBF6FF))
                        val glow = Brush.radialGradient(
                            colors = listOf(Color(0x35E2D4FB), Color(0x20FFD8C9), Color.Transparent),
                            center = Offset(size.width * 0.85f, 0f),
                            radius = size.width,
                        )
                        drawRect(glow)
                    }
                    .padding(horizontal = 7.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(11.dp).clip(CircleShape).background(Color(0xFFB7A8F0)))
                    Spacer(Modifier.width(4.dp))
                    Text("Panelglass", fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 8.5.sp, color = Color(0xFF2E2140))
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(15.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFEFE7FB), RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.width(30.dp).height(2.5.dp).background(Color(0xFFD6CEE8), RoundedCornerShape(1.dp)))
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(4.dp).clip(CircleShape).background(Color(0xFFB7A8F0)))
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(UiR.string.library_pinned), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 6.5.sp, color = Color(0xFFB7A8F0))
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFEFE7FB), RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(15.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFFA6E3C4)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("R", fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 7.5.sp, color = Color(0xFF2E2140))
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.width(34.dp).height(2.5.dp).background(Color(0xFF2E2140), RoundedCornerShape(1.dp)))
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.width(20.dp).height(2.dp).background(Color(0xFF7B6E93), RoundedCornerShape(1.dp)))
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .width(52.dp)
                            .height(15.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .border(1.dp, Color(0xFFEFE7FB), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFB7A8F0)))
                    }
                }
            }
        }
        AppTheme.PAPER_INK -> {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF7F5F0))
                    .padding(horizontal = 7.dp, vertical = 6.dp),
            ) {
                Text("Panelglass", fontFamily = FontFamily.Serif, fontWeight = FontWeight.W800, fontSize = 9.5.sp, color = Color(0xFF211E1A))
                Text(stringResource(UiR.string.library_subtitle), fontFamily = Jakarta, fontSize = 5.5.sp, color = Color(0xFFA39C8E))
                Spacer(Modifier.height(4.dp))
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(32.dp).height(2.5.dp).background(Color(0xFFA39C8E), RoundedCornerShape(1.dp)))
                    Spacer(Modifier.height(3.dp))
                    HorizontalDivider(thickness = 1.dp, color = Color(0xFFE4DFD3))
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(UiR.string.library_pinned), fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 6.5.sp, color = Color(0xFF6B6459))
                Spacer(Modifier.height(3.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.width(36.dp).height(2.5.dp).background(Color(0xFF211E1A), RoundedCornerShape(1.dp)))
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.width(24.dp).height(2.dp).background(Color(0xFF6B6459), RoundedCornerShape(1.dp)))
                    }
                    Box(Modifier.size(4.dp).clip(CircleShape).background(Color(0xFF3A4562)))
                }
                Spacer(Modifier.height(3.dp))
                HorizontalDivider(thickness = 1.dp, color = Color(0xFFE4DFD3))
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    HorizontalDivider(thickness = 1.dp, color = Color(0xFFE4DFD3))
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(UiR.string.nav_sites), fontFamily = FontFamily.Serif, fontSize = 6.sp, fontWeight = FontWeight.W700, color = Color(0xFF211E1A))
                    Spacer(Modifier.height(1.dp))
                    Box(Modifier.width(13.dp).height(1.5.dp).background(Color(0xFF3A4562)))
                }
            }
        }
        AppTheme.DEFAULT -> {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF7F5EF))
                    .padding(horizontal = 7.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFFCC336)))
                    Spacer(Modifier.width(4.dp))
                    Text("Panelglass", fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 8.5.sp, color = Color(0xFF0A1019))
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(15.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFEAE6DA), RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.width(30.dp).height(2.5.dp).background(Color(0xFF9AA2AD), RoundedCornerShape(1.dp)))
                }
                Spacer(Modifier.height(5.dp))
                Text(stringResource(UiR.string.library_pinned).uppercase(), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 6.sp, color = Color(0xFF9AA2AD))
                Spacer(Modifier.height(3.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFEAE6DA), RoundedCornerShape(6.dp))
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(15.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFFE9B8)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("R", fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 7.5.sp, color = Color(0xFF0A1019))
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.width(34.dp).height(2.5.dp).background(Color(0xFF0A1019), RoundedCornerShape(1.dp)))
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.width(20.dp).height(2.dp).background(Color(0xFF5B6472), RoundedCornerShape(1.dp)))
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .background(Color.White)
                        .border(width = 1.dp, color = Color(0xFFEAE6DA)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(width = 18.dp, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFCC336)))
                }
            }
        }
    }
}


