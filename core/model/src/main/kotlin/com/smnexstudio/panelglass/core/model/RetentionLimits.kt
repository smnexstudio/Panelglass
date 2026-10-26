package com.smnexstudio.panelglass.core.model

import kotlinx.serialization.Serializable

/**
 * How long a translation that has been made is kept on disk, so that scrolling back to it costs no AI call.
 *
 * The three modes trade disk against repeated work: `OFF` keeps nothing, `CHAPTER` keeps what this chapter needs
 * until the reader moves on, and `WEEK` keeps a week of reading.
 */
@Serializable
enum class SavedTranslations {
    OFF,
    CHAPTER,
    WEEK,
}

/**
 * The limits of what the reader keeps, and what it does when the user has not chosen.
 *
 * Every value the user can pick is clamped to what the app supports, so a stored value from an older version (or a
 * hand-edited file) can never make the app do something it does not support.
 */
object RetentionLimits {
    /** The screens of overlays kept around the viewport. */
    val SCREEN_CHOICES = listOf(0, 1, 2, 3, 4, 5, 10)
    /** The decoded memory the overlays may use, in MB. */
    val OVERLAY_MB_CHOICES = listOf(0, 32, 64, 128, 256)
    /** The disk the saved translations may use, in MB. */
    val STORAGE_MB_CHOICES = listOf(50, 100, 200, 500, 1000)

    /** The screens to keep, or [value] when it is one the app supports. */
    fun clampScreens(value: Int): Int = if (value in SCREEN_CHOICES) value else DEFAULT_SCREENS

    /** The memory cap in MB, or [value] when it is one the app supports. */
    fun clampOverlayMb(value: Int): Int = if (value in OVERLAY_MB_CHOICES) value else DEFAULT_OVERLAY_MB

    /** The storage cap in MB, or [value] when it is one the app supports. */
    fun clampStorageMb(value: Int): Int = if (value in STORAGE_MB_CHOICES) value else DEFAULT_STORAGE_MB

    /**
     * The screens to keep when the user has not chosen: fewer on a small phone, more on a big one.
     *
     * [totalRamMb] is what the kernel manages, not the marketed size.
     */
    fun autoScreens(totalRamMb: Long): Int = when {
        totalRamMb < 4096 -> 2
        totalRamMb < 8192 -> 3
        else -> 4
    }

    /** The memory cap in MB when the user has not chosen. */
    fun autoOverlayMb(totalRamMb: Long): Int = when {
        totalRamMb < 4096 -> 32
        totalRamMb < 8192 -> 64
        else -> 128
    }

    /** What the reader keeps when nothing is chosen: today's three screens. */
    const val DEFAULT_SCREENS = 3

    /** The memory cap when nothing is chosen: the app's own default. */
    const val DEFAULT_OVERLAY_MB = 64

    /** The disk the saved translations may use when nothing is chosen. */
    const val DEFAULT_STORAGE_MB = 200
}
