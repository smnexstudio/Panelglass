package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CustomEndpointTest {
    @Test fun lanHttpRequiresExplicitMode() {
        assertEquals("http://localhost:8080/v1", CustomEndpoint.normalize("http://localhost:8080/v1/chat/completions", true))
        assertNull(CustomEndpoint.normalize("http://localhost:8080/v1"))
        assertNull(CustomEndpoint.normalize("http://192.168.1.5:1234/v1"))
    }

    @Test fun lanModeRejectsPublicHttpAddressesAndNames() {
        for (host in listOf("8.8.8.8", "172.32.0.1", "example.com", "localhost.example.com", "server.lan.example.com")) {
            assertNull(CustomEndpoint.normalize("http://$host:8080/v1", true), host)
        }
    }

    @Test fun lanModeKeepsPrivateAndLocalEndpointsNormalized() {
        for (host in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.5", "localhost", "server.lan", "mypc", "[::1]", "[fd00::1]")) {
            assertEquals("http://$host:8080/v1", CustomEndpoint.normalize("http://$host:8080/v1/chat/completions/", true), host)
        }
    }

    @Test fun lanModeDoesNotRestrictHttpsCloudEndpoints() {
        assertEquals("https://example.com/v1", CustomEndpoint.normalize("https://example.com/v1/chat/completions", true))
    }

    @Test fun lanInputRejectsSpecialUseAndPublicAddressBoundaries() {
        for (host in listOf("169.254.1.1", "169.254.169.254", "100.64.0.1", "100.127.255.255", "0.0.0.0", "224.0.0.1", "172.15.255.255", "172.32.0.0", "[fe80::1]", "[::]", "[ff02::1]", "[2001:db8::1]", "[::ffff:8.8.8.8]")) {
            assertNull(CustomEndpoint.normalize("http://$host:8080/v1", true), host)
        }
    }

    @Test fun lanInputAcceptsPrivateAddressBoundariesAndExpandedIpv6Loopback() {
        for (host in listOf("10.0.0.0", "10.255.255.255", "127.0.0.1", "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255", "[fc00::1]", "[fdff::1]", "[0:0:0:0:0:0:0:1]")) {
            assertEquals("http://$host:8080/v1", CustomEndpoint.normalize("http://$host:8080/v1", true), host)
        }
    }

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

    @Test fun publicHttpIsRefusedInProduction() = assertNull(CustomEndpoint.normalize("http://8.8.8.8:1234/v1"))

    @Test fun plainHttpIsAllowedForTests() =
        assertEquals("http://192.168.1.5:1234/v1", CustomEndpoint.normalize("http://192.168.1.5:1234/v1", allowHttp = true))

    @Test fun theCompletionsUrlIsTheBasePlusThePath() {
        assertEquals("https://api.openai.com/v1/chat/completions", CustomEndpoint.completionsUrl("https://api.openai.com/v1"))
        assertEquals("https://x.com/chat/completions", CustomEndpoint.completionsUrl("https://x.com"))
    }
}
