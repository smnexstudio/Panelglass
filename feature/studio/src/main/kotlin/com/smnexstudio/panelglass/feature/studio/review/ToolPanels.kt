package com.smnexstudio.panelglass.feature.studio.review

import com.smnexstudio.panelglass.core.ui.verticalScrollbar
import com.smnexstudio.panelglass.core.ui.fonts.FontBrowserSheet
import com.smnexstudio.panelglass.core.ui.fonts.fontBrowserInSheet
import com.smnexstudio.panelglass.core.ui.fonts.FontBrowser
import com.smnexstudio.panelglass.core.ui.fonts.FontField
import com.smnexstudio.panelglass.core.ui.fonts.FontChoices
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.model.BrushMode
import com.smnexstudio.panelglass.core.model.BubbleStyle
import com.smnexstudio.panelglass.core.model.FontRole
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.SectionLabel
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import kotlin.math.roundToInt
import com.smnexstudio.panelglass.core.ui.R as UiR

/** Text colours offered for lettering (ARGB); "Auto" (null) picks ink that reads on what is under the text. */
private val TEXT_COLOURS = listOf(0xFF000000, 0xFFFFFFFF, 0xFF1F3A93, 0xFFC0392B, 0xFF27AE60, 0xFF8E44AD, 0xFFF39C12, 0xFF7F8C8D).map { it.toInt() }
/** Fills: transparent (keep the art) first. */
private val FILL_COLOURS = listOf(0x00000000, 0xFFFFFFFF, 0xFF000000, 0xFFF5F0E6, 0xFFFFF3B0, 0xFFFFD6E0, 0xFFD6ECFF, 0xFFDFF5DC).map { it.toLong().toInt() }
private val HUES = (0..6).map { Color(ColorMath.toArgb(Hsv(it * 60f, 1f, 1f))) }

/**
 * A tool's options, shown in the editor pane under the page instead of over it, so every change is seen on the page
 * as it is made. [onDone] closes the tool.
 */
@Composable
internal fun ToolPanel(title: String, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // No Done: changes are saved as they are made, and the tool closes from the tool row (tap it again, or the first).
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 16.sp, color = Tokens.Ink, modifier = Modifier.weight(1f))
        }
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScrollbar(scroll).verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            content()
        }
    }
}

/**
 * Text style for the selected bubble (design canvas, step 6): font, size (Auto or 8–120 px) with bold / italic /
 * capitals, colour, outline and its colour, and whether the changes go to this bubble or every bubble on the page.
 */
