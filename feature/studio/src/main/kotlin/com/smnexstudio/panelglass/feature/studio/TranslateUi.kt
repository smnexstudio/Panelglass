package com.smnexstudio.panelglass.feature.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.engine.mt.GoogleTranslateEngine
import com.smnexstudio.panelglass.core.engine.mt.LanguagePackStore
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.StudioStage
import com.smnexstudio.panelglass.core.ui.CardDivider
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.TokenCard
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.uiName
import com.smnexstudio.panelglass.feature.studio.translate.ChapterRun
import com.smnexstudio.panelglass.feature.studio.translate.RunPhase
import com.smnexstudio.panelglass.feature.studio.translate.StudioTranslator
import java.io.File
import com.smnexstudio.panelglass.core.ui.R as UiR

/** A Google Translate pack's language, named in the UI's language. */
private fun packName(tag: String): String =
    Lang.entries.firstOrNull { GoogleTranslateEngine.tag(it) == tag }?.uiName() ?: LanguagePackStore.displayName(tag)

/** What went wrong, in the app's words (the same messages Settings and the reader use). */
@Composable
internal fun failureText(f: EngineFailure): String {
    val name = f.engine.uiName()
    @Composable fun withReason(res: Int, reason: String): String =
        stringResource(res, name).let { if (reason.isEmpty()) it else stringResource(UiR.string.error_with_reason, it, reason) }
    return when (f) {
        is EngineFailure.MissingKey -> stringResource(UiR.string.error_needs_key, name)
        is EngineFailure.ModelMissing -> stringResource(UiR.string.error_model_missing)
        is EngineFailure.PackMissing -> stringResource(UiR.string.error_pack_missing, f.tags.joinToString(", ") { packName(it) })
        is EngineFailure.QuotaExceeded -> stringResource(UiR.string.error_quota, name)
        is EngineFailure.RateLimited -> stringResource(UiR.string.error_rate_limited, name)
        is EngineFailure.Overloaded -> withReason(UiR.string.error_overloaded, f.reason)
        is EngineFailure.Malformed -> stringResource(UiR.string.error_malformed, name)
        is EngineFailure.Network -> stringResource(UiR.string.error_network, name)
        is EngineFailure.Unavailable -> when {
            f.reason == StudioTranslator.UNREADABLE_PAGE -> stringResource(UiR.string.studio_error_unreadable)
            f.reason.startsWith("Watchdog") -> stringResource(UiR.string.error_too_long, name)
            else -> withReason(UiR.string.error_unavailable, f.reason)
        }
    }
}

/**
 * The chapter's translation: which engine and languages, how far it got, and the one action that fits (Translate,
 * Continue, Cancel, or what a failure needs: add the key, download the language pack).
 */
@Composable
internal fun TranslationCard(
    engine: EngineId,
    src: Lang,
    tgt: Lang,
    pages: List<StudioPage>,
    run: ChapterRun?,
    onTranslate: () -> Unit,
    onTranslateAgain: () -> Unit,
    onCancel: () -> Unit,
    onChangeEngine: () -> Unit,
    onAddKey: (EngineId) -> Unit,
    onDownloadPacks: (List<String>) -> Unit,
    onReview: () -> Unit,
) {
    val translated = pages.count { StudioStage.TRANSLATED in it.stages }
    val reviewed = pages.count { it.reviewed }
    // Inside the page grid, whose own padding lines it up with the page tiles.
    Column(Modifier.padding(vertical = 6.dp)) {
        TokenCard {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(engine.uiName(), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 15.sp, color = Tokens.Ink)
                    Text(src.uiName() + " → " + tgt.uiName(), fontFamily = Jakarta, fontSize = 12.5.sp, color = Tokens.InkSoft)
                }
                if (run?.active != true) TextAction(stringResource(UiR.string.studio_change), onClick = onChangeEngine)
            }
            CardDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                when {
                    run != null && run.active -> {
                        val label = when (run.phase) {
                            RunPhase.READING -> stringResource(UiR.string.studio_reading_progress, run.done + 1, run.total)
                            RunPhase.TRANSLATING -> stringResource(UiR.string.studio_translating_progress, run.done + 1, run.total)
                            RunPhase.CLEANING -> stringResource(UiR.string.studio_cleaning_progress, run.done + 1, run.total)
                            else -> stringResource(UiR.string.studio_waiting)
                        }
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tokens.Ink)
                        Spacer(Modifier.height(8.dp))
                        if (run.total > 0) LinearProgressIndicator(
                            progress = { run.done.toFloat() / run.total }, modifier = Modifier.fillMaxWidth(),
                            color = Tokens.Ink, trackColor = Tokens.Ink.copy(alpha = 0.12f),
                        ) else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tokens.Ink, trackColor = Tokens.Ink.copy(alpha = 0.12f))
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onCancel)
                        }
                    }
                    else -> {
                        Text(
                            stringResource(UiR.string.studio_translated_count, translated, pages.size) + " · " +
                                stringResource(UiR.string.studio_reviewed, reviewed, pages.size),
                            fontFamily = Jakarta, fontSize = 13.sp, color = Tokens.InkSoft,
                        )
                        val failure = run?.failure
                        when {
                            run?.phase == RunPhase.FAILED && failure != null && run.failedPages <= 1 && StudioTranslator.stopsTheRun(failure) ->
                                Text(failureText(failure), style = MaterialTheme.typography.bodyMedium, color = Tokens.Error, modifier = Modifier.padding(top = 6.dp))
                            run?.phase == RunPhase.FAILED && run.failedPages > 0 ->
                                Text(
                                    stringResource(UiR.string.studio_pages_failed, run.failedPages) + (failure?.let { "\n" + failureText(it) }.orEmpty()),
                                    style = MaterialTheme.typography.bodyMedium, color = Tokens.Error, modifier = Modifier.padding(top = 6.dp),
                                )
                            run?.phase == RunPhase.CANCELLED ->
                                Text(stringResource(UiR.string.studio_cancelled), style = MaterialTheme.typography.bodyMedium, color = Tokens.InkSoft, modifier = Modifier.padding(top = 6.dp))
                            run?.phase == RunPhase.DONE && translated == pages.size ->
                                Text(stringResource(UiR.string.studio_translation_done), style = MaterialTheme.typography.bodyMedium, color = Tokens.Ink, modifier = Modifier.padding(top = 6.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            if (translated > 0) TextAction(stringResource(UiR.string.studio_translate_again), Tokens.InkSoft, onTranslateAgain)
                            Spacer(Modifier.width(8.dp))
                            when (failure) {
                                is EngineFailure.MissingKey -> PrimaryPill(stringResource(UiR.string.action_add_key).uppercase(), letterSpaced = true, onClick = { onAddKey(failure.engine) })
                                is EngineFailure.PackMissing -> PrimaryPill(stringResource(UiR.string.action_download).uppercase(), letterSpaced = true, onClick = { onDownloadPacks(failure.tags) })
                                else -> if (translated < pages.size && pages.isNotEmpty()) PrimaryPill(
                                    stringResource(if (translated == 0 && pages.none { StudioStage.DETECTED in it.stages }) UiR.string.studio_translate else UiR.string.studio_translate_continue).uppercase(),
                                    letterSpaced = true, onClick = onTranslate,
                                ) else if (pages.isNotEmpty()) PrimaryPill(stringResource(UiR.string.studio_review).uppercase(), letterSpaced = true, onClick = onReview)
                            }
                        }
                    }
                }
            }
        }
    }
}
