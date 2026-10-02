package com.smnexstudio.panelglass.feature.studio.review

import com.smnexstudio.panelglass.core.ui.verticalScrollbar
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.smnexstudio.panelglass.feature.studio.ConfirmDialog
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.R as UiR

enum class EditorTab { BUBBLE, PROOFREAD }

/** The texts of one bubble as the user sees them: an unsaved edit wins over the saved text. */
data class BubbleTexts(val source: String, val translation: String)

/** A two-option segmented pill (Bubble | Proofread, Original | Clean | Final). */
@Composable
internal fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Tokens.Ink.copy(alpha = 0.06f)).padding(3.dp)) {
        for (o in options) {
            val on = o == selected
            Text(
                label(o), fontFamily = Jakarta, fontWeight = if (on) FontWeight.W700 else FontWeight.W600, fontSize = 13.sp,
                color = if (on) Tokens.Card else Tokens.InkSoft,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Tokens.Ink else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(o) }.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** The editor's header: the tabs, and on the right the bubble stepper (Bubble) or the bubble count (Proofread). */
@Composable
internal fun EditorHeader(tab: EditorTab, onTab: (EditorTab) -> Unit, index: Int, count: Int, onPrev: () -> Unit, onNext: () -> Unit, kind: String? = null) {
    // One line: "‹ ③ Bubble 3 of 5 [Speech] ›" and the Proofread list's button; in the list, its title and the way back.
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (tab == EditorTab.BUBBLE) {
            IconButton(onClick = onPrev, enabled = index > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(UiR.string.studio_previous_bubble), tint = if (index > 0) Tokens.Ink else Tokens.InkFaint)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (index >= 0) Box(Modifier.size(24.dp).clip(CircleShape).background(Tokens.Yellow), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 12.sp, color = Tokens.Ink)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (index >= 0) stringResource(UiR.string.studio_bubble_of, index + 1, count) else pluralStringResource(UiR.plurals.studio_bubbles, count, count),
                    fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, color = Tokens.Ink, maxLines = 1,
                )
                kind?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 11.sp, color = Tokens.SkyDeep, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Tokens.Sky.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            IconButton(onClick = onNext, enabled = index < count - 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(UiR.string.studio_next_bubble), tint = if (index < count - 1) Tokens.Ink else Tokens.InkFaint)
            }
        } else {
            Text(
                stringResource(UiR.string.studio_tab_proofread) + " · " + pluralStringResource(UiR.plurals.studio_bubbles, count, count),
                fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 15.sp, color = Tokens.Ink,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
        val proof = tab == EditorTab.PROOFREAD
        IconButton(
            onClick = { onTab(if (proof) EditorTab.BUBBLE else EditorTab.PROOFREAD) },
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (proof) Tokens.Ink else Tokens.Ink.copy(alpha = 0.06f)),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ViewList, stringResource(if (proof) UiR.string.studio_tab_bubble else UiR.string.studio_tab_proofread),
                tint = if (proof) Tokens.Card else Tokens.Ink, modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** What a bubble is: speech, caption, free text, a sound effect or text in the art. */
@Composable
internal fun kindName(b: Bubble): String = stringResource(
    when (b.kind) {
        RegionKind.ENCLOSED -> UiR.string.studio_kind_speech
        RegionKind.CAPTION -> UiR.string.studio_kind_caption
        RegionKind.FREE -> UiR.string.studio_kind_free
        RegionKind.SFX -> UiR.string.studio_kind_sfx
        RegionKind.IN_SCENE -> UiR.string.studio_kind_scene
    },
)

/** A language badge ("JA", "EN") in front of a text. */
@Composable
private fun LangBadge(lang: Lang, target: Boolean = false) {
    Text(
        lang.code.substringBefore('-').uppercase(), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 10.sp,
        color = if (target) Tokens.Ink else Tokens.Card,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (target) Tokens.Yellow else Tokens.Ink).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** "JA  Original text" over its field. */
@Composable
private fun FieldLabel(lang: Lang, target: Boolean, label: String) {
    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LangBadge(lang, target)
        Spacer(Modifier.width(6.dp))
        Text(label, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 12.sp, color = Tokens.InkSoft)
    }
}

/** A text box: the original on a quiet ground, the translation with a strong outline. */
@Composable
private fun TextBox(value: String, onChange: (String) -> Unit, strong: Boolean, placeholder: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (strong) Tokens.Card else Tokens.Bg)
            .border(if (strong) 1.5.dp else 1.dp, if (strong) Tokens.Ink else Tokens.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = Tokens.InkFaint, fontSize = 15.sp)
        BasicTextField(
            value = value, onValueChange = onChange, maxLines = 4,
            textStyle = TextStyle(color = Tokens.Ink, fontSize = 15.sp, fontWeight = if (strong) FontWeight.W600 else FontWeight.Normal),
            cursorBrush = SolidColor(Tokens.Ink), modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A small rounded action under the fields (Re-translate, Not text, Delete). */
@Composable
private fun ActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, busy: Boolean = false, on: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(if (on) Tokens.Ink else Tokens.Ink.copy(alpha = 0.06f))
            .clickable(enabled = !busy, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(15.dp), color = Tokens.Ink, strokeWidth = 2.dp)
        else Icon(icon, null, tint = if (on) Tokens.Card else Tokens.Ink, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 13.sp, color = if (on) Tokens.Card else Tokens.Ink)
    }
}

/** A text box with a language badge in front and an optional action icon at its end, as in the review mockup. */
@Composable
private fun BadgedField(
    value: String, onChange: (String) -> Unit, lang: Lang, strong: Boolean, placeholder: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tokens.Card)
            .border(if (strong) 1.5.dp else 1.dp, if (strong) Tokens.Ink else Tokens.Border, RoundedCornerShape(12.dp))
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp)) { LangBadge(lang) }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).padding(top = 1.dp)) {
            if (value.isEmpty()) Text(placeholder, color = Tokens.InkFaint, fontSize = 15.sp)
            BasicTextField(
                value = value, onValueChange = onChange, maxLines = 4,
                textStyle = TextStyle(color = Tokens.Ink, fontSize = 15.sp, fontWeight = if (strong) FontWeight.W600 else FontWeight.Normal),
                cursorBrush = SolidColor(Tokens.Ink), modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
}

/** The Bubble tab: the selected bubble's original and translation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BubbleTab(
    bubble: Bubble?,
    count: Int,
    texts: BubbleTexts?,
    src: Lang,
    tgt: Lang,
    retranslating: Boolean,
    onSource: (String) -> Unit,
    onTranslation: (String) -> Unit,
    onRetranslate: () -> Unit,
    onToggleIgnored: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var confirmDelete by remember(bubble?.id) { mutableStateOf(false) }
    if (bubble == null || texts == null) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                stringResource(if (count == 0) UiR.string.studio_no_bubbles else UiR.string.studio_select_bubble_hint),
                style = MaterialTheme.typography.bodyMedium, color = Tokens.InkSoft,
            )
        }
        return
    }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScrollbar(scroll).verticalScroll(scroll).padding(horizontal = 12.dp)) {
        if (bubble.ignored) Text(
            stringResource(UiR.string.studio_dismissed),
            fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.sp, color = Tokens.InkSoft, modifier = Modifier.padding(bottom = 4.dp),
        )
        FieldLabel(src, target = false, label = stringResource(UiR.string.studio_field_source))
        TextBox(texts.source, onSource, strong = false, placeholder = stringResource(UiR.string.studio_not_read))
        Spacer(Modifier.height(10.dp))
        FieldLabel(tgt, target = true, label = stringResource(UiR.string.studio_field_translation))
        TextBox(texts.translation, onTranslation, strong = true, placeholder = stringResource(UiR.string.studio_not_translated))
        FlowRow(Modifier.padding(top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(Icons.Filled.Translate, stringResource(UiR.string.studio_retranslate), busy = retranslating, onClick = onRetranslate)
            ActionChip(
                if (bubble.ignored) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                stringResource(if (bubble.ignored) UiR.string.studio_restore_bubble else UiR.string.studio_not_text),
                on = bubble.ignored, onClick = onToggleIgnored,
            )
            ActionChip(Icons.Outlined.Delete, stringResource(UiR.string.studio_delete_bubble), onClick = { confirmDelete = true })
        }
    }
    // Deleting takes the bubble off the page and out of the count; undo brings it back.
    if (confirmDelete) ConfirmDialog(
        stringResource(UiR.string.studio_delete_bubble_title), stringResource(UiR.string.studio_delete_bubble_confirm),
        stringResource(UiR.string.action_delete), onDismiss = { confirmDelete = false },
    ) { confirmDelete = false; onDelete() }
}

/** A text field for sheets and the focus-mode card. */
@Composable
internal fun reviewField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, done: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, minLines = 1, maxLines = 4,
        shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth(),
        keyboardOptions = if (done != null) KeyboardOptions(imeAction = ImeAction.Done) else KeyboardOptions.Default,
        keyboardActions = KeyboardActions(onDone = { done?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Tokens.Ink, unfocusedBorderColor = Tokens.Border, focusedTextColor = Tokens.Ink, unfocusedTextColor = Tokens.Ink,
            cursorColor = Tokens.Ink, focusedLabelColor = Tokens.InkSoft, unfocusedLabelColor = Tokens.InkFaint,
        ),
    )
}