@Composable
internal fun TextStylePanel(
    style: BubbleStyle, tgt: Lang, choices: FontChoices, onStyle: (BubbleStyle) -> Unit, onApplyAll: (BubbleStyle) -> Unit,
    role: FontRole = FontRole.DIALOGUE, pageColours: List<Int> = emptyList(),
) {
    // The font browser takes the panel's place on a wide screen and opens as a sheet on a phone; either way every font
    // tapped there shows on the page at once.
    var browsing by rememberSaveable { mutableStateOf(false) }
    var everyBubble by rememberSaveable { mutableStateOf(false) }
    val change: (BubbleStyle) -> Unit = { s -> onStyle(s); if (everyBubble) onApplyAll(s) }
    val inSheet = fontBrowserInSheet()
    if (browsing && !inSheet) {
        FontBrowser(style.fontId, tgt, role, choices, onFont = { change(style.copy(fontId = it)) }, onBack = { browsing = false })
        return
    }
    if (browsing) FontBrowserSheet(style.fontId, tgt, role, choices, onFont = { change(style.copy(fontId = it)) }, onDismiss = { browsing = false })
    PanelLabel(stringResource(UiR.string.studio_font))
    FontField(style.fontId, tgt, role, onClick = { browsing = true })
    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) {
            ValueSlider(
                stringResource(UiR.string.studio_size), style.sizePx?.let { "${it.roundToInt()} px" } ?: "",
                style.sizePx ?: 32f, 8f..120f, onChange = { change(style.copy(sizePx = it)) },
                trailing = {
                    Text(
                        stringResource(UiR.string.studio_size_auto), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.sp,
                        color = if (style.sizePx == null) Tokens.Card else Tokens.Ink,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (style.sizePx == null) Tokens.Ink else Tokens.Ink.copy(alpha = 0.06f))
                            .clickable { change(style.copy(sizePx = if (style.sizePx == null) 32f else null)) }.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        ToggleGroup(
            listOf(
                ToggleItem("B", style.bold, stringResource(UiR.string.studio_bold), FontWeight.W900) { change(style.copy(bold = !style.bold)) },
                ToggleItem("I", style.italic, stringResource(UiR.string.studio_italic), italic = true) { change(style.copy(italic = !style.italic)) },
                ToggleItem("AA", style.uppercase, stringResource(UiR.string.studio_uppercase)) { change(style.copy(uppercase = !style.uppercase)) },
            ),
            Modifier.padding(bottom = 10.dp),
        )
    }
    ColorRow(
        stringResource(UiR.string.studio_text_color), style.textColor, TEXT_COLOURS, auto = true, withAlpha = false,
        onColor = { change(style.copy(textColor = it)) }, recent = pageColours,
    )
    val none = stringResource(UiR.string.studio_outline_none)
    val thin = stringResource(UiR.string.studio_outline_thin)
    val thick = stringResource(UiR.string.studio_outline_thick)
    PanelLabel(stringResource(UiR.string.studio_outline))
    OptionGroup(
        listOf(0f, 2f, 4f), when { style.outlineWidthPx == 0f -> 0f; style.outlineWidthPx <= 2.5f -> 2f; else -> 4f },
        label = { when (it) { 0f -> none; 2f -> thin; else -> thick } }, onSelect = { change(style.copy(outlineWidthPx = it)) },
    )
    if (style.outlineWidthPx > 0f) ColorRow(
        stringResource(UiR.string.studio_outline_color), style.outlineColor, TEXT_COLOURS, auto = true, withAlpha = false,
        onColor = { change(style.copy(outlineColor = it)) }, recent = pageColours,
    )
    ApplyTo(everyBubble) { every -> everyBubble = every; if (every) onApplyAll(style) }
    Spacer(Modifier.height(8.dp))
}

/** The bubble's fill under its text (design canvas, step 10): none (keep the art) or a colour, with an opacity. */
@Composable
internal fun FillPanel(style: BubbleStyle, onStyle: (BubbleStyle) -> Unit, onApplyAll: (BubbleStyle) -> Unit, pageColours: List<Int> = emptyList()) {
    var everyBubble by rememberSaveable { mutableStateOf(false) }
    val change: (BubbleStyle) -> Unit = { s -> onStyle(s); if (everyBubble) onApplyAll(s) }
    val alpha = style.fillColor ushr 24
    val rgb = style.fillColor and 0x00FFFFFF
    val filled = alpha > 0
    val noFill = stringResource(UiR.string.studio_fill_mode_none)
    val colour = stringResource(UiR.string.studio_fill_mode_colour)
    Spacer(Modifier.height(4.dp))
    OptionGroup(
        listOf(false, true), filled, label = { if (it) colour else noFill },
        // Colour starts from white at full opacity, the usual patch over a cleanup's mark.
        onSelect = { on -> change(style.copy(fillColor = if (on) (if (rgb == 0) 0xFFFFFF else rgb) or (0xFF shl 24) else 0)) },
    )
    if (filled) {
        ColorRow(
            stringResource(UiR.string.studio_fill_color), style.fillColor, FILL_COLOURS.filter { it ushr 24 != 0 }, auto = false, withAlpha = true,
            // A new colour keeps the opacity chosen.
            onColor = { c -> c?.let { change(style.copy(fillColor = (it and 0xFFFFFF) or ((if (it ushr 24 == 0xFF) alpha else it ushr 24).coerceAtLeast(1) shl 24))) } },
            recent = pageColours,
        )
        ValueSlider(
            stringResource(UiR.string.studio_opacity), "${(alpha * 100 / 255f).roundToInt()}%", alpha / 255f, 0.05f..1f,
            onChange = { change(style.copy(fillColor = rgb or ((it * 255).roundToInt().coerceIn(1, 255) shl 24))) },
        )
    }
    ApplyTo(everyBubble) { every -> everyBubble = every; if (every) onApplyAll(style) }
    PanelNote(stringResource(UiR.string.studio_fill_note))
    Spacer(Modifier.height(8.dp))
}

/**
 * While reshaping (design canvas, step 11): how the points work, the preset shapes, and the bubble's own border (a
 * line along the shape, in any colour).
 */
@Composable
internal fun ReshapePanel(
    onRectangle: () -> Unit, onEllipse: () -> Unit,
    style: BubbleStyle? = null, onStyle: (BubbleStyle) -> Unit = {}, pageColours: List<Int> = emptyList(),
) {
    Text(stringResource(UiR.string.studio_reshape_hint), fontFamily = Jakarta, fontSize = 13.5.sp, lineHeight = 20.sp, color = Tokens.Ink, modifier = Modifier.padding(top = 4.dp))
    PanelLabel(stringResource(UiR.string.studio_shape_preset))
    val rect = stringResource(UiR.string.studio_shape_rect)
    val ellipse = stringResource(UiR.string.studio_shape_ellipse)
    OptionGroup(listOf(true, false), null, label = { if (it) rect else ellipse }, onSelect = { if (it) onRectangle() else onEllipse() })
    if (style != null) {
        // Sharp to round: the corners of the shape as drawn (fill, lettering and border follow it).
        ValueSlider(
            stringResource(UiR.string.studio_corners),
            if (style.cornerRoundness <= 0f) stringResource(UiR.string.studio_corners_sharp) else "${(style.cornerRoundness * 100).roundToInt()}%",
            style.cornerRoundness, 0f..1f, onChange = { onStyle(style.copy(cornerRoundness = if (it < 0.02f) 0f else it)) },
        )
        val none = stringResource(UiR.string.studio_outline_none)
        val thin = stringResource(UiR.string.studio_outline_thin)
        val thick = stringResource(UiR.string.studio_outline_thick)
        PanelLabel(stringResource(UiR.string.studio_bubble_border))
        OptionGroup(
            listOf(0f, 3f, 7f), when { style.borderWidthPx == 0f -> 0f; style.borderWidthPx <= 4f -> 3f; else -> 7f },
            label = { when (it) { 0f -> none; 3f -> thin; else -> thick } }, onSelect = { onStyle(style.copy(borderWidthPx = it)) },
        )
        if (style.borderWidthPx > 0f) ColorRow(
            stringResource(UiR.string.studio_bubble_border_color), style.borderColor ?: (0xFF000000).toInt(), TEXT_COLOURS, auto = false, withAlpha = false,
            onColor = { onStyle(style.copy(borderColor = it)) }, recent = pageColours,
        )
    }
    PanelNote(stringResource(UiR.string.studio_reshape_note))
    Spacer(Modifier.height(8.dp))
}

/** While the brush is on (design canvas, step 12): what a stroke does, its size, smart fill, the strokes so far. */
@Composable
internal fun BrushPanel(
    mode: BrushMode, radius: Float, onMode: (BrushMode) -> Unit, onRadius: (Float) -> Unit, lamaHint: Boolean = false,
    strokes: Int = 0, onUndo: () -> Unit = {},
) {
    val clean = stringResource(UiR.string.studio_brush_clean)
    val restore = stringResource(UiR.string.studio_brush_restore)
    Spacer(Modifier.height(4.dp))
    OptionGroup(BrushMode.entries, mode, label = { if (it == BrushMode.CLEAN) clean else restore }, onSelect = onMode)
    ValueSlider(stringResource(UiR.string.studio_brush_size), "${(radius * 2).roundToInt()} px", radius, 4f..80f, onChange = onRadius)
    PanelSwitch(
        stringResource(UiR.string.studio_smart_fill), stringResource(if (lamaHint) UiR.string.studio_lama_hint else UiR.string.studio_smart_fill_on),
        checked = !lamaHint, enabled = false, onChange = {},
    )
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(pluralStringResource(UiR.plurals.studio_brush_strokes, strokes, strokes), fontFamily = Jakarta, fontSize = 13.sp, color = Tokens.InkSoft, modifier = Modifier.weight(1f))
        if (strokes > 0) TextAction(stringResource(UiR.string.studio_undo_last), onClick = onUndo)
    }
    Spacer(Modifier.height(8.dp))
}

