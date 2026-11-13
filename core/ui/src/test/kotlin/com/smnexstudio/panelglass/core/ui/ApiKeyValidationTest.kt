package com.smnexstudio.panelglass.core.ui

import com.smnexstudio.panelglass.core.model.EngineId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiKeyValidationTest {
    private fun custom(baseUrl: String, hasKey: Boolean = false, key: String = "test-key", model: String = "test-model") =
        apiKeyCanSave(EngineId.CUSTOM, hasKey, key, model, baseUrl)

    @Test fun validHttpsCanBeSaved() {
        assertTrue(custom("https://example.com/v1"))
        assertTrue(custom("  HTTPS://example.com/v1/chat/completions/  "))
    }

    @Test fun plainHttpCannotBeSavedEvenWithAnExistingKey() {
        assertFalse(custom("http://192.168.1.5:1234/v1"))
        assertFalse(custom("http://192.168.1.5:1234/v1", hasKey = true, key = ""))
    }

    @Test fun invalidAddressesCannotBeSaved() {
        listOf("", "  ", "https://", "example.com/v1", "ftp://example.com",
            "https://user:pass@example.com", "https://example.com/v1?key=secret",
            "https://example.com/v1#fragment", "https://bad host/v1").forEach {
            assertFalse(custom(it), "Invalid endpoint was accepted")
        }
    }

    @Test fun anExistingKeyCanBeKept() {
        assertTrue(custom("https://example.com/v1", hasKey = true, key = ""))
    }

    @Test fun aKeyIsRequired() {
        assertFalse(custom("https://example.com/v1", key = "  "))
    }

    @Test fun aModelIsRequired() {
        assertFalse(custom("https://example.com/v1", model = "  "))
    }

    @Test fun otherProvidersDoNotRequireACustomEndpoint() {
        assertTrue(apiKeyCanSave(EngineId.GEMINI, false, "test-key", "test-model", ""))
        assertFalse(apiKeyCanSave(EngineId.GEMINI, false, "", "test-model", ""))
    }

    private fun hostChange(saved: String, now: String, hasKey: Boolean = true, key: String = "", engine: EngineId = EngineId.CUSTOM) =
        keyHostChange(engine, hasKey, key, saved, now)

    @Test fun aNewHostWithTheSavedKeyIsAskedAbout() {
        assertEquals("api.openai.com" to "api.deepseek.com", hostChange("https://api.openai.com/v1", "https://api.deepseek.com/v1"))
        assertEquals("example.com" to "example.com:8443", hostChange("https://example.com/v1", "https://example.com:8443/v1"))
    }

    @Test fun theSameHostIsNotAskedAboutWhateverThePathOrCase() {
        assertNull(hostChange("https://api.example.com/v1", "https://API.example.com/v2/chat/completions"))
        assertNull(hostChange("https://api.example.com", "https://api.example.com/openai/v1"))
    }

    @Test fun aNewKeyNoSavedKeyOrAnotherEngineIsNeverAskedAbout() {
        assertNull(hostChange("https://a.example.com/v1", "https://b.example.com/v1", key = "new-key"))
        assertNull(hostChange("https://a.example.com/v1", "https://b.example.com/v1", hasKey = false))
        assertNull(hostChange("https://a.example.com/v1", "https://b.example.com/v1", engine = EngineId.GEMINI))
    }

    @Test fun anAddressSetForTheFirstTimeOrUnusableIsNotAskedAbout() {
        assertNull(hostChange("", "https://b.example.com/v1"))
        assertNull(hostChange("https://a.example.com/v1", "not an address"))
    }
}
