package com.smnexstudio.panelglass.core.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The dialogue-context level: how many preceding lines the model is sent, and the character sheet's cleaning. */
class DialogueContextTest {
    private fun config(engine: EngineId, level: DialogueContext) =
        TranslateConfig(Lang.JA, Lang.EN, engine, dialogueContext = level)

    @Test
    fun offKeepsTodaysTwoLines() {
        assertEquals(2, config(EngineId.GEMINI, DialogueContext.OFF).contextLimit())
        assertEquals(2, config(EngineId.QWEN15_LOCAL, DialogueContext.OFF).contextLimit())
        assertEquals(2, config(EngineId.LOCAL_USER, DialogueContext.OFF).contextLimit())
    }

    @Test
    fun standardSendsSixLinesToACloudEngine() {
        assertEquals(6, config(EngineId.GEMINI, DialogueContext.STANDARD).contextLimit())
        assertEquals(6, config(EngineId.CUSTOM, DialogueContext.STANDARD).contextLimit())
    }

    @Test
    fun anOnDeviceEngineNeverGetsMoreThanFourLines() {
        // The on-device models share a small cache between prompt and reply.
        assertEquals(4, config(EngineId.QWEN15_LOCAL, DialogueContext.STANDARD).contextLimit())
        assertEquals(4, config(EngineId.GEMMA4_LOCAL, DialogueContext.STANDARD).contextLimit())
        assertEquals(4, config(EngineId.LOCAL_USER, DialogueContext.STANDARD).contextLimit())
    }

    @Test
    fun deepSendsTwelveLinesToACloudEngine() {
        assertEquals(12, config(EngineId.GEMINI, DialogueContext.DEEP).contextLimit())
        assertEquals(4, config(EngineId.QWEN15_LOCAL, DialogueContext.DEEP).contextLimit())
    }

    @Test
    fun aConfigThatSetsNothingBehavesAsOff() {
        val cfg = TranslateConfig(Lang.JA, Lang.EN, EngineId.GEMINI)
        assertEquals(DialogueContext.OFF, cfg.dialogueContext)
        assertEquals("", cfg.castSheet)
        assertEquals(false, cfg.speakerTags)
        assertEquals(2, cfg.contextLimit())
    }
}

/** The character sheet: what is kept and what is cut. */
class CastSheetTest {
    @Test
    fun aSheetIsTrimmed() {
        assertEquals("Mika: girl", CastSheet.clean("  Mika: girl  "))
    }

    @Test
    fun controlCharactersAreRemovedButNewlinesStay() {
        assertEquals("a\nb", CastSheet.clean("a\u0007\nb"))
        assertEquals("ab", CastSheet.clean("a\u0000b"))
    }

    @Test
    fun threeBlankLinesBecomeOne() {
        assertEquals("a\n\nb", CastSheet.clean("a\n\n\n\nb"))
    }

    @Test
    fun aLongSheetIsCut() {
        val long = "x".repeat(CastSheet.MAX + 500)
        assertEquals(CastSheet.MAX, CastSheet.clean(long).length)
    }

    @Test
    fun aPromptSheetIsShorterThanTheStoredOne() {
        val long = "y".repeat(CastSheet.MAX)
        assertEquals(400, CastSheet.forPrompt(long, 400).length)
        assertEquals(0, CastSheet.forPrompt("   ", 400).length)
    }
}

/** The context pair and the translated item keep their shape when they are written down and read back. */
class ContextPairSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aPairWithASpeakerSurvivesTheRoundTrip() {
        val pair = ContextPair("おはよう", "Good morning", "A")
        val text = json.encodeToString(ContextPair.serializer(), pair)
        val back = json.decodeFromString(ContextPair.serializer(), text)
        assertEquals(pair, back)
    }

    @Test
    fun aPairWithoutASpeakerSurvivesTheRoundTrip() {
        val pair = ContextPair("おはよう", "Good morning")
        val text = json.encodeToString(ContextPair.serializer(), pair)
        val back = json.decodeFromString(ContextPair.serializer(), text)
        assertEquals(pair, back)
        assertNull(back.speaker)
    }
}
