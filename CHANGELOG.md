# Changelog

User-visible changes to Panelglass. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions follow [Semantic Versioning](https://semver.org/). A pull request adds its entry under **Unreleased**; at
release time a maintainer renames that heading to the version and date (see CONTRIBUTING › Releases). The release
workflow publishes the version's section as the GitHub Release notes.

## [Unreleased]

### Changed

- Panelglass now needs Android 12 or later (it ran on Android 8.0 before).
- Panelglass now browses over https only. `http://` addresses and links open as `https://`, and a site that only
  serves plain http no longer loads.
- A link opened from another app is no longer translated automatically, even with translate-on-open on. Tap
  **Start** to translate it.
- Translations now stay on the page when you zoom in or out, growing and shrinking with the art, instead of
  disappearing and being translated again.
- Moving to a new phone no longer copies Panelglass's data (site logins, history, sites, settings). Set it up again
  on the new phone.

### Fixed

- A very large block-list download can no longer exhaust memory, and a damaged translation cache file is discarded
  safely.
- A web page can no longer impersonate Panelglass's in-page helper to read "Translate page" results or disturb
  screen translation, and an oversized reply from a page is ignored instead of slowing the reader down.
- A pop-up window is now checked without running its scripts or reading local files, before Panelglass decides
  whether to open it.

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
