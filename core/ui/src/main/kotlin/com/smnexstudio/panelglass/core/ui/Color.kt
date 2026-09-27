package com.smnexstudio.panelglass.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.smnexstudio.panelglass.core.model.AppTheme

/** Full semantic color tokens for each theme. */
data class PanelglassColors(
    val bg: Color,
    val card: Color,
    val border: Color,
    val ink: Color,
    val inkRaised: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val yellow: Color,
    val yellowDeep: Color,
    val yellowTint: Color,
    val orange: Color,
    val sky: Color,
    val skyDeep: Color,
    val error: Color,
    val tiles: List<Color>,
) {
    fun tileFor(name: String): Color = tiles[(name.hashCode() and 0x7fffffff) % tiles.size]
}

/** Default theme — warm off-white, yellow pill, ink text. */
val DefaultColors = PanelglassColors(
    bg = Color(0xFFF7F5EF),
    card = Color(0xFFFFFFFF),
    border = Color(0xFFEAE6DA),
    ink = Color(0xFF0A1019),
    inkRaised = Color(0xFF161E2B),
    inkSoft = Color(0xFF5B6472),
    inkFaint = Color(0xFF9AA2AD),
    yellow = Color(0xFFFCC336),
    yellowDeep = Color(0xFFE8A91E),
    yellowTint = Color(0xFFFFF8E8),
    orange = Color(0xFFEE6F11),
    sky = Color(0xFFA2D4E6),
    skyDeep = Color(0xFF5BA9C7),
    error = Color(0xFFE5484D),
    tiles = listOf(
        Color(0xFFFFE9B8), Color(0xFFD5EEF7), Color(0xFFFFD9C2), Color(0xFFDDF3E1),
        Color(0xFFECE3FA), Color(0xFFFCE1EA), Color(0xFFE2ECFA), Color(0xFFEFF0C9),
    ),
)

/** Panel Pop — neo-brutalist comic: warm cream, white cards, near-black outlines, rotating red/blue/yellow. */
val PanelPopColors = PanelglassColors(
    bg = Color(0xFFFFF6E9),
    card = Color(0xFFFFFFFF),
    border = Color(0xFF111318),
    ink = Color(0xFF111318),
    inkRaised = Color(0xFF1D212A),
    inkSoft = Color(0xFF4A4E58),
    inkFaint = Color(0xFF7A7E88),
    yellow = Color(0xFFFFD23F), // Comic yellow
    yellowDeep = Color(0xFFE5B800),
    yellowTint = Color(0xFFFFF6D6),
    orange = Color(0xFFFF3B30), // Comic red
    sky = Color(0xFFFFD23F),
    skyDeep = Color(0xFF2F6FED), // Comic blue
    error = Color(0xFFFF3B30),
    tiles = listOf(
        Color(0xFFFF3B30), // Red
        Color(0xFF2F6FED), // Blue
        Color(0xFFFFD23F), // Yellow
    ),
)

/** Soft Bloom — pastel, rounded: lavender-white, pale lilac border, deep plum text, lavender primary, mint & peach tiles. */
val SoftBloomColors = PanelglassColors(
    bg = Color(0xFFFBF6FF),
    card = Color(0xFFFFFFFF),
    border = Color(0xFFEFE7FB),
    ink = Color(0xFF2E2140),
    inkRaised = Color(0xFF3B2D52),
    inkSoft = Color(0xFF7B6E93),
    inkFaint = Color(0xFFA59AB8),
    yellow = Color(0xFFB7A8F0), // Lavender
    yellowDeep = Color(0xFF9C87E8), // Deep lavender
    yellowTint = Color(0xFFF3EEFF),
    orange = Color(0xFF9C87E8),
    sky = Color(0xFFEDE7FA),
    skyDeep = Color(0xFF9C87E8),
    error = Color(0xFFE5484D),
    tiles = listOf(
        Color(0xFFA6E3C4), // Mint
        Color(0xFFFFC9B3), // Peach
        Color(0xFFB7A8F0), // Lavender
    ),
)

/** Paper & Ink — minimal, editorial: warm paper, near-black ink, hairline pale tan dividers, muted indigo accent. */
val PaperInkColors = PanelglassColors(
    bg = Color(0xFFF7F5F0),
    card = Color(0xFFF7F5F0), // Flat paper surface, no raised cards
    border = Color(0xFFE4DFD3),
    ink = Color(0xFF211E1A),
    inkRaised = Color(0xFF332F2A),
    inkSoft = Color(0xFF6B6459),
    inkFaint = Color(0xFFA39C8E),
    yellow = Color(0xFF3A4562), // Muted indigo
    yellowDeep = Color(0xFF252D40),
    yellowTint = Color(0xFFEAE6DF),
    orange = Color(0xFF3A4562),
    sky = Color(0xFFEAE6DF),
    skyDeep = Color(0xFF3A4562),
    error = Color(0xFFB33A3A),
    tiles = listOf(
        Color(0xFFEAE6DF), Color(0xFFE2DDD5), Color(0xFFD9D4CB),
    ),
)

fun colorsFor(theme: AppTheme): PanelglassColors = when (theme) {
    AppTheme.DEFAULT -> DefaultColors
    AppTheme.PANEL_POP -> PanelPopColors
    AppTheme.SOFT_BLOOM -> SoftBloomColors
    AppTheme.PAPER_INK -> PaperInkColors
}

val LocalPanelglassColors = staticCompositionLocalOf { DefaultColors }
val LocalAppTheme = compositionLocalOf { AppTheme.DEFAULT }

/** Design tokens dynamically bound to the active [LocalPanelglassColors]. */
object Tokens {
    val Yellow: Color @Composable get() = LocalPanelglassColors.current.yellow
    val YellowDeep: Color @Composable get() = LocalPanelglassColors.current.yellowDeep
    val YellowTint: Color @Composable get() = LocalPanelglassColors.current.yellowTint
    val Orange: Color @Composable get() = LocalPanelglassColors.current.orange
    val Sky: Color @Composable get() = LocalPanelglassColors.current.sky
    val SkyDeep: Color @Composable get() = LocalPanelglassColors.current.skyDeep
    val Ink: Color @Composable get() = LocalPanelglassColors.current.ink
    val InkRaised: Color @Composable get() = LocalPanelglassColors.current.inkRaised
    val InkSoft: Color @Composable get() = LocalPanelglassColors.current.inkSoft
    val InkFaint: Color @Composable get() = LocalPanelglassColors.current.inkFaint
    val Bg: Color @Composable get() = LocalPanelglassColors.current.bg
    val Card: Color @Composable get() = LocalPanelglassColors.current.card
    val Border: Color @Composable get() = LocalPanelglassColors.current.border
    val Error: Color @Composable get() = LocalPanelglassColors.current.error
    val Tiles: List<Color> @Composable get() = LocalPanelglassColors.current.tiles

    @Composable
    fun tileFor(name: String): Color = LocalPanelglassColors.current.tileFor(name)
}
