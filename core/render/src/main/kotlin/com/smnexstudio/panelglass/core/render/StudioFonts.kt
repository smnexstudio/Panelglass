package com.smnexstudio.panelglass.core.render

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import com.smnexstudio.panelglass.core.model.FontRole
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.UserFont
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One font the Studio can letter with. [id] is `cat:<id>` (bundled, from `assets/fonts/catalogue.json`), `user:<sha256>`
 * (added by the user, files in `filesDir/fonts/user/`) or `sys:sans` / `sys:serif` (the phone's own). [scripts] are
 * catalogue script ids (`latin`, `vietnamese`, `cyrillic`, `ja`, `ko`, `zh-hans`, `zh-hant`, `thai`, `arabic`,
 * `devanagari`), measured from the font's character map.
 */
data class FontEntry(
    val id: String,
    val name: String,
    val role: FontRole,
    val tags: List<String>,
    val scripts: Set<String>,
    val licence: String = "",
    /** Style (`regular`, `bold`, `italic`, `boldItalic`) → an asset path under `fonts/`, or a user font file name. */
    val files: Map<String, String> = emptyMap(),
    /** A resource font of this module (`coming_soon`, …), shared with the reader. */
    val res: String? = null,
    /** Variation settings for the regular face (a variable font's ExtraBold entry, `'wght' 800`). */
    val variation: String? = null,
    /** How a variable font draws bold, when it has no bold file. */
    val boldVariation: String? = null,
) {
    val isUser: Boolean get() = id.startsWith(USER)
    val isSystem: Boolean get() = id.startsWith(SYS)

    companion object {
        const val CAT = "cat:"
        const val USER = "user:"
        const val SYS = "sys:"
    }
}

/**
 * The Studio's fonts (docs/STUDIO_PLAN.md › Fonts): the bundled core set, the user's fonts and the phone's sans and
 * serif, with the default per target language and role. Every typeface is built once, on first use, with the system
 * sans as glyph fallback, so a character a font lacks (an emoji, a rare kanji) is drawn by the phone's Noto fonts
 * instead of a box. A font that fails to load is remembered as unusable and the language default is used instead.
 */
object StudioFonts {
    private var context: Context? = null
    @Volatile private var catalogue: List<FontEntry>? = null
    @Volatile private var users: List<FontEntry> = emptyList()
    private val faces = ConcurrentHashMap<String, Typeface>()
    private val broken = ConcurrentHashMap.newKeySet<String>()
    /** Where user font files live, set by the user font store. */
    @Volatile var userDir: File? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        if (userDir == null) userDir = File(context.filesDir, "fonts/user")
    }

    /** The phone's own sans and serif (Noto for every script): they draw everything. */
    val system: List<FontEntry> by lazy {
        listOf(
            FontEntry("${FontEntry.SYS}sans", "Sans", FontRole.CAPTION, listOf("sans", "clean"), SCRIPTS.toSet()),
            FontEntry("${FontEntry.SYS}serif", "Serif", FontRole.CAPTION, listOf("serif", "book"), SCRIPTS.toSet()),
        )
    }

    /** Bundled fonts, read from the catalogue on first use (none in a JVM test without assets). */
    val bundled: List<FontEntry>
        get() = catalogue ?: load().also { catalogue = it }

    val user: List<FontEntry> get() = users

    /** Every font: the user's first, then the core set, then the phone's own. */
    val entries: List<FontEntry> get() = users + bundled + system

    /** Replaces the user's fonts (the user font store calls this whenever they change). */
    fun setUserFonts(list: List<UserFont>) {
        users = list.map { u ->
            FontEntry(u.id, u.displayName, u.role, u.tags, u.scripts, files = u.files)
        }
        faces.keys.removeIf { it.startsWith(FontEntry.USER) && users.none { u -> it.startsWith(u.id + "|") } }
    }

    /** The font for [id], resolving the ids the editor saved before the catalogue (`bundled:<id>`). */
    fun find(id: String?): FontEntry? {
        if (id == null) return null
        val key = if (id.startsWith("bundled:")) FontEntry.CAT + id.removePrefix("bundled:") else id
        return entries.firstOrNull { it.id == key }
    }

    /** The catalogue script [tgt] is written in. */
    fun scriptOf(tgt: Lang): String = when (tgt) {
        Lang.VI -> "vietnamese"
        Lang.RU -> "cyrillic"
        Lang.JA -> "ja"
        Lang.KO -> "ko"
        Lang.ZH -> "zh-hans"
        Lang.ZH_TW -> "zh-hant"
        Lang.TH -> "thai"
        Lang.AR -> "arabic"
        Lang.HI -> "devanagari"
        else -> "latin"
    }

    /** Whether [f] draws [tgt]'s script. */
    fun fits(f: FontEntry, tgt: Lang): Boolean = scriptOf(tgt) in f.scripts

    /** A line in [tgt]'s script to preview a font with. */
    fun sample(tgt: Lang): String = SAMPLES[scriptOf(tgt)] ?: SAMPLES.getValue("latin")

    /**
     * The default font for [tgt] and [role] (the plan's core-set table): comic lettering per script for dialogue, a
     * bold display face for sound effects, and the phone's sans (or Plus Jakarta Sans for Latin) for captions.
     */
    fun defaultFor(tgt: Lang, role: FontRole = FontRole.DIALOGUE): String {
        val id = defaultId(tgt, role)
        // A catalogue missing (a JVM test) or a font removed from it: the phone's sans.
        return id.takeIf { it.startsWith(FontEntry.SYS) || find(it) != null } ?: "${FontEntry.SYS}sans"
    }

    /** The plan's table itself, unchecked (tests compare it with the catalogue). */
    fun defaultId(tgt: Lang, role: FontRole): String {
        val c = FontEntry.CAT
        val sans = "${FontEntry.SYS}sans"
        return when (role) {
            FontRole.DIALOGUE -> when (tgt) {
                Lang.VI, Lang.RU -> "${c}shantell_sans"
                Lang.JA -> "${c}zen_antique"
                Lang.KO -> "${c}gaegu"
                Lang.ZH -> "${c}zcool_kuaile"
                Lang.ZH_TW -> "${c}jf_open_huninn"
                Lang.TH -> "${c}itim"
                Lang.AR -> "${c}reem_kufi_fun"
                Lang.HI -> "${c}kalam"
                else -> "${c}comic_neue"
            }
            FontRole.SFX -> when (tgt) {
                Lang.VI -> "${c}shantell_sans_extrabold"
                Lang.RU -> "${c}rubik_mono_one"
                Lang.JA -> "${c}dela_gothic_one"
                Lang.KO -> "${c}black_han_sans"
                Lang.ZH -> "${c}smiley_sans"
                Lang.ZH_TW -> sans // Smiley Sans lacks many Traditional characters
                Lang.TH -> "${c}kanit_black"
                Lang.AR -> "${c}lalezar"
                Lang.HI -> "${c}baloo_2_extrabold"
                else -> "${c}bangers"
            }
            FontRole.CAPTION -> if (scriptOf(tgt) == "latin") "${c}plus_jakarta_sans" else sans
        }
    }

    /**
     * The typeface for [id] (null or unknown: [tgt]'s default for [role]) in the requested style. A font with a real
     * bold or italic file uses it; a variable font uses its bold weight; anything else is made bold or slanted by the
     * platform.
     */
    fun typeface(id: String?, tgt: Lang, bold: Boolean, italic: Boolean, role: FontRole = FontRole.DIALOGUE): Typeface {
        val entry = find(id)?.takeIf { it.id !in broken } ?: find(defaultFor(tgt, role)) ?: system.first()
        val key = "${entry.id}|$bold|$italic"
        faces[key]?.let { return it }
        val built = runCatching { build(entry, bold, italic) }.getOrNull()
        if (built == null) {
            if (entry.isSystem) return Typeface.DEFAULT
            broken += entry.id // never tried again in this process; the default stands in
            return typeface(null, tgt, bold, italic, role)
        }
        faces[key] = built
        return built
    }

    /** Whether [id] failed to load ("unusable" in the font list). */
    fun isBroken(id: String): Boolean = id in broken

    private fun build(f: FontEntry, bold: Boolean, italic: Boolean): Typeface {
        val styleFlag = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        if (f.isSystem) return Typeface.create(if (f.id.endsWith("serif")) Typeface.SERIF else Typeface.SANS_SERIF, styleFlag)
        val ctx = context ?: error("StudioFonts.init not called")
        val fonts = ArrayList<Font>()
        fun add(builder: Font.Builder, weight: Int, slant: Int, variation: String?) {
            builder.setWeight(weight).setSlant(slant)
            variation?.let { builder.setFontVariationSettings(it) }
            fonts += builder.build()
        }
        fun builder(file: String): Font.Builder = when {
            f.isUser -> Font.Builder(File(userDir ?: error("no user font directory"), file))
            else -> Font.Builder(ctx.assets, "fonts/$file")
        }
        if (f.res != null) {
            val resId = RES.getValue(f.res)
            add(Font.Builder(ctx.resources, resId), 400, FontStyle.FONT_SLANT_UPRIGHT, null)
        } else {
            val regular = f.files["regular"] ?: f.files.values.first()
            add(builder(regular), if (f.variation != null) 800 else 400, FontStyle.FONT_SLANT_UPRIGHT, f.variation)
            f.files["bold"]?.let { add(builder(it), 700, FontStyle.FONT_SLANT_UPRIGHT, null) }
                ?: f.boldVariation?.let { add(builder(regular), 700, FontStyle.FONT_SLANT_UPRIGHT, it) }
            f.files["italic"]?.let { add(builder(it), 400, FontStyle.FONT_SLANT_ITALIC, null) }
            f.files["boldItalic"]?.let { add(builder(it), 700, FontStyle.FONT_SLANT_ITALIC, null) }
        }
        val family = FontFamily.Builder(fonts.first()).apply { fonts.drop(1).forEach { runCatching { addFont(it) } } }.build()
        val wantWeight = if (bold) 700 else fonts.first().style.weight
        val wantSlant = if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT
        val base = Typeface.CustomFallbackBuilder(family)
            .setSystemFallback("sans-serif")
            .setStyle(FontStyle(wantWeight, wantSlant))
            .build()
        // No real bold or italic face: the platform's fake bold / slant on what there is.
        val hasBold = fonts.any { it.style.weight >= 700 }
        val hasItalic = fonts.any { it.style.slant == FontStyle.FONT_SLANT_ITALIC }
        val fakeBold = bold && !hasBold
        val fakeItalic = italic && !hasItalic
        val fake = when {
            fakeBold && fakeItalic -> Typeface.BOLD_ITALIC
            fakeBold -> Typeface.BOLD
            fakeItalic -> Typeface.ITALIC
            else -> return base
        }
        return Typeface.create(base, fake)
    }

    private fun load(): List<FontEntry> {
        val ctx = context ?: return emptyList()
        return runCatching {
            val json = ctx.assets.open("fonts/catalogue.json").bufferedReader().use { it.readText() }
            parse(json)
        }.getOrDefault(emptyList())
    }

    /** Free fonts worth adding (Find more fonts): only listed, never downloaded by the app. */
    data class Suggestion(val name: String, val role: FontRole, val scripts: Set<String>, val tags: List<String>, val licence: String, val url: String)

    val suggestions: List<Suggestion> by lazy {
        val ctx = context ?: return@lazy emptyList()
        runCatching {
            val fonts = JSONObject(ctx.assets.open("fonts/suggestions.json").bufferedReader().use { it.readText() }).getJSONArray("fonts")
            (0 until fonts.length()).map { i ->
                val o = fonts.getJSONObject(i)
                Suggestion(
                    o.getString("name"),
                    when (o.optString("role")) { "sfx" -> FontRole.SFX; "caption" -> FontRole.CAPTION; else -> FontRole.DIALOGUE },
                    o.getJSONArray("scripts").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
                    o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    o.optString("licence"),
                    o.getString("url"),
                )
            }.filter { it.url.startsWith("https://") }
        }.getOrDefault(emptyList())
    }

    /**
     * Whether [f] matches every word of [query]: name, tags, role and script names (the caller passes the scripts'
     * names in the UI language), ignoring case and accents.
     */
    fun matches(query: String, vararg fields: String): Boolean {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val hay = fold(fields.joinToString(" "))
        return words.all { it in hay }
    }

    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** The catalogue's entries (public for tests). */
    fun parse(json: String): List<FontEntry> {
        val fonts = JSONObject(json).getJSONArray("fonts")
        return (0 until fonts.length()).map { i ->
            val o = fonts.getJSONObject(i)
            val files = o.optJSONObject("files")?.let { fo -> fo.keys().asSequence().associateWith { fo.getString(it) } }.orEmpty()
            FontEntry(
                id = FontEntry.CAT + o.getString("id"),
                name = o.getString("name"),
                role = when (o.optString("role")) { "sfx" -> FontRole.SFX; "caption" -> FontRole.CAPTION; else -> FontRole.DIALOGUE },
                tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                scripts = o.optJSONArray("scripts")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet(),
                licence = o.optString("licence"),
                files = files,
                res = o.optString("res").ifEmpty { null },
                variation = o.optString("variation").ifEmpty { null },
                boldVariation = o.optString("boldVariation").ifEmpty { null },
            )
        }
    }

    /** Script ids, in the order the font lists show them. */
    val SCRIPTS = listOf("latin", "vietnamese", "cyrillic", "ja", "ko", "zh-hans", "zh-hant", "thai", "arabic", "devanagari")

    /** One preview line per script (also the coverage probe for user fonts: a font draws a script when it has them all). */
    val SAMPLES = mapOf(
        "latin" to "Hey! What's this?",
        "vietnamese" to "Tiếng Việt ạảấầẩẫậđ",
        "cyrillic" to "Эй! Что это?",
        "ja" to "えっ！これは何？",
        "ko" to "어! 이게 뭐야?",
        "zh-hans" to "嘿！这是什么？",
        "zh-hant" to "嘿！這是什麼？",
        "thai" to "เฮ้! นี่อะไร",
        "arabic" to "مهلاً! ما هذا؟",
        "devanagari" to "अरे! यह क्या है?",
    )

    private val RES = mapOf(
        "coming_soon" to R.font.coming_soon,
        "plus_jakarta_sans" to R.font.plus_jakarta_sans,
        "luckiest_guy" to R.font.luckiest_guy,
    )
}
