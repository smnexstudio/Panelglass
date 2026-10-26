package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The limits of what the reader keeps, and what it does when the user has not chosen. */
class RetentionLimitsTest {
    @Test
    fun aSupportedScreenCountIsKept() {
        for (v in RetentionLimits.SCREEN_CHOICES) assertEquals(v, RetentionLimits.clampScreens(v))
    }

    @Test
    fun anUnsupportedScreenCountFallsBackToTheDefault() {
        assertEquals(RetentionLimits.DEFAULT_SCREENS, RetentionLimits.clampScreens(-1))
        assertEquals(RetentionLimits.DEFAULT_SCREENS, RetentionLimits.clampScreens(7))
        assertEquals(RetentionLimits.DEFAULT_SCREENS, RetentionLimits.clampScreens(99))
    }

    @Test
    fun aSupportedMemoryCapIsKept() {
        for (v in RetentionLimits.OVERLAY_MB_CHOICES) assertEquals(v, RetentionLimits.clampOverlayMb(v))
    }

    @Test
    fun anUnsupportedMemoryCapFallsBackToTheDefault() {
        assertEquals(RetentionLimits.DEFAULT_OVERLAY_MB, RetentionLimits.clampOverlayMb(0 + 999))
        assertEquals(RetentionLimits.DEFAULT_OVERLAY_MB, RetentionLimits.clampOverlayMb(-5))
    }

    @Test
    fun aSupportedStorageCapIsKept() {
        for (v in RetentionLimits.STORAGE_MB_CHOICES) assertEquals(v, RetentionLimits.clampStorageMb(v))
    }

    @Test
    fun anUnsupportedStorageCapFallsBackToTheDefault() {
        assertEquals(RetentionLimits.DEFAULT_STORAGE_MB, RetentionLimits.clampStorageMb(7))
        assertEquals(RetentionLimits.DEFAULT_STORAGE_MB, RetentionLimits.clampStorageMb(0))
    }

    @Test
    fun theAutomaticScreenCountFollowsTheMemory() {
        assertEquals(2, RetentionLimits.autoScreens(2048))
        assertEquals(2, RetentionLimits.autoScreens(4095))
        assertEquals(3, RetentionLimits.autoScreens(4096))
        assertEquals(3, RetentionLimits.autoScreens(8191))
        assertEquals(4, RetentionLimits.autoScreens(8192))
        assertEquals(4, RetentionLimits.autoScreens(16384))
    }

    @Test
    fun theAutomaticMemoryCapFollowsTheMemory() {
        assertEquals(32, RetentionLimits.autoOverlayMb(2048))
        assertEquals(32, RetentionLimits.autoOverlayMb(4095))
        assertEquals(64, RetentionLimits.autoOverlayMb(4096))
        assertEquals(64, RetentionLimits.autoOverlayMb(8191))
        assertEquals(128, RetentionLimits.autoOverlayMb(8192))
        assertEquals(128, RetentionLimits.autoOverlayMb(16384))
    }
}
