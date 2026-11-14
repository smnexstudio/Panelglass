package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CustomProviderTest {
    @Test fun everyPresetReadsBackFromItsOwnAddress() {
        CustomProvider.presets.forEach { assertEquals(it, CustomProvider.of(it.baseUrl)) }
    }

    @Test fun everyPresetAddressIsAUsableHttpsBase() {
        CustomProvider.presets.forEach {
            assertEquals(it.baseUrl, CustomEndpoint.normalize(it.baseUrl), "${it.name} is not stored as typed")
            assertTrue(it.baseUrl.startsWith("https://"))
        }
    }

    @Test fun theSameAddressWrittenDifferentlyStillMatches() {
        assertEquals(CustomProvider.GPT, CustomProvider.of("  HTTPS://API.OpenAI.com/v1/  "))
        assertEquals(CustomProvider.GPT, CustomProvider.of("https://api.openai.com/v1/chat/completions"))
        assertEquals(CustomProvider.OPENROUTER, CustomProvider.of("https://openrouter.ai/api/v1/chat/completions"))
    }

    @Test fun anotherPathOnAPresetHostIsTheUsersOwnAddress() {
        assertEquals(CustomProvider.CUSTOM, CustomProvider.of("https://api.deepseek.com/v1"))
        assertEquals(CustomProvider.CUSTOM, CustomProvider.of("https://api.openai.com"))
    }

    @Test fun anyOtherOrUnusableAddressIsCustom() {
        listOf("", "  ", "https://example.com/v1", "http://api.openai.com/v1", "not an address").forEach {
            assertEquals(CustomProvider.CUSTOM, CustomProvider.of(it), it)
        }
    }

    @Test fun customHasNoAddressAndIsNotAPreset() {
        assertFalse(CustomProvider.CUSTOM.isPreset)
        assertFalse(CustomProvider.CUSTOM in CustomProvider.presets)
        assertEquals("", CustomProvider.CUSTOM.baseUrl)
        assertNotNull(CustomProvider.DEFAULT.takeIf { it.isPreset })
    }
}
