package com.smnexstudio.panelglass.core.data.block

/**
 * Fetches the ad and pop-up block lists now, rather than at the daily refresh (Settings › Sites › Block lists).
 * The lists live in `feature:browser` (`BlockListRepository`), which binds this; Settings only asks for an update.
 */
interface BlockListUpdater {
    /** True when a fresh list replaced a cached one; false when every source failed. A fetch already running is joined. */
    suspend fun updateNow(): Boolean
}
