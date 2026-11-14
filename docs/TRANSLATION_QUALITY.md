# Translation quality

What you can do to get better translations, what Panelglass does on its own, and what it cannot fix yet. For how a
screen is translated step by step, see [FLOW.md](FLOW.md).

## Contents

- [The quick checklist](#the-quick-checklist)
- [Pick the engine for the job](#pick-the-engine-for-the-job)
- [Source language: set it, or let the page decide](#source-language-set-it-or-let-the-page-decide)
- [Glossary and pronouns (per series)](#glossary-and-pronouns-per-series)
- [Translation notes](#translation-notes)
- [Sound effects](#sound-effects)
- [What Panelglass cleans up by itself](#what-panelglass-cleans-up-by-itself)
- [Known limits](#known-limits)
- [Measuring a change](#measuring-a-change)

## The quick checklist

1. **Source language right?** The bar shows it (`KO → EN`). Panelglass switches to Japanese, Korean or Chinese by
   itself when the page is clearly in one of them; a saved site's language always wins.
2. **Engine suited to the series?** Gemini reads the bubbles itself and is the most accurate; the on-device models are
   private and offline; ML Kit is free but literal.
3. **Names and pronouns in the glossary** (reader ⋮ › *Glossary & pronouns*), for the characters that matter.
4. **A few translation notes** (Settings › Languages & notes › *Translation notes*): tone, honorifics, how to treat missing subjects.
5. **Sound effects mode** (Settings › Languages & notes › *Sound effects*): *Replace* to erase the original effects.

## Pick the engine for the job

| Engine | Strengths | Weak spots seen on real chapters |
|---|---|---|
| **Gemini** (your key) | Reads each bubble from the image, so OCR errors do not reach it; follows notes and pronouns well | Needs a network; crops of the screen go to Google |
| **Gemma 4 E2B** (on device, 8 GB RAM) | Natural dialogue; follows notes and glossary pronouns | 5–15 s per screen; occasionally switches language mid-sentence (cleaned up, see below) |
| **Qwen 2.5 1.5B** (on device) | Runs on smaller phones | Weaker on tone and context |
| **ML Kit** (on device) | Free, fast, offline | Literal; titles and honorifics go wrong (공자님 "young lord" came out as "Confucius"); ignores notes and pronouns, uses only glossary terms |

Notes and pronouns are used by **Gemini and the on-device models**. ML Kit has no prompt: it only replaces
glossary terms.

## Source language: set it, or let the page decide

A wrong source language is the most damaging mistake: Korean read with the Japanese recognizer comes out as nonsense.

- **Settings › Languages & notes › From** is the default for every site.
- **A saved site** (Sites › long-press › languages) can have its own pair; it always wins.
- **The page's own language.** After a page loads, the reader identifies the language of its visible text (ML Kit
  language identification, with `<html lang>` as a hint). If the page is Japanese, Korean or Chinese and differs from
  the configured source, this reading session switches to it, and the bar shows the new pair. The choice is kept for
  later chapters on the same site. A source language saved on the site, or set by a launch intent, is never
  overridden.

## Glossary and pronouns (per series)

Open a chapter, then **⋮ › Glossary & pronouns**.

- **Names and terms.** Add a name or term as written on the page and how it should read in your language (60
  characters each). Every engine uses these: the AI engines are told, ML Kit substitutes them.
- **Pronoun.** Give a character *He*, *She* or *They*. Japanese and Korean often leave the subject out, and a model
  then guesses, usually *he*. With pronouns set, the AI engines receive a characters list:

  ```
  Characters:
  - Yuki (ユキ): female, she/her
  - Ren (レン): male, he/him
  ```

- **Notes for this series** (500 characters): who is who, how characters speak. For example:

  ```
  The narrator is a boy. The innkeeper speaks formally; the twins talk like kids.
  ```

- **Scope.** The sheet shows what the notes apply to (*For peppercarrot.com/ja*). For a saved site it is that site's
  own address and path, so two series on one site keep separate notes; for an unsaved page it is the host and first
  path segment. Save a series as a site to give it notes of its own.
- Closing the sheet after a change translates what is on screen again with it.

**Verified on a phone (Gemma 4, Japanese → English):** without notes, the lines with no subject came out as *he*
(4 lines) and none as *she*; with a note saying the characters are female, the same screens gave 5 *she* lines and 1
*he*, and the one *he* left was a line where the Japanese itself writes 彼 ("he"). The model fills in what the
original leaves out and keeps what it states.

## Translation notes

**Settings › Languages & notes › Translation notes** apply to every series; series notes add to them. Short, factual lines work best,
especially for the on-device models (each note is capped at 500 characters).

Settings starts with these notes (`TranslationNotes.DEFAULT`); edit them, or empty them to send none (emptied
notes stay empty):

```
Natural, casual English dialogue. Keep honorifics (-san, -sama, senpai).
Never add a pronoun or object that isn't in the original; if the subject is unclear, rephrase without one.
Short lines that fit a speech bubble.
```

What it changed on a real screen (Gemma 4, Japanese → English): the register became more casual ("Get out of here,
kid!!" → "Get lost, kid!!"; "defeat me" → "take me down"), and nothing else got worse. Notes cannot fix a word the
OCR misread.

How notes reach the engines: the pipeline puts the characters list, the series notes and the Settings notes together
(`TranslationNotes.compose`) and sends them with every call as the reader's own instructions, which the models follow
(page text never counts as instructions). The glossary and notes are part of the patch-cache key, so editing them
translates cached pages again.

With Gemini, the notes and glossary leave the phone with each request, on your own key. Every reply is capped by the
number of bubbles (300 + 120 tokens each, at most 8,192), so no note can make a call write, and bill, more than a
page needs.

## Sound effects

**Settings › Languages & notes › Sound effects** decides what happens to them in the reader:

| Mode | Result |
|---|---|
| **Keep** | The original effect stays untouched |
| **Replace** (default) | The original is erased and the translation drawn in its place |

Gloss (a small translation beside the original) and Overlay (the translation drawn over the original) were removed;
a choice of either now reads as Replace.

How effects are found and read:

1. **Two detectors.** The comic detector finds balloons and most text; a second pass (PP-OCRv6 tiny) looks for text
   it missed. On Korean webtoon screenshots the comic detector found 1 of 8 brush-lettered effects, the second pass 7.
2. **Reading.** ML Kit reads the crop; if it finds nothing, it reads it again in high contrast (black ink on white).
   Gemini reads the crop itself.
3. **Translating.** A bundled dictionary of common Japanese and Korean effects (hiragana and katakana treated alike,
   extra ッ or ー ignored) answers first, then a cache of earlier translations, then the engine.
4. **Unreadable effects.** An effect the detectors are sure of but nothing can read is never translated. With
   **Replace** it is still erased, unless the art behind it is too busy to repaint cleanly; then it is left as it
   is rather than covered with a blank box.

## What Panelglass cleans up by itself

| Problem | What happens |
|---|---|
| The model repeats the source before the translation (`예를 들자면: For example.`) | Cut to the translation |
| The model switches script mid-line (`…, ofもちろん`, `I was गहराईly impressed`) | The line is translated again on its own; if the retry still has foreign words, they are removed |
| A lone character only the full-page OCR saw (the app's own ✓ read as ソ) | Dropped; the status disc's corner is never read |
| A line of only punctuation (`……`) inside a balloon | Erased with the balloon, not left behind |
| Outlined lettering on dark art | The erase reaches the outline, not only the fill |

## Known limits

- **OCR misreads on stylised or small text.** One rare word (矮軀, "puny body") was read as two different wrong
  words in two runs; no prompt fixes that. Gemini, which reads the image itself, avoids this class of error.
- **Big stylised exclamations and most sound effects are found but not read**, so they are erased (Replace) rather
  than translated.
- **Busy or rainbow art.** When the eraser cannot repaint the background cleanly, a caption is covered with a flat
  box in a colour taken from the art, which can look out of place.
- **Joined balloons split one sentence.** ML Kit translates each balloon alone ("Good" / "Morning.").
- **Notes on unsaved series are shared** by every series under the same host and first path segment.

## Measuring a change

Judge a prompt or setting change on the same screens, before and after:

1. Install the debug build ([CONTRIBUTING › Building](../CONTRIBUTING.md#building)); it sits beside the release app.
2. Turn on the debug dump ([FLOW.md › Diagnostics](FLOW.md#diagnostics)) and translate a fixed set of screens.
3. Compare `regions.txt` between runs: the translations, how many still hold source script, which pronouns appear,
   and the `timing` lines in logcat.
