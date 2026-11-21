package com.smnexstudio.panelglass.core.ui

import com.smnexstudio.panelglass.core.model.EngineId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LanConfigValidationTest {
    @Test fun lanNeedsAnEndpointAndModelButNotAKey() {
        assertTrue(apiKeyCanSave(EngineId.CUSTOM_LAN, false, "", "model", "http://192.168.1.2:8080/v1"))
        assertFalse(apiKeyCanSave(EngineId.CUSTOM_LAN, false, "", "", "http://192.168.1.2:8080/v1"))
        assertFalse(apiKeyCanSave(EngineId.CUSTOM_LAN, false, "", "model", ""))
    }
    @Test fun lanCannotSavePublicHttpEndpoints() {
        for (url in listOf("http://8.8.8.8/v1", "http://example.com/v1")) {
            assertFalse(apiKeyCanSave(EngineId.CUSTOM_LAN, false, "", "model", url), url)
        }
    }
    @Test fun cloudStillRequiresHttpsAndAKey() {
        assertFalse(apiKeyCanSave(EngineId.CUSTOM, true, "", "model", "http://192.168.1.2/v1"))
        assertFalse(apiKeyCanSave(EngineId.CUSTOM, false, "", "model", "https://cloud.example/v1"))
    }
    @Test fun savedLanKeyRequiresConfirmationForAnotherHost() {
        assertNotNull(keyHostChange(EngineId.CUSTOM_LAN, true, "", "http://192.168.1.2/v1", "http://192.168.1.3/v1"))
    }
}
