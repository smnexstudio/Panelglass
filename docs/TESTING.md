# Testing

What the automated tests cover, how to run them, and the checks that still need a phone. The architecture under test
is in [ARCHITECTURE.md](ARCHITECTURE.md); the translation flow in [FLOW.md](FLOW.md).

Counts as of 2026-10-02: **368 unit tests** in 59 classes, **2 instrumented tests**, all passing.

## Contents

- [Running the tests](#running-the-tests)
- [How the tests are built](#how-the-tests-are-built)
- [Unit tests by module](#unit-tests-by-module)
- [Instrumented tests](#instrumented-tests)
- [Checks on a phone](#checks-on-a-phone)
- [Not covered](#not-covered)
- [Adding a test](#adding-a-test)

## Running the tests

```bash
./gradlew testDebugUnitTest :core:model:test          # every unit test (what CI runs)
./gradlew :core:engine:testDebugUnitTest --tests "*RegistryAndQwenTest*"   # one class
./gradlew :core:data:connectedDebugAndroidTest         # key store, on a device
./gradlew :core:pipeline:connectedDebugAndroidTest     # whole pipeline, on a device
```

`:core:model` is a plain Kotlin module: its tests run under `test`, not `testDebugUnitTest`, so name it explicitly.
Reports land in `<module>/build/reports/tests/`. CI (`.github/workflows/ci.yml`) runs the unit tests and a debug
build for every pull request into `main` and every push to `main`; the instrumented tests need a device and are run
by hand.

If the build fails with "jlink executable … does not exist", see [CONTRIBUTING.md › Building](../CONTRIBUTING.md#building).

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
- **Pixels** are synthetic: bubbles, hatched art, grey boxes and text drawn onto bitmaps in the test.

## Unit tests by module

### core:model (29)

| Class | Tests | Covers |
|---|---|---|
| `BubbleFontTest` | 4 | Saved font names round-trip; retired fonts map to their successor; unknown names are null; each names its Studio catalogue id (`cat:…`), what a choice saved before the catalogue reads back as |
| `ComicInfoTest` | 10 | ComicInfo.xml round-trip; blank fields left out; escaping of `& < > " '` and control characters; a DOCTYPE entity (external or internal) is never resolved, character references are; CDATA, self-closing and nested elements; garbage gives an empty result; the first complete element of each field wins (attributes, `</Title >`, self-closing left to a later one); an unclosed comment or CDATA; ten crafted 256 KB inputs (unclosed tags, comments, CDATA, DOCTYPEs) each parsed in linear time, under 2 s |
| `StageInvalidationTest` | 2 | A reshape or a brush stroke clears Cleaned and nothing else; editing text or style clears nothing |
| `NaturalSortTest` | 6 | Page order by file name: numbers by value (`2` before `10`, `p9` before `p10`), zero padding, case ignored, several number runs, runs longer than a Long |
| `SiteMatchTest` | 5 | Which saved site owns a URL: subdomains, segment-boundary prefixes, several sites on one host |
| `WebUrlTest` | 6 | https only: https kept, http upgraded, a bare host gets `https://`, whitespace trimmed; `javascript:`/`file:`/`content:`/`intent:`/`data:` and non-URLs are null |

### core:data (25)

| Class | Tests | Covers |
|---|---|---|
| `Migration3To4Test` | 2 | A version-3 database opened by the app: sites, history, glossary and SFX cache survive; the Studio tables exist; `url_etag` and `ocr_cache` are dropped |
| `StudioDaoTest` | 14 | Studio library (Robolectric, in-memory Room): an import numbers its pages and counts them; appended pages and new chapters go last; reviewed progress; reordering pages and chapters sticks; moving pages renumbers both chapters and leaves the files; deleting pages, a chapter or a manga cascades to rows and files; a chapter moves to another manga; the orphan sweep spares saved and in-flight pages; JSON columns round-trip; paths outside the Studio root are refused |
| `PatchCacheTest` | 5 | Round-trip of patch geometry and bytes; LRU eviction over budget; a corrupt file is dropped, not thrown; an out-of-range length is refused before allocating and the file deleted; the key includes everything that changes rendering, the reader's font too |
| `SystemDownloadsIntegrityTest` | 4 | A download matching its SHA-256 is moved into place and remembered; a mismatch is deleted, never placed; a file already on disk is checked once; a file changed after its check is checked again |

### core:engine (72)

| Class | Tests | Covers |
|---|---|---|
| `RegistryAndQwenTest` | 28 | **Engine selection:** the default is Google Translate; only Gemini, Google Translate and the on-device models are offered; a planned engine never resolves; a keyed engine without its key is `MissingKey`, never a fallback; removing a key keeps the selection. **On-device prompts:** numbered-line payload with SFX marked; token-budget chunking; lenient line parser that drops echoes and stray numbers; streamed reply cut when complete; partial replies keep what parsed and retry only the rest; byte-level stand-ins repaired; system text and payload as separate turns. **Model lifecycle:** a missing model is a typed failure; loading one model releases the other; Gemma 4 only on 8 GB phones; *Qwen runs on* reloads Qwen on the other backend at its next call; Automatic picks the CPU under 6 GB; a model busy when the UI is hidden is released after its last call; coming back first keeps it |
| `ProviderEngineTest` | 9 | Against MockWebServer: 401 → `MissingKey`; a missing key never reaches the network; Gemini steps down past a request option a model refuses and remembers it; display names resolve to ids, Gemma models get no Gemini-only options; the model list keeps text models, Gemini first; OpenAI / OpenRouter send the user's model and key |
| `LlmContractTest` | 8 | The cloud JSON contract: strict and tolerant parsing (prose, code fences), length mismatch and garbage are `Malformed`; HTTP status mapping, Gemini "high demand" is `Overloaded` with the provider's message; the retry policy is bounded and capped; glossary and montage mapping in the prompt |
| `GoogleTranslateEngineTest` | 9 | ML Kit translation behind its seam: every item translated, pack downloaded once; a pack the user has not downloaded is a typed `PackMissing` and nothing is fetched; glossary terms before and after; names learned in one request apply to the next; Japanese prepared first, Korean passed through; an echoed sound word romanised for Latin targets; a failed pack download is a typed `Unavailable` |
| `JaPrepTest` | 7 | Japanese preparation for ML Kit: names found by honorific, whole-bubble and repetition (a loanword seen twice is not a name); names and honorifics swapped; casual negatives standardised; romaji for names and sound words |
| `PageTranslatorTest` | 5 | *Translate page*: language detection falls back to the page language and rejects romanised text; same language returned unchanged; order kept, a failed text left as it was; all failing is an error; a language pair prepared once |
| `BatchingTranslatorTest` | 4 | Gemini reads unread regions from their crops and returns what it read; an on-device model gets one image per call; a failure surfaces typed instead of switching provider; a cancelled caller cancels its generation |
| `LanguagePackStoreTest` | 6 | Google Translate packs: every app language once (Chinese variants share a pack); only Japanese, Korean and Chinese download automatically; any other pack is missing until the user downloads it, and asking never downloads; English and packs already present are never fetched; a failure is reported for that language only; delete removes one pack, never English |

### core:ocr (32)

| Class | Tests | Covers |
|---|---|---|
| `RegionClustererTest` | 8 | Vertical columns merge right to left; centred horizontal lines merge top to bottom; vertical and horizontal never merge; font-size mismatch and distance keep bubbles apart; Japanese reads top-right first, English top-left; a Korean caption read twice is kept once |
| `RegionBuilderTest` | 6 | One region per text box, read right to left; text boxes claim lines before the balloon around them; lines outside every box fall back to clustering; the box class seeds the kind but keeps colours; free text is left for the pixel classifier; lines with no letter are not text |
| `RegionClassifierTest` | 6 | White bubble on busy art is `ENCLOSED`; grey box is `CAPTION`; text over art is `FREE`; big katakana over art is `SFX`; rotated free text is `IN_SCENE`; white lettering on a black burst reads light on dark |
| `CropGeometryTest` | 4 | Crop scaling (small crops grow up to 3×, large ones shrink to the cap); rotated-pass rows map back to their column, both directions; corners round-trip |
| `MangaOcrRepeatTest` | 3 | A looped sentence collapses to one; a partial repeat cut by the token cap is dropped only when truncated; short real repeats are kept |
| `UprightTiltTest` | 3 | ML Kit's angle as a tilt from upright: a vertical column (±90°) and upside-down text are upright; a real tilt is kept |
| `PanelCutterTest` | 2 | Synthetic panels detected and regions ordered by panel, right to left for manga |

### core:render (43)

| Class | Tests | Covers |
|---|---|---|
| `FontCatalogueTest` | 5 | The bundled font catalogue as shipped: every file is there with its pinned SHA-256 and its licence beside it; every target language's dialogue and sound-effect default draws that language's script; roles and variation settings are read; search ignores case and accents and needs every word; every font the reader offered before the catalogue is in it |
| `FontNameTableTest` | 4 | A font file's kind from its signature (TrueType, CFF, collection; a ZIP refused); family and style from the `name` table, the typographic names (16/17) winning; truncated or table-less files give nothing |
| `SfxRendererTest` | 5 | Sound effects on a native canvas: an effect starts centred on its box in the Settings mode (a tall one turned, never on its side); Keep draws nothing and Overlay draws around the centre; moving and rotating follow the transform (the transform box turns with it); Gloss draws below the effect; presets set the look and keep the place, and switching preset drops a double outline |
| `PageCleanerTest` | 7 | The Studio's clean layer on plain pixels: a flat balloon is filled with its own colour; pixels outside the bubble's polygon never change; dismissed bubbles and sound effects not set to Replace are kept; a clean stroke removes what it covers and nothing else; a restore stroke brings the original back; the original is never modified; with LaMa, a brush stroke and a replaced effect over art go to it (nothing outside the polygon changes) while a plain balloon keeps its exact flat fill |
| `PolygonFitTest` | 4 | Where text goes in a shape: a rectangle keeps almost all of itself; an ellipse gets the centred inscribed rectangle (1/√2 of its axes); a concave L stays out of its notch; degenerate shapes give nothing |
| `StudioRendererTest` | 9 | Studio lettering on a Robolectric native canvas: a transparent fill keeps every pixel outside the polygon; a 50% fill blends over the art; text stays inside the inner rectangle; dismissed and empty bubbles are not lettered; a fixed size is used as given; a bubble border is drawn along the shape in its colour, and none by default; round corners leave the corner itself and keep the edges; text in an oval balloon shaped as its box stays on the paper (nothing drawn in the box's corners); a filled bubble's text uses its shape as drawn |
| `BalloonFitTest` | 4 | A balloon's paper: the text box stays inside an oval, not its bounding box; a speck of leftover text at the centre does not stop it; the traced outline follows the oval with text still in it; dark art or a small white patch is no balloon |
| `TextEraserTest` | 6 | Bubble glyphs filled with the background; a text box as large as the balloon still erases the ink; bright halo uses the halo fill; inpainting on a smooth gradient is accepted, a smear over busy art rejected; mask rotation recovered |
| `LongestRunTest` | 5 | The unbreakable run the text fitter tests: an English word with its punctuation; hyphens break; ellipses and quotes do not join words; CJK breaks between any two characters |
| `PanelInkTest` | 3 | Ink on a mask: contrasting ink kept, otherwise black on a light mask and white on a dark one |

### feature:browser (26)

| Class | Tests | Covers |
|---|---|---|
| `BlockListTest` | 11 | Hosts and AdBlock formats parsed; exact hosts and their subdomains blocked, parents and unrelated hosts not; `@@` exceptions win; wildcard and context-scoped rules skipped; localhost ignored; cached form round-trips |
| `PatchAnchorTest` | 8 | A patch is anchored to the image under its centre; it follows a reader moving its page in its own layer and scroll anchoring; no key, no anchor; an image off screen leaves the patch in place; the anchor is the same place on the art at any zoom; a patch's CSS rect scales with the zoom; one bubble captured at two zooms is a duplicate |
| `ContentChangeTest` | 4 | Page turn without a scroll: another page is a change; our own patches and a fading control are not; all-patched cannot tell |
| `SameSiteTest` | 3 | Same-site test for pop-ups: subdomains share a site, public suffixes do not; case and trailing dot ignored |

### feature:studio (100)

| Class | Tests | Covers |
|---|---|---|
| `CanvasTransformTest` | 10 | The review canvas's view: the fit shows the whole page centred; a touch maps to the same page pixel at 1×, 3× and 8× and after a pan; a pinch keeps the point under the fingers; zoom stays within 1–8×; panning never shows space beyond the page; double-tap on a bubble fits it, on the art toggles fit ↔ 2.5×; following a selection keeps the zoom and pans it into view, zooming out only for a bubble that cannot fit; a resized canvas (keyboard) keeps the centre and scale |
| `PageCanvasGestureTest` | 8 | The canvas's gestures on a Robolectric Compose tree: a tap selects the bubble under it and empty art clears it; double-tap zooms to a bubble, and on the art in and back out; two fingers zoom; a long press edits in place; in Add mode a drag draws a box in page pixels; one finger pans when zoomed |
| `PolygonHitTest` | 6 | Tap tests on bubble shapes: rectangle, concave L, ellipse; the smallest containing shape wins (a text box inside its balloon); a tap just outside picks within the slop; a degenerate polygon never contains |
| `ScrollbarTest` | 5 | The editor's scrollbar: none when everything fits; at the top for a long list; reaches the bottom at the end; moves with the scroll; a very long list still has a thumb to see |
| `StorageSpaceTest` | 2 | Imports and exports need their size plus a 64 MB reserve (an unknown free space is not refused); a full storage is recognised from `ENOSPC` through an exception's causes, other write errors are not |
| `ExportNamesTest` | 4 | Export names: reserved characters, trailing dots, device names and long names are made safe, any script kept; `Ch 012 - Title`, `Ch 012.5`, the title alone, or `Chapter <n>` by position; `001.png` widening past 999 pages, `001.jpg` for JPEG pages; archives named `Ch 012 - Title.cbz`; ComicInfo from the manga and chapter (date split, the chapter's own language, right-to-left only for a right-to-left source) |
| `StudioExporterTest` | 15 | Export into plain directories (the MediaStore / SAF seam replaced): by default pages and ComicInfo.xml in Downloads `<Manga>/<Ch …>/`; a CBZ holds the pages and ComicInfo.xml; a second export replaces files and deletes page files beyond the new last page but keeps other files; the overwrite count per format; chapters in order, an unnumbered one named by position and lettered in its own language, several chapters named by the manga's folder; a picked folder serves its manga only and can be dropped for Downloads; a folder without permission fails before writing; not enough free space is refused before writing; a full storage while writing is told from another write error; an unreadable page stops it and leaves no half archive; the format and page quality are remembered; JPEG pages replace the PNG ones; each folder or archive is listed with its size on disk; the plan names the files, the pages, the size estimate, the free space, the files it will replace and the unreviewed pages; the run's start, end and time left |
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

### core:pipeline (8)

| Class | Tests | Covers |
|---|---|---|
| `TileDetectionTest` | 8 | Tall pages (over 2 widths) are tiled, short ones not; tiles cover a 800 × 20000 strip and overlap; the copies an overlap makes merge into one; the copy a tile saw whole wins over a cut one; a box cut in every tile is their union; different box kinds and separate bubbles stay apart; page edges are not cuts |

`core:ui`, `feature:library`, `feature:settings` and `:app` have no unit tests; the pipeline is also covered end to end
by an instrumented test.

## Instrumented tests

Run with a device or emulator attached.

| Test | Command | Covers |
|---|---|---|
| `SecureKeyStoreInstrumentedTest` | `./gradlew :core:data:connectedDebugAndroidTest` | An API key round-trips across store instances and is encrypted at rest with the real AndroidKeyStore |
| `PipelineInstrumentedTest` | `./gradlew :core:pipeline:connectedDebugAndroidTest` | A rendered page (white bubble on hatched art, free text on grey) goes through the whole pipeline to fitted patches; `ENCLOSED` / `FREE` hold; the second run comes from the patch cache. ML Kit Latin, no network |

## Checks on a phone

Behaviour that depends on a real GPU, real sites or real memory pressure is checked by hand. Use a debug build
(`run-as` and the launch extras only work there) and `adb shell cmd package compile -m speed -f
com.smnexstudio.panelglass` after installing.

| Check | How | Pass when |
|---|---|---|
| Screen translation, each engine | `am start -n com.smnexstudio.panelglass/.MainActivity --es url <chapter> --es engine <ENGINE_ID> --ez start true` (`--es src JA --es tgt HI` for a language sweep) | Bubbles are replaced in place; `adb logcat -s TranslationPipeline` shows a `timing` line per screen |
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
| Studio export | Export a chapter as Images, then the whole manga as CBZ and as ZIP from the Manga screen; export again; Change folder › Choose folder, export, then back to Downloads; revoke the picked folder in the Files app and export; import a large PDF and export with the storage nearly full (fill it with `adb shell dd`) | No picker for Downloads: files land in `Download/Panelglass/<Manga>/` (`Ch 001 - Title/001.png…` + `ComicInfo.xml`, or `Ch 001 - Title.cbz`); "Export anyway?" for unreviewed pages, "Replace exported files?" the second time; a revoked folder says to choose it again; a full storage says how much is needed and free, before anything is written; Kavita / Komga read the CBZ with its title and number |
| Studio fonts | Text style › Font: search "bold", filter Sound effects, turn off "Fits English", star two fonts, long-press one; set a Japanese target and check the list; My fonts (Studio tab › Aa): add a `.ttf`, a Regular + Bold `.otf` pair, a `.zip` and the same `.ttf` again; rename one, set its role, use it on a bubble, delete it | Each row draws its name and a sample in its own face; stars put fonts first; a font without the target's script is hidden until the switch is off; the pair becomes one font, the zip is refused (not a font), the repeat says "already added"; the delete dialog says how many bubbles use it, and those bubbles then letter with the default ("Font removed · using …") |
| Studio sound effects | On a page with effects (Proofread › Effects only): Keep, Gloss, Overlay, Replace; Impact, Horror and Match original; drag the effect, turn it by the top handle, scale it by the corner; Stretch, Slant, Bend; Remember this effect, then translate another chapter with the same effect; Add › Sound effect over a missed one | Replace erases the original (re-cleaned after a moment) and Keep brings it back; the transform box follows the effect at any zoom; the remembered effect gets the same translation later |
| Studio LaMa | Settings › Models › Studio cleanup (LaMa): download (208 MB); brush out text over art and set an effect to Replace; time a page on the phone; on a phone under 4 GB the row reads "Needs 4 GB of RAM" | Text over art is filled from the picture, not smeared; the download survives leaving the app; `dumpsys meminfo` drops by the model's size after cleaning |
| Studio storage | Settings › Storage › Studio library: sizes per manga; Delete one | The dialog says what goes (chapters, pages, translations, cleaned pages, size) and that exported files stay; the manga and its files are gone after |
| Studio library | Reorder pages and chapters by long-press drag, move pages to another chapter and a chapter to another manga, delete each; kill the app mid-import (`adb shell am kill`) and reopen the Studio tab | Orders stick after leaving the screen; nothing left in `files/studio/pages` that no page uses |

Clear `cache/patches` (`adb shell run-as com.smnexstudio.panelglass rm -rf cache/patches`) before a timing or memory
check: a cached page never calls the engine. The first page load after opening the reader can stay blank on some
sites; ⋮ › *Reload* loads it.

## Not covered

- **The real LiteRT-LM runtime, GPU and CPU**, and the real manga-ocr / detector sessions: only on a phone.
- **Live provider APIs** (Gemini): MockWebServer only; the emulator's HTTPS to providers is intercepted on the
  development machine, so real calls need a phone.
- **Compose UI**: only the Studio's drag-to-reorder and review-canvas gestures (`GridReorderTest`, `PageCanvasGestureTest`). Settings, the reader, the site dialogs and the Studio screens are checked by hand.
- **`mt.js` / `pt.js`**: the page scripts have no JavaScript tests; their Kotlin sides (anchoring, change detection)
  are unit-tested.
- **CPU timing on a low-end phone**: not measured.

## Adding a test

- Put it in the module of the code under test, next to its neighbours (`src/test/kotlin/…`); use Robolectric
  (`@RunWith(RobolectricTestRunner::class)`) only when an Android class is needed.
- Name tests as the behaviour in a sentence (`aModelBusyWhenTheUiIsHiddenIsReleasedAfterItsLastCall`).
- Provider code: MockWebServer, never a live API. On-device code: a scripted `LlmRunner`, never LiteRT-LM.
- Update the counts and tables here when you add a class.
