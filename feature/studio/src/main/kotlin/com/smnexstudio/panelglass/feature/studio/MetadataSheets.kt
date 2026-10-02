package com.smnexstudio.panelglass.feature.studio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.feature.studio.cover.PickedPreview
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.MangaStatus
import com.smnexstudio.panelglass.core.ui.CardDivider
import com.smnexstudio.panelglass.core.ui.ChoiceSheet
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.SectionLabel
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.TokenCard
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.ValueRow
import com.smnexstudio.panelglass.core.ui.uiName
import com.smnexstudio.panelglass.core.ui.R as UiR

@Composable
internal fun statusName(s: MangaStatus): String = stringResource(
    when (s) {
        MangaStatus.UNKNOWN -> UiR.string.studio_status_unknown
        MangaStatus.ONGOING -> UiR.string.studio_status_ongoing
        MangaStatus.COMPLETED -> UiR.string.studio_status_completed
        MangaStatus.HIATUS -> UiR.string.studio_status_hiatus
        MangaStatus.CANCELLED -> UiR.string.studio_status_cancelled
    },
)

/**
 * The manga's own fields, for a new manga or an edit. Save needs a title. [withCover] (a new manga) adds an optional
 * cover slot beside the titles; Save hands back the picture picked, if any.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaSheet(initial: Manga, title: String, onDismiss: () -> Unit, withCover: Boolean = false, onSave: (Manga, Uri?) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        MangaFields(initial, title, withCover, onDismiss, onSave)
    }
}

@Composable
private fun MangaFields(initial: Manga, heading: String, withCover: Boolean, onDismiss: () -> Unit, onSave: (Manga, Uri?) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var cover by rememberSaveable { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) cover = uri }
    var pickSrc by remember { mutableStateOf(false) }
    var pickTgt by remember { mutableStateOf(false) }
    var pickStatus by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).imePadding()) {
        Text(heading, style = MaterialTheme.typography.titleLarge, color = Tokens.Ink)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            if (withCover) {
                CoverSlot(cover, onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                StudioField(draft.title, { draft = draft.copy(title = it) }, stringResource(UiR.string.studio_field_title))
                Spacer(Modifier.height(8.dp))
                StudioField(draft.altTitle, { draft = draft.copy(altTitle = it) }, stringResource(UiR.string.studio_field_alt_title))
            }
        }
        if (withCover) Text(
            stringResource(UiR.string.studio_cover_optional_note), style = MaterialTheme.typography.bodySmall, color = Tokens.InkFaint,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            StudioField(draft.author, { draft = draft.copy(author = it) }, stringResource(UiR.string.studio_field_author), Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StudioField(draft.artist, { draft = draft.copy(artist = it) }, stringResource(UiR.string.studio_field_artist), Modifier.weight(1f))
        }
        Text(stringResource(UiR.string.studio_field_names_hint), style = MaterialTheme.typography.bodySmall, color = Tokens.InkFaint, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        StudioField(draft.genres, { draft = draft.copy(genres = it) }, stringResource(UiR.string.studio_field_genres))
        Spacer(Modifier.height(8.dp))
        StudioField(draft.summary, { draft = draft.copy(summary = it) }, stringResource(UiR.string.studio_field_summary), singleLine = false)
        SectionLabel(stringResource(UiR.string.section_translation), inset = 0.dp)
        TokenCard {
            ValueRow(stringResource(UiR.string.lang_translate_from), draft.srcLang.uiName(), onClick = { pickSrc = true })
            CardDivider()
            ValueRow(stringResource(UiR.string.lang_translate_to), draft.tgtLang.uiName(), onClick = { pickTgt = true })
            CardDivider()
            ValueRow(stringResource(UiR.string.studio_field_status), statusName(draft.status), onClick = { pickStatus = true })
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onDismiss)
            Spacer(Modifier.width(8.dp))
            PrimaryPill(
                stringResource(UiR.string.action_save).uppercase(), letterSpaced = true, enabled = draft.title.isNotBlank(),
                onClick = { onSave(draft.trimmed(), cover.takeIf { withCover }) },
            )
        }
    }
    if (pickSrc) ChoiceSheet(stringResource(UiR.string.lang_source), Lang.sourceChoices, draft.srcLang, { it.uiName() }, onPick = { draft = draft.copy(srcLang = it) }, onDismiss = { pickSrc = false })
    if (pickTgt) ChoiceSheet(stringResource(UiR.string.lang_target), Lang.targetChoices, draft.tgtLang, { it.uiName() }, onPick = { draft = draft.copy(tgtLang = it) }, onDismiss = { pickTgt = false })
    if (pickStatus) {
        val names = MangaStatus.entries.associateWith { statusName(it) }
        ChoiceSheet(stringResource(UiR.string.studio_field_status), MangaStatus.entries, draft.status, { names.getValue(it) }, onPick = { draft = draft.copy(status = it) }, onDismiss = { pickStatus = false })
    }
}

/** The optional cover of a new manga: the picture picked, or a dashed "Add cover" frame. */
@Composable
private fun CoverSlot(cover: Uri?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(width = 84.dp, height = 126.dp).clip(shape).background(Tokens.YellowTint.copy(alpha = 0.5f))
            .border(1.5.dp, Tokens.YellowDeep, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) PickedPreview(cover, Modifier.matchParentSize())
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, tint = Tokens.Ink)
            Text(
                stringResource(UiR.string.studio_add_cover), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.sp,
                color = Tokens.Ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

private fun Manga.trimmed() = copy(
    title = title.trim(), altTitle = altTitle.trim(),
    author = People.split(author).joinToString(", "), artist = People.split(artist).joinToString(", "),
    summary = summary.trim(), genres = genres.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "),
)

/**
 * Several authors or artists in one field: names separated by commas or semicolons (ComicInfo's `Writer` and
 * `Penciller` are comma lists too). Saved as `A, B`; shown one name per line.
 */
object People {
    private val separators = Regex("[,;、，；]")

    fun split(s: String): List<String> = s.split(separators).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/** Chapter fields as text while editing (a half-typed number is not a number yet). */
data class ChapterDraft(
    val number: String = "",
    val volume: String = "",
    val title: String = "",
    val language: Lang? = null,
    val releaseDate: String = "",
    val notes: String = "",
) {
    val numberValid: Boolean get() = number.isBlank() || number.trim().replace(',', '.').toFloatOrNull()?.let { it >= 0 } == true
    val volumeValid: Boolean get() = volume.isBlank() || volume.trim().toIntOrNull()?.let { it >= 0 } == true
    val dateValid: Boolean get() = releaseDate.isBlank() || DATE.matches(releaseDate.trim())
    val valid: Boolean get() = numberValid && volumeValid && dateValid

    fun applyTo(c: Chapter): Chapter = c.copy(
        number = number.trim().replace(',', '.').toFloatOrNull(),
        volume = volume.trim().toIntOrNull(),
        title = title.trim(),
        language = language,
        releaseDate = releaseDate.trim(),
        notes = notes.trim(),
    )

    companion object {
        private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
        fun of(c: Chapter) = ChapterDraft(
            number = c.numberLabel.orEmpty(), volume = c.volume?.toString().orEmpty(), title = c.title,
            language = c.language, releaseDate = c.releaseDate, notes = c.notes,
        )
    }
}

/** Chapter number, volume and title: the part of a chapter's fields the import step also shows. */
@Composable
internal fun ChapterNumberFields(draft: ChapterDraft, onChange: (ChapterDraft) -> Unit) {
    Row {
        StudioField(
            draft.number, { onChange(draft.copy(number = it)) }, stringResource(UiR.string.studio_field_number),
            Modifier.weight(1f), keyboardType = KeyboardType.Decimal,
        )
        Spacer(Modifier.width(8.dp))
        StudioField(
            draft.volume, { onChange(draft.copy(volume = it)) }, stringResource(UiR.string.studio_field_volume),
            Modifier.weight(1f), keyboardType = KeyboardType.Number,
        )
    }
    Spacer(Modifier.height(8.dp))
    StudioField(draft.title, { onChange(draft.copy(title = it)) }, stringResource(UiR.string.studio_field_chapter_title))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChapterSheet(initial: Chapter, mangaLang: Lang, onDismiss: () -> Unit, onSave: (Chapter) -> Unit) {
    var draft by remember(initial) { mutableStateOf(ChapterDraft.of(initial)) }
    var pickLang by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).imePadding()) {
            Text(stringResource(UiR.string.studio_edit_chapter), style = MaterialTheme.typography.titleLarge, color = Tokens.Ink)
            Spacer(Modifier.height(12.dp))
            ChapterNumberFields(draft) { draft = it }
            Spacer(Modifier.height(8.dp))
            StudioField(draft.releaseDate, { draft = draft.copy(releaseDate = it) }, stringResource(UiR.string.studio_field_release_date))
            Spacer(Modifier.height(8.dp))
            StudioField(draft.notes, { draft = draft.copy(notes = it) }, stringResource(UiR.string.studio_field_notes), singleLine = false)
            Spacer(Modifier.height(12.dp))
            TokenCard {
                ValueRow(
                    stringResource(UiR.string.studio_field_language),
                    draft.language?.uiName() ?: stringResource(UiR.string.lang_default, mangaLang.uiName()),
                    onClick = { pickLang = true },
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onDismiss)
                Spacer(Modifier.width(8.dp))
                PrimaryPill(
                    stringResource(UiR.string.action_save).uppercase(), letterSpaced = true, enabled = draft.valid,
                    onClick = { onSave(draft.applyTo(initial)) },
                )
            }
        }
    }
    if (pickLang) ChoiceSheet(
        stringResource(UiR.string.studio_field_language), Lang.targetChoices, draft.language ?: mangaLang, { it.uiName() },
        onPick = { draft = draft.copy(language = it.takeIf { l -> l != mangaLang }) }, onDismiss = { pickLang = false },
    )
}
