package com.smnexstudio.panelglass.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.model.AppTheme
import kotlin.math.roundToInt

/** Plus Jakarta Sans (OFL, `core/ui/PlusJakartaSans-OFL.txt`), one variable file instanced at the five weights in use. */
@OptIn(ExperimentalTextApi::class)
val Jakarta: FontFamily = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(R.font.plus_jakarta_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private fun typographyFor(theme: AppTheme): Typography {
    val headingFont = if (theme == AppTheme.PAPER_INK) FontFamily.Serif else Jakarta
    fun style(size: Int, weight: Int, line: Int = size + 6) =
        TextStyle(fontFamily = headingFont, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp)
    fun body(size: Int, weight: Int, line: Int = size + 6) =
        TextStyle(fontFamily = Jakarta, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp)

    return Typography(
        displaySmall = style(28, 800, 34),
        headlineSmall = style(22, 800, 28),
        titleLarge = style(20, 800, 26),
        titleMedium = style(17, 700, 22),
        titleSmall = body(15, 600, 20),
        bodyLarge = body(15, 500, 22),
        bodyMedium = body(14, 500, 20),
        bodySmall = body(12, 500, 16),
        labelLarge = body(14, 700, 20),
        labelMedium = body(12, 700, 16).copy(letterSpacing = 0.08.em),
        labelSmall = body(11, 600, 14),
    )
}

private fun shapesFor(theme: AppTheme): Shapes = when (theme) {
    AppTheme.PANEL_POP -> Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(10.dp),
        large = RoundedCornerShape(12.dp),
        extraLarge = RoundedCornerShape(14.dp),
    )
    AppTheme.SOFT_BLOOM -> Shapes(
        extraSmall = RoundedCornerShape(14.dp),
        small = RoundedCornerShape(18.dp),
        medium = RoundedCornerShape(22.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )
    AppTheme.PAPER_INK -> Shapes(
        extraSmall = RoundedCornerShape(2.dp),
        small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(4.dp),
        large = RoundedCornerShape(6.dp),
        extraLarge = RoundedCornerShape(8.dp),
    )
    AppTheme.DEFAULT -> Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
}

/** Theme-aware Material 3 wrapper supporting Default, Panel Pop, Soft Bloom, and Paper & Ink. */
@Composable
fun PanelglassTheme(theme: AppTheme = AppTheme.DEFAULT, content: @Composable () -> Unit) {
    val colors = colorsFor(theme)
    val scheme = lightColorScheme(
        primary = colors.yellow,
        onPrimary = if (theme == AppTheme.PANEL_POP) Color(0xFF111318) else if (theme == AppTheme.SOFT_BLOOM) Color.White else colors.ink,
        primaryContainer = colors.yellowTint,
        onPrimaryContainer = colors.ink,
        secondary = colors.skyDeep,
        onSecondary = colors.ink,
        secondaryContainer = colors.sky,
        onSecondaryContainer = colors.ink,
        tertiary = colors.orange,
        onTertiary = colors.card,
        background = colors.bg,
        onBackground = colors.ink,
        surface = colors.card,
        onSurface = colors.ink,
        surfaceVariant = colors.bg,
        onSurfaceVariant = colors.inkSoft,
        surfaceContainer = colors.card,
        surfaceContainerLow = colors.card,
        surfaceContainerHigh = colors.card,
        surfaceContainerHighest = colors.card,
        outline = colors.inkFaint,
        outlineVariant = colors.border,
        error = colors.error,
        onError = colors.card,
        scrim = colors.ink,
    )

    CompositionLocalProvider(
        LocalPanelglassColors provides colors,
        LocalAppTheme provides theme,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typographyFor(theme),
            shapes = shapesFor(theme),
            content = content,
        )
    }
}

/** Dynamic corner radii per theme. */
object Radii {
    val card: Dp @Composable get() = when (LocalAppTheme.current) {
        AppTheme.PANEL_POP -> 10.dp
        AppTheme.SOFT_BLOOM -> 22.dp
        AppTheme.PAPER_INK -> 2.dp
        AppTheme.DEFAULT -> 16.dp
    }
    val row: Dp @Composable get() = when (LocalAppTheme.current) {
        AppTheme.PANEL_POP -> 10.dp
        AppTheme.SOFT_BLOOM -> 18.dp
        AppTheme.PAPER_INK -> 2.dp
        AppTheme.DEFAULT -> 14.dp
    }
    val pill: Dp @Composable get() = when (LocalAppTheme.current) {
        AppTheme.PANEL_POP -> 10.dp
        AppTheme.SOFT_BLOOM -> 24.dp
        AppTheme.PAPER_INK -> 4.dp
        AppTheme.DEFAULT -> 24.dp
    }
    val round: Dp = 999.dp
}

/** Theme-specific screen ground texture / gradient (halftone dots for Panel Pop, diffused aura for Soft Bloom). */
fun Modifier.themeBackground(theme: AppTheme): Modifier = when (theme) {
    // The halftone grid is one repeating tile (two staggered rows) painted as a single shader rect. Drawing the
    // ~2,000 dots a phone screen holds as individual circles cost ~50 ms of display-list recording per frame,
    // and the screen redraws on every frame of a transition or scroll.
    AppTheme.PANEL_POP -> this.drawWithCache {
        val dotRadiusPx = 1.25.dp.toPx()
        val tileW = 14.dp.toPx().roundToInt().coerceAtLeast(2)
        val stepPx = tileW.toFloat()
        val rowPx = stepPx * 0.866f
        val tileH = (rowPx * 2).roundToInt().coerceAtLeast(2)
        val tile = ImageBitmap(tileW, tileH)
        val paint = Paint().apply { color = Color(0x18111318); isAntiAlias = true }
        Canvas(tile).apply {
            // Row 0 sits at x = 0 (its dot straddles the tile edge, so paint both halves); row 1 is offset half a step.
            drawCircle(Offset(0f, stepPx / 2), dotRadiusPx, paint)
            drawCircle(Offset(stepPx, stepPx / 2), dotRadiusPx, paint)
            drawCircle(Offset(stepPx / 2, stepPx / 2 + rowPx), dotRadiusPx, paint)
        }
        val dots = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        onDrawBehind {
            drawRect(Color(0xFFFFF6E9))
            drawRect(dots)
        }
    }
    AppTheme.SOFT_BLOOM -> this.drawBehind {
        drawRect(Color(0xFFFBF6FF))
        val glowBrush = Brush.radialGradient(
            colors = listOf(Color(0x30E2D4FB), Color(0x18FFD8C9), Color.Transparent),
            center = Offset(size.width * 0.82f, 0f),
            radius = size.width * 0.95f,
        )
        drawRect(glowBrush)
    }
    AppTheme.PAPER_INK -> this.background(Color(0xFFF7F5F0))
    AppTheme.DEFAULT -> this.background(Color(0xFFF7F5EF))
}