/** While adding (design canvas, step 14): what the box becomes, and how to draw it. */
@Composable
internal fun AddTextPanel(sfx: Boolean, onKind: (Boolean) -> Unit) {
    Text(stringResource(UiR.string.studio_add_text_hint), fontFamily = Jakarta, fontSize = 13.5.sp, lineHeight = 20.sp, color = Tokens.Ink, modifier = Modifier.padding(top = 4.dp))
    PanelLabel(stringResource(UiR.string.studio_add_as))
    val text = stringResource(UiR.string.studio_add_kind_text)
    val effect = stringResource(UiR.string.studio_add_kind_sfx)
    OptionGroup(listOf(false, true), sfx, label = { if (it) effect else text }, onSelect = onKind)
    PanelNote(stringResource(UiR.string.studio_add_reads))
    Spacer(Modifier.height(8.dp))
}

// ---- colour -----------------------------------------------------------------------------------------------------

/** A colour row (the older name, kept for the sound-effect panel): see [ColorRow]. */
@Composable
internal fun ColorChoice(label: String, color: Int?, presets: List<Int>, auto: Boolean, withAlpha: Boolean, onColor: (Int?) -> Unit) =
    ColorRow(label, color, presets, auto, withAlpha, onColor)

/** The hue bar; it only takes sideways drags, so the panel still scrolls through it. */
@Composable
internal fun HueBar(hue: Float, onHue: (Float) -> Unit) {
    fun at(x: Float, w: Int) = (x / w).coerceIn(0f, 1f) * 359.9f
    Canvas(
        Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp))
            .pointerInput(Unit) { detectTapGestures { onHue(at(it.x, size.width)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { onHue(at(it.x, size.width)) }) { ch, _ -> ch.consume(); onHue(at(ch.position.x, size.width)) }
            },
    ) {
        drawRect(Brush.horizontalGradient(HUES))
        val r = size.height / 2f
        val c = Offset(((hue / 360f) * size.width).coerceIn(r, size.width - r), r)
        drawCircle(Color(ColorMath.toArgb(Hsv(hue, 1f, 1f))), r - 2.dp.toPx(), c)
        drawCircle(Color.White, r - 2.dp.toPx(), c, style = Stroke(3.dp.toPx()))
    }
}

