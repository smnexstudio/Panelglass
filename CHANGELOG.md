# Changelog

User-visible changes to Panelglass. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions follow [Semantic Versioning](https://semver.org/). A pull request adds its entry under **Unreleased**; at
release time a maintainer renames that heading to the version and date (see CONTRIBUTING › Releases). The release
workflow publishes the version's section as the GitHub Release notes.

## [Unreleased]

### Added

- **Notifications are asked for at the first open** (Android 13+), so the first-start downloads and Studio runs show
  their progress. Asked once; no storage permission is asked for or needed (the Studio uses the system pickers).
- A finished download the system saved under another name (`model-1.part`) is now found and kept; leftover
  numbered copies are deleted.
- **First-open setup.** After the notification question, the app lists what it still needs (manga-ocr ~140 MB, LaMa
  ~208 MB on phones with 4 GB of RAM or more, the Japanese, Korean and Chinese language packs) with the total size,
  and asks Wi-Fi only (the default) or Wi-Fi or mobile data. Nothing downloads before that answer; Later asks again at
  the next open. A model you delete afterwards is not downloaded again.
- **Download over Wi-Fi only** (Settings › Models & downloads, on by default): every model and language-pack download
  waits for Wi-Fi unless it is turned off. Changing it applies to downloads already waiting too: those that have
  received nothing are queued again under the new choice, ML Kit's language packs included. A model row says
  "waiting for Wi-Fi" while it is held back.
- **Default export folder.** Settings › Studio › Default folder can now be changed: Downloads (`Download/Panelglass`)
  or any folder you pick. Every manga without a folder of its own exports there; the export screen's folder choice
  offers "Default: <folder>" beside picking one for the manga.
- **Custom AI providers.** The Custom AI key sheet offers DeepSeek, Grok, GPT, Claude and OpenRouter by name:
  picking one fills in its address (locked), and **Custom** takes any other. Settings › Engine & keys shows the
  provider with the model ("DeepSeek · deepseek-chat"). Nothing new is stored: the provider is read from the saved
  address.
- **Custom AI**, a translation engine for your own OpenAI-compatible server. Name its address and the model in
  Settings and paste your key: the app sends a plain text Chat Completions request to `<your address>/chat/completions`
  and reads the text on the device, so no image ever leaves the phone. The address must be HTTPS; a pasted
  `…/chat/completions` address is accepted and no `/v1` is added for you. Replies from home servers are read
  tolerantly (a bare object, a fenced or wrapped array, text parts), a malformed reply is retried in one self-contained
  request that asks only for the bubbles the reply missed or garbled (the good ones are kept, so a bad reply never
  costs the whole page twice), and the key sheet warns when the model name gives away a model of about a billion parameters or less
  (saving is still allowed).
- **Privacy policy and licences in the app**: Settings › About & updates › Privacy and Licences open the
  [privacy policy](PRIVACY.md) and the [licences](LICENSES.md) that shipped with your version, readable offline, with
  a link to the same page on GitHub. The policy now also says what Google's ML Kit libraries report to Google
  (diagnostics such as the device model and app version; never your text or pages).

### Changed

- **Settings, redesigned**: one long page became a hub with search and eight pages: Engine & keys, Languages, notes &
  sound effects, Models & downloads, Reader, Studio, Storage, Appearance & language, About & updates. Each hub row says
  what its page holds now; on a tablet or a wide window the page opens beside the hub.
  - **Engine & keys** lists every engine as a choice, the free ones on the phone first; the chosen cloud engine shows
    its model (and Custom AI its address), and the Try box sits next to it.
  - **Languages**: a button swaps From and To, recent language pairs are one tap away, translation notes are edited in
    place with Reset to default, and the glossary count is shown.
  - **Storage** shows what Panelglass takes and what is free; **Reader** can clear the translated pages cache, update
    the block lists now, and set the language Translate page uses.
  - **About** links to the release notes, the source code, a new issue, the licences and the privacy notes.

### Security

- Model downloads refuse cleartext addresses, and the update client refuses cleartext requests and redirects.
- Translation engines no longer follow redirects (a redirect would re-send the page text, and for Gemini the key, to
  another host), refuse plain HTTP on every hop, and stop reading a reply past 4 MB.
- Changing the Custom AI address to another server while keeping the saved key now asks first: keep the key for the
  new server, or enter a new one.

## [0.5.0] - 2026-11-08

### Changed

- The free on-device engine is now named **Google Translate (ML Kit)** in the engine picker, Settings, the reader and the Studio, so it is not mistaken for the Google Translate app or its cloud service. It works exactly as before.
- **README demo and screenshots**: the reader recording, its preview, the before/after captures and every Studio
  screenshot now show the openly licensed webcomic [Pepper&amp;Carrot](https://www.peppercarrot.com) by David Revoy
  (CC BY 4.0, Japanese translation by guruguru) instead of commercial manga, with its credit in the README and NOTICE.
  The old captures, and a screenshot of a third-party reading site, are removed.

### Added

- **More comic archives import into the Studio**: `.zip`, `.cb7` and `.7z` beside `.cbz`, told apart by their contents rather than their name (a 7z named `.cbz` opens too). Archives made by Mihon, other readers and archivers that the Studio used to call damaged or empty now open: pages stored uncompressed with their sizes after the data, Zip64 and Deflate64 archives, and file names in Japanese or Chinese encodings. Pages are recognised by their image data, so pages without an extension or named `.jfif` come in, as do AVIF files most encoders write. An archive holding chapter archives (a `.zip` of `.cbz` files) imports as one chapter each, and a pack of several series (a reader app's download folder zipped whole) offers **One manga per series**: each series becomes its own manga, titled from its ComicInfo.xml or its folder, and a manga already in the Studio with the same title gets that series' chapters.
- **Exports show up properly in Mihon** and other local-folder readers: every export now writes the manga's `cover.jpg` (the Studio's cover, or the first page) and a series `ComicInfo.xml` into its folder, so Mihon's local source shows the cover, and the publication status reaches Mihon (Mihon's own ComicInfo field, also in each chapter) instead of "Unknown". Importing a chapter Mihon downloaded picks its status and summary up too, and an alternative title travels both ways as ComicInfo's `LocalizedSeries`
- The import now lists what it left out and why: images in a format it can't read (HEIC, JPEG XL, BMP, TIFF), entries packed with an unsupported compression method, and archives nested two levels deep. RAR (`.cbr`) and password-protected archives get their own message instead of "damaged".

## [0.4.0] - 2026-10-31

### Added

- **Export clean pages** from the Studio: a new step in Export asks *Translated* (the lettered pages, as before) or *Clean* (text-free pages: the bubbles' text and replaced sound effects erased, nothing lettered), for lettering elsewhere. Clean chapters are named `… (Clean)`, so they never replace a translated export.
- **Studio translation in the background**: a chapter keeps translating when you leave the app or turn the screen off, with a progress notification (Pause, Resume, Cancel; tap it to open the chapter). It waits on its own while the phone is too hot, in Battery Saver or below 15% without a charger, while Gemini has no network, or while the reader is using the on-device model, and carries on afterwards. If Android stops it, it picks up where it left off the next time you open the app, and an interrupted **Translate again** is finished rather than forgotten. **Settings › Models › Studio runs only while charging** (off by default) holds on-device runs until you plug in. Android 13+ asks once to allow the notification.

## [0.3.0] - 2026-10-29

### Added

- **Glossary & pronouns** in the reader (⋮): character names and terms per series, each character with a pronoun (he, she, they), plus notes for the series. Gemini and the on-device models use them; Google Translate uses the names and terms.
- **Translation notes** in Settings: instructions for the AI engines on every series (tone, honorifics, how to treat missing subjects). They start with suggested notes for English (casual dialogue, honorifics kept, no invented pronouns, short lines); edit them, or empty them to send none.
- **Sound effects** setting: Keep or Replace (the default) the original effects in the reader.
- **A second text detector** (PP-OCRv6, bundled, 1.8 MB) finds sound effects and other text the comic detector missed; with Replace, effects nothing can read are still erased.
- **In-app updates**: a newer release is offered once a day (Update or Later), downloaded in the background, checked against its published checksum and handed to Android's installer. **Settings › About** checks on demand, and *Check automatically* turns the daily check off.
- More Japanese and Korean sound effects in the built-in dictionary. Hiragana and katakana spellings of an effect both match, and so does a spelling with an extra or missing ッ or ー (ガツーン, ガツンッ → ガツン).

### Changed

- The reader switches to the page's own language (Japanese, Korean or Chinese) when it differs from Settings, unless the site has its own.
- Text that reads as nothing is read again in high contrast, which finds narration on dark art and outlined lettering.
- Editing a glossary or notes translates pages again instead of showing cached ones.

### Fixed

- Choosing **Auto** as the reader's font now sticks for someone who had picked a font before the font catalogue.
- A translation provider's reply that is not JSON (a Wi-Fi login page in its place) now shows as an unusable reply, like any other, instead of a generic error.
- TalkBack names every switch in Settings and the Studio's panels (it read "Switch, off" with no name), and the whole row now turns the switch on or off.

## [0.2.0] - 2026-10-02

### Added

- **Studio**, a new tab that translates chapters you already have into image files:
  - **Import** images, a PDF or a `.cbz`. ComicInfo.xml fills in the details, and a CBZ with a folder per chapter becomes several chapters. Manga get covers, details and a searchable library.
  - **Translate** a whole chapter with the engine chosen in Settings; stop and **Continue** at any time.
  - **Review** bubble by bubble: correct the original or the translation, re-translate, add missed text, mark false detections *Not text* or delete them, and mark pages reviewed. Zoom up to 8×; undo and redo every step.
  - **Clean and restyle**: the original text is removed and the translation lettered inside each balloon's traced shape. Set font, size, colour, outline, fill, border and rounded corners per bubble or page; reshape bubbles and brush out leftovers. Optional **LaMa** cleanup (208 MB) rebuilds artwork under text.
  - **Sound effects**: keep or replace, with presets, and move, turn, stretch or bend them.
  - **Fonts**: 21 bundled fonts with a default for every target language, plus your own `.ttf` / `.otf` / `.ttc`.
  - **Export** a chapter or a whole manga as images, CBZ or ZIP (with ComicInfo.xml) to Download/Panelglass or a folder you pick.
- **Reader fonts**: Settings › Fonts offers every Studio font and your own. **Auto**, the new default, picks a comic font made for the target language.
- **Tablets and Chromebooks**: a navigation rail and side-by-side layouts on wide windows, and keyboard shortcuts in the editor.
- **Settings › Storage** lists each Studio manga's size and deletes it.

### Changed

- **Google Translate is the default engine** on a new install. If you use Qwen and never picked an engine, you stay on Qwen.
- **Language packs download only when needed**: Japanese, Korean and Chinese at first start; any other when you tap **Download** or pick it in Settings › Models.
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
  - Press **Start** and the screen is translated. After each scroll, the next screen is translated once the page settles.
  - Translations stay pinned to the artwork while you scroll or the site shifts its layout.
  - Scrolling and paged readers both work: a tap or swipe page turn is noticed, pages still downloading are picked up when they arrive, and rotating the phone translates the new layout.
  - Balloons cut off at the screen edge wait until they are fully on screen.
  - Only comic art is translated, never the site's toolbars floating above it.
  - **⋮ → Re-translate** reads the current screen again from scratch.
- **In-place lettering.**
  - The original text is removed and the translation is fitted into the same balloon or caption box, sized to stay readable.
  - Vertical Japanese is supported.
  - Three lettering fonts are included.
- **Four translation engines, cloud or fully offline:**
  - **Gemini**, with your own API key: it reads each balloon itself for the best quality and speed, and works with any Gemini or Gemma model your key can use.
  - **Google Translate**: on-device (ML Kit), free, with ~30 MB offline language packs.
  - **Qwen 2.5 1.5B**: an on-device model, a one-time 1.6 GB download. It runs on the GPU, or on the CPU on phones under 6 GB of RAM; **Settings → Models → Qwen runs on** changes it.
  - **Gemma 4 E2B**: a higher-quality on-device model, a 2.6 GB download for phones with 8 GB of RAM.
- **Built for comics.**
  - A bundled comic text and bubble detector finds the lettering.
  - Japanese is read by the optional manga-ocr model (140 MB download) or by ML Kit.
  - Model downloads keep going in the background and resume after interruptions. Every file is checked against a pinned SHA-256.
- **Languages.**
  - Translate from Japanese, Korean, Chinese (Simplified and Traditional), English, Spanish, French, German, Italian, Portuguese, Russian, Indonesian or Vietnamese.
  - Translate into any of those, or into Thai, Arabic or Hindi.
- **Your sites.**
  - No sites ship with the app: add the readers you use and pin favourites.
  - Set languages, auto-translate and ad blocking per site.
  - Recent chapters are kept in History.
- **Ad and pop-up blocking.**
  - Public block lists (AdGuard DNS, HaGeZi pop-up ads, StevenBlack as a fallback) are fetched and refreshed daily.
  - Pop-ups and popunders are caught, and interstitials and tap-hijacking overlays are cleaned up.
- **Translate page** (**⋮ → Translate page**): translates the site's own text, such as titles, chapter lists and comments, on the device. **Show original** puts the page back.
- **Try a translation** box in Settings, to check an engine before reading.
- **Themes and app language.**
  - Four themes: Default, Panel Pop, Soft Bloom, and Paper & Ink.
  - The interface is available in 16 languages (Arabic right to left), switched in **Settings → App language**.
- **Privacy.**
  - API keys are encrypted with the Android Keystore and never logged.
  - The offline engines keep everything on the device, and there is no Panelglass server.
  - Nothing is backed up, and uninstalling removes everything.
