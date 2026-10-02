package com.smnexstudio.panelglass.feature.studio.review

import kotlin.math.abs
import kotlin.math.roundToInt

/** Hue 0–360, saturation and value 0–1: what the colour picker's square and hue bar edit. */
internal data class Hsv(val h: Float, val s: Float, val v: Float)

/** Colour conversions and the hex field's parsing, without Android so they can be tested on the JVM. */
internal object ColorMath {
    fun toHsv(argb: Int): Hsv {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val d = max - minOf(r, g, b)
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return Hsv(h, if (max == 0f) 0f else d / max, max)
    }

    /** [hsv] as an opaque colour with [alpha] (0–255). */
    fun toArgb(hsv: Hsv, alpha: Int = 0xFF): Int {
        val h = ((hsv.h % 360f) + 360f) % 360f
        val c = hsv.v * hsv.s
        val x = c * (1 - abs((h / 60f) % 2f - 1))
        val m = hsv.v - c
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (alpha.coerceIn(0, 255) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** `RRGGBB`, or `AARRGGBB` when [withAlpha] and the colour is not opaque. */
    fun format(argb: Int, withAlpha: Boolean): String {
        val alpha = argb ushr 24
        return if (withAlpha && alpha != 0xFF) "%08X".format(argb) else "%06X".format(argb and 0xFFFFFF)
    }

    /**
     * A typed colour: `RGB`, `RRGGBB`, or with [withAlpha] also `AARRGGBB`, with or without `#`. A colour typed without
     * alpha keeps [keepAlpha]. Null for anything else.
     */
    fun parse(text: String, withAlpha: Boolean, keepAlpha: Int = 0xFF): Int? {
        val t = text.trim().removePrefix("#")
        if (t.isEmpty() || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val alphaBits = keepAlpha.coerceIn(0, 255) shl 24
        return when (t.length) {
            3 -> (t.map { "$it$it" }.joinToString("").toLong(16).toInt()) or alphaBits
            6 -> t.toLong(16).toInt() or alphaBits
            8 -> if (withAlpha) t.toLong(16).toInt() else null
            else -> null
        }
    }
}