/** The colour as a hex code to read or type; a code is applied as soon as it is complete. */
@Composable
internal fun HexField(argb: Int, withAlpha: Boolean, onColor: (Int) -> Unit) {
    var text by remember { mutableStateOf(ColorMath.format(argb, withAlpha)) }
    var editing by remember { mutableStateOf(false) }
    // The field follows the square and the bar, but never rewrites what is being typed.
    LaunchedEffect(argb) { if (!editing) text = ColorMath.format(argb, withAlpha) }
    val valid = ColorMath.parse(text, withAlpha, argb ushr 24) != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Tokens.Border, RoundedCornerShape(8.dp)).background(Color(argb)))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(UiR.string.studio_color_hex), fontFamily = Jakarta, fontSize = 12.sp, color = Tokens.InkSoft)
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                .border(1.dp, if (valid) Tokens.Border else MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text("#", color = Tokens.InkSoft, fontSize = 15.sp)
            BasicTextField(
                value = text,
                onValueChange = { t ->
                    val clean = t.removePrefix("#").filter { it.isLetterOrDigit() }.take(if (withAlpha) 8 else 6).uppercase()
                    text = clean
                    ColorMath.parse(clean, withAlpha, argb ushr 24)?.takeIf { clean.length >= 6 }?.let(onColor)
                },
                singleLine = true,
                textStyle = TextStyle(color = Tokens.Ink, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Tokens.Ink),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { ColorMath.parse(text, withAlpha, argb ushr 24)?.let(onColor) }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                    editing = f.isFocused
                    if (!f.isFocused) text = ColorMath.format(argb, withAlpha)
                },
            )
        }
    }
}
