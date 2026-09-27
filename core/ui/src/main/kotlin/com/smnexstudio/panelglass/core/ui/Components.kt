package com.smnexstudio.panelglass.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.model.AppTheme

// ---- text ------------------------------------------------------------------------------------

/**
 * Theme-aware section header label (e.g. comic banner for Panel Pop, italic serif for Paper & Ink). [pinned] marks the
 * pinned-sites header, which some themes style apart. [inset] is the side margin: 16 dp on a screen, 0 inside a dialog
 * or sheet whose content is already padded, so the label lines up with the card under it.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, pinned: Boolean = false, inset: Dp = 16.dp) {
    val theme = LocalAppTheme.current
    when (theme) {
        AppTheme.PANEL_POP -> {
            val isPinned = pinned
            val badgeBg = if (isPinned) Color(0xFFFF3B30) else Color(0xFF111318)
            val textColor = if (isPinned) Color.White else Color(0xFFFFD23F)
            Box(modifier = modifier.padding(start = inset, top = 18.dp, bottom = 8.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .border(2.dp, Color(0xFF111318), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        text.uppercase(),
                        fontFamily = Jakarta,
                        fontWeight = FontWeight.W800,
                        fontSize = 11.5.sp,
                        letterSpacing = 0.08.em,
                        color = textColor,
                    )
                }
            }
        }
        AppTheme.SOFT_BLOOM -> {
            val isPinned = pinned
            Row(
                modifier = modifier.padding(start = inset, top = 18.dp, end = inset, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = null,
                        tint = Color(0xFFB7A8F0),
                        modifier = Modifier.size(14.dp).padding(end = 4.dp),
                    )
                }
                Text(
                    text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                    fontFamily = Jakarta,
                    fontWeight = FontWeight.W700,
                    fontSize = 13.sp,
                    color = Color(0xFFB7A8F0),
                )
            }
        }
        AppTheme.PAPER_INK -> {
            Text(
                text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.W500,
                fontSize = 14.sp,
                color = Tokens.InkSoft,
                modifier = modifier.padding(start = inset, top = 18.dp, end = inset, bottom = 6.dp),
            )
        }
        AppTheme.DEFAULT -> {
            Text(
                text.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.InkFaint,
                modifier = modifier.padding(start = inset, top = 18.dp, end = inset, bottom = 8.dp),
            )
        }
    }
}

/** Smaller sheet-section label: 11.5 sp / 700 / 0.04 em. */
@Composable
fun SheetSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontFamily = Jakarta,
        fontWeight = FontWeight.W700,
        fontSize = 11.5.sp,
        letterSpacing = 0.04.em,
        color = Tokens.InkFaint,
        modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
    )
}

// ---- surfaces --------------------------------------------------------------------------------

/** Theme-aware card container (Panel Pop sticker shadow, Soft Bloom diffuse glow, Paper & Ink hairline dividers). */
@Composable
fun TokenCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalAppTheme.current
    when (theme) {
        AppTheme.PANEL_POP -> {
            val shape = RoundedCornerShape(Radii.card)
            Box(modifier = modifier.fillMaxWidth().padding(end = 3.5.dp, bottom = 3.5.dp)) {
                Box(
                    Modifier
                        .matchParentSize()
                        .offset(x = 3.5.dp, y = 3.5.dp)
                        .clip(shape)
                        .background(Color(0xFF111318)),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Tokens.Card)
                        .border(2.5.dp, Color(0xFF111318), shape),
                    content = content,
                )
            }
        }
        AppTheme.SOFT_BLOOM -> {
            val shape = RoundedCornerShape(Radii.card)
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val rPx = 22.dp.toPx()
                        drawRoundRect(
                            color = Color(0x30B7A8F0),
                            topLeft = Offset(0f, 3.dp.toPx()),
                            size = size,
                            cornerRadius = CornerRadius(rPx, rPx),
                        )
                    },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Tokens.Card)
                        .border(1.dp, Tokens.Border, shape),
                    content = content,
                )
            }
        }
        AppTheme.PAPER_INK -> {
            Column(
                modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
                    .border(1.dp, Tokens.Border, RoundedCornerShape(Radii.card)),
                content = content,
            )
        }
        AppTheme.DEFAULT -> {
            Column(
                modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.card))
                    .background(Tokens.Card)
                    .border(1.dp, Tokens.Border, RoundedCornerShape(Radii.card)),
                content = content,
            )
        }
    }
}

