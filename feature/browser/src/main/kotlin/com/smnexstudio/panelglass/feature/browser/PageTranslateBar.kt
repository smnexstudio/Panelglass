package com.smnexstudio.panelglass.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smnexstudio.panelglass.core.engine.mt.PageTranslator
import com.smnexstudio.panelglass.core.ui.ChoiceSheet
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.languageName
import com.smnexstudio.panelglass.core.ui.R as UiR
import com.smnexstudio.panelglass.feature.browser.PageTextState.Status

/**
 * Chrome-style translate bar under the reader's top bar: `[Detect language ▾] → [English ▾]  ✕`, and below it what
 * is happening with Show original / Translate. The lists are every language ML Kit translates.
 */
@Composable
internal fun PageTranslateBar(
    s: PageTextState,
    onSource: (String?) -> Unit,
    onTarget: (String) -> Unit,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
    onClose: () -> Unit,
    onDownloadPacks: () -> Unit,
) {
    var srcSheet by remember { mutableStateOf(false) }
    var tgtSheet by remember { mutableStateOf(false) }
    val languages = remember { PageTranslator.languages().sortedBy { languageName(it) } }

    Column(Modifier.fillMaxWidth().background(Tokens.Ink).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val src = s.srcTag?.let { languageName(it) }
                ?: s.detectedTag?.let { stringResource(UiR.string.pt_detected, languageName(it)) }
                ?: stringResource(UiR.string.pt_detect)
            LangChip(src, Modifier.weight(1f, fill = false)) { srcSheet = true }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Tokens.InkFaint, modifier = Modifier.padding(horizontal = 6.dp).size(16.dp))
            LangChip(languageName(s.tgtTag), Modifier.weight(1f, fill = false)) { tgtSheet = true }
            Spacer(Modifier.weight(0.01f))
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(UiR.string.cd_close_translate_bar), tint = Tokens.InkFaint, modifier = Modifier.size(18.dp))
            }
        }
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val busy = s.on && s.status in setOf(Status.DETECTING, Status.DOWNLOADING, Status.TRANSLATING)
            if (busy) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Tokens.Yellow); Spacer(Modifier.size(8.dp)) }
            Text(
                statusText(s),
                style = MaterialTheme.typography.labelSmall,
                color = if (s.status == Status.FAILED || s.status == Status.UNDETECTED || s.status == Status.NEEDS_PACK) Tokens.Error else Tokens.InkFaint,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (s.on && s.status == Status.NEEDS_PACK) TextAction(stringResource(UiR.string.action_download), Tokens.Yellow, onDownloadPacks)
            else if (s.on) TextAction(stringResource(UiR.string.pt_show_original), Tokens.Yellow, onShowOriginal)
            else TextAction(stringResource(UiR.string.pt_translate), Tokens.Yellow, onTranslate)
        }
    }

    val detectLabel = stringResource(UiR.string.pt_detect)
    if (srcSheet) ChoiceSheet(
        stringResource(UiR.string.pt_source), listOf<String?>(null) + languages, s.srcTag,
        render = { it?.let { tag -> languageName(tag) } ?: detectLabel },
        onPick = onSource, onDismiss = { srcSheet = false },
    )
    if (tgtSheet) ChoiceSheet(
        stringResource(UiR.string.pt_target), languages, s.tgtTag,
        render = { languageName(it) }, onPick = onTarget, onDismiss = { tgtSheet = false },
    )
}

@Composable
private fun statusText(s: PageTextState): String = when {
    !s.on -> stringResource(UiR.string.pt_original)
    else -> when (s.status) {
        Status.IDLE, Status.DETECTING -> stringResource(UiR.string.pt_detecting)
        Status.DOWNLOADING -> stringResource(UiR.string.pt_downloading)
        Status.TRANSLATING -> stringResource(UiR.string.pt_translating)
        Status.DONE -> stringResource(UiR.string.pt_done)
        Status.SAME_LANGUAGE -> stringResource(UiR.string.pt_same, languageName(s.tgtTag))
        Status.UNDETECTED -> stringResource(UiR.string.pt_undetected)
        Status.NEEDS_PACK -> stringResource(UiR.string.error_pack_missing, s.missingPacks.joinToString(", ") { languageName(it) })
        Status.FAILED -> stringResource(UiR.string.pt_failed)
    }
}

@Composable
private fun LangChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.widthIn(max = 180.dp).clip(RoundedCornerShape(16.dp)).background(Tokens.InkRaised).clickable(onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Tokens.Bg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Tokens.InkFaint, modifier = Modifier.size(18.dp))
    }
}
