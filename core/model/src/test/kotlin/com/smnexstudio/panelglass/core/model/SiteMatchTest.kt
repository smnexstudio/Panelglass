package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SiteMatchTest {
    private val seriesA = Site(id = 1, name = "A", url = "https://rawkuma.net/manga/series-a/", targetLang = Lang.ZH_TW)
    private val seriesB = Site(id = 2, name = "B", url = "https://rawkuma.net/manga/series-b/", targetLang = Lang.ES)
    private val root = Site(id = 3, name = "Rawkuma", url = "https://rawkuma.net/")
    private val other = Site(id = 4, name = "Kakao", url = "https://page.kakao.com/content/70277593/")

    /** The reported bug: two sites on one host each keep their own languages. */
    @Test
    fun sitesOnOneHostEachOwnTheirOwnChapters() {
        val sites = listOf(seriesA, seriesB)
        assertEquals(seriesA, Site.bestMatch(sites, "https://rawkuma.net/manga/series-a/chapter-39.411211/"))
        assertEquals(seriesB, Site.bestMatch(sites, "https://rawkuma.net/manga/series-b/chapter-2/"))
        assertEquals(seriesB, Site.bestMatch(listOf(seriesB, seriesA), "https://rawkuma.net/manga/series-b"))
    }

    @Test
    fun theDomainRootOwnsPagesNoSeriesClaims() {
        val sites = listOf(seriesA, root, seriesB)
        assertEquals(seriesA, Site.bestMatch(sites, "https://rawkuma.net/manga/series-a/chapter-1/"))
        assertEquals(root, Site.bestMatch(sites, "https://rawkuma.net/manga/series-c/"))
        assertEquals(root, Site.bestMatch(sites, "https://www.rawkuma.net/?s=query"))
    }

    @Test
    fun aPrefixMustEndOnASegment() {
        assertEquals(root, Site.bestMatch(listOf(seriesA, root), "https://rawkuma.net/manga/series-ab/"))
    }

    @Test
    fun subdomainsBelongToTheirSite() {
        assertEquals(root, Site.bestMatch(listOf(root, other), "https://cdn.rawkuma.net/manga/x/"))
        assertEquals(other, Site.bestMatch(listOf(root, other), "https://page.kakao.com/content/70277593/viewer/1"))
    }

    @Test
    fun anotherHostMatchesNothing() {
        assertNull(Site.bestMatch(listOf(seriesA, seriesB, root), "https://example.com/manga/series-a/"))
        assertNull(Site.bestMatch(listOf(seriesA), ""))
    }
}
