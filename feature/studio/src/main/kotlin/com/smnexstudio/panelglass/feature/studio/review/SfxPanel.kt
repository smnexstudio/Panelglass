package com.smnexstudio.panelglass.feature.studio.review

import com.smnexstudio.panelglass.core.ui.fonts.FontBrowserSheet
import com.smnexstudio.panelglass.core.ui.fonts.fontBrowserInSheet
import com.smnexstudio.panelglass.core.ui.fonts.FontBrowser
import com.smnexstudio.panelglass.core.ui.fonts.FontField
import com.smnexstudio.panelglass.core.ui.fonts.FontChoices
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.FontRole
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.SfxEdit
import com.smnexstudio.panelglass.core.model.SfxEditMode
import com.smnexstudio.panelglass.core.render.SfxPreset
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.SectionLabel
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import kotlin.math.roundToInt
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * A sound effect's options (docs/STUDIO_PLAN.md › Sound effects): keep, gloss, overlay or replace it; a preset to
 * start from; font, fill and outlines; and its size, stretch, rotation, slant and bend. On the page, the transform box
 * moves, turns and scales it too. "Remember this effect" keeps its replacement for every later page of the manga.
 */
@Composable
internal fun SfxPanel(
    b: Bubble, edit: SfxEdit, tgt: Lang, choices: FontChoices, remembered: Boolean,
    onEdit: (SfxEdit) -> Unit, onRemember: () -> Unit, lamaHint: Boolean = false,
) {
    var browsing by rememberSaveable { mutableStateOf(false) }
    val inSheet = fontBrowserInSheet()
    if (browsing && !inSheet) {
        FontBrowser(edit.style.fontId, tgt, FontRole.SFX, choices, onFont = { onEdit(edit.copy(style = edit.style.copy(fontId = it))) }, onBack = { browsing = false })
        return
    }
    if (browsing) FontBrowserSheet(edit.style.fontId, tgt, FontRole.SFX, choices, onFont = { onEdit(edit.copy(style = edit.style.copy(fontId = it))) }, onDismiss = { browsing = false })
    val modes = mapOf(
        SfxEditMode.KEEP to stringResource(UiR.string.studio_sfx_keep),
        SfxEditMode.GLOSS to stringResource(UiR.string.studio_sfx_gloss),
        SfxEditMode.OVERLAY to stringResource(UiR.string.studio_sfx_overlay),
        SfxEditMode.REPLACE to stringResource(UiR.string.studio_sfx_replace),
    )
    Spacer(Modifier.height(4.dp))
    OptionGroup(SfxEditMode.entries, edit.mode, label = { modes.getValue(it) }, onSelect = { onEdit(edit.copy(mode = it)) })
    Text(
        stringResource(
            when (edit.mode) {
                SfxEditMode.KEEP -> UiR.string.studio_sfx_keep_note
                SfxEditMode.GLOSS -> UiR.string.studio_sfx_gloss_note
                SfxEditMode.OVERLAY -> UiR.string.studio_sfx_overlay_note
                SfxEditMode.REPLACE -> UiR.string.studio_sfx_replace_note
            },
        ),
        style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(top = 6.dp),
    )
    if (edit.mode == SfxEditMode.REPLACE && lamaHint) Text(
        stringResource(UiR.string.studio_lama_hint), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(top = 4.dp),
    )
    if (edit.mode == SfxEditMode.OVERLAY || edit.mode == SfxEditMode.REPLACE) {
        PanelLabel(stringResource(UiR.string.studio_sfx_presets))
        val presets = mapOf(
            SfxPreset.IMPACT to stringResource(UiR.string.studio_sfx_preset_impact),
            SfxPreset.PUNCH to stringResource(UiR.string.studio_sfx_preset_punch),
            SfxPreset.HORROR to stringResource(UiR.string.studio_sfx_preset_horror),
            SfxPreset.SOFT to stringResource(UiR.string.studio_sfx_preset_soft),
            SfxPreset.RUMBLE to stringResource(UiR.string.studio_sfx_preset_rumble),
            SfxPreset.MATCH to stringResource(UiR.string.studio_sfx_preset_match),
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            SfxPreset.entries.forEach { p -> PresetChip(presets.getValue(p), edit.preset == p.name) { onEdit(p.apply(edit, b, tgt)) } }
        }
        PanelLabel(stringResource(UiR.string.studio_font))
        FontField(edit.style.fontId, tgt, FontRole.SFX, onClick = { browsing = true })
        Row(Modifier.padding(top = 8.dp)) {
            PresetChip(stringResource(UiR.string.studio_bold), edit.style.bold) { onEdit(edit.copy(style = edit.style.copy(bold = !edit.style.bold))) }
            PresetChip(stringResource(UiR.string.studio_uppercase), edit.style.uppercase) { onEdit(edit.copy(style = edit.style.copy(uppercase = !edit.style.uppercase))) }
            PresetChip(stringResource(UiR.string.studio_sfx_double_outline), edit.style.outerOutlineColor != null) {
                onEdit(edit.copy(style = edit.style.copy(outerOutlineColor = if (edit.style.outerOutlineColor == null) android.graphics.Color.WHITE else null)))
            }
        }
        ColorChoice(
            stringResource(UiR.string.studio_fill_color), edit.style.textColor, SFX_COLOURS, auto = true, withAlpha = false,
            onColor = { onEdit(edit.copy(style = edit.style.copy(textColor = it))) },
        )
        ColorChoice(
            stringResource(UiR.string.studio_outline_color), edit.style.outlineColor, OUTLINE_COLOURS, auto = true, withAlpha = false,
            onColor = { onEdit(edit.copy(style = edit.style.copy(outlineColor = it))) },
        )
        val t = edit.transform
        PanelLabel(stringResource(UiR.string.studio_sfx_shape))
        val size = kotlin.math.sqrt(t.scaleX * t.scaleY)
        val stretch = t.scaleX / t.scaleY
        LabeledSlider(stringResource(UiR.string.studio_sfx_size), size, 0.3f..3f, "${(size * 100).roundToInt()}%") { s ->
            onEdit(edit.copy(transform = t.copy(scaleX = s * kotlin.math.sqrt(stretch), scaleY = s / kotlin.math.sqrt(stretch))))
        }
        LabeledSlider(stringResource(UiR.string.studio_sfx_stretch), stretch, 0.5f..2f, "${(stretch * 100).roundToInt()}%") { r ->
            onEdit(edit.copy(transform = t.copy(scaleX = size * kotlin.math.sqrt(r), scaleY = size / kotlin.math.sqrt(r))))
        }
        LabeledSlider(stringResource(UiR.string.studio_sfx_rotation), t.rotationDeg, -180f..180f, "${t.rotationDeg.roundToInt()}°") {
            onEdit(edit.copy(transform = t.copy(rotationDeg = it)))
        }
        LabeledSlider(stringResource(UiR.string.studio_sfx_slant), t.skewX, -0.6f..0.6f, "${(t.skewX * 100).roundToInt()}") {
            onEdit(edit.copy(transform = t.copy(skewX = it)))
        }
        LabeledSlider(stringResource(UiR.string.studio_sfx_bend), t.curve, -1f..1f, "${(t.curve * 100).roundToInt()}") {
            onEdit(edit.copy(transform = t.copy(curve = it)))
        }
        LabeledSlider(stringResource(UiR.string.studio_sfx_spacing), edit.style.letterSpacing, -0.1f..0.6f, "${(edit.style.letterSpacing * 100).roundToInt()}") {
            onEdit(edit.copy(style = edit.style.copy(letterSpacing = it)))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (remembered) Text(stringResource(UiR.string.studio_sfx_remembered), fontFamily = Jakarta, fontSize = 13.sp, color = Tokens.InkSoft, modifier = Modifier.weight(1f))
        else {
            Spacer(Modifier.weight(1f))
            TextAction(stringResource(UiR.string.studio_sfx_remember), onClick = onRemember)
        }
    }
}

private val SFX_COLOURS = listOf(0xFFFFD21F, 0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFF8B0000, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00).map { it.toInt() }
private val OUTLINE_COLOURS = listOf(0xFF000000, 0xFFFFFFFF, 0x00000000, 0xFF8B0000, 0xFF1F3A93).map { it.toLong().toInt() }

@Composable
private fun PresetChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 13.sp, color = if (on) Tokens.Card else Tokens.Ink,
        modifier = Modifier.padding(end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(50))
            .background(if (on) Tokens.Ink else Tokens.Ink.copy(alpha = 0.06f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) =
    ValueSlider(label, shown, value, range, onChange)

