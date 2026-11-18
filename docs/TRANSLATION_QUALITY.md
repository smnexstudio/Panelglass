# Translation Quality Guide

This guide explains how to get the highest translation accuracy in Panelglass, how the app's internal prompt and cleanup pipeline works, and how to configure glossaries, pronouns, notes, and sound effects for manga, manhwa, and manhua.

For an architectural breakdown of the translation pipeline, see [FLOW.md](FLOW.md).

---

## Contents

- [The Quick Checklist](#the-quick-checklist)
- [Translation Engine Selection](#translation-engine-selection)
- [Source Language: Manual vs. Automatic](#source-language-manual-vs-automatic)
- [Character Glossaries & Pronouns (Per Series)](#character-glossaries--pronouns-per-series)
- [Custom Translation Notes](#custom-translation-notes)
- [Sound Effects (SFX) Handling](#sound-effects-sfx-handling)
- [Automated Pipeline Cleanups](#automated-pipeline-cleanups)
- [Known Limits & Edge Cases](#known-limits--edge-cases)
- [Benchmarking & Measuring Changes](#benchmarking--measuring-changes)

---

## The Quick Checklist

Follow these 5 steps to optimize quality before reading a new series:

```
┌────────────────────────────────────────────────────────────────────────┐
│ 1. Verify Source Language  → Check reader bottom bar (e.g., KO → EN)   │
│ 2. Select Optimal Engine   → Gemini for art-rich text, Gemma for offline│
│ 3. Add Character Pronouns  → Reader ⋮ › Glossary & pronouns (he/she)  │
│ 4. Set Translation Notes   → Settings › Languages & notes (tone/honor) │
│ 5. Choose SFX Mode         → Settings › Sound effects (Keep vs Replace) │
└────────────────────────────────────────────────────────────────────────┘
```

1. **Verify the Source Language:** Check the bottom bar (`KO → EN`, `JA → EN`, `ZH → EN`). Panelglass auto-detects Japanese, Korean, or Chinese; saved sites preserve your manual overrides.
2. **Match the Engine to the Series:** Use Gemini when text overlaps busy art (reads images directly); use Gemma 4 for natural offline dialogue; use ML Kit for fast, low-battery offline reading.
3. **Set Names & Pronouns in the Glossary:** Tap reader **⋮ › Glossary & pronouns** to define main character names and gender pronouns (*he*, *she*, *they*).
4. **Tune Translation Notes:** Go to **Settings › Languages & notes › Translation notes** to specify dialogue tone, formality, honorific handling, and missing-subject behavior.
5. **Set Sound Effect Preferences:** Configure **Settings › Languages & notes › Sound effects** to *Replace* (erase original art lettering) or *Keep*.

---

## Translation Engine Selection

Each engine has distinct trade-offs between speed, offline capability, OCR robustness, and contextual awareness:

| Engine | Type | Strengths | Known Limitations | Best For |
|---|---|---|---|---|
| **Google Gemini** | Cloud (User API Key) | • Directly inspects image crops (bypasses OCR errors)<br>• Follows complex character pronouns and notes<br>• Preserves nuance and comic idioms | • Requires active internet connection<br>• Image crops sent to Google API | Complex artwork, stylized lettering, dialogue with implicit subjects |
| **Gemma 4 E2B** | On-Device (LiteRT-LM) | • Completely private and offline<br>• Natural, conversational dialogue flow<br>• Accurately adheres to character pronouns | • Requires device with 8 GB+ RAM<br>• Takes 5–15 s per screen | High-end phones, offline reading of character-driven manga |
| **Qwen 2.5 1.5B** | On-Device (LiteRT-LM) | • Fully offline<br>• Runs on 6 GB RAM devices (or CPU mode) | • Slightly more literal phrasing<br>• Less sensitive to subtle character tone | Offline reading on mid-range hardware |
| **Google Translate** | On-Device (ML Kit) | • Instant (sub-second)<br>• Zero setup, minimal RAM (~30 MB) | • Literal, machine-like translations<br>• Ignores notes/pronouns (replaces glossary only)<br>• Prone to honorific errors (e.g. 공자님 → "Confucius") | Fast action series, low battery, low-spec phones |
| **Custom AI** | Cloud / Local (OpenAI-compatible) | • Full user control over provider and model<br>• Flexible self-hosted inference | • Latency depends on server<br>• Text-only (relies on device OCR) | Power users with local servers or specialized LLMs |

> [!NOTE]
> Character notes and pronoun hints are passed directly to **Gemini, Gemma 4, Qwen 2.5, and Custom AI**. Google ML Kit does not accept prompt instructions; it applies exact dictionary term substitution.

---

## Source Language: Manual vs. Automatic

> [!IMPORTANT]
> Selecting the wrong source language causes severe translation failures (e.g., reading Korean characters with the Japanese OCR model yields gibberish).

Panelglass resolves the source language through a clear priority chain:

1. **Saved Site Override (Highest Priority):** Saved in **Sites › Long-press site › Languages**. This override is always respected.
2. **Launch Intent:** An explicit source passed via external launcher extras (`--es src JA`).
3. **Automatic Page Detection:** When a page loads, the reader inspects visible DOM text (`pt.js` + ML Kit Language Identification). If Japanese, Korean, or Chinese is detected with high confidence and differs from the current setting, the session switches automatically, updating the bottom bar.
4. **Global Default (Fallback):** Defined in **Settings › Languages & notes › Source language**.

---

## Character Glossaries & Pronouns (Per Series)

Japanese and Korean comic dialogue frequently omits grammatical subjects. Without context, AI models guess the subject (defaulting to "he"). Providing pronoun definitions resolves this ambiguity.

Open any chapter, tap **⋮ › Glossary & pronouns**:

### 1. Character Pronouns & Names
Define major characters, their original spellings, and their pronouns (terms are capped at 60 characters):

Each glossary entry has the original spelling, your rendering, and an optional pronoun (*he*, *she* or *they*). Entries with a pronoun are sent to the engine as a "Characters" list:

```
Characters:
- Yuki (ユキ): female, she/her
- Ren (レン): male, he/him
```

Entries without a pronoun are still substituted as terms (Google Translate uses only this part).

### 2. Series Context Notes
Provide concise, factual context (up to 500 characters) about relationships and speech registers:

```
The narrator is a young boy. 
The innkeeper speaks in an arch, formal tone.
The twins speak informally in sync.
```

### 3. Scope & Persistence
- **Series Scope:** Notes apply to the current series (derived from the domain and first path segment, e.g. `site.com/series-name`, or a saved site).
- **Auto-Retranslation:** Saving changes in the glossary sheet automatically invalidates the on-screen patch cache and retranslates the visible viewport.
- **Backup Support:** Glossaries and series notes can be saved to a file and merged back, on this or another phone, via **Settings › Backup & restore**.

> [!TIP]
> **Real-World Impact (Gemma 4, JA → EN):** In a test chapter with unstated subjects, translating without notes produced 4 *he* lines and 0 *she* lines. Adding a note specifying that the characters were female yielded 5 *she* lines and 1 *he* line (where the original explicitly wrote 彼 / "he").

---

## Custom Translation Notes

Global instructions configured in **Settings › Languages & notes › Translation notes** apply to every translated series. Series-specific notes are appended to these global instructions.

Default translation notes (`TranslationNotes.DEFAULT`):

```
Natural, casual English dialogue. Keep honorifics (-san, -sama, senpai).
Never add a pronoun or object that isn't in the original; if the subject is unclear, rephrase without one.
Short lines that fit a speech bubble.
```

### How Notes Reach the Engines
1. **Composition:** The pipeline merges the character pronoun list, series notes, and global settings notes via `TranslationNotes.compose`.
2. **Prompt Injection:** Notes are passed as system instructions (`LlmContract` for cloud, `LocalPrompt` for LiteRT-LM). Page text is strictly framed as untrusted data, never instructions.
3. **Cache Invalidation:** The hash of the composed notes forms part of the `PatchCache` key. Changing notes automatically prompts fresh translations.
4. **Billing Safety:** Every cloud call (Gemini and Custom AI) caps its reply at `300 + 120` tokens per bubble (maximum 8,192 tokens), so verbose notes cannot make one call write thousands of tokens.

---

## Sound Effects (SFX) Handling

Configure sound effects in **Settings › Languages & notes › Sound effects**:

| Mode | Visual Behavior | How It Works |
|---|---|---|
| **Replace** *(Default)* | The original Japanese/Korean sound effect is erased and typeset with the translated equivalent. | Detectors identify SFX; dictionary/OCR reads the text; inpainter erases the original; translated lettering is drawn over the artwork. |
| **Keep** | The original artwork lettering remains untouched. | Sound effect regions are excluded from the erase and render stages. |

### How Sound Effects are Detected & Translated
1. **Two-Tier Detection:** The primary comic detector locates speech balloons; the secondary `PP-OCRv6 tiny` detector finds brush-lettered sound effects outside balloons.
2. **Recognition:** manga-ocr (Japanese) or ML Kit reads the crop; if ML Kit finds nothing, it retries with Otsu high-contrast inversion. Gemini reads the image crop directly.
3. **Dictionary Lookup:** Common Japanese and Korean sound effects are checked against an internal dictionary (`SfxDictionary`), normalizing variants (ignoring prolonged sound marks like ー or small ッ).
4. **Unreadable Effects Fallback:** Stylized artwork effects that cannot be deciphered by OCR are never fabricated. Under *Replace* mode, the original effect is cleanly erased only if the background can be safely inpainted; otherwise, the original art is left intact.

---

## Automated Pipeline Cleanups

Panelglass includes automated heuristics to sanitize machine translations before rendering:

| Anomaly | Root Cause | Automated Resolution |
|---|---|---|
| **Source Text Echo** | LLM outputs `예를 들자면: For example.` | The source prefix is detected and trimmed (`withoutSource`). |
| **Mid-Sentence Script Switch** | Model outputs `I was गहराईly impressed` or `..., ofもちろん` | The line is isolated and re-translated; if foreign characters persist, stray words are dropped (`withoutStrayScript`). |
| **False Positive Single Glyphs** | UI status indicator (e.g. ✓) read as Japanese ソ | The app's own status disc is excluded from detection; a lone letter found only by the full-page backstop is dropped. |
| **Punctuation-Only Bubbles** | Speech balloon containing only `……` or `———` | Erased cleanly with the balloon, avoiding redundant punctuation rendering. |
| **Outlined Lettering Artifacts** | Dark outlines around white comic text | Inpainting mask is expanded by 8% of glyph height, erasing both fill and outline. |

---

## Known Limits & Edge Cases

- **Stylized Typography:** Ornate calligraphy, distressed lettering, or extremely small background text may be misread by OCR. Gemini avoids this by reading directly from raw image pixels.
- **Complex Art Backgrounds:** When sound effects cross multicolored gradients or intricate line art, the ray-inpainter may decline the erase operation to avoid smearing the artwork.
- **Conjoined Balloons:** Balloons linked by narrow necks may be split or merged depending on detector confidence. ML Kit translates each balloon independently, whereas LLM engines see several bubbles together to preserve context (cloud: up to 60 per call; on-device: 4 at a time, in reading order).
- **Unsaved Series Scope:** For unsaved websites, notes and glossaries are scoped to `host/first-path-segment`. Save the series as a bookmark in **Sites** to isolate notes.

---

## Benchmarking & Measuring Changes

To objectively evaluate prompt adjustments, glossary changes, or model upgrades:

1. **Install the Debug Build:** Build and deploy `com.smnexstudio.panelglass.debug` (runs side-by-side with the release app).
2. **Enable Diagnostic Dumps:** Create the debug directory via ADB:
   ```bash
   adb shell run-as com.smnexstudio.panelglass.debug mkdir -p files/debug-dump
   ```
3. **Capture & Translate:** Scroll through a test chapter.
4. **Inspect the Output:** Pull the generated artifacts:
   ```bash
   adb exec-out run-as com.smnexstudio.panelglass.debug tar c -C files debug-dump > debug-dump.tar
   ```
5. **Analyze `regions.txt`:** Inspect each region's kind, box, text, translation and erase method; pipeline timings are logged under the `TranslationPipeline` logcat tag. Delete `files/debug-dump` when done.
