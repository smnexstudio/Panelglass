package com.smnexstudio.panelglass.feature.browser.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SameSiteTest {
    @Test fun subdomainsShareTheirSite() =
        assertEquals(MangaWebViewClient.siteOf("img.reader.example.com"), MangaWebViewClient.siteOf("www.example.com"))

    @Test fun publicSuffixesAreNotOneSite() {
        assertEquals("example.co.jp", MangaWebViewClient.siteOf("a.example.co.jp"))
        assertNotEquals(MangaWebViewClient.siteOf("manga.co.jp"), MangaWebViewClient.siteOf("ads.co.jp"))
        assertNotEquals(MangaWebViewClient.siteOf("reader.com.br"), MangaWebViewClient.siteOf("popunder.com.br"))
    }

    @Test fun caseAndTrailingDotIgnored() = assertEquals("example.com", MangaWebViewClient.siteOf("WWW.Example.COM."))
}
