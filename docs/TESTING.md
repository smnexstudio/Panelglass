# Testing

What the automated tests cover, how to run them, and the checks that still need a phone. The architecture under test
is in [ARCHITECTURE.md](ARCHITECTURE.md); the translation flow in [FLOW.md](FLOW.md).

Upstream baseline as of 2026-10-30: **667 unit tests** in 103 classes, all passing; **2 instrumented tests** (need a device, not run
in CI). Line coverage of the JVM tests, measured by JaCoCo: 56.9% of all code (branch 41.7%): 66.9% of the logic and
41.8% of the Compose UI; per module in the test report.

The upstream counts and coverage above are historical baseline figures, not validation of this branch. Custom AI adds endpoint, engine and save-validation tests; the branch test run must be checked separately.

## Contents

- [Running the tests](#running-the-tests)
- [Test report and CI](#test-report-and-ci)
- [How the tests are built](#how-the-tests-are-built)
- [Unit tests by module](#unit-tests-by-module)
- [Instrumented tests](#instrumented-tests)
- [Checks on a phone](#checks-on-a-phone)
- [Not covered](#not-covered)
- [Known flaky test](#known-flaky-test)
- [Adding a test](#adding-a-test)

## Running the tests

```bash
./gradlew testDebugUnitTest :core:model:test          # every unit test (what CI runs)
./gradlew :core:engine:testDebugUnitTest --tests "*RegistryAndQwenTest*"   # one class
./gradlew :core:data:connectedDebugAndroidTest         # key store, on a device
./gradlew :core:pipeline:connectedDebugAndroidTest     # whole pipeline, on a device
```

```bash
./gradlew jacocoUnitTestReport                         # coverage, after the tests, in its own invocation
python3 tools/test-report/report.py --root . --out build/test-report   # HTML report: expected vs actual per test
```

`:core:model` is a plain Kotlin module: its tests run under `test`, not `testDebugUnitTest`, so name it explicitly.
Reports land in `<module>/build/reports/tests/`. CI (`.github/workflows/ci.yml`) runs the unit tests and a debug
build for every pull request into `main` and every push to `main`; the instrumented tests need a device and are run
by hand.

If the build fails with "jlink executable … does not exist", see [CONTRIBUTING.md › Building](../CONTRIBUTING.md#building).

## Test report and CI

- **Coverage**: Gradle's own JaCoCo plugin (no added dependency, root `build.gradle.kts`). Every JVM test task runs
  with the agent; `jacocoUnitTestReport` in each module writes XML and CSV for that module's classes, crediting any
  module's tests that ran them. It reads other modules' test runs, so run it after the tests in its own invocation.
  Coverage is reported, never a gate: a line run is not a behaviour checked.
- **Report** (`tools/test-report/report.py`, Python standard library only): reads the JUnit XML results, the JaCoCo
  CSVs and the test sources. For every test it shows **Expected** (the test's KDoc, else its name read as a sentence),
  **Actual** (every assertion held, or the failed assertion's own expected and actual values and message) and a
  **PASS / FAIL / SKIP** verdict, with per-module counts and coverage, a filter and a search. Coverage is given
  three ways: all code, **logic** and **Compose UI** (the classes of files that declare `@Composable` functions; view
  models and everything else are logic), so well-tested logic is not hidden behind untested UI. It writes
  `test-report.html`, `summary.md` (for a pull request) and `results.json`; exit code 1 when a test failed or none ran.
- **CI** (`.github/workflows/ci.yml`): every pull request into `main` and every push to `main` runs all JVM tests
  (`--continue`, so every module reports), coverage, the report, then the debug build. The report is uploaded as the
  `test-report` artifact (30 days), the raw JUnit and JaCoCo XML as `test-results` (30 days), and its summary written
  to the run's summary page. On a pull request a separate
  `report` job, the only one allowed to write to the pull request and never running its code, posts the verdict, the
  per-module table and every failed test's expected and actual as **one comment, edited on each push** (marker
  `<!-- panelglass-test-report -->`), linking to the HTML report. GitHub cannot attach a file to a comment, so the HTML
  is the linked artifact (a zip; open `test-report.html`). A fork's pull request has a read-only token: no comment,
  the verdict stays in the run's summary. Instrumented tests are not run in CI.

## How the tests are built

- **JUnit 5**, with Robolectric tests (anything that needs a `Context`, bitmaps or Android classes) run through the
  JUnit vintage engine.
- **Providers are never called live.** Cloud engines are tested against **MockWebServer**: the request each one sends
  (model, key header, JSON options) and how every status maps to an `EngineFailure`.
- **LiteRT-LM is never loaded on the JVM.** `LocalLlmEngine.runnerFactory` is swapped for a scripted `LlmRunner`
  (fixed replies, prompts recorded, closes counted). `GatedRunner` suspends inside a generation so a test can act
  while the model is busy.
- **Storage fakes** (`core/engine/src/test/…/Fakes.kt`): `MemoryDataStore` (an in-memory DataStore),
  `testSettings()` (a real `SettingsRepository` on it), `testKeyStore()` (a `SecureKeyStore` with reversible
  "encryption", since the JVM has no AndroidKeyStore), `testSupport()` (OkHttp + JSON for the HTTP engines).
- **Device RAM** is set with Robolectric's `ShadowActivityManager.setMemoryInfo` to test RAM-dependent choices.
- **No live network.** HTTP goes to MockWebServer, or an OkHttp interceptor that answers (or fails) each request
  (`AppUpdatesTest` routes GitHub's hosts to a local server; `OfflineEnginesTest` fails every request and checks none
  was tried).
- **Time through seams, not sleeps**: `AppUpdates(now)`, `PatchCache(now)`, `BlockListRepository.now`,
  `TranslationPipeline.stageTimeoutMs`, `LocalEngines.releaseGraceMs`, and a virtual-time main dispatcher for view
  models (`ReviewSavingTest`). A wait is on a condition with a timeout, never a fixed delay.
- **Preferences**: tests that write more than once use an in-memory DataStore (`MemoryDataStore`, one per module's
  tests): a file-backed one cannot replace its file on Windows.
- **View models** run on `Dispatchers.setMain(UnconfinedTestDispatcher())` with the real repositories on Room in
  memory; `onCleared` is reached through a `ViewModelStore`. A view model built by hand is kept in `TestViewModels`
  (one per feature module's tests) and cleared before the database closes: a scope left running queries a closed
  database and fails a later test.
- **Compose screens** run on Robolectric with their real view model (`SettingsRig`, `ExportRig`, or the repositories
  directly), and a click is the node's own semantics action (a sheet or dialog still animating would miss an injected
  touch). Room answers on its own threads, so a check waits on a condition with real time between tries
  (`eventually`); the database is closed by an outer rule (`RuleChain`), after the compose rule has disposed the
  screen.
- **Pixels** are synthetic: bubbles, hatched art, grey boxes and text drawn onto bitmaps in the test.

## Unit tests by module

### core:model (44)

| Class | Tests | Covers |
|---|---|---|
| `BubbleFontTest` | 4 | Saved font names round-trip; retired fonts map to their successor; unknown names are null; each names its Studio catalogue id (`cat:…`), what a choice saved before the catalogue reads back as |
| `ComicInfoTest` | 10 | ComicInfo.xml round-trip; blank fields left out; escaping of `& < > " '` and control characters; a DOCTYPE entity (external or internal) is never resolved, character references are; CDATA, self-closing and nested elements; garbage gives an empty result; the first complete element of each field wins (attributes, `</Title >`, self-closing left to a later one); an unclosed comment or CDATA; ten crafted 256 KB inputs (unclosed tags, comments, CDATA, DOCTYPEs) each parsed in linear time, under 2 s |
| `StageInvalidationTest` | 2 | A reshape or a brush stroke clears Cleaned and nothing else; editing text or style clears nothing |
| `NaturalSortTest` | 6 | Page order by file name: numbers by value (`2` before `10`, `p9` before `p10`), zero padding, case ignored, several number runs, runs longer than a Long |
| `SiteMatchTest` | 6 | Which saved site owns a URL: subdomains, segment-boundary prefixes, several sites on one host; a saved series scopes its own glossary and notes (`seriesKeyFor`) |
| `TranslationNotesTest` | 5 | What the AI engines are told: characters with pronouns first, then series and Settings notes; a term without a pronoun is no character; each note capped; a glossary term is trimmed and cut to 60 characters |
| `PatchTest` | 2 | A rendered patch is shown from its pixels and encoded to WEBP only when its bytes are first read, once; a cached patch has bytes only |
| `ReadStateTest` | 3 | Only the read state marks a region unread or unreadable, never its text; a Studio region saved before the state existed is upgraded from its filler text; a region with a state round-trips unchanged |
| `WebUrlTest` | 6 | https only: https kept, http upgraded, a bare host gets `https://`, whitespace trimmed; `javascript:`/`file:`/`content:`/`intent:`/`data:` and non-URLs are null |
| `CustomEndpointTest` | 18 | The "Custom AI" server address: a base is kept as written; whitespace trimmed; a pasted `…/chat/completions` (with or without a trailing slash) is reduced to its base; any version path is preserved and `/v1` is never added; blank, scheme-less, host-less, spaced, query, fragment, other-scheme and credential-bearing addresses are null; plain http is refused unless the test switch allows it |

### core:data (80)

| Class | Tests | Covers |
|---|---|---|
| `Migration3To4Test` | 2 | A version-3 database opened by the app: sites, history, glossary and SFX cache survive; the Studio tables exist; `url_etag` and `ocr_cache` are dropped |
| `Migration4To5Test` | 2 | A version-4 database opened by the app: glossary entries survive without a pronoun and take one; series notes are stored, read and removed per series |
| `RetiredSfxModeTest` | 3 | Studio effects saved as Gloss or Overlay (removed) open as Replace with their text and transform kept; Overlay, never written, reads as Replace; Keep and Replace read as saved |
| `StudioDaoTest` | 14 | Studio library (Robolectric, in-memory Room): an import numbers its pages and counts them; appended pages and new chapters go last; reviewed progress; reordering pages and chapters sticks; moving pages renumbers both chapters and leaves the files; deleting pages, a chapter or a manga cascades to rows and files; a chapter moves to another manga; the orphan sweep spares saved and in-flight pages; JSON columns round-trip; paths outside the Studio root are refused |
| `PatchCacheTest` | 5 | Round-trip of patch geometry and bytes; LRU eviction over budget (on a stepped clock, no sleeps); a corrupt file is dropped, not thrown; an out-of-range length is refused before allocating and the file deleted; the key includes everything that changes rendering, the reader's font too |
| `ReleaseInfoTest` | 11 | In-app updates: GitHub's latest-release reply is read; drafts, pre-releases and a release without its `.sha256` are not offered; an APK URL that leaves this repository's releases once resolved (`..`, encoded dots, `%2F`, a query, a fragment, a user or a port) is refused, as is one outside this repository's https releases is refused; versions compare as numbers; the checksum line is matched by file name; a tag with a suffix (`v0.3.0-test`) is not a release; installed, older and interrupted downloads are spent, a newer APK is kept; missing or odd optional fields are read or refused without throwing; only a release-shaped reply counts as an answer; nothing is newer than an installed version that cannot be read |
| `AppUpdatesTest` | 20 | The update flow against a local server standing in for GitHub (no internet): a newer release is offered and the check remembered, with GitHub's media type and nothing about the user; the same, older, pre-release or asset-less release is up to date; an unreadable installed version is never offered an update; HTTP 403, 404, 429, 500, 502, 503, an HTML page, an empty or cut reply, no connection, a refused connection and a server that never answers are a failed check, never "up to date", and not remembered; repeated checks end with one answer; at start nothing happens in a debug build, with the check turned off or within a day, a failure shows nothing and is retried next start, only a newer release is shown; spent APKs and `.part` files are deleted at start; a verified download is ready for the installer, a checksum mismatch or a missing checksum fails with nothing handed over, an earlier finished download is reused; Cancel and a check during the download remove the system download; Later is ignored while downloading |
| `SettingsRepositoryTest` | 12 | Settings as stored across releases: defaults on a fresh install; a removed engine (Qwen 0.5B, Gemma 3) reads as the default; removed sound-effect modes read as Replace; a retired Gemini model resolves to the current default, blank clears; a font saved before the catalogue reads as its catalogue id, and Auto chosen afterwards sticks; unknown values fall back; notes trimmed and capped; update settings, the automatic-packs flag, per-model request profiles and favourite fonts round-trip |
| `SystemDownloadsQueueTest` | 5 | DownloadManager bookkeeping (Robolectric's): a download is queued once and remembered across restarts; a stale half file is cleared when one starts; cancel removes and forgets it; following a download never started fails; one the system lost is reported and forgotten, never awaited forever |
| `UnmigratedVersionsTest` | 2 | The database as the app opens it (`DataModule.db`): a version with no migration (2) and a newer one (an older APK over a newer build) are rebuilt empty at version 5 and usable, not a crashed launch |
| `SystemDownloadsIntegrityTest` | 4 | A download matching its SHA-256 is moved into place and remembered; a mismatch is deleted, never placed; a file already on disk is checked once; a file changed after its check is checked again |

### core:engine (106)

| Class | Tests | Covers |
|---|---|---|
| `LocalReplyTest` | 6 | Tagged and unfinished reasoning excluded; numbered final replies retained; unstructured and wrong-item replies rejected |
| `RegistryAndQwenTest` | 28 | **Engine selection:** the default is Google Translate; only Gemini, Custom AI, Google Translate and the on-device models (including the user's own) are offered; a planned engine never resolves; a keyed engine without its key is `MissingKey`, never a fallback; removing a key keeps the selection. **On-device prompts:** numbered-line payload with SFX marked; token-budget chunking; lenient line parser that drops echoes and stray numbers; streamed reply cut when complete; partial replies keep what parsed and retry only the rest; byte-level stand-ins repaired; system text and payload as separate turns. **Model lifecycle:** a missing model is a typed failure; loading one model releases the other; Gemma 4 only on 8 GB phones; *Qwen runs on* reloads Qwen on the other backend at its next call; Automatic picks the CPU under 6 GB; a model busy when the UI is hidden is released after its last call; coming back first keeps it |
| `ProviderEngineTest` | 9 | Against MockWebServer: 401 → `MissingKey`; a missing key never reaches the network; Gemini steps down past a request option a model refuses and remembers it; display names resolve to ids, Gemma models get no Gemini-only options; the model list keeps text models, Gemini first; OpenAI / OpenRouter send the user's model and key |
| `LlmContractTest` | 15 | The cloud JSON contract: strict and tolerant parsing (prose, code fences), length mismatch and garbage are `Malformed`; HTTP status mapping, Gemini "high demand" is `Overloaded` with the provider's message; the retry policy is bounded and capped; glossary and montage mapping in the prompt; the reader's notes reach the cloud, on-device and per-item prompts and survive `withItems`; an on-device reply that repeats the source before its translation is cut to the translation, an ordinary colon is kept; a line holding another writing system than the target's (もちろん, गहराई in English) is retried, then its foreign words are cut; the reply's output-token budget grows with the items (300 + 120 each) up to 8,192 |
| `PromptPartsTest` | 2 | Glossary terms and the last two preceding lines are rendered once, the same way in the cloud and on-device prompts |
| `CustomOpenAiEngineTest` | 12 | The "Custom AI" engine against MockWebServer: the user's model and key reach the configured server at `<base>/chat/completions`; a pasted full address is not appended twice; the path the user wrote is the path called; the request is text only (no image, no base64); a missing key, a missing model and a missing address are typed failures that never reach the network; a plain http address is refused in production; a server that refuses `temperature` is asked once more without it, and twice-refused is a typed failure; 401 → `MissingKey` |
| `HfCatalogTest` | 8 | The Hugging Face catalogue against MockWebServer (trimmed real fixtures): the search returns the author's repositories with their download counts; a gated repository is marked; a listing keeps only `.litertlm` files that have a 64-hex LFS hash (plain files, directories and unhashed files are dropped); the download address is pinned to the commit and URL-escaped; only `https://huggingface.co/` addresses are downloadable; a repository id is `owner/name` and nothing else; a bad id is refused before any request |
| `UserModelTest` | 7 | A user's own model: the context budget is read from the file name (`ekv4096` → 4096, absent → 2048) and clamped to 512..32768; the file name is built from the id and never contains a path; hub and imported ids differ; the model survives a JSON round trip (an unknown field is ignored); it runs on the `LOCAL_USER` engine; a truncated file is refused |
| `UserModelLibraryTest` | 7 | The model library (Robolectric): the list is saved and read back; an empty list is saved as none; corrupt JSON reads as an empty library; the active model is set and cleared; an active model that is gone reads as no model; the same model twice is refused; only a Hugging Face address is ever downloaded |
| `OfflineModelEngineTest` | 5 | The "Offline model" engine (Robolectric): without an active model, and with an active model whose file is not there, the failure is `ModelMissing`; a model whose file is really on the phone translates through its own engine with the on-device prompt; it is text only; the same model keeps the same engine |
| `GoogleTranslateEngineTest` | 9 | ML Kit translation behind its seam: every item translated, pack downloaded once; a pack the user has not downloaded is a typed `PackMissing` and nothing is fetched; glossary terms before and after; names learned in one request apply to the next; Japanese prepared first, Korean passed through; an echoed sound word romanised for Latin targets; a failed pack download is a typed `Unavailable` |
| `JaPrepTest` | 7 | Japanese preparation for ML Kit: names found by honorific, whole-bubble and repetition (a loanword seen twice is not a name); names and honorifics swapped; casual negatives standardised; romaji for names and sound words |
| `PageTranslatorTest` | 5 | *Translate page*: language detection falls back to the page language and rejects romanised text; same language returned unchanged; order kept, a failed text left as it was; all failing is an error; a language pair prepared once |
| `BatchingTranslatorTest` | 6 | A page of more than one call's items (60) is translated whole; Gemini reads unread regions from their crops and returns what it read (its request capped at the items' output budget); an on-device model gets one image per call; two series whose notes have the same hash never share a call; a failure surfaces typed instead of switching provider; a cancelled caller cancels its generation |
| `SfxDictionaryTest` | 4 | The bundled SFX tables: hiragana finds the katakana entry; another ッ or ー still matches (ガツーン → ガツン); other words miss; both tables parse with a rendering for every entry |
| `LanguagePackStoreTest` | 6 | Google Translate packs: every app language once (Chinese variants share a pack); only Japanese, Korean and Chinese download automatically; any other pack is missing until the user downloads it, and asking never downloads; English and packs already present are never fetched; a failure is reported for that language only; delete removes one pack, never English |
| `HttpFailureWireTest` | 8 | Cloud failures through Retrofit and OkHttp, not the status mapper alone: a server that never answers and a dropped connection are `Network`; 429 carries the server's Retry-After, a quota 429 is `QuotaExceeded`; 500/502/503/504 are `Overloaded` with the provider's message; a 200 that is no JSON (a captive portal) is `Malformed`, never an untyped exception; an overloaded provider is retried on the same engine and model, retries are bounded, a rejected key is never retried |
| `OfflineEnginesTest` | 4 | Airplane mode with every HTTP request failing: Google Translate (packs on the phone) and both on-device models warm up and translate without trying a single request; the cloud engine fails as its own `Network` failure, no fallback; its warm-up offline is silent |
| `ModelPinningTest` | 3 | Every on-device model's URL is https and pinned to a 40-hex commit, with a 64-hex SHA-256 and sane sizes; files, hashes and engines are distinct; a retired model's file is no current model's |

### core:ocr (45)

| Class | Tests | Covers |
|---|---|---|
| `HighContrastTest` | 3 | The second OCR reading: dark lettering stays black on white, light lettering on dark art is inverted, a flat crop has nothing to separate |
| `MissedTextTest` | 4 | The PP-OCRv6 second pass: a confident probability blob becomes a box grown as PaddleOCR unclips, a faint one is not text; nearby characters (타 + 닥) merge into one block; only boxes outside the comic detector's, and not tiny art print, are kept |
| `UnsureFreeTextTest` | 2 | manga-ocr's unsure readings of free-text boxes (speed lines, small print, a watermark: 0.62–0.73) are not text; real free text (≥ 0.87), any balloon and an empty reading are kept |
| `RegionClustererTest` | 9 | A region takes its lines' read state (unread, unreadable), and with read lines only their text; vertical columns merge right to left; centred horizontal lines merge top to bottom; vertical and horizontal never merge; font-size mismatch and distance keep bubbles apart; Japanese reads top-right first, English top-left; a Korean caption read twice is kept once |
| `RegionBuilderTest` | 7 | One region per text box, read right to left; text boxes claim lines before the balloon around them; lines outside every box fall back to clustering; the box class seeds the kind but keeps colours; free text is left for the pixel classifier; lines with no letter are not text; a region knows whether a detector boxed it (a lone full-page letter is dropped by the pipeline) |
| `RegionClassifierTest` | 6 | White bubble on busy art is `ENCLOSED`; grey box is `CAPTION`; text over art is `FREE`; big katakana over art is `SFX`; rotated free text is `IN_SCENE`; white lettering on a black burst reads light on dark |
| `CropGeometryTest` | 4 | Crop scaling (small crops grow up to 3×, large ones shrink to the cap); rotated-pass rows map back to their column, both directions; corners round-trip |
| `MangaOcrRepeatTest` | 3 | A looped sentence collapses to one; a partial repeat cut by the token cap is dropped only when truncated; short real repeats are kept |
| `UprightTiltTest` | 3 | ML Kit's angle as a tilt from upright: a vertical column (±90°) and upside-down text are upright; a real tilt is kept |
| `PanelCutterTest` | 2 | Synthetic panels detected and regions ordered by panel, right to left for manga |
| `ModelPinningTest` | 2 | manga-ocr's and LaMa's download bases are pinned to a commit; LaMa's hash and size floor are sane |

### core:render (62)

| Class | Tests | Covers |
|---|---|---|
| `FontCatalogueTest` | 5 | The bundled font catalogue as shipped: every file is there with its pinned SHA-256 and its licence beside it; every target language's dialogue and sound-effect default draws that language's script; roles and variation settings are read; search ignores case and accents and needs every word; every font the reader offered before the catalogue is in it |
| `FontNameTableTest` | 4 | A font file's kind from its signature (TrueType, CFF, collection; a ZIP refused); family and style from the `name` table, the typographic names (16/17) winning; truncated or table-less files give nothing |
| `SfxRendererTest` | 4 | Sound effects on a native canvas: an effect starts centred on its box in the Settings mode (a tall one turned, never on its side); Keep draws nothing and Replace draws around the centre; moving and rotating follow the transform (the transform box turns with it); presets set the look and keep the place, and switching preset drops a double outline |
| `PageCleanerTest` | 7 | The Studio's clean layer on plain pixels: a flat balloon is filled with its own colour; pixels outside the bubble's polygon never change; dismissed bubbles and sound effects not set to Replace are kept; a clean stroke removes what it covers and nothing else; a restore stroke brings the original back; the original is never modified; with LaMa, a brush stroke and a replaced effect over art go to it (nothing outside the polygon changes) while a plain balloon keeps its exact flat fill |
| `PolygonFitTest` | 4 | Where text goes in a shape: a rectangle keeps almost all of itself; an ellipse gets the centred inscribed rectangle (1/√2 of its axes); a concave L stays out of its notch; degenerate shapes give nothing |
| `StudioRendererTest` | 9 | Studio lettering on a Robolectric native canvas: a transparent fill keeps every pixel outside the polygon; a 50% fill blends over the art; text stays inside the inner rectangle; dismissed and empty bubbles are not lettered; a fixed size is used as given; a bubble border is drawn along the shape in its colour, and none by default; round corners leave the corner itself and keep the edges; text in an oval balloon shaped as its box stays on the paper (nothing drawn in the box's corners); a filled bubble's text uses its shape as drawn |
| `BalloonFitTest` | 4 | A balloon's paper: the text box stays inside an oval, not its bounding box; a speck of leftover text at the centre does not stop it; the traced outline follows the oval with text still in it; dark art or a small white patch is no balloon |
| `BalloonShapeTest` | 3 | The reader's balloon text: every rectangle offered lies on the balloon's paper, wide and tall ones included; the outline follows the balloon, not its box; a balloon without paper or outside the crop keeps its box |
| `TextEraserTest` | 8 | Bubble glyphs filled with the background; a text box as large as the balloon still erases the ink; bright halo uses the halo fill; inpainting on a smooth gradient is accepted, a smear over busy art rejected; mask rotation recovered; light lettering in a thick dark outline loses the outline too (the mask reaches with the glyph size) |
| `LongestRunTest` | 5 | The unbreakable run the text fitter tests: an English word with its punctuation; hyphens break; ellipses and quotes do not join words; CJK breaks between any two characters |
| `PanelInkTest` | 3 | Ink on a mask: contrasting ink kept, otherwise black on a light mask and white on a dark one |
| `RegionRendererTest` | 6 | The reader's patches on synthetic pages: balloon text becomes a patch over the whole balloon, its bitmap the patch's size; outside the balloon the patch is transparent or the art's own colour, never painted; nothing is drawn for an empty translation, free text off or sound effects kept; overlay mode covers free text; an unreadable effect is erased to its background; an erase that changes nothing is no patch |

### feature:browser (49)

| Class | Tests | Covers |
|---|---|---|
| `BlockListTest` | 11 | Hosts and AdBlock formats parsed; exact hosts and their subdomains blocked, parents and unrelated hosts not; `@@` exceptions win; wildcard and context-scoped rules skipped; localhost ignored; cached form round-trips |
| `PageScanPlanTest` | 12 | The plan of a whole-page scan: a step is 70% of the viewport, the stops ascend and end at the bottom of the page, a page that fits is one stop, a very long page is capped, the patches that do not fit in memory go first when they are the farthest from the viewport, and the page key drops the fragment |
| `PatchAnchorTest` | 8 | A patch is anchored to the image under its centre; it follows a reader moving its page in its own layer and scroll anchoring; no key, no anchor; an image off screen leaves the patch in place; the anchor is the same place on the art at any zoom; a patch's CSS rect scales with the zoom; one bubble captured at two zooms is a duplicate |
| `SessionLanguagesTest` | 4 | The page's detected language applies on its host only; a site's saved source wins over it; the launch intent wins over both; ML Kit tags map to Japanese, Korean or Chinese (a chosen Chinese script is kept) |
| `SeriesMemoryTest` | 2 | Glossary edits (Robolectric, in-memory Room): nothing is saved before the series is known; terms are trimmed and saved to the current series, a term with no rendering is not, and the writes are handed over once |
| `ContentChangeTest` | 4 | Page turn without a scroll: another page is a change; our own patches and a fading control are not; all-patched cannot tell |
| `SameSiteTest` | 3 | Same-site test for pop-ups: subdomains share a site, public suffixes do not; case and trailing dot ignored |
| `NavigationPolicyTest` | 11 | The WebView's policy (Robolectric `WebView`): non-web schemes (`javascript:`, `intent:`, `market:`, `file:`, `data:`, `content:`, custom) never load or leave the app; only a tapped `mailto:`/`tel:`/`sms:` opens another app; an http link is followed as https, a redirect down to http is not upgraded; same-site https loads; a script-driven cross-site navigation is a blocked pop-up, a tapped one or a redirect is not; a blocked host never gets the main frame, even on a tap; sub-frames are left alone; only a tapped same-site https new window opens; blocked sub-requests get an empty body of the expected type; a new page resets the count |
| `BlockListRepositoryTest` | 6 | The runtime block lists (an interceptor answers each URL): AdGuard failing falls back to StevenBlack; a list too small to be real is refused; offline, the cached lists are loaded and kept; one source failing keeps its last copy in the merge; the legacy cache file seeds the ad list; a stale list is refreshed on load and replaced |

### feature:studio (187)

| Class | Tests | Covers |
|---|---|---|
| `CanvasTransformTest` | 10 | The review canvas's view: the fit shows the whole page centred; a touch maps to the same page pixel at 1×, 3× and 8× and after a pan; a pinch keeps the point under the fingers; zoom stays within 1–8×; panning never shows space beyond the page; double-tap on a bubble fits it, on the art toggles fit ↔ 2.5×; following a selection keeps the zoom and pans it into view, zooming out only for a bubble that cannot fit; a resized canvas (keyboard) keeps the centre and scale |
| `PageCanvasGestureTest` | 8 | The canvas's gestures on a Robolectric Compose tree: a tap selects the bubble under it and empty art clears it; double-tap zooms to a bubble, and on the art in and back out; two fingers zoom; a long press edits in place; in Add mode a drag draws a box in page pixels; one finger pans when zoomed |
| `PolygonHitTest` | 6 | Tap tests on bubble shapes: rectangle, concave L, ellipse; the smallest containing shape wins (a text box inside its balloon); a tap just outside picks within the slop; a degenerate polygon never contains |
| `ScrollbarTest` | 5 | The editor's scrollbar: none when everything fits; at the top for a long list; reaches the bottom at the end; moves with the scroll; a very long list still has a thumb to see |
| `StorageSpaceTest` | 2 | Imports and exports need their size plus a 64 MB reserve (an unknown free space is not refused); a full storage is recognised from `ENOSPC` through an exception's causes, other write errors are not |
| `ExportNamesTest` | 5 | Export names: reserved characters, trailing dots, device names and long names are made safe, any script kept; `Ch 012 - Title`, `Ch 012.5`, the title alone, or `Chapter <n>` by position; `001.png` widening past 999 pages, `001.jpg` for JPEG pages; archives named `Ch 012 - Title.cbz`; a clean export's name ends in ` (Clean)`, kept whole; ComicInfo from the manga and chapter (date split, the chapter's own language, right-to-left only for a right-to-left source) |
| `StudioExporterTest` | 16 | Export into plain directories (the MediaStore / SAF seam replaced): by default pages and ComicInfo.xml in Downloads `<Manga>/<Ch …>/`; a CBZ holds the pages and ComicInfo.xml; a second export replaces files and deletes page files beyond the new last page but keeps other files; the overwrite count per format; chapters in order, an unnumbered one named by position and lettered in its own language, several chapters named by the manga's folder; a picked folder serves its manga only and can be dropped for Downloads; a folder without permission fails before writing; not enough free space is refused before writing; a full storage while writing is told from another write error; an unreadable page stops it and leaves no half archive; the format and page quality are remembered; JPEG pages replace the PNG ones; each folder or archive is listed with its size on disk; the plan names the files, the pages, the size estimate, the free space, the files it will replace and the unreviewed pages; the run's start, end and time left; a clean export sits beside the translated one, each counting only its own earlier file, its ComicInfo without a language |
| `CoverCropTest` | 3 | The 2 : 3 cover window: a wide picture is cut across and slides sideways, a long strip is cut downwards (top first for a page), an exact 2 : 3 picture is kept whole, the bias is clamped |
| `LibraryOrderTest` | 2 | The Studio library's search matches the title or alternative title, ignoring case and spaces; newest first or title A–Z |
| `WidthClassTest` | 1 | The phone / tablet / laptop breakpoints (600 and 840 dp) every Studio layout switches on |
| `FolderChaptersTest` | 3 | A CBZ's folders as chapters: two chapter folders become two numbered chapters (title after the number kept); numbers from `Chapter 66`, `c012`, `第14話`, a bare `15`; one folder, loose pages, or loose pages beside folders stay one chapter |
| `PeopleTest` | 2 | Several authors or artists in one field: names split on commas and semicolons (Japanese 、 too), trimmed, a repeat dropped; one name or none stays as it is |
| `ColorMathTest` | 5 | The colour picker's maths: colours survive a trip through hue / saturation / value; primary hues land at 0, 120 and 240; alpha is kept; the hex field takes `RGB`, `RRGGBB` and (for fills) `AARRGGBB`, keeps the opacity when none is typed, and refuses anything else; codes are written back without alpha when opaque |
| `TileCacheTest` | 6 | Zoom tiles: the base is enough until the zoom needs more pixels, then the matching sample; only visible tiles are listed; edge tiles clipped to the page; the LRU stays under its byte budget and frees what it drops; 12 MB under 4 GB of RAM, 24 MB otherwise; a page change frees everything |
| `StudioTranslatorTest` | 12 | Chapter runs with a scripted detector and engine (Robolectric, in-memory Room): every page is read before any is translated, and each page gets the previous page's last lines; a run stopped by a failure resumes from the page missing a stage without reading or translating anything twice; a missing key stops before any work and never switches the engine; a timeout fails only its page; an engine that reads the crops fills in the source text, and a text engine reads such a page again; Translate again redoes every page but keeps dismissed bubbles and clears Reviewed; an edited source text is what gets translated; one bubble is translated again with the bubbles before it as context; a boxed bubble is read, translated and added last; a boxed bubble is kept when the engine cannot be used; every page is cleaned after every page is translated, and a second run has nothing to clean; cleaning a page uses its bubbles and strokes |
| `GridReorderTest` | 2 | Compose (Robolectric): a long press and drag over clickable tiles moves the item and drops once, without clicking; a tap still clicks |
| `CbzImporterTest` | 15 | CBZ through `CbzReader` and `PageStager`: natural page order; `__MACOSX`, hidden and non-image entries skipped; entry names never become paths (zip-slip); ComicInfo.xml read, an oversized one ignored; an oversized entry (streamed or declared) refuses the archive; a zip bomb in a skipped entry stops at the total cap; too many entries refused; an over-pixel page skipped; GIF / AVIF / animated WebP converted; a fake image skipped; not a zip refused; only `.cbz` names are taken; `readAtMost` (the API 31 stand-in for `readNBytes`) across short reads |
| `ImageHeaderTest` | 7 | Size and format from the header alone: PNG, JPEG past a large EXIF segment, the three WebP forms; animated WebP, GIF and AVIF are converted, still images copied; HEIC and junk are not images |
| `ImageImporterTest` | 8 | Twenty picked images keep the natural order and their bytes; a preview reorder sticks; over-cap, oversized and HEIC files are refused without leaving a file; converted formats have their own cap; an undecodable file is skipped; discarded pages are deleted; series / chapter / volume guessed from file names; PDF pages rendered at 2×, capped at 2000 px wide and by pixels |
| `StudioImporterTest` | 9 | The import orchestrator over content URIs (a fake documents provider): picked images staged in natural order with progress; an unreadable file skipped and the rest imported; a pick with no image refused and nothing left; too many pages refused before staging; a cancelled import leaves no page directory; a CBZ staged with its ComicInfo and title; a `.zip` refused by name; a CBZ without pages refused; discard deletes staged pages |
| `ReviewPanelsTest` | 22 | The review editor's panes in Compose, each hosted with its value in state: bold, italic and capitals switch on their own; Auto size toggles with 32 px; an outline offers its colour, none removes it; "Every bubble" applies at once and keeps applying; Auto text colour; the colour picker previews a typed hex code and Cancel restores, Use keeps it; a fill starts opaque white, No fill clears it; reshape presets and a border with its colour row; brush mode, size, stroke count and undo (none without strokes); Add as text or effect; a sound effect's Keep hides the replacement controls, bold and double outline toggle, a preset is recorded, Remember until remembered; the Bubble tab's hint, both text fields, re-translate, Not text, delete only after confirming, a dismissed bubble's Restore; Proofread numbers (none for a dismissed bubble), the effects-only filter and selection; the header's bubble stepper and tab switch |
| `ExportViewModelTest` | 10 | The export view model over the real exporter and plain directories: chapters with translated pages start ticked, or only the chapter the screen was opened for; select all / none / one; Next needs a ticked chapter, then what to export, format and folder, and Back steps one at a time; the plan naming `Ch 001/` (Images by default); an export writes the chosen format and remembers format and quality; a finished export is not shown on the next visit; Export again returns to the list; a folder that cannot be kept is refused and Downloads restores; a picked folder is used, named and kept for the manga; a clean export writes clean pages under `(Clean)` and is remembered |
| `ExportScreenTest` | 6 | The export screen in Compose end to end: an untranslated chapter is marked and Next disabled; the whole flow writes a ZIP of smaller pages and Done leaves; Back returns step by step to the list; a clean export writes text-free pages; the confirm step warns about a file it replaces; Select all ticks every chapter with pages |
| `ImportViewModelTest` | 11 | One import from pick to save over the real importer and Room: the picker is not opened again when the screen is rebuilt from its saved state; picked images become a new manga in the order shown, a new manga needing a title; pages reordered and removed before saving (a removed page's directory deleted), Settings' source language prefilled; a CBZ's ComicInfo names series, number and title and joins the manga of that title; an existing manga suggests the next chapter number; a folder per chapter becomes one chapter each; pages added to a chapter join it; a refused pick says why and stages nothing; an empty pick does nothing; leaving before saving deletes the staged pages; saving twice saves once |
| `FontsViewModelTest` | 6 | My fonts over the real font store (Comic Neue from `core:render`'s assets, native graphics): a picked font is added under its family name with its scripts; a second style joins its family; the same file again and a file that is no font are refused; an empty pick does nothing; rename, role and delete with its files (the file check is skipped on Windows, which cannot delete a font the process has loaded); a blank name is ignored |
| `ReviewSavingTest` | 5 | The review editor never loses typing (Room in memory, virtual time): a translation is saved when typing pauses (400 ms), not before; fast typing is one save and one undo step, with undo and redo; a page turn and leaving the screen save pending typing at once; an edit that changes nothing is no undo step |

### core:pipeline (27)

| Class | Tests | Covers |
|---|---|---|
| `TileDetectionTest` | 8 | Tall pages (over 2 widths) are tiled, short ones not; tiles cover a 800 × 20000 strip and overlap; the copies an overlap makes merge into one; the copy a tile saw whole wins over a cut one; a box cut in every tile is their union; different box kinds and separate bubbles stay apart; page edges are not cuts |
| `DetectionStageTest` | 7 | A free-text box the detector is sure of with nothing read in it becomes an unreadable line; read, unsure and balloon boxes do not; a reading nested in one that holds its text is dropped, neighbours and other text are kept; two columns of one balloon (a narrow one beside a wide one, the balloon smaller than the lettering) become one region read right to left; text far apart in a box spanning joined balloons is not merged |
| `SeriesResolverTest` | 2 | The patch-cache digest: empty with no glossary or notes; follows terms and notes, not their order |
| `TranslationPipelineTest` | 10 | The reader's pipeline on the JVM with a scripted detector and on-device model and the real detection stage, batching translator, renderer, patch cache and glossary: a page is detected, translated and rendered to in-page patches; the same pixels again come from the cache with no detection or engine call; Re-translate bypasses the cache; a notes or glossary change misses it; a hung engine fails its image with the watchdog; a broken engine is a typed failure; cancelling the caller cancels the generation; a snapshot too small is skipped before detection; nothing in the region of interest, or an area already translated, means no engine call |

### feature:settings (27)

| Class | Tests | Covers |
|---|---|---|
| `SettingsViewModelTest` | 8 | The engine choice (real registry, every HTTP request failing): a keyed engine without a key opens its sheet and keeps the selection; saving the key there completes it; a sheet closed without a key selects nothing; an engine needing no key is selected at once; removing a key keeps the selection; planned engines and models the phone cannot hold are not offered; the Try box without a key fails typed without a request, and translates with the selected on-device engine |
| `SettingsScreenTest` | 14 | The Settings screen in Compose over a real view model (`SettingsRig`: no network): the rows show the saved choices; a theme tapped is saved and marked; every button and switch has a label for TalkBack (`unlabelledControls`); the source language, Keep for sound effects and Qwen on the CPU are saved from their sheets; the default notes are shown, edited notes saved and their first line shown, Cancel keeps them, emptied notes stay empty; ad blocking and the daily update check are switches; the engine row opens the picker; arriving for a key opens the key dialog without switching; Clear empties history; a Studio manga is deleted only after its dialog |
| `EnginePickerTest` | 5 | `core:ui`'s picker in Compose: a keyed engine's disc without a key asks for the key, with one selects and closes; its name opens its sheet without selecting; an on-device engine is selected at once; planned and hidden engines are not listed |

### feature:library (16)

| Class | Tests | Covers |
|---|---|---|
| `LibraryScreenTest` | 11 | The Sites and History screens in Compose over Room: every control on the list has a label for TalkBack; with no sites the list says how to add one; a site added through the dialog is saved as https with no language override; Add is disabled until the address is a web address; opening a site asks for its languages and keeps a changed pair before the reader opens; Cancel opens nothing; pinning moves a site under Pinned; search filters by name or host; the site sheet (long press) saves a rename and deletes; History opens a visit, removes one and clears all |
| `LibraryViewModelTest` | 5 | Sites: an http address is saved as https and named from its host; `javascript:`, `file:`, `intent:` and non-URLs are never saved; a new site takes the ad-block default; opening with the Settings pair stores no override, another pair is kept; pin and delete |

### app (17)

| Class | Tests | Covers |
|---|---|---|
| `UpdateDialogTest` | 8 | The update dialog in Compose for every state: idle shows nothing; Available offers Update or Later; a manual check can be cancelled; up to date and a failed check offer no update or retry; a failed download offers Retry; a download offers only Cancel; a verified download offers Install or Later |
| `StartupTasksTest` | 4 | Every start: someone who never chose and has Qwen stays on Qwen; an explicit choice is never changed; without Qwen nothing is written; the first-start packs are fetched until done once, offline retried next start |
| `ManifestPolicyTest` | 5 | The committed manifest: only INTERNET, ACCESS_NETWORK_STATE and REQUEST_INSTALL_PACKAGES; no cleartext switch or network security config; no backup; only the launcher exported; the FileProvider unexported and sharing only `updates/` and the cache's `share/` |

### core:ui (7)

| Class | Tests | Covers |
|---|---|---|
| `ApiKeyValidationTest` | 7 | HTTPS save gating; invalid and cleartext endpoints rejected even with a saved key; key and model requirements; other providers unaffected |
| `AppLocaleTest` | 7 | The app language (Robolectric, API 31 and 34): locales normalised to the UI languages (`ja-JP`, `zh-TW`, `zh-Hant-CN`, `zh-CN`, Java's `in`, an unknown language → English); Android 12 opens in English on a French phone and keeps a choice across a restart (default locale too); an unknown stored tag reads as English; Android 13+ puts a first start on English, follows a per-app language set in the system and restores the saved one when the system forgets it; `set` goes through the system |

Its engine picker and card rows are tested from `feature:settings`; the pipeline is also covered end to end by an
instrumented test.

## Instrumented tests

Run with a device or emulator attached.

| Test | Command | Covers |
|---|---|---|
| `SecureKeyStoreInstrumentedTest` | `./gradlew :core:data:connectedDebugAndroidTest` | An API key round-trips across store instances and is encrypted at rest with the real AndroidKeyStore |
| `PipelineInstrumentedTest` | `./gradlew :core:pipeline:connectedDebugAndroidTest` | A rendered page (white bubble on hatched art, free text on grey) goes through the whole pipeline to fitted patches; `ENCLOSED` / `FREE` hold; the second run comes from the patch cache. ML Kit Latin, no network |

## Checks on a phone

Behaviour that depends on a real GPU, real sites or real memory pressure is checked by hand. Use a debug build
(`run-as` and the launch extras only work there) and `adb shell cmd package compile -m speed -f
com.smnexstudio.panelglass.debug` after installing.

| Check | How | Pass when |
|---|---|---|
| Screen translation, each engine | `am start -n com.smnexstudio.panelglass.debug/com.smnexstudio.panelglass.MainActivity --es url <chapter> --es engine <ENGINE_ID> --ez start true` (`--es src JA --es tgt HI` for a language sweep) | Bubbles are replaced in place; `adb logcat -s TranslationPipeline` shows a `timing` line per screen |
| Custom AI engine | Settings › Translation model › Custom AI: enter a key, a model and the server's address; save; pick the engine and translate. Repeat with a pasted `…/chat/completions` address | The bubbles are translated by that server; the address is kept as typed; a plain `http://` address is refused; no image is sent (the request body carries text only) |
| On-device backend | Settings › Models › *Qwen runs on* → GPU / CPU, then open the reader | `LiteRT-LM loaded on gpu` / `on cpu` in `adb logcat -s LocalLlmEngine` |
| Memory per engine and state | [MEMORY_USAGE.md › Reproducing](MEMORY_USAGE.md#reproducing) | Figures in line with that document; no process killed while translating |
| Model released in the background | Start translating, press Home mid-generation, poll `dumpsys meminfo` | `UI hidden: releasing the model after its last call` ~5 s after the last `generate` line; PSS back to ~0.5 GB |
| Page turns, rotation, slow pages | A paged reader (tap to turn), rotate, a slow image host | Old patches cleared and the new page translated; `ScreenSession` log lines |
| What a screen saw | Debug dump ([FLOW.md › Diagnostics](FLOW.md#diagnostics)) | `regions.txt` kinds, texts and fit sizes look right |
| ONNX Runtime options | `run-as … touch files/debug-ortbench`, open the reader, `adb logcat -s OrtBench` | Medians per option set (remove the file after) |
| On-device model quality | `run-as … touch files/debug-llm-compare`, start the app, read `files/debug-llm-compare.out` | Every installed model translates the fixed lines |
| UI | Every changed screen in all four themes and at least one right-to-left language (Arabic) | Nothing clipped or overlapping; rows aligned |
| Studio import | Studio tab › Import: a PDF, a CBZ with ComicInfo.xml, 20 images at once (then *Add more images* on the chapter), a long manhwa strip; also a plain `.zip` and a password-protected PDF | Pages in the right order with thumbnails; details pre-filled from ComicInfo.xml; `.zip` and the locked PDF refused with a message; Cancel mid-import leaves no chapter |
| Studio translate | A chapter's **Translate** with Google Translate (emulator) and Gemini / Qwen (phone only); Cancel mid-run, then Continue; kill the app mid-run (`adb shell am kill`) and Continue; a tall strip; Gemini without a key; a target language whose pack is missing | `studio GOOGLE: translate=…` per page in `adb logcat -s TranslationPipeline` (`detect tiled: tiles=…` for a strip); a tapped page lists every bubble's source and translation; Continue translates only the pages left; no key → **Add key** opens its sheet and the engine stays as it was; a missing pack → **Download**, then the run goes on |
| Studio review | Tap a translated page: select bubbles on the page and in Proofread, ◀ ▶ through them at 3×; double-tap a bubble; pinch to 8× on a dense page; edit a translation (Bubble tab, long-press in place, focus mode's card) with the on-screen keyboard up; Re-translate after correcting the source; Add bubble over a missed SFX; dismiss and restore a bubble; Reviewed, next page; leave and come back | The canvas follows the selection without re-zooming; text stays sharp when zoomed (tiles); the bubble being edited stays visible above the keyboard; edits and the reviewed state survive leaving the screen; the minimap appears above 2× |
| Studio clean and style | On a translated page: Final shows the lettering on the cleaned art; Clean and Original toggle; restyle a bubble (search the font list, bold, size, a preset and a custom colour by square, hue bar and hex code, outline and its colour, Apply to every bubble), watching the page change with each one; fill one at 50%; reshape one into a concave shape (the text follows while the corner is dragged) and into an ellipse; brush out a leftover mark, restore a stroke of art; undo and redo each; Run › Clean every page | The page stays in view under every tool (its options are in the pane below); Final opens on the cleaned art, never the original text; text fits inside each shape; cleaned art has no source text left; a reshape or stroke re-cleans the page after a moment ("Cleaning…"); undo brings back the exact previous state |
| Studio export | Export a chapter as Images, then the whole manga as CBZ and as ZIP from the Manga screen; export again; Change folder › Choose folder, export, then back to Downloads; revoke the picked folder in the Files app and export; import a large PDF and export with the storage nearly full (fill it with `adb shell dd`); export it again choosing **Clean** | No picker for Downloads: files land in `Download/Panelglass/<Manga>/` (`Ch 001 - Title/001.png…` + `ComicInfo.xml`, or `Ch 001 - Title.cbz`); "Export anyway?" for unreviewed pages, "Replace exported files?" the second time; a revoked folder says to choose it again; a full storage says how much is needed and free, before anything is written; Kavita / Komga read the CBZ with its title and number; the Clean export sits beside the translated one as `Ch 001 - Title (Clean)`, its bubbles and replaced effects empty and nothing lettered |
| Studio fonts | Text style › Font: search "bold", filter Sound effects, turn off "Fits English", star two fonts, long-press one; set a Japanese target and check the list; My fonts (Studio tab › Aa): add a `.ttf`, a Regular + Bold `.otf` pair, a `.zip` and the same `.ttf` again; rename one, set its role, use it on a bubble, delete it | Each row draws its name and a sample in its own face; stars put fonts first; a font without the target's script is hidden until the switch is off; the pair becomes one font, the zip is refused (not a font), the repeat says "already added"; the delete dialog says how many bubbles use it, and those bubbles then letter with the default ("Font removed · using …") |
| Studio sound effects | On a page with effects (Proofread › Effects only): Keep, Replace; Impact, Horror and Match original; drag the effect, turn it by the top handle, scale it by the corner; Stretch, Slant, Bend; Remember this effect, then translate another chapter with the same effect; Add › Sound effect over a missed one | Replace erases the original (re-cleaned after a moment) and Keep brings it back; the transform box follows the effect at any zoom; the remembered effect gets the same translation later |
| Studio LaMa | Settings › Models › Studio cleanup (LaMa): download (208 MB); brush out text over art and set an effect to Replace; time a page on the phone; on a phone under 4 GB the row reads "Needs 4 GB of RAM" | Text over art is filled from the picture, not smeared; the download survives leaving the app; `dumpsys meminfo` drops by the model's size after cleaning |
| Studio storage | Settings › Storage › Studio library: sizes per manga; Delete one | The dialog says what goes (chapters, pages, translations, cleaned pages, size) and that exported files stay; the manga and its files are gone after |
| Studio library | Reorder pages and chapters by long-press drag, move pages to another chapter and a chapter to another manga, delete each; kill the app mid-import (`adb shell am kill`) and reopen the Studio tab | Orders stick after leaving the screen; nothing left in `files/studio/pages` that no page uses |

Clear `cache/patches` (`adb shell run-as com.smnexstudio.panelglass.debug rm -rf cache/patches`) before a timing or memory
check: a cached page never calls the engine. The first page load after opening the reader can stay blank on some
sites; ⋮ › *Reload* loads it.

## Not covered

- **The real LiteRT-LM runtime, GPU and CPU**, and the real manga-ocr / detector sessions: only on a phone.
- **Live provider APIs** (Gemini): MockWebServer only; the emulator's HTTPS to providers is intercepted on the
  development machine, so real calls need a phone.
- **Compose UI**: Settings, Sites and History, Export and the review editor's panels are tested in Compose, as are the
  Studio's drag-to-reorder, the review-canvas gestures, the update dialog and the engine picker. The reader, the
  Studio's library, manga, chapter and import screens, the review screen around its panels, My fonts and the font
  browser are checked by hand.
- **The reader's view model and screen capture** (`ReaderViewModel`, `ScreenTranslate`, `PipelineScheduler`): they
  own a WebView and PixelCopy; testing them needs seams that do not exist yet. Their pure helpers are tested.
- **Studio home, manga and chapter view models**: their repositories are tested; the view models are not (import,
  export, fonts and review are).
- **Instrumented and end-to-end tests in CI**: none run there (no device); an offline start-to-export run on an
  emulator and a smoke test of the R8 release APK are still manual (`release.yml` tests the debug build).
- **`mt.js` / `pt.js`**: the page scripts have no JavaScript tests; their Kotlin sides (anchoring, change detection)
  are unit-tested.
- **CPU timing on a low-end phone**: not measured.

## Known flaky test

- `StudioImporterTest.discardDeletesStagedPages` fails now and then **on Windows only**: an antivirus scan can hold a
  PNG just written, and `StudioFiles.deletePageDir` (`runCatching { deleteRecursively() }`) leaves its directory. On
  Android nothing holds the file, and a directory left behind is removed by the orphan sweep. The assertion is kept.

## Adding a test

- Put it in the module of the code under test, next to its neighbours (`src/test/kotlin/…`); use Robolectric
  (`@RunWith(RobolectricTestRunner::class)`) only when an Android class is needed.
- Name tests as the behaviour in a sentence (`aModelBusyWhenTheUiIsHiddenIsReleasedAfterItsLastCall`).
- Provider code: MockWebServer, never a live API. On-device code: a scripted `LlmRunner`, never LiteRT-LM.
- Update the counts and tables here when you add a class.
