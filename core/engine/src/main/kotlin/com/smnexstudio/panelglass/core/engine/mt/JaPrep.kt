package com.smnexstudio.panelglass.core.engine.mt

import com.smnexstudio.panelglass.core.model.TextNorm

/**
 * Japanese input shaping for ML Kit, which translates one sentence at a time with no context. It cannot tell a
 * katakana name from a loanword (レント became "rent", "alquiler", "аренда"), spells the same name differently from
 * one bubble to the next, does not know casual negatives (見えねぇ, 知らねぇ), and hands short sound words (はっ)
 * back untranslated. Names are replaced by a fixed romanisation before translation, casual forms are made standard,
 * and a kana-only line the model echoed back is romanised.
 */
object JaPrep {
    private val KATAKANA_RUN = Regex("[\\u30A1-\\u30FA\\u30FC]{2,}")
    private val HONORIFICS = linkedMapOf(
        "ちゃん" to "-chan", "さん" to "-san", "くん" to "-kun", "君" to "-kun", "さま" to "-sama", "様" to "-sama", "殿" to "-dono",
    )
    private val NAME_MARKERS = listOf("という", "って")

    /** Frequent loanwords in manga dialogue: katakana, but never names. */
    private val LOANWORDS = setOf(
        "アイテム", "ギルド", "スキル", "レベル", "モンスター", "パーティー", "パーティ", "ダンジョン", "クエスト", "ポーション",
        "マジック", "ステータス", "ランク", "ボス", "チーム", "メンバー", "ゲーム", "ドラゴン", "ゴブリン", "スライム",
        "エルフ", "ドワーフ", "ナイフ", "カード", "ポイント", "ゴールド", "コイン", "ホテル", "テスト", "ミス", "チャンス",
    )

    /** Kana before ねぇ that make it the casual negative (知らねぇ = 知らない), not a drawn-out ね (いいねぇ, そうだねぇ). */
    // No だ: そうだねぇ is "isn't it", never a negative. と is for ことねぇ (聞いたことねぇ = never heard).
    private const val NEG_BEFORE = "あかさたなはまやらわがざばぱえけせてねへめれげぜでべぺきしちにひみりぎじぢびぴゃと"
    private val CASUAL_NEG = Regex("(?<=[$NEG_BEFORE])ね[ぇえー]")
    private val DOTS = Regex("[・…‥.]{2,}")

    /**
     * Names in [texts] (every bubble of one request), katakana → romanisation. A katakana run counts as a name when
     * an honorific or という/って follows it, when it is the whole bubble (ヴァンジェ?), or when it recurs in 2+ bubbles.
     */
    fun names(texts: List<String>): Map<String, String> {
        val seen = HashMap<String, Int>()
        val out = LinkedHashMap<String, String>()
        for (text in texts) {
            val whole = text.filter { TextNorm.isKatakana(it) || TextNorm.isHiragana(it) || TextNorm.isCjkIdeograph(it) }
            for (m in KATAKANA_RUN.findAll(text).distinctBy { it.value }) {
                val run = m.value
                if (run in LOANWORDS || run.all { it == 'ー' }) continue
                seen[run] = (seen[run] ?: 0) + 1
                val after = text.substring(m.range.last + 1)
                if (HONORIFICS.keys.any(after::startsWith) || NAME_MARKERS.any(after::startsWith) || whole == run) {
                    out[run] = Romaji.of(run, capitalize = true)
                }
            }
        }
        for ((run, n) in seen) if (n >= 2 && run !in out) out[run] = Romaji.of(run, capitalize = true)
        return out
    }

    /** Standard forms ML Kit knows: 知らねぇ → 知らない, 知ってんだ → 知っているんだ, ・・・ → …. */
    fun normalize(text: String): String = text
        .replace(CASUAL_NEG, "ない")
        .replace("ってんだ", "っているんだ")
        .replace(DOTS, "…")

