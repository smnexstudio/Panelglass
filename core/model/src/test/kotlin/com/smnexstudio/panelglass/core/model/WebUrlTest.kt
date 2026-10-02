package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebUrlTest {
    @Test fun httpsIsKept() = assertEquals("https://rawkuma.net/manga/a/", WebUrl.https("https://rawkuma.net/manga/a/"))

    @Test fun httpIsUpgraded() {
        assertEquals("https://rawkuma.net/manga/a/?p=2", WebUrl.https("http://rawkuma.net/manga/a/?p=2"))
        assertEquals("https://Example.com/", WebUrl.https("HTTP://Example.com/"))
    }

    @Test fun bareHostGetsHttps() {
        assertEquals("https://rawkuma.net/manga", WebUrl.https("rawkuma.net/manga"))
        assertEquals("https://example.com:8443/x", WebUrl.https("example.com:8443/x"))
    }

    @Test fun whitespaceIsTrimmed() = assertEquals("https://example.com", WebUrl.https("  http://example.com \n"))

    @Test fun otherSchemesAreRefused() {
        listOf(
            "javascript:alert(1)", "javascript:a.b()", "file:///sdcard/x.html", "content://com.x.provider/a.png",
            "intent://x.y#Intent;end", "ftp://example.com/a", "data:text/html,a.b", "about:blank",
        ).forEach { assertNull(WebUrl.https(it), it) }
    }

    @Test fun nonUrlsAreNull() {
        listOf("", "   ", "one piece", "localhost", "http://", "https://").forEach { assertNull(WebUrl.https(it), it) }
    }
}
