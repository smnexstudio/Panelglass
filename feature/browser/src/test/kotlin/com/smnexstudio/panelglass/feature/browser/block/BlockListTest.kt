package com.smnexstudio.panelglass.feature.browser.block

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric because the matcher takes android.net.Uri; JUnit 4 via the vintage engine. */
@RunWith(RobolectricTestRunner::class)
class BlockListTest {
    private val list = BlockList.parse(
        """
        # StevenBlack-style entries
        0.0.0.0 0.0.0.0
        127.0.0.1 localhost
        0.0.0.0 ads.example.com
        0.0.0.0 tracker.net   # trailing comment
        ! AdGuard-style entries
        ||popunder.io^
        ||banner.example.org^${'$'}third-party
        ||*.wildcard.com^
        """.trimIndent(),
    )

    @Test fun parsesBothFormats() = assertEquals(4, list.size)

    @Test fun exactHostBlocks() = assertTrue(list.blocks(Uri.parse("https://ads.example.com/x.js")))

    @Test fun subdomainOfBlockedHostBlocks() {
        assertTrue(list.blocks(Uri.parse("https://cdn.static.tracker.net/p.gif")))
        assertTrue(list.blocks(Uri.parse("http://a.b.popunder.io/")))
    }

    @Test fun parentOfBlockedHostDoesNotBlock() = assertFalse(list.blocks(Uri.parse("https://example.com/")))

    @Test fun unrelatedHostDoesNotBlock() = assertFalse(list.blocks(Uri.parse("https://comics.example.net/ch1")))

    @Test fun localhostAndNullHostIgnored() {
        assertFalse(list.blocks(Uri.parse("http://localhost/")))
        assertFalse(list.blocks(Uri.parse("about:blank")))
    }

    @Test fun wildcardEntriesAreDropped() = assertFalse(list.blocksHost("x.wildcard.com"))

    @Test fun emptyListBlocksNothing() = assertFalse(BlockList.EMPTY.blocks(Uri.parse("https://ads.example.com/")))

    @Test fun exceptionsWinOverEveryList() {
        val merged = list + BlockList.parse("||cdn.example.net^\n@@||ok.cdn.example.net^\n@@||ads.example.com^")
        assertTrue(merged.blocksHost("x.cdn.example.net"))
        assertFalse(merged.blocksHost("ok.cdn.example.net"))
        assertFalse(merged.blocksHost("img.ok.cdn.example.net"))
        // An exception from one list lifts the other list's block.
        assertFalse(merged.blocksHost("ads.example.com"))
        assertTrue(merged.blocksHost("tracker.net"))
    }

    @Test fun contextScopedRulesAreSkipped() {
        val l = BlockList.parse("||scoped.com^\$domain=site.com\n||bad.com^\$badfilter\n||pop.com^\$popup,third-party\n||Upper.COM^")
        assertFalse(l.blocksHost("scoped.com"))
        assertFalse(l.blocksHost("bad.com"))
        assertTrue(l.blocksHost("pop.com"))
        assertTrue(l.blocksHost("upper.com"))
        assertTrue(l.blocksHost("WWW.UPPER.COM"))
    }

    @Test fun cachedFormRoundTrips() {
        val merged = list + BlockList.parse("@@||ok.tracker.net^")
        val again = BlockList.parse(StringBuilder().also { merged.write(it) })
        assertEquals(merged.size, again.size)
        assertTrue(again.blocksHost("tracker.net"))
        assertFalse(again.blocksHost("ok.tracker.net"))
    }
}
