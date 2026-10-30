package com.smnexstudio.panelglass.core.ui

import com.smnexstudio.panelglass.core.model.EngineId
import org.junit.jupiter.api.Assertions.assertFalse
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
}
