package com.smnexstudio.panelglass

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.engine.mt.LanguagePackStore
import com.smnexstudio.panelglass.core.model.ModelState
import com.smnexstudio.panelglass.core.ocr.LamaStore
import com.smnexstudio.panelglass.core.ocr.MangaOcrStore
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.StickerDialog
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import java.util.Locale
import com.smnexstudio.panelglass.core.ui.R as UiR

/** One thing the first-open setup offers to download, with its size. */
data class FirstDownload(val name: String, val bytes: Long)

/**
 * The first-open setup (after the notification question): what Panelglass still needs on this phone — manga-ocr,
 * LaMa where the phone can run it, the Japanese, Korean and Chinese language packs — and how big it is, so nothing
 * large starts without the user seeing it. They pick Wi-Fi only (the default) or mobile data too.
 */
object FirstDownloads {
    /** What one ML Kit pack takes, roughly (ML Kit's own figure; its files are not ours to measure). */
    private const val PACK_BYTES = 30L * 1024 * 1024

    /** What is still missing, in the order it downloads; empty when everything is on the phone already. */
    suspend fun missing(context: Context, ocr: MangaOcrStore, lama: LamaStore, packs: LanguagePackStore): List<FirstDownload> {
        val out = ArrayList<FirstDownload>()
        if (ocr.state.value !is ModelState.Ready) out += FirstDownload(context.getString(UiR.string.model_manga_ocr), MangaOcrStore.EXPECTED_BYTES)
        if (lama.fitsThisPhone && lama.state.value !is ModelState.Ready) out += FirstDownload(context.getString(UiR.string.model_lama), LamaStore.EXPECTED_BYTES)
        val have = runCatching { packs.downloadedNow() }.getOrDefault(emptySet())
        for (tag in LanguagePackStore.AUTOMATIC) {
            if (tag in have) continue
            out += FirstDownload(context.getString(UiR.string.setup_pack, packName(context, tag)), PACK_BYTES)
        }
        return out
    }

    /** The language of an ML Kit tag in the app's language ("ja" → "Japanese"). */
    private fun packName(context: Context, tag: String): String {
        val ui = context.resources.configuration.locales[0] ?: Locale.ENGLISH
        return Locale.forLanguageTag(tag).getDisplayLanguage(ui).replaceFirstChar { it.titlecase(ui) }
    }
}

/**
 * The setup dialog: the list, its total, and Wi-Fi only / Wi-Fi or mobile data. [onDownload] gets the choice
 * (true = Wi-Fi only); [onLater] closes it, and the next open asks again.
 */
@Composable
fun FirstDownloadsDialog(items: List<FirstDownload>, onDownload: (wifiOnly: Boolean) -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    val total = items.sumOf { it.bytes }
    StickerDialog(onDismiss = onLater) {
        Text(
            stringResource(UiR.string.setup_title), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp,
            color = Tokens.Ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            pluralStringResource(UiR.plurals.setup_components, items.size, items.size, Formatter.formatShortFileSize(context, total)),
            fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 13.sp, color = Tokens.InkSoft,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in items) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, fontFamily = Jakarta, fontSize = 14.sp, color = Tokens.Ink, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(Formatter.formatShortFileSize(context, item.bytes), fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 13.sp, color = Tokens.InkSoft)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(UiR.string.setup_over), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 13.sp, color = Tokens.Ink)
        Column(Modifier.selectableGroup()) {
            NetworkChoice(stringResource(UiR.string.setup_wifi), wifiOnly) { wifiOnly = true }
            NetworkChoice(stringResource(UiR.string.setup_any), !wifiOnly) { wifiOnly = false }
        }
        Text(stringResource(UiR.string.setup_note), fontFamily = Jakarta, fontSize = 11.5.sp, lineHeight = 16.sp, color = Tokens.InkFaint)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            TextAction(stringResource(UiR.string.update_later), Tokens.InkSoft, onLater)
            Spacer(Modifier.width(8.dp))
            PrimaryPill(stringResource(UiR.string.action_download).uppercase(), letterSpaced = true, onClick = { onDownload(wifiOnly) })
        }
    }
}

@Composable
private fun NetworkChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Tokens.Ink, unselectedColor = Tokens.InkFaint))
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = Jakarta, fontSize = 14.sp, color = Tokens.Ink, modifier = Modifier.padding(vertical = 4.dp))
    }
}
