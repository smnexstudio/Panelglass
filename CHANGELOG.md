# Changelog

User-visible changes to Panelglass. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions follow [Semantic Versioning](https://semver.org/). A pull request adds its entry under **Unreleased**; at
release time a maintainer renames that heading to the version and date (see CONTRIBUTING › Releases). The release
workflow publishes the version's section as the GitHub Release notes.

## [Unreleased]

### Changed

- One Automatic / CPU / GPU setting now controls both built-in models, preserving the previous Qwen backend choice. No NPU support or proprietary libraries are included.

### Added

- **Export clean pages** from the Studio: a new step in Export asks *Translated* (the lettered pages, as before) or
  *Clean* (text-free pages: the bubbles' text and replaced sound effects erased, nothing lettered), for lettering
  elsewhere. Clean chapters are named `… (Clean)`, so they never replace a translated export.

## [0.3.0] - 2026-10-29

### Added

- **Glossary & pronouns** in the reader (⋮): character names and terms per series, each character with a pronoun
  (he, she, they), plus notes for the series. Gemini and the on-device models use them; Google Translate uses the
  names and terms.
- **Translation notes** in Settings: instructions for the AI engines on every series (tone, honorifics, how to treat
  missing subjects). They start with suggested notes for English (casual dialogue, honorifics kept, no invented
  pronouns, short lines); edit them, or empty them to send none.
- **Sound effects** setting: Keep or Replace (the default) the original effects in the reader.
- **A second text detector** (PP-OCRv6, bundled, 1.8 MB) finds sound effects and other text the comic detector
  missed; with Replace, effects nothing can read are still erased.
- **In-app updates**: a newer release is offered once a day (Update or Later), downloaded in the background, checked
  against its published checksum and handed to Android's installer. **Settings › About** checks on demand, and
  *Check automatically* turns the daily check off.
- More Japanese and Korean sound effects in the built-in dictionary. Hiragana and katakana spellings of an effect both
  match, and so does a spelling with an extra or missing ッ or ー (ガツーン, ガツンッ → ガツン).

### Changed

- The reader switches to the page's own language (Japanese, Korean or Chinese) when it differs from Settings, unless
  the site has its own.
- Text that reads as nothing is read again in high contrast, which finds narration on dark art and outlined lettering.
- Editing a glossary or notes translates pages again instead of showing cached ones.

### Fixed

- Choosing **Auto** as the reader's font now sticks for someone who had picked a font before the font catalogue.
- A translation provider's reply that is not JSON (a Wi-Fi login page in its place) now shows as an unusable reply,
  like any other, instead of a generic error.
- TalkBack names every switch in Settings and the Studio's panels (it read "Switch, off" with no name), and the
  whole row now turns the switch on or off.

## [0.2.0] - 2026-10-02

### Added

- **Studio**, a new tab that translates chapters you already have into image files:
  - **Import** images, a PDF or a `.cbz`. ComicInfo.xml fills in the details, and a CBZ with a folder per chapter
    becomes several chapters. Manga get covers, details and a searchable library.
  - **Translate** a whole chapter with the engine chosen in Settings; stop and **Continue** at any time.
  - **Review** bubble by bubble: correct the original or the translation, re-translate, add missed text, mark false
    detections *Not text* or delete them, and mark pages reviewed. Zoom up to 8×; undo and redo every step.
  - **Clean and restyle**: the original text is removed and the translation lettered inside each balloon's traced
    shape. Set font, size, colour, outline, fill, border and rounded corners per bubble or page; reshape bubbles and
    brush out leftovers. Optional **LaMa** cleanup (208 MB) rebuilds artwork under text.
  - **Sound effects**: keep or replace, with presets, and move, turn, stretch or bend them.
  - **Fonts**: 21 bundled fonts with a default for every target language, plus your own `.ttf` / `.otf` / `.ttc`.
  - **Export** a chapter or a whole manga as images, CBZ or ZIP (with ComicInfo.xml) to Download/Panelglass or a
    folder you pick.
- **Reader fonts**: Settings › Fonts offers every Studio font and your own. **Auto**, the new default, picks a comic
  font made for the target language.
- **Tablets and Chromebooks**: a navigation rail and side-by-side layouts on wide windows, and keyboard shortcuts in
  the editor.
- **Settings › Storage** lists each Studio manga's size and deletes it.

### Changed

- **Google Translate is the default engine** on a new install. If you use Qwen and never picked an engine, you stay
  on Qwen.
- **Language packs download only when needed**: Japanese, Korean and Chinese at first start; any other when you tap
  **Download** or pick it in Settings › Models.
- Panelglass now needs **Android 12** or later.
- **https only**: `http://` addresses and links open as `https://`; a site that only serves http no longer loads.
- Links opened from another app are no longer translated automatically; tap **Start**.
- Translations stay on the page while you zoom.
- Moving to a new phone no longer copies Panelglass's data.

### Fixed

- Changing the reader font now redraws pages translated earlier.
- A very large block list can no longer exhaust memory, and a damaged cache file is discarded safely.
- A web page can no longer impersonate Panelglass's in-page helper, and an oversized reply from a page is ignored.
- Pop-up windows are checked without running their scripts or reading local files.

## [0.1.0] - 2026-09-27

The first release. Panelglass is a manga translator for Android: open a raw manga, manhwa, manhua or webtoon site,
press **Start**, and every speech balloon, caption and sound effect is erased and redrawn in your language, right
where it was. It needs Android 8.0 or later.

### Added

- **Screen translation in the reader.**
  - Press **Start** and the screen is translated. After each scroll, the next screen is translated once the page
    settles.
  - Translations stay pinned to the artwork while you scroll or the site shifts its layout.
  - Scrolling and paged readers both work: a tap or swipe page turn is noticed, pages still downloading are picked
    up when they arrive, and rotating the phone translates the new layout.
  - Balloons cut off at the screen edge wait until they are fully on screen.
  - Only comic art is translated, never the site's toolbars floating above it.
  - **⋮ → Re-translate** reads the current screen again from scratch.
- **In-place lettering.**
  - The original text is removed and the translation is fitted into the same balloon or caption box, sized to stay
    readable.
  - Vertical Japanese is supported.
  - Three lettering fonts are included.
- **Four translation engines, cloud or fully offline:**
  - **Gemini**, with your own API key: it reads each balloon itself for the best quality and speed, and works with
    any Gemini or Gemma model your key can use.
  - **Google Translate**: on-device (ML Kit), free, with ~30 MB offline language packs.
  - **Qwen 2.5 1.5B**: an on-device model, a one-time 1.6 GB download. It runs on the GPU, or on the CPU on phones
    under 6 GB of RAM; **Settings → Models → Qwen runs on** changes it.
  - **Gemma 4 E2B**: a higher-quality on-device model, a 2.6 GB download for phones with 8 GB of RAM.
- **Built for comics.**
  - A bundled comic text and bubble detector finds the lettering.
  - Japanese is read by the optional manga-ocr model (140 MB download) or by ML Kit.
  - Model downloads keep going in the background and resume after interruptions. Every file is checked against a
    pinned SHA-256.
- **Languages.**
  - Translate from Japanese, Korean, Chinese (Simplified and Traditional), English, Spanish, French, German,
    Italian, Portuguese, Russian, Indonesian or Vietnamese.
  - Translate into any of those, or into Thai, Arabic or Hindi.
- **Your sites.**
  - No sites ship with the app: add the readers you use and pin favourites.
  - Set languages, auto-translate and ad blocking per site.
  - Recent chapters are kept in History.
- **Ad and pop-up blocking.**
  - Public block lists (AdGuard DNS, HaGeZi pop-up ads, StevenBlack as a fallback) are fetched and refreshed daily.
  - Pop-ups and popunders are caught, and interstitials and tap-hijacking overlays are cleaned up.
- **Translate page** (**⋮ → Translate page**): translates the site's own text, such as titles, chapter lists and
  comments, on the device. **Show original** puts the page back.
- **Try a translation** box in Settings, to check an engine before reading.
- **Themes and app language.**
  - Four themes: Default, Panel Pop, Soft Bloom, and Paper & Ink.
  - The interface is available in 16 languages (Arabic right to left), switched in **Settings → App language**.
- **Privacy.**
  - API keys are encrypted with the Android Keystore and never logged.
  - The offline engines keep everything on the device, and there is no Panelglass server.
  - Nothing is backed up, and uninstalling removes everything.