/** Theme-styled card/row container used for site cards and history rows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val theme = LocalAppTheme.current
    when (theme) {
        AppTheme.PANEL_POP -> {
            val shape = RoundedCornerShape(Radii.card)
            Box(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).padding(end = 3.5.dp, bottom = 3.5.dp)) {
                Box(
                    Modifier
                        .matchParentSize()
                        .offset(x = 3.5.dp, y = 3.5.dp)
                        .clip(shape)
                        .background(Color(0xFF111318)),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Tokens.Card)
                        .border(2.5.dp, Color(0xFF111318), shape)
                        .then(
                            if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                            else Modifier.clickable(onClick = onClick),
                        )
                        .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }
        AppTheme.SOFT_BLOOM -> {
            val shape = RoundedCornerShape(Radii.card)
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .drawBehind {
                        val rPx = 22.dp.toPx()
                        drawRoundRect(
                            color = Color(0x30B7A8F0),
                            topLeft = Offset(0f, 3.dp.toPx()),
                            size = size,
                            cornerRadius = CornerRadius(rPx, rPx),
                        )
                    },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Tokens.Card)
                        .border(1.dp, Tokens.Border, shape)
                        .then(
                            if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                            else Modifier.clickable(onClick = onClick),
                        )
                        .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }
        AppTheme.PAPER_INK -> {
            Column(modifier = modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                            else Modifier.clickable(onClick = onClick),
                        )
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
                HorizontalDivider(thickness = 1.dp, color = Tokens.Border)
            }
        }
        AppTheme.DEFAULT -> {
            val shape = RoundedCornerShape(Radii.card)
            Row(
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(shape)
                    .background(Tokens.Card)
                    .border(1.dp, Tokens.Border, shape)
                    .then(
                        if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                        else Modifier.clickable(onClick = onClick),
                    )
                    .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
fun CardDivider() = HorizontalDivider(thickness = 1.dp, color = Tokens.Border)

/**
 * One tappable row inside a [TokenCard]: leading icon, label, value (SkyDeep/600, ellipsised) right-aligned against
 * the chevron at the card's edge.
 */
@Composable
fun ValueRow(label: String, value: String, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(12.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink, modifier = Modifier.weight(1f))
        // The value fills its share and sits against the chevron, at the card's edge like every row's action.
        Text(
            value, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 14.sp, color = Tokens.SkyDeep,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f).padding(start = 10.dp),
        )
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Tokens.InkFaint, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

/** Card row with a title, a subtitle and a trailing text action (Download / Delete / Cancel / Clear). */
@Composable
fun ActionRow(title: String, subtitle: String, action: String?, actionColor: Color = Tokens.SkyDeep, onAction: () -> Unit = {}, below: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(top = 2.dp))
            }
            if (action != null) TextAction(action, actionColor, onAction)
        }
        below?.invoke()
    }
}

/** Text button in the token colours (SkyDeep for downloads, Error for delete/clear, InkSoft for cancel). */
@Composable
fun TextAction(text: String, color: Color = Tokens.SkyDeep, onClick: () -> Unit) {
    Text(
        text, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, color = color,
        modifier = Modifier.clip(RoundedCornerShape(Radii.round)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** Toggle row inside a card. */
@Composable
fun ToggleCardRow(label: String, checked: Boolean, secondary: String? = null, onChange: (Boolean) -> Unit) {
    val theme = LocalAppTheme.current
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink)
            if (secondary != null) Text(secondary, style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = if (theme == AppTheme.SOFT_BLOOM) Color.White else Tokens.Ink,
                checkedTrackColor = Tokens.Yellow,
                uncheckedThumbColor = Tokens.Card,
                uncheckedTrackColor = Tokens.Border,
                uncheckedBorderColor = Tokens.Border,
            ),
        )
    }
}

// ---- tiles, chips, buttons -------------------------------------------------------------------

