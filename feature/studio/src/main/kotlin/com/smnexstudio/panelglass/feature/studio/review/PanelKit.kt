package com.smnexstudio.panelglass.feature.studio.review

import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.StickerDialog
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.widthClass
import com.smnexstudio.panelglass.core.ui.R as UiR

// The controls every tool panel is built from, in the design canvas's style: a small grey label over each control,
// option groups on a grey track with the chosen one raised, ink sliders with their value, colour rows that open the
// full picker.

/** A control's name, small and grey, over it. */
@Composable
internal fun PanelLabel(text: String, modifier: Modifier = Modifier, value: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 12.sp, color = Tokens.InkSoft, modifier = Modifier.weight(1f))
        value?.let { Text(it, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.sp, color = Tokens.Ink) }
    }
}

/** One choice of several on a grey track; the chosen one is raised in white. */
@Composable
internal fun <T> OptionGroup(options: List<T>, selected: T?, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tokens.Ink.copy(alpha = 0.06f)).padding(3.dp)) {
        for (o in options) {
            val on = o == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) Tokens.Card else Color.Transparent)
                    .clickable { onSelect(o) }.padding(vertical = 9.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.5.sp, color = if (on) Tokens.Ink else Tokens.InkSoft, maxLines = 1)
            }
        }
    }
}

/** One switch in a row of switches on the grey track (bold, italic, capitals: each on or off on its own). */
internal class ToggleItem(val text: String, val on: Boolean, val description: String, val weight: FontWeight = FontWeight.W700, val italic: Boolean = false, val onClick: () -> Unit)

@Composable
internal fun ToggleGroup(items: List<ToggleItem>, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(Tokens.Ink.copy(alpha = 0.06f)).padding(3.dp)) {
        for (t in items) {
            Box(
                Modifier.size(width = 44.dp, height = 36.dp).clip(RoundedCornerShape(9.dp)).background(if (t.on) Tokens.Ink else Color.Transparent)
                    .clickable(onClickLabel = t.description, onClick = t.onClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.text, fontFamily = Jakarta, fontWeight = t.weight, fontSize = 14.sp,
                    fontStyle = if (t.italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                    color = if (t.on) Tokens.Card else Tokens.Ink,
                )
            }
        }
    }
}

/** A slider with its name and value over it. */
@Composable
internal fun ValueSlider(
    label: String, shown: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit,
    enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 12.sp, color = Tokens.InkSoft, modifier = Modifier.weight(1f))
            Text(shown, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.sp, color = if (enabled) Tokens.Ink else Tokens.InkFaint)
            trailing?.let { Spacer(Modifier.width(8.dp)); it() }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = Tokens.Ink, activeTrackColor = Tokens.Ink, inactiveTrackColor = Tokens.Ink.copy(alpha = 0.1f)),
        )
    }
}

/** A short explanation under the controls; [warn] for one that needs attention. */
@Composable
internal fun PanelNote(text: String, warn: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp))
            .background(if (warn) Tokens.YellowTint else Tokens.Ink.copy(alpha = 0.04f)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(Icons.Outlined.Info, null, tint = Tokens.InkSoft, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontFamily = Jakarta, fontSize = 12.5.sp, lineHeight = 18.sp, color = Tokens.InkSoft)
    }
}

/** A switch with its name and what it does. */
@Composable
internal fun PanelSwitch(label: String, note: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 14.sp, color = if (enabled) Tokens.Ink else Tokens.InkFaint)
            Text(note, fontFamily = Jakarta, fontSize = 12.sp, color = Tokens.InkSoft)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Tokens.Ink, checkedThumbColor = Tokens.Card),
        )
    }
}

/** "This bubble / Every bubble on the page": where the panel's changes go. */
@Composable
internal fun ApplyTo(everyBubble: Boolean, onChange: (Boolean) -> Unit) {
    val this1 = stringResource(UiR.string.studio_apply_this)
    val every = stringResource(UiR.string.studio_apply_every)
    PanelLabel(stringResource(UiR.string.studio_apply_to))
    OptionGroup(listOf(false, true), everyBubble, label = { if (it) every else this1 }, onSelect = onChange)
}

// ---- colour ---------------------------------------------------------------------------------------------------------

private val RAINBOW = (0..6).map { Color(ColorMath.toArgb(Hsv(it * 60f, 1f, 1f))) }

