package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SiteMatchTest {
    private val seriesA = Site(id = 1, name = "A", url = "https://example.org/manga/series-a/", targetLang = Lang.ZH_TW)
    private val seriesB = Site(id = 2, name = "B", url = "https://example.org/manga/series-b/", targetLang = Lang.ES)
    private val root = Site(id = 3, name = "Example", url = "https://example.org/")
    private val other = Site(id = 4, name = "Kakao", url = "https://page.kakao.com/content/70277593/")

    /** The reported bug: two sites on one host each keep their own languages. */
    @Test
    fun sitesOnOneHostEachOwnTheirOwnChapters() {
        val sites = listOf(seriesA, seriesB)
        assertEquals(seriesA, Site.bestMatch(sites, "https://example.org/manga/series-a/chapter-39.411211/"))
        assertEquals(seriesB, Site.bestMatch(sites, "https://example.org/manga/series-b/chapter-2/"))
        assertEquals(seriesB, Site.bestMatch(listOf(seriesB, seriesA), "https://example.org/manga/series-b"))
    }

    @Test
    fun theDomainRootOwnsPagesNoSeriesClaims() {
        val sites = listOf(seriesA, root, seriesB)
        assertEquals(seriesA, Site.bestMatch(sites, "https://example.org/manga/series-a/chapter-1/"))
        assertEquals(root, Site.bestMatch(sites, "https://example.org/manga/series-c/"))
        assertEquals(root, Site.bestMatch(sites, "https://www.example.org/?s=query"))
    }

    @Test
    fun aPrefixMustEndOnASegment() {
        assertEquals(root, Site.bestMatch(listOf(seriesA, root), "https://example.org/manga/series-ab/"))
    }

    @Test
    fun subdomainsBelongToTheirSite() {
        assertEquals(root, Site.bestMatch(listOf(root, other), "https://cdn.example.org/manga/x/"))
        assertEquals(other, Site.bestMatch(listOf(root, other), "https://page.kakao.com/content/70277593/viewer/1"))
    }

    /** Names and pronouns of one saved series never leak into another on the same host. */
    @Test
    fun aSavedSeriesScopesItsOwnGlossary() {
        assertEquals("example.org/manga/series-a", Site.seriesKeyFor(seriesA, "https://example.org/manga/series-a/chapter-1/"))
        assertEquals("example.org/manga/series-b", Site.seriesKeyFor(seriesB, "https://example.org/manga/series-b/chapter-1/"))
        assertEquals("example.org", Site.seriesKeyFor(root, "https://example.org/manga/series-c/"))
        // No saved site: host and first path segment, as before.
        assertEquals("example.org/manga", Site.seriesKeyFor(null, "https://example.org/manga/series-c/chapter-2/"))
    }

    @Test
    fun anotherHostMatchesNothing() {
        assertNull(Site.bestMatch(listOf(seriesA, seriesB, root), "https://example.com/manga/series-a/"))
        assertNull(Site.bestMatch(listOf(seriesA), ""))
    }
}