    /** [normalize], then each name (and an honorific after it) replaced by its romanisation, longest name first. */
    fun prepare(text: String, names: Map<String, String>): String {
        var s = normalize(text)
        for ((kana, roman) in names.entries.sortedByDescending { it.key.length }) {
            for ((h, suffix) in HONORIFICS) s = s.replace(kana + h, roman + suffix)
            s = s.replace(kana, roman)
        }
        return s
    }

    /** Kana and punctuation only: a sound word or interjection that romanisation can stand in for. */
    fun isKanaOnly(text: String): Boolean =
        text.any { TextNorm.isKatakana(it) || TextNorm.isHiragana(it) } && text.none { TextNorm.isCjkIdeograph(it) || it.isLetterOrDigit() && !TextNorm.isCjk(it) }
}

/** Hepburn romanisation of kana, for names and sound words: レント → Rento, ヴァンジェ → Vanje, はっ → Hah. */
object Romaji {
    private val BASE: Map<Char, String> = buildMap {
        val rows = listOf(
            "アイウエオ" to listOf("a", "i", "u", "e", "o"),
            "カキクケコ" to listOf("ka", "ki", "ku", "ke", "ko"), "ガギグゲゴ" to listOf("ga", "gi", "gu", "ge", "go"),
            "サシスセソ" to listOf("sa", "shi", "su", "se", "so"), "ザジズゼゾ" to listOf("za", "ji", "zu", "ze", "zo"),
            "タチツテト" to listOf("ta", "chi", "tsu", "te", "to"), "ダヂヅデド" to listOf("da", "ji", "zu", "de", "do"),
            "ナニヌネノ" to listOf("na", "ni", "nu", "ne", "no"),
            "ハヒフヘホ" to listOf("ha", "hi", "fu", "he", "ho"), "バビブベボ" to listOf("ba", "bi", "bu", "be", "bo"),
            "パピプペポ" to listOf("pa", "pi", "pu", "pe", "po"),
            "マミムメモ" to listOf("ma", "mi", "mu", "me", "mo"),
            "ヤユヨ" to listOf("ya", "yu", "yo"),
            "ラリルレロ" to listOf("ra", "ri", "ru", "re", "ro"),
            "ワヲンヴ" to listOf("wa", "o", "n", "vu"),
            "ァィゥェォ" to listOf("a", "i", "u", "e", "o"),
            "ャュョ" to listOf("ya", "yu", "yo"),
        )
        for ((chars, romaji) in rows) chars.forEachIndexed { i, c -> put(c, romaji[i]) }
    }
    private const val SMALL_Y = "ャュョ"
    private const val SMALL_VOWEL = "ァィゥェォ"

    fun of(kana: String, capitalize: Boolean = false): String {
        val k = kana.map { if (TextNorm.isHiragana(it) && it.code in 0x3041..0x3096) (it.code + 0x60).toChar() else it }
        val sb = StringBuilder()
        var double = false
        var i = 0
        while (i < k.size) {
            val c = k[i]
            val next = k.getOrNull(i + 1)
            var syl: String? = when {
                c == 'ッ' -> { double = true; i++; continue }
                c == 'ー' -> { i++; continue }
                else -> BASE[c]
            }
            if (syl == null) { sb.append(c); i++; continue }
            if (next != null && next in SMALL_Y && syl.endsWith("i") && syl.length > 1) {
                val stem = syl.dropLast(1)
                val v = BASE.getValue(next)
                syl = if (stem.endsWith("sh") || stem.endsWith("ch") || stem == "j") stem + v.last() else stem + v
                i++
            } else if (next != null && next in SMALL_VOWEL && c !in SMALL_VOWEL) {
                val stem = if (syl.length == 1) "w" else syl.dropLast(1)
                syl = stem + BASE.getValue(next)
                i++
            }
            if (double) { sb.append(if (syl.startsWith("ch")) 't' else syl.first()); double = false }
            sb.append(syl)
            i++
        }
        if (double) sb.append('h')   // a trailing ッ is a cut-off sound: はっ → hah
        val s = sb.toString()
        return if (capitalize) s.replaceFirstChar { it.uppercase() } else s
    }
}