/** The swatch that opens the colour picker: a colour wheel's ring around the colour picked there (white until then). */
@Composable
private fun RainbowSwatch(color: Int?, selected: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Brush.sweepGradient(RAINBOW))
            .border(if (selected) 3.dp else 0.dp, Tokens.Yellow, CircleShape)
            .clickable(onClickLabel = description, onClick = onClick).padding(if (selected) 7.dp else 6.dp).clip(CircleShape)
            .background(color?.let { Color(it or (0xFF shl 24)) } ?: Tokens.Card),
        contentAlignment = Alignment.Center,
    ) {
        // An icon, not a "+" character: a glyph sits on its baseline and looks low in the circle.
        if (color == null) Icon(Icons.Filled.Add, contentDescription = null, tint = Tokens.Ink, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun Swatch(color: Int?, selected: Boolean, description: String? = null, onClick: () -> Unit) {
    val none = color == null || android.graphics.Color.alpha(color) == 0
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) Tokens.Yellow else Tokens.Ink.copy(alpha = 0.15f), CircleShape)
            .clickable(onClickLabel = description, onClick = onClick).padding(if (selected) 4.dp else 0.dp).clip(CircleShape)
            .background(if (none) Tokens.Card else Color(color!!)),
        contentAlignment = Alignment.Center,
    ) {
        // "Auto" and "None" have no colour of their own: a slash says so.
        if (none) Text("⁄", color = Tokens.InkSoft, fontSize = 18.sp)
    }
}

/**
 * A colour row: Auto (when [auto]; null), a few presets, and the colour's hex code, which opens the full picker
 * ([ColorPickerPopup]). The picker previews on the page as it changes; Cancel puts the first colour back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorRow(
    label: String, color: Int?, presets: List<Int>, auto: Boolean, withAlpha: Boolean, onColor: (Int?) -> Unit,
    recent: List<Int> = emptyList(),
) {
    var picking by remember { mutableStateOf(false) }
    // The colour when the picker opened: what Cancel puts back (the picker previews every change on the page).
    var openedWith by remember { mutableStateOf<Int?>(null) }
    fun same(a: Int, b: Int?) = b != null && if (a ushr 24 == 0) b ushr 24 == 0 else b ushr 24 != 0 && (a and 0xFFFFFF) == (b and 0xFFFFFF)
    PanelLabel(label)
    // The swatches wrap rather than scroll, so the last one is never cut off or covered; the rainbow one at the end opens
    // the full picker and shows the colour picked there.
    val custom = color != null && presets.none { same(it, color) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (auto) Swatch(null, color == null, stringResource(UiR.string.studio_color_auto)) { onColor(null) }
        for (c in presets) Swatch(c, same(c, color)) { onColor(c) }
        RainbowSwatch(if (custom) color else null, custom, stringResource(UiR.string.studio_color_custom)) { openedWith = color; picking = true }
    }
    if (picking) ColorPickerPopup(
        initial = openedWith?.takeIf { it ushr 24 != 0 } ?: (0xFF shl 24), withAlpha = withAlpha, recent = recent,
        onPreview = { onColor(it) }, onCancel = { onColor(openedWith) }, onClose = { picking = false },
    )
}

/**
 * The full colour picker: saturation / brightness square, hue, opacity (when [withAlpha]), hex and RGB, the colours
 * already on the page, and the old and new colour side by side. A sheet on a phone (no scrim: the page keeps its true
 * colours above it), a card on a wide screen. Every change previews at once through [onPreview].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColorPickerPopup(initial: Int, withAlpha: Boolean, recent: List<Int>, onPreview: (Int) -> Unit, onCancel: () -> Unit, onClose: () -> Unit) {
    val start = remember { initial }
    var current by remember { mutableIntStateOf(initial) }
    val set: (Int) -> Unit = { current = it; onPreview(it) }
    val cancel = { onCancel(); onClose() }
    val body: @Composable () -> Unit = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(UiR.string.studio_color_title), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 18.sp, color = Tokens.Ink)
            Spacer(Modifier.height(12.dp))
            ColorPickerBody(current, withAlpha, set)
            if (recent.isNotEmpty()) {
                PanelLabel(stringResource(UiR.string.studio_color_recent))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { c -> Swatch(c, (c and 0xFFFFFF) == (current and 0xFFFFFF)) { set(if (withAlpha) c else c or (0xFF shl 24)) } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).border(1.dp, Tokens.Border, CircleShape).background(Color(start)))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Tokens.InkSoft, modifier = Modifier.padding(horizontal = 6.dp).size(16.dp))
                Box(Modifier.size(26.dp).clip(CircleShape).border(1.dp, Tokens.Border, CircleShape).background(Color(current)))
                Spacer(Modifier.weight(1f))
                TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, cancel)
                Spacer(Modifier.width(4.dp))
                PrimaryPill(stringResource(UiR.string.studio_color_use).uppercase(), letterSpaced = true, onClick = onClose)
            }
        }
    }
    if (widthClass().wide) StickerDialog(onDismiss = cancel) { body() }
    else ModalBottomSheet(
        onDismissRequest = cancel, containerColor = Tokens.Card, scrimColor = Color.Transparent,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 20.dp)) { body() }
    }
}

/** Square, hue bar, opacity bar and the code fields; every change is reported at once. */
@Composable
private fun ColorPickerBody(argb: Int, withAlpha: Boolean, onColor: (Int) -> Unit) {
    val alpha = argb ushr 24
    // Hue is kept here: a grey or black colour has none of its own, and dragging through one must not lose it.
    var hsv by remember { mutableStateOf(ColorMath.toHsv(argb)) }
    LaunchedEffect(argb) { if (ColorMath.toArgb(hsv, alpha) != argb) hsv = ColorMath.toHsv(argb) }
    val latest by rememberUpdatedState(hsv)
    val report by rememberUpdatedState(onColor)
    val alphaNow by rememberUpdatedState(alpha)
    fun emit(next: Hsv) { hsv = next; report(ColorMath.toArgb(next, alphaNow)) }

    val hueColor = Color(ColorMath.toArgb(Hsv(hsv.h, 1f, 1f)))
    Canvas(
        Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                fun at(p: Offset) = emit(latest.copy(s = (p.x / size.width).coerceIn(0f, 1f), v = 1f - (p.y / size.height).coerceIn(0f, 1f)))
                detectTapGestures { at(it) }
            }
            .pointerInput(Unit) {
                fun at(p: Offset) = emit(latest.copy(s = (p.x / size.width).coerceIn(0f, 1f), v = 1f - (p.y / size.height).coerceIn(0f, 1f)))
                detectDragGestures(onDragStart = { at(it) }) { ch, _ -> ch.consume(); at(ch.position) }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val c = Offset(hsv.s * size.width, (1f - hsv.v) * size.height)
        drawCircle(Color.White, 10.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black, 11.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
    }
    PanelLabel(stringResource(UiR.string.studio_hue), value = "${hsv.h.toInt()}°")
    HueBar(hsv.h) { h -> emit(latest.copy(h = h)) }
    if (withAlpha) {
        PanelLabel(stringResource(UiR.string.studio_opacity), value = "${alpha * 100 / 255}%")
        AlphaBar(argb) { a -> report((argb and 0xFFFFFF) or (a shl 24)) }
    }
    Spacer(Modifier.height(12.dp))
    HexField(argb, withAlpha, onColor)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        ChannelField("R", r, Modifier.weight(1f)) { v -> onColor((argb and 0xFF00FFFF.toInt()) or (v shl 16)) }
        ChannelField("G", g, Modifier.weight(1f)) { v -> onColor((argb and 0xFFFF00FF.toInt()) or (v shl 8)) }
        ChannelField("B", b, Modifier.weight(1f)) { v -> onColor((argb and 0xFFFFFF00.toInt()) or v) }
    }
}

