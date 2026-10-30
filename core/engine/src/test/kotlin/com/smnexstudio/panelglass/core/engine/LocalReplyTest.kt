package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.local.LocalReply
import com.smnexstudio.panelglass.core.engine.local.LocalPrompt
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalReplyTest {
    private val items = listOf(RegionItem(0, "invented", RegionKind.ENCLOSED, "おはよう"))

    @Test fun taggedReasoningIsNotLettered() {
        assertEquals("0: Good morning.", LocalReply.visible("<think>0: I should reason.</think>0: Good morning."))
    }

    @Test fun unfinishedReasoningIsNotAnAnswer() {
        assertEquals("", LocalReply.visible("<think>0: I should reason."))
    }

    @Test fun anImplicitReasoningPrefixIsDiscarded() {
        assertEquals("0: Good morning.", LocalReply.visible("0: internal thought</think>0: Good morning."))
    }

    @Test fun analysisTagsAreCaseInsensitive() {
        assertEquals("0: Good morning.", LocalReply.visible("<ANALYSIS>invented reasoning</ANALYSIS>0: Good morning."))
    }

    @Test fun theParserNeverAcceptsANumberedLineFromReasoning() {
        assertEquals(mapOf(0 to "Good morning."), LocalPrompt.parse("<think>0: Not an answer</think>\n0: Good morning.", items, Lang.EN))
    }

    @Test fun unstructuredAndWrongItemRepliesAreNotTranslations() {
        assertTrue(LocalPrompt.parse("I cannot translate that request.", items, Lang.EN).isEmpty())
        assertTrue(LocalPrompt.parse("9: Good morning.", items, Lang.EN).isEmpty())
    }
}
