# Privacy

Panelglass is a reader and translator for comics. It has no account, no analytics of its own, no ads and no server:
nothing you read or translate is sent to the Panelglass project. This page says what stays on your phone, what leaves
it, and where it goes.

This is the privacy policy of the Panelglass app published at https://github.com/smnexstudio/Panelglass. A build of
a fork may differ: read that fork's own policy.

## What stays on your phone

- **The pages you read.** Screen translation captures what is on screen, finds the text and reads it on the phone.
  The captures are never stored, except as translated patches in the app's cache (**Settings › Sites › Translated
  pages cache**).
- **Your sites, history, glossaries and translation notes.** They are kept in the app's private storage.
- **Studio files.** Imported pages, edits and your own fonts stay in the app's private storage. An export is written
  only to the folder you pick. **Settings › Storage** deletes a manga and everything it took.
- **API keys.** They are encrypted with the Android Keystore (AES-GCM), never written to logs, and never backed up.
- **Logs.** They hold timings and the type of a failure only: no keys, page addresses or page text.

Uninstalling the app removes everything it stored. Nothing is backed up.

## What leaves your phone, by engine

The engine is the one you choose in **Settings › Engine & keys**. Panelglass never switches to another one by itself.

| Engine | What is sent | To whom |
|---|---|---|
| Google Translate (ML Kit) | No page text: translation runs on the phone | (see "Google's ML Kit" below) |
| Qwen 2.5 and Gemma 4 (on-device) | Nothing: the model runs on the phone | No one |
| Gemini | Small images of the text on your screen, the glossary and your translation notes | Google, under your own API key and Google's terms |
| Custom AI | The text read on the phone (never an image), the glossary and your notes | The provider you picked (DeepSeek, xAI, OpenAI, Anthropic, OpenRouter) or the server address you entered |

With Gemini or Custom AI, the provider's own privacy policy and terms apply to what you send them. Requests go
straight from your phone to the provider, over HTTPS.

**Translate page** (reader ⋮ › Translate page) identifies and translates the page's text on the phone with ML Kit:
the page text is not sent anywhere.

## Other connections

- **The sites you open.** The reader is a web browser: the sites you visit see your visit as in any browser, and
  keep their own cookies. The reader uses Android System WebView, which follows your phone's settings (Google Safe
  Browsing checks, and usage reports only if you allowed them for Android).
- **Block lists.** When ad blocking is on, the app downloads the AdGuard DNS filter, the HaGeZi pop-up ads list and,
  as a fallback, StevenBlack's hosts list, from GitHub.
- **Downloads you start.** On-device models come from Hugging Face. ML Kit language packs come from Google; the
  Japanese, Korean and Chinese packs, manga-ocr and LaMa (from Hugging Face) are fetched once, after the first-open
  setup lists them and you agree, over Wi-Fi unless you allow mobile data (Settings › Models & downloads). Every model file is checked against its SHA-256
  before it is used.
- **Update check.** Once a day the app asks GitHub for the latest release. Nothing about you is sent beyond what any
  web request carries (your IP address). Turn it off in **Settings › About & updates › Check automatically**. An
  update is verified against its SHA-256 before Android installs it.
- **Google's ML Kit.** Text recognition, language identification and translation are Google ML Kit libraries that
  run on the phone. Google's ML Kit sends Google diagnostics and usage data: the device model and Android version,
  the app's package name and version, a per-installation identifier, performance figures, error codes and, for
  translation and language identification, the languages involved. It never sends the text or the images. Google
  describes this at https://developers.google.com/ml-kit/android-data-disclosure.

Everything goes over HTTPS. The app sends nothing in clear text: an `http://` address is opened as `https://`, and a
site that only serves plain http does not load.

## Permissions

| Permission | Why |
|---|---|
| Network access | Sites, the engine you chose, downloads, the update check |
| Install packages | Installing an update you accepted |
| Foreground service, notifications, keep awake | Translating a Studio chapter in the background, with its progress notification; the progress of model and language-pack downloads (asked once, at the first open) |

The app does not ask for storage, camera, microphone, location or contacts.

## Children

Panelglass is not aimed at children and collects nothing about anyone.

## Changes

This page changes with the app. Each version of the app carries the policy it was released with, under
**Settings › About & updates › Privacy**. Changes are listed in the [changelog](CHANGELOG.md).

## Contact

Open an issue at https://github.com/smnexstudio/Panelglass/issues.