/** Opacity over a checkerboard, from clear to the colour; it only takes sideways drags. */
@Composable
private fun AlphaBar(argb: Int, onAlpha: (Int) -> Unit) {
    fun at(x: Float, w: Int) = ((x / w).coerceIn(0f, 1f) * 255).toInt()
    val solid = Color(argb or (0xFF shl 24))
    Canvas(
        Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp))
            .pointerInput(Unit) { detectTapGestures { onAlpha(at(it.x, size.width)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { onAlpha(at(it.x, size.width)) }) { ch, _ -> ch.consume(); onAlpha(at(ch.position.x, size.width)) }
            },
    ) {
        val cell = 6.dp.toPx()
        var x = 0f
        var col = 0
        while (x < size.width) {
            var y = 0f
            var row = col
            while (y < size.height) {
                drawRect(if (row % 2 == 0) Color(0xFFDDDDDD) else Color.White, Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell))
                y += cell; row++
            }
            x += cell; col++
        }
        drawRect(Brush.horizontalGradient(listOf(solid.copy(alpha = 0f), solid)))
        val r = size.height / 2f
        val c = Offset(((argb ushr 24) / 255f * size.width).coerceIn(r, size.width - r), r)
        drawCircle(Color.White, r - 2.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black, r - 0.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
    }
}

/** One of R, G, B as a number to type (0–255). */
@Composable
private fun ChannelField(label: String, value: Int, modifier: Modifier, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Tokens.Border, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.sp, color = Tokens.InkSoft)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = text,
            onValueChange = { t ->
                val clean = t.filter { it.isDigit() }.take(3)
                text = clean
                clean.toIntOrNull()?.takeIf { it in 0..255 }?.let(onValue)
            },
            singleLine = true, textStyle = TextStyle(color = Tokens.Ink, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Tokens.Ink), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