/** Rounded square with the first letter of [name] on a theme-rotated tint. */
@Composable
fun IconTile(name: String, size: Int = 44, radius: Int = 12, tint: Color = Tokens.tileFor(name)) {
    val theme = LocalAppTheme.current
    val tileRadius = when (theme) {
        AppTheme.PANEL_POP -> 10
        AppTheme.SOFT_BLOOM -> 16
        AppTheme.PAPER_INK -> 4
        AppTheme.DEFAULT -> radius
    }
    // Letter colour from the tile's own lightness, not the theme: the engine picker's pale provider tints need dark
    // ink in every theme, Panel Pop's red and blue need white.
    val textColor = if (tint.luminance() > 0.5f) Tokens.Ink else Color.White
    val tileModifier = Modifier
        .size(size.dp)
        .clip(RoundedCornerShape(tileRadius.dp))
        .background(tint)
        .then(if (theme == AppTheme.PANEL_POP) Modifier.border(2.5.dp, Color(0xFF111318), RoundedCornerShape(tileRadius.dp)) else Modifier)

    Box(
        modifier = tileModifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase(),
            fontFamily = if (theme == AppTheme.PAPER_INK) FontFamily.Serif else Jakarta,
            fontWeight = FontWeight.W800,
            fontSize = (size * 0.42f).sp,
            color = textColor,
        )
    }
}

/** Theme-styled primary button. */
@Composable
fun PrimaryPill(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, letterSpaced: Boolean = false) {
    val theme = LocalAppTheme.current
    val bg = if (enabled) Tokens.Yellow else Tokens.Border
    val shape = RoundedCornerShape(Radii.pill)
    val pillModifier = modifier
        .clip(shape)
        .background(bg)
        .then(if (theme == AppTheme.PANEL_POP && enabled) Modifier.border(2.5.dp, Color(0xFF111318), shape) else Modifier)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 22.dp, vertical = 12.dp)

    Box(
        modifier = pillModifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontFamily = Jakarta,
            fontWeight = FontWeight.W800,
            fontSize = 14.sp,
            letterSpacing = if (letterSpaced) 0.08.em else 0.em,
            // Soft Bloom's lavender and Paper & Ink's indigo pills are dark: light text on them.
            color = when {
                !enabled -> Tokens.InkFaint
                theme == AppTheme.SOFT_BLOOM -> Color.White
                theme == AppTheme.PAPER_INK -> Tokens.Bg
                else -> Tokens.Ink
            },
        )
    }
}

/** Dashed row — the "+ Add site" row styled per theme. */
@Composable
fun DashedRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    when (theme) {
        AppTheme.PANEL_POP -> {
            Box(
                modifier = modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(Radii.card)).background(Tokens.Card).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val stroke = Stroke(width = 2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
                    val r = 10.dp.toPx()
                    drawRoundRect(color = Color(0xFF111318), cornerRadius = CornerRadius(r, r), style = stroke)
                }
                Text(text, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 15.sp, color = Color(0xFF111318))
            }
        }
        AppTheme.SOFT_BLOOM -> {
            Box(
                modifier = modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(Radii.card)).background(Color(0xFFF6F0FE)).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val stroke = Stroke(width = 1.8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
                    val r = 22.dp.toPx()
                    drawRoundRect(color = Color(0xFFB7A8F0), cornerRadius = CornerRadius(r, r), style = stroke)
                }
                Text(text, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, color = Color(0xFF9C87E8))
            }
        }
        AppTheme.PAPER_INK -> {
            Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
                Text(
                    text,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.W600,
                    fontSize = 15.sp,
                    color = Tokens.InkSoft,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
                HorizontalDivider(thickness = 1.dp, color = Tokens.Border)
            }
        }
        AppTheme.DEFAULT -> {
            val strokeColor = Tokens.YellowDeep
            Box(
                modifier = modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(Radii.card)).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val stroke = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx())))
                    val r = 16.dp.toPx()
                    drawRoundRect(color = strokeColor, cornerRadius = CornerRadius(r, r), style = stroke)
                }
                Text(text, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 15.sp, color = Tokens.Ink)
            }
        }
    }
}

