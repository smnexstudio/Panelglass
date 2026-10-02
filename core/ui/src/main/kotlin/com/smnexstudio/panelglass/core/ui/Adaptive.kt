package com.smnexstudio.panelglass.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * How wide the window is, in the Material breakpoints: a phone (under 600 dp), a tablet or an unfolded foldable
 * (600–839 dp), a laptop, Chromebook window or landscape tablet (840 dp and up). Read from the window, not the device,
 * so a resized Chromebook window or split screen switches layout as it crosses a line.
 */
enum class WidthClass {
    COMPACT, MEDIUM, EXPANDED;

    val wide: Boolean get() = this != COMPACT

    companion object {
        fun of(widthDp: Int): WidthClass = when {
            widthDp >= 840 -> EXPANDED
            widthDp >= 600 -> MEDIUM
            else -> COMPACT
        }
    }
}

@Composable
fun widthClass(): WidthClass = WidthClass.of(LocalConfiguration.current.screenWidthDp)

/** Whether the window is short (a laptop in landscape, a phone on its side): large layouts keep their bars slim. */
@Composable
fun isShortWindow(): Boolean = LocalConfiguration.current.screenHeightDp < 600

/**
 * The navigation rail of tablets and laptops: the logo, then each tab as an icon in a pill over its name, the
 * selected one yellow. Takes the place of the bottom [PillNavBar] once the window is [WidthClass.wide].
 */
@Composable
fun NavRail(tabs: List<NavTab>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxHeight()) {
        Column(
            Modifier.width(88.dp).fillMaxHeight().background(Tokens.Card).statusBarsPadding().navigationBarsPadding().padding(top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Image(painterResource(R.drawable.ic_panelglass_logo), contentDescription = "Panelglass", modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(4.dp))
            for (t in tabs) {
                val on = t.key == selected
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Tab) { onSelect(t.key) }
                        .semantics { this.selected = on }.padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(width = 56.dp, height = 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Tokens.Yellow else Tokens.Card),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(t.icon, contentDescription = null, tint = if (on) Tokens.Ink else Tokens.InkFaint, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.label, fontFamily = Jakarta, fontWeight = if (on) FontWeight.W800 else FontWeight.W600, fontSize = 11.5.sp,
                        color = if (on) Tokens.Ink else Tokens.InkFaint, maxLines = 1,
                    )
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Tokens.Border))
    }
}
