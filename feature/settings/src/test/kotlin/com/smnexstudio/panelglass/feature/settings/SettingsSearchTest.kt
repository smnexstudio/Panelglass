package com.smnexstudio.panelglass.feature.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Settings hub's search: labels found by any part, without case or accents, once each, in the hub's order. */
class SettingsSearchTest {
    private val index = listOf(
        SearchHit(SettingsPage.ENGINE, "Engine & keys"),
        SearchHit(SettingsPage.ENGINE, "Server address"),
        SearchHit(SettingsPage.READER, "Block ads and pop-ups"),
        SearchHit(SettingsPage.APPEARANCE, "Thème"),
        SearchHit(SettingsPage.STORAGE, "Translated pages cache"),
        SearchHit(SettingsPage.READER, "Translated pages cache"),
        SearchHit(SettingsPage.READER, "Translated pages cache"),
    )

    @Test fun aBlankQueryFindsNothing() {
        assertTrue(searchSettings("", index).isEmpty())
        assertTrue(searchSettings("   ", index).isEmpty())
    }

    @Test fun anyPartOfALabelMatchesWithoutCaseOrAccents() {
        assertEquals(listOf(SearchHit(SettingsPage.ENGINE, "Server address")), searchSettings("ADDR", index))
        assertEquals(listOf(SearchHit(SettingsPage.APPEARANCE, "Thème")), searchSettings("theme", index))
        assertEquals(listOf(SearchHit(SettingsPage.READER, "Block ads and pop-ups")), searchSettings(" pop ", index))
    }

    @Test fun eachLabelComesOncePerPageInTheIndexOrder() {
        assertEquals(
            listOf(SearchHit(SettingsPage.STORAGE, "Translated pages cache"), SearchHit(SettingsPage.READER, "Translated pages cache")),
            searchSettings("cache", index),
        )
    }

    @Test fun nothingMatchingIsEmpty() {
        assertTrue(searchSettings("zzz", index).isEmpty())
    }
}