/** Theme-aware search field. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, onGo: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    when (theme) {
        AppTheme.PAPER_INK -> {
            Column(modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, fontFamily = FontFamily.Serif, fontSize = 15.sp, color = Tokens.InkFaint)
                        BasicTextField(
                            value = value, onValueChange = onValueChange, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tokens.Ink, fontFamily = FontFamily.Serif),
                            cursorBrush = SolidColor(Tokens.Ink),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { onGo() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = Tokens.Border)
            }
        }
        AppTheme.PANEL_POP -> {
            val shape = RoundedCornerShape(Radii.card)
            Row(
                modifier = modifier.fillMaxWidth().height(48.dp)
                    .clip(shape).background(Tokens.Card)
                    .border(2.5.dp, Color(0xFF111318), shape).padding(start = 14.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF111318), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Tokens.InkFaint)
                    BasicTextField(
                        value = value, onValueChange = onValueChange, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFF111318), fontWeight = FontWeight.W600),
                        cursorBrush = SolidColor(Color(0xFF111318)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onGo() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        AppTheme.SOFT_BLOOM -> {
            val shape = RoundedCornerShape(Radii.pill)
            Box(
                modifier = modifier.fillMaxWidth().drawBehind {
                    val rPx = 24.dp.toPx()
                    drawRoundRect(
                        color = Color(0x28B7A8F0),
                        topLeft = Offset(0f, 2.dp.toPx()),
                        size = size,
                        cornerRadius = CornerRadius(rPx, rPx),
                    )
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                        .clip(shape).background(Tokens.Card)
                        .border(1.dp, Tokens.Border, shape).padding(start = 14.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFFB7A8F0), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Tokens.InkFaint)
                        BasicTextField(
                            value = value, onValueChange = onValueChange, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tokens.Ink),
                            cursorBrush = SolidColor(Tokens.YellowDeep),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { onGo() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        AppTheme.DEFAULT -> {
            Row(
                modifier = modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(Radii.round)).background(Tokens.Card)
                    .border(1.dp, Tokens.Border, RoundedCornerShape(Radii.round)).padding(start = 14.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Tokens.InkFaint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Tokens.InkFaint)
                    BasicTextField(
                        value = value, onValueChange = onValueChange, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tokens.Ink),
                        cursorBrush = SolidColor(Tokens.Ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onGo() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Filled circle with a check — the selected marker in pickers. */
@Composable
fun CheckDisc(selected: Boolean) {
    val theme = LocalAppTheme.current
    if (selected) {
        val bg = Tokens.Yellow
        val checkTint = if (theme == AppTheme.PANEL_POP) Color(0xFF111318) else if (theme == AppTheme.SOFT_BLOOM || theme == AppTheme.PAPER_INK) Color.White else Tokens.Ink
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(bg)
                .then(if (theme == AppTheme.PANEL_POP) Modifier.border(2.dp, Color(0xFF111318), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.cd_selected), tint = checkTint, modifier = Modifier.size(14.dp))
        }
    } else {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(if (theme == AppTheme.PANEL_POP) 2.dp else 1.5.dp, if (theme == AppTheme.PANEL_POP) Color(0xFF111318) else Tokens.InkFaint, CircleShape),
        )
    }
}

/** Four-point sparkle, YellowDeep — the "Translation model" row icon. */
@Composable
fun SparkleIcon(size: Int = 18, color: Color = Tokens.YellowDeep) {
    Canvas(Modifier.size(size.dp)) {
        val w = this.size.width; val h = this.size.height
        val cx = w / 2; val cy = h / 2; val pinch = 0.22f
        val p = Path().apply {
            moveTo(cx, 0f)
            quadraticTo(cx + w * pinch / 2, cy - h * pinch / 2, w, cy)
            quadraticTo(cx + w * pinch / 2, cy + h * pinch / 2, cx, h)
            quadraticTo(cx - w * pinch / 2, cy + h * pinch / 2, 0f, cy)
            quadraticTo(cx - w * pinch / 2, cy - h * pinch / 2, cx, 0f)
            close()
        }
        drawPath(p, color)
    }
}

// ---- navigation ------------------------------------------------------------------------------

class NavTab(val key: String, val label: String, val icon: ImageVector)

/**
 * Theme-aware bottom navigation bar:
 * - Default: White bar with yellow pill
 * - Panel Pop: Neo-brutalist panel pill with 2.5dp black border
 * - Soft Bloom: Floating pill-shaped nav bar with lavender circular active tab
 * - Paper & Ink: Minimal editorial tabs with muted indigo underline on active tab
 */
