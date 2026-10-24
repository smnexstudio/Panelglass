package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CustomEndpointTest {
    @Test fun aPlainBaseIsKept() = assertEquals("https://api.openai.com/v1", CustomEndpoint.normalize("https://api.openai.com/v1"))

    @Test fun surroundingWhitespaceIsTrimmed() = assertEquals("https://api.openai.com/v1", CustomEndpoint.normalize("  https://api.openai.com/v1/  "))

    @Test fun theCompletionsPathIsStripped() =
        assertEquals("https://api.openai.com/v1", CustomEndpoint.normalize("https://api.openai.com/v1/chat/completions"))

    @Test fun theCompletionsPathWithATrailingSlashIsStripped() =
        assertEquals("https://x.com/v1", CustomEndpoint.normalize("https://x.com/v1/chat/completions/"))

    @Test fun aPathWithoutAVersionIsKept() = assertEquals("https://openrouter.ai/api/v1", CustomEndpoint.normalize("https://openrouter.ai/api/v1"))

    @Test fun anyPathIsPreservedAsWritten() {
        assertEquals("https://example.com/proxy/openai", CustomEndpoint.normalize("https://example.com/proxy/openai"))
        assertEquals("https://example.com", CustomEndpoint.normalize("https://example.com"))
    }

    @Test fun anEmptyAddressIsNull() = assertNull(CustomEndpoint.normalize(""))

    @Test fun aBlankAddressIsNull() = assertNull(CustomEndpoint.normalize("   "))

    @Test fun anAddressWithoutASchemeIsNull() = assertNull(CustomEndpoint.normalize("api.openai.com/v1"))

    @Test fun anAddressWithoutAHostIsNull() = assertNull(CustomEndpoint.normalize("https://"))

    @Test fun anAddressWithASpaceInItIsNull() = assertNull(CustomEndpoint.normalize("https://a b.com"))

    @Test fun anAddressWithAQueryStringIsNull() = assertNull(CustomEndpoint.normalize("https://x.com/v1?key=1"))

    @Test fun anAddressWithAFragmentIsNull() = assertNull(CustomEndpoint.normalize("https://x.com/v1#top"))

    @Test fun anotherSchemeIsNull() = assertNull(CustomEndpoint.normalize("ftp://x.com"))

    @Test fun credentialsInTheAddressAreNull() = assertNull(CustomEndpoint.normalize("https://user:pass@x.com/v1"))

    @Test fun plainHttpIsRefusedInProduction() = assertNull(CustomEndpoint.normalize("http://192.168.1.5:1234/v1"))

    @Test fun plainHttpIsAllowedForTests() =
        assertEquals("http://192.168.1.5:1234/v1", CustomEndpoint.normalize("http://192.168.1.5:1234/v1", allowHttp = true))

    @Test fun theCompletionsUrlIsTheBasePlusThePath() {
        assertEquals("https://api.openai.com/v1/chat/completions", CustomEndpoint.completionsUrl("https://api.openai.com/v1"))
        assertEquals("https://x.com/chat/completions", CustomEndpoint.completionsUrl("https://x.com"))
    }
}
