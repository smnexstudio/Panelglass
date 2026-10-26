package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.llm.LlmContract
import com.smnexstudio.panelglass.core.engine.local.LocalPrompt
import com.smnexstudio.panelglass.core.model.ContextPair
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TranslateRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptPartsTest {
    private val glossary = linkedMapOf("ミナ" to "Mina", "先輩" to "senpai")
    private val prior = listOf(ContextPair("一", "One."), ContextPair("二", "Two."), ContextPair("三", "Three."))

    @Test
    fun rendersTermsAndTheLastTwoLines() {
        assertEquals("- ミナ => Mina\n- 先輩 => senpai\n", PromptParts.glossaryList(glossary))
        assertEquals("ミナ => Mina; 先輩 => senpai", PromptParts.glossaryInline(glossary))
        assertEquals("- 二 => Two.\n- 三 => Three.\n", PromptParts.context(prior))
        assertEquals("", PromptParts.glossaryList(emptyMap()))
    }

    @Test
    fun theLineCountIsTheCallers() {
        // The producer decides how many lines there are; the default is today's two.
        assertEquals("- 一 => One.\n- 二 => Two.\n- 三 => Three.\n", PromptParts.context(prior, 3))
        assertEquals("- 三 => Three.\n", PromptParts.context(prior, 1))
    }

    @Test
    fun cloudAndOnDevicePromptsShowTheSameRenderings() {
        val req = TranslateRequest(listOf(RegionItem(0, "p", RegionKind.ENCLOSED, "はい")), Lang.JA, Lang.EN, glossary, prior)
        val cloud = LlmContract.systemPrompt(req)
        val local = LocalPrompt.system(req)
        assertTrue(cloud.contains("Glossary (always use these renderings):\n- ミナ => Mina\n- 先輩 => senpai\n"))
        assertTrue(local.contains("Glossary: ミナ => Mina; 先輩 => senpai\n"))
        // Both show the lines they were given, in the same rendering.
        for (p in listOf(cloud, local)) {
            assertTrue(p.contains("- 一 => One.\n"), p)
            assertTrue(p.contains("- 三 => Three.\n"), p)
        }
    }

    @Test
    fun theCloudPromptShowsEveryLineItIsGiven() {
        // The producer may send up to the level's limit; the prompt must not cut it back to two.
        val six = (1..6).map { ContextPair("s$it", "t$it") }
        val req = TranslateRequest(listOf(RegionItem(0, "p", RegionKind.ENCLOSED, "はい")), Lang.JA, Lang.EN, priorContext = six)
        val cloud = LlmContract.systemPrompt(req)
        assertTrue(cloud.contains("- s1 => t1\n"), cloud)
        assertTrue(cloud.contains("- s6 => t6\n"), cloud)
    }

    @Test
    fun theOnDevicePromptTakesAtMostFourLines() {
        val six = (1..6).map { ContextPair("s$it", "t$it") }
        val req = TranslateRequest(listOf(RegionItem(0, "p", RegionKind.ENCLOSED, "はい")), Lang.JA, Lang.EN, priorContext = six)
        val local = LocalPrompt.system(req)
        assertTrue(local.contains("- s3 => t3\n"), local)
        assertTrue(local.contains("- s6 => t6\n"), local)
        assertFalse(local.contains("- s1 => t1\n"), local)
    }
}