@Composable
fun PillNavBar(tabs: List<NavTab>, selected: String?, onSelect: (String) -> Unit) {
    val theme = LocalAppTheme.current
    when (theme) {
        AppTheme.SOFT_BLOOM -> {
            // Floating pill-shaped nav bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val rPx = 30.dp.toPx()
                            drawRoundRect(
                                color = Color(0x35B7A8F0),
                                topLeft = Offset(0f, 4.dp.toPx()),
                                size = size,
                                cornerRadius = CornerRadius(rPx, rPx),
                            )
                        }
                        .clip(RoundedCornerShape(30.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFEFE7FB), RoundedCornerShape(30.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (t in tabs) {
                            val active = t.key == selected
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (active) Color(0xFFB7A8F0) else Color.Transparent)
                                    .clickable { onSelect(t.key) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    t.icon,
                                    contentDescription = t.label,
                                    tint = if (active) Color.White else Color(0xFFA59AB8),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        AppTheme.PAPER_INK -> {
            // Minimal editorial bar with active item underline
            Column(Modifier.fillMaxWidth().background(Color(0xFFF7F5F0))) {
                HorizontalDivider(thickness = 1.dp, color = Color(0xFFE4DFD3))
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 10.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (t in tabs) {
                        val active = t.key == selected
                        Column(
                            Modifier.clickable { onSelect(t.key) }.padding(horizontal = 20.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                t.label,
                                fontFamily = FontFamily.Serif,
                                fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.W700 else FontWeight.W500,
                                color = if (active) Color(0xFF211E1A) else Color(0xFF6B6459),
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .width(26.dp)
                                    .height(2.5.dp)
                                    .background(if (active) Color(0xFF3A4562) else Color.Transparent),
                            )
                        }
                    }
                }
            }
        }
        AppTheme.PANEL_POP -> {
            Column(Modifier.fillMaxWidth().background(Color(0xFFFFF6E9))) {
                Box(Modifier.fillMaxWidth().height(2.5.dp).background(Color(0xFF111318)))
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (t in tabs) {
                        val active = t.key == selected
                        Column(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(t.key) }.padding(horizontal = 14.dp, vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(width = 54.dp, height = 30.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (active) Color(0xFFFFD23F) else Color.Transparent)
                                    .then(if (active) Modifier.border(2.5.dp, Color(0xFF111318), RoundedCornerShape(10.dp)) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(t.icon, contentDescription = t.label, tint = Color(0xFF111318), modifier = Modifier.size(20.dp))
                            }
                            Text(
                                t.label, fontFamily = Jakarta, fontSize = 11.5.sp,
                                fontWeight = if (active) FontWeight.W800 else FontWeight.W600,
                                color = if (active) Color(0xFF111318) else Color(0xFF4A4E58),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        AppTheme.DEFAULT -> {
            Column(Modifier.fillMaxWidth().background(Tokens.Card)) {
                HorizontalDivider(thickness = 1.dp, color = Tokens.Border)
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (t in tabs) {
                        val active = t.key == selected
                        Column(
                            Modifier.clip(RoundedCornerShape(Radii.card)).clickable { onSelect(t.key) }.padding(horizontal = 18.dp, vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(width = 52.dp, height = 28.dp).clip(RoundedCornerShape(14.dp)).background(if (active) Tokens.Yellow else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(t.icon, contentDescription = t.label, tint = if (active) Tokens.Ink else Tokens.InkFaint, modifier = Modifier.size(20.dp))
                            }
                            Text(
                                t.label, fontFamily = Jakarta, fontSize = 11.5.sp,
                                fontWeight = if (active) FontWeight.W700 else FontWeight.W600,
                                color = if (active) Tokens.Ink else Tokens.InkFaint, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- sheets ----------------------------------------------------------------------------------

/**
 * Bottom sheet with a title and a list of options; the current one carries the check disc. The list scrolls (a
 * language list is long); [subtitle] adds a second line under an option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceSheet(
    title: String, options: List<T>, selected: T, render: (T) -> String, onPick: (T) -> Unit, onDismiss: () -> Unit,
    subtitle: ((T) -> String?)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.Card) {
        LazyColumn(Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text(title, style = MaterialTheme.typography.titleLarge, color = Tokens.Ink, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
            items(options) { o ->
                val sel = o == selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row))
                        .background(if (sel) Tokens.YellowTint else Color.Transparent)
                        .clickable { onPick(o); onDismiss() }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(render(o), style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink)
                        subtitle?.invoke(o)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft) }
                    }
                    CheckDisc(sel)
                }
            }
        }
    }
}
