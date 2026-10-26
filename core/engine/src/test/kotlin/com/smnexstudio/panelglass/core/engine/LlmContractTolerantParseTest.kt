package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.llm.LlmContract
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reply parser, as a local OpenAI-compatible server answers it.
 *
 * A server at home is not the same server as a hosted one: it may answer with a bare object instead of an array, or
 * with the array inside a fence, or wrapped in a `translations` key, or with the text of a bubble in the `english`
 * key. The parser has to make sense of what a small local model actually writes, and it must still refuse a reply
 * that does not carry the items that were asked for.
 */
class LlmContractTolerantParseTest {
    private val one = listOf(RegionItem(0, "img1", RegionKind.ENCLOSED, "こんにちは"))

    private fun parsed(raw: String, expected: List<RegionItem> = one) =
        LlmContract.parse(EngineId.CUSTOM, raw, expected)

    private fun malformed(raw: String, expected: List<RegionItem> = one): EngineException {
        val e = assertThrows(EngineException::class.java) { LlmContract.parse(EngineId.CUSTOM, raw, expected) }
        assertTrue(
            e.failure is EngineFailure.Malformed,
            "expected Malformed for $raw, got ${e.failure}",
        )
        return e
    }

    @Test
    fun aBareObjectIsAccepted() {
        assertEquals(listOf("Hi"), parsed("""{"i":0,"text":"Hi"}""").map { it.text })
    }

    @Test
    fun aFencedPrettyPrintedArrayIsAccepted() {
        val raw = "```json\n[\n {\n \"i\": 0,\n \"text\": \"Hi\"\n }\n]\n```"
        assertEquals(listOf("Hi"), parsed(raw).map { it.text })
    }

    @Test
    fun aTranslationsWrapperIsAccepted() {
        assertEquals(listOf("Hi"), parsed("""{"translations":[{"i":0,"text":"Hi"}]}""").map { it.text })
    }

    @Test
    fun extraKeysAreIgnored() {
        val raw = """[{"i":0,"imageId":"x","kind":"ENCLOSED","text":"Hi"}]"""
        assertEquals(listOf("Hi"), parsed(raw).map { it.text })
    }

    @Test
    fun aWrongIdIsMalformed() {
        malformed("""[{"i":1,"text":"Hi"}]""")
    }

    @Test
    fun aWrongCountIsMalformed() {
        val two = listOf(
            RegionItem(0, "img1", RegionKind.ENCLOSED, "a"),
            RegionItem(1, "img1", RegionKind.SFX, "b"),
        )
        malformed("""[{"i":0,"text":"Hi"}]""", two)
    }

    @Test
    fun proseBeforeTheArrayIsAccepted() {
        assertEquals(listOf("Hi"), parsed("""Here you go: [{"i":0,"text":"Hi"}]""").map { it.text })
    }

    @Test
    fun aBareObjectWhoseTextHasBracketsIsAccepted() {
        // The trap: a naive "outermost [ ... ]" cut takes the [Sound] inside the text instead of the object.
        assertEquals(listOf("[Sound]"), parsed("""{"i":0,"text":"[Sound]"}""").map { it.text })
    }

    @Test
    fun aBareObjectWithProseAndAFenceIsAccepted() {
        val raw = "Sure, here it is:\n```json\n{\"i\":0,\"text\":\"Hi\"}\n```"
        assertEquals(listOf("Hi"), parsed(raw).map { it.text })
    }

    @Test
    fun aNonPrimitiveIdOrTextIsMalformed() {
        malformed("""{"i":[0],"text":"Hi"}""")
        malformed("""[{"i":0,"text":{"a":1}}]""")
    }

    @Test
    fun anEmptyTextIsAcceptedButAMissingOneIsMalformed() {
        assertEquals(listOf(""), parsed("""[{"i":0,"text":""}]""").map { it.text })
        malformed("""[{"i":0}]""")
    }

    @Test
    fun theEnglishAndTranslationKeysAreAccepted() {
        assertEquals(listOf("Hi"), parsed("""[{"i":0,"english":"Hi"}]""").map { it.text })
        assertEquals(listOf("Hi"), parsed("""[{"i":0,"translation":"Hi"}]""").map { it.text })
    }

    @Test fun duplicateRequestedIdsAreMalformed() {
        malformed("""[{"i":0,"text":"Hi"},{"i":0,"text":"Different"}]""")
    }

    @Test fun duplicateRequestedIdsInsideAWrapperAreMalformed() {
        malformed("""{"translations":[{"i":0,"text":"Hi"},{"i":0,"text":"Different"}]}""")
    }

    @Test fun stringAndNumericFormsAreTheSameRequestedId() {
        malformed("""[{"i":0,"text":"Hi"},{"i":"0","text":"Different"}]""")
    }

    @Test fun numbersAndBooleansAreNotTranslationText() {
        malformed("""[{"i":0,"text":42}]""")
        malformed("""[{"i":0,"text":true}]""")
    }
}