/** The Proofread tab: every bubble of the page, translation over original; a tap selects it on the page. */
@Composable
internal fun ProofreadTab(all: List<Bubble>, texts: (Bubble) -> BubbleTexts, selectedId: Long?, onSelect: (Long) -> Unit) {
    val list = rememberLazyListState()
    // Sound effects can be listed alone, to check them together.
    var effectsOnly by remember { mutableStateOf(false) }
    val hasEffects = all.any { it.kind == RegionKind.SFX }
    val bubbles = if (effectsOnly && hasEffects) all.filter { it.kind == RegionKind.SFX } else all
    val selectedIndex = bubbles.indexOfFirst { it.id == selectedId }
    LaunchedEffect(selectedId) { if (selectedIndex >= 0) list.animateScrollToItem(selectedIndex) }
    if (bubbles.isEmpty()) {
        Text(stringResource(UiR.string.studio_no_bubbles), style = MaterialTheme.typography.bodyMedium, color = Tokens.InkSoft, modifier = Modifier.padding(16.dp))
        return
    }
    var n = 0
    val numbers = all.associate { it.id to if (it.ignored) null else ++n }
    Column {
    if (hasEffects) Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Text(
            stringResource(UiR.string.studio_sfx_only), fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 12.sp,
            color = if (effectsOnly) Tokens.Card else Tokens.Ink,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (effectsOnly) Tokens.Ink else Tokens.Ink.copy(alpha = 0.06f))
                .clickable { effectsOnly = !effectsOnly }.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
    // The scrollbar says there is more below: a page often has more bubbles than the pane shows.
    LazyColumn(state = list, modifier = Modifier.verticalScrollbar(list), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        itemsIndexed(bubbles, key = { _, b -> b.id }) { _, b ->
            val t = texts(b)
            val sel = b.id == selectedId
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (sel) Tokens.YellowTint else Tokens.Card).clickable { onSelect(b.id) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(if (sel) Tokens.Yellow else Tokens.Ink.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(numbers[b.id]?.toString() ?: "–", fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 12.sp, color = Tokens.Ink)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            t.translation.ifBlank { stringResource(UiR.string.studio_not_translated) },
                            fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp,
                            color = if (b.ignored || t.translation.isBlank()) Tokens.InkFaint else Tokens.Ink,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (b.kind == RegionKind.SFX) Tag(stringResource(UiR.string.studio_sfx_tag))
                        if (b.ignored) Tag(stringResource(UiR.string.studio_dismissed))
                    }
                    Text(
                        t.source.ifBlank { stringResource(UiR.string.studio_not_read) }, style = MaterialTheme.typography.bodySmall,
                        color = Tokens.InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun Tag(text: String) {
    // Ink on a faint wash of ink: readable in every theme (some themes' border colour is as dark as the text).
    Text(
        text, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 10.sp, color = Tokens.Ink,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(Tokens.Ink.copy(alpha = 0.1f)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
