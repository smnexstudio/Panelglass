package com.smnexstudio.panelglass.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * One Settings page: a header (back arrow when [onBack] is set, then the title) over a scrolling column. On a wide
 * window the page sits beside the hub and has no back arrow.
 */
@Composable
internal fun SettingsPageFrame(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 16.dp, top = 6.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.cd_back), tint = Tokens.Ink)
            }
            Text(
                title, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp), content = content)
    }
}

/** A hub row: a tinted icon tile, the page's name and what it holds now, an optional [badge], the chevron. */
@Composable
internal fun HubRow(icon: ImageVector, key: String, title: String, subtitle: String, selected: Boolean, badge: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) Tokens.YellowTint else Color.Transparent)
            .clickable(onClick = onClick).heightIn(min = 68.dp).padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Tokens.tileFor(key)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Tokens.Ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 15.sp, color = Tokens.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (badge != null) Text(
            badge, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 11.sp, color = Tokens.Ink, maxLines = 1,
            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).background(Tokens.YellowTint).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Tokens.InkFaint, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

/** A row of mutually exclusive options; the current one is raised. Each option is a radio button for TalkBack. */
@Composable
internal fun <T> SegmentedChoice(options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tokens.Bg).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (o in options) {
            val on = o == selected
            Text(
                label(o), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, textAlign = TextAlign.Center,
                color = if (on) Tokens.Ink else Tokens.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) Tokens.Card else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(o) }).padding(vertical = 10.dp, horizontal = 6.dp),
            )
        }
    }
}

/** A horizontal bar split into [parts] (share of the whole, colour) over [track]. */
@Composable
internal fun UsageBar(parts: List<Pair<Float, Color>>, track: Color, height: Int = 10) {
    Row(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(height.dp)).background(track)) {
        val shown = parts.filter { it.first > 0f }
        for ((share, color) in shown) Box(Modifier.weight(share.coerceAtLeast(0.004f)).fillMaxHeight().background(color))
        val rest = 1f - shown.sumOf { it.first.toDouble() }.toFloat()
        if (rest > 0.001f) Spacer(Modifier.weight(rest))
    }
}

/** A colour dot and a label, for a [UsageBar]'s legend. */
@Composable
internal fun LegendItem(color: Color, text: String, textColor: Color = Tokens.InkSoft) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.5.sp, color = textColor)
    }
}

/** A footnote under a card, inset like the section labels. */
@Composable
internal fun PageNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(start = 32.dp, end = 24.dp, top = 8.dp))
}

/** A small status chip ("Key saved", "Ready"). */
@Composable
internal fun StatusChip(text: String, good: Boolean = true) {
    Text(
        text, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 11.sp, maxLines = 1,
        color = if (good) Tokens.Ink else Tokens.InkSoft,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (good) Tokens.YellowTint else Tokens.Bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

