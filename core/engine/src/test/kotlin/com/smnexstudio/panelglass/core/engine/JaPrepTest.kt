package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.mt.JaPrep
import com.smnexstudio.panelglass.core.engine.mt.Romaji
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JaPrepTest {
    @Test
    fun romajiCoversNamesAndSoundWords() {
        assertEquals("Rento", Romaji.of("レント", capitalize = true))
        assertEquals("Vanje", Romaji.of("ヴァンジェ", capitalize = true))
        assertEquals("Namiria", Romaji.of("ナミリア", capitalize = true))
        assertEquals("Hah", Romaji.of("はっ", capitalize = true))
        assertEquals("Heh", Romaji.of("へっ", capitalize = true))
        assertEquals("matcha", Romaji.of("マッチャ"))
        assertEquals("kitto", Romaji.of("キット"))
        assertEquals("shoujo", Romaji.of("ショウジョ"))
        assertEquals("fai", Romaji.of("ファイ"))
        assertEquals("wisuki", Romaji.of("ウィスキー"))
    }

    @Test
    fun namesAreFoundByHonorificMarkerWholeBubbleAndRepetition() {
        val names = JaPrep.names(listOf(
            "レントちゃんの生み出したアイテムなんでしょ?",
            "ヴァンジェという男の名に聞き覚えは?",
            "ナミリア!",
            "ウィラード伯爵領都か",
            "ウィラードに居る",
        ))
        assertEquals("Rento", names["レント"])
        assertEquals("Vanje", names["ヴァンジェ"])
        assertEquals("Namiria", names["ナミリア"])
        assertEquals("Wirado", names["ウィラード"])
        assertFalse("アイテム" in names)
    }

    @Test
    fun aLoanwordSeenTwiceIsStillNotAName() {
        assertTrue(JaPrep.names(listOf("アイテムだ", "そのアイテム")).isEmpty())
    }

    @Test
    fun casualNegativesBecomeStandard() {
        assertEquals("いや知らないな", JaPrep.normalize("いや知らねぇな"))
        assertEquals("姿が見えないな", JaPrep.normalize("姿が見えねぇな"))
        assertEquals("できない", JaPrep.normalize("できねぇ"))
        assertEquals("じゃない", JaPrep.normalize("じゃねぇ"))
        assertEquals("でもそんな名前聞いたことないぞ", JaPrep.normalize("でもそんな名前聞いたことねぇぞ"))
        assertEquals("何でお前が知っているんだ?", JaPrep.normalize("何でお前が知ってんだ?"))
        assertEquals("…", JaPrep.normalize("・・・・・・"))
    }

    @Test
    fun aDrawnOutNeIsNotANegative() {
        assertEquals("いいねぇ", JaPrep.normalize("いいねぇ"))
        assertEquals("そうだねぇ", JaPrep.normalize("そうだねぇ"))
        assertEquals("ねぇ", JaPrep.normalize("ねぇ"))
    }

    @Test
    fun prepareSwapsNamesAndHonorifics() {
        val names = mapOf("レント" to "Rento", "ヴァンジェ" to "Vanje")
        assertEquals("Rento-chanの生み出したアイテムなんでしょ?", JaPrep.prepare("レントちゃんの生み出したアイテムなんでしょ?", names))
        assertEquals("Rento-sanは", JaPrep.prepare("レントさんは", names))
        assertEquals("Vanjeという男", JaPrep.prepare("ヴァンジェという男", names))
    }

    @Test
    fun kanaOnlyMeansNoKanjiAndNoLatin() {
        assertTrue(JaPrep.isKanaOnly("はっ"))
        assertTrue(JaPrep.isKanaOnly("ドン!!"))
        assertFalse(JaPrep.isKanaOnly("知らねぇ"))
        assertFalse(JaPrep.isKanaOnly("OKだ"))
    }
}
