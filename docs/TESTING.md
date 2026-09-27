# Testing

What the automated tests cover, how to run them, and the checks that still need a phone. The architecture under test
is in [ARCHITECTURE.md](ARCHITECTURE.md); the translation flow in [FLOW.md](FLOW.md).

Counts as of 2026-09-27: **157 unit tests** in 26 classes, **2 instrumented tests**, all passing.

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

### core:model (8)

| Class | Tests | Covers |
|---|---|---|
| `BubbleFontTest` | 3 | Saved font names round-trip; retired fonts map to their successor; unknown names are null |
| `SiteMatchTest` | 5 | Which saved site owns a URL: subdomains, segment-boundary prefixes, several sites on one host |

### core:data (8)

| Class | Tests | Covers |
|---|---|---|
| `PatchCacheTest` | 4 | Round-trip of patch geometry and bytes; LRU eviction over budget; a corrupt file is dropped, not thrown; the key includes everything that changes rendering |
| `SystemDownloadsIntegrityTest` | 4 | A download matching its SHA-256 is moved into place and remembered; a mismatch is deleted, never placed; a file already on disk is checked once; a file changed after its check is checked again |

### core:engine (72)

| Class | Tests | Covers |
|---|---|---|
| `RegistryAndQwenTest` | 28 | **Engine selection:** the default is the on-device model; only Gemini, Google Translate and the on-device models are offered; a planned engine never resolves; a keyed engine without its key is `MissingKey`, never a fallback; removing a key keeps the selection. **On-device prompts:** numbered-line payload with SFX marked; token-budget chunking; lenient line parser that drops echoes and stray numbers; streamed reply cut when complete; partial replies keep what parsed and retry only the rest; byte-level stand-ins repaired; system text and payload as separate turns. **Model lifecycle:** a missing model is a typed failure; loading one model releases the other; Gemma 4 only on 8 GB phones; *Qwen runs on* reloads Qwen on the other backend at its next call; Automatic picks the CPU under 6 GB; a model busy when the UI is hidden is released after its last call; coming back first keeps it |
| `ProviderEngineTest` | 9 | Against MockWebServer: 401 → `MissingKey`; a missing key never reaches the network; Gemini steps down past a request option a model refuses and remembers it; display names resolve to ids, Gemma models get no Gemini-only options; the model list keeps text models, Gemini first; OpenAI / OpenRouter send the user's model and key |
| `LlmContractTest` | 8 | The cloud JSON contract: strict and tolerant parsing (prose, code fences), length mismatch and garbage are `Malformed`; HTTP status mapping, Gemini "high demand" is `Overloaded` with the provider's message; the retry policy is bounded and capped; glossary and montage mapping in the prompt |
| `GoogleTranslateEngineTest` | 8 | ML Kit translation behind its seam: every item translated, pack downloaded once; glossary terms before and after; names learned in one request apply to the next; Japanese prepared first, Korean passed through; an echoed sound word romanised for Latin targets; a failed pack download is a typed `Unavailable` |
| `JaPrepTest` | 7 | Japanese preparation for ML Kit: names found by honorific, whole-bubble and repetition (a loanword seen twice is not a name); names and honorifics swapped; casual negatives standardised; romaji for names and sound words |
| `PageTranslatorTest` | 5 | *Translate page*: language detection falls back to the page language and rejects romanised text; same language returned unchanged; order kept, a failed text left as it was; all failing is an error; a language pair prepared once |
| `BatchingTranslatorTest` | 4 | Gemini reads unread regions from their crops and returns what it read; an on-device model gets one image per call; a failure surfaces typed instead of switching provider; a cancelled caller cancels its generation |
| `LanguagePackStoreTest` | 3 | Google Translate packs: every app language once (Chinese variants share a pack); download-all skips packs present; a failure keeps progress and reports typed |

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

### core:render (14)

| Class | Tests | Covers |
|---|---|---|
| `TextEraserTest` | 6 | Bubble glyphs filled with the background; a text box as large as the balloon still erases the ink; bright halo uses the halo fill; inpainting on a smooth gradient is accepted, a smear over busy art rejected; mask rotation recovered |
| `LongestRunTest` | 5 | The unbreakable run the text fitter tests: an English word with its punctuation; hyphens break; ellipses and quotes do not join words; CJK breaks between any two characters |
| `PanelInkTest` | 3 | Ink on a mask: contrasting ink kept, otherwise black on a light mask and white on a dark one |

### feature:browser (23)

| Class | Tests | Covers |
|---|---|---|
| `BlockListTest` | 11 | Hosts and AdBlock formats parsed; exact hosts and their subdomains blocked, parents and unrelated hosts not; `@@` exceptions win; wildcard and context-scoped rules skipped; localhost ignored; cached form round-trips |
| `PatchAnchorTest` | 5 | A patch is anchored to the image under its centre; it follows a reader moving its page in its own layer and scroll anchoring; no key, no anchor; an image off screen leaves the patch in place |
| `ContentChangeTest` | 4 | Page turn without a scroll: another page is a change; our own patches and a fading control are not; all-patched cannot tell |
| `SameSiteTest` | 3 | Same-site test for pop-ups: subdomains share a site, public suffixes do not; case and trailing dot ignored |

`core:pipeline`, `core:ui`, `feature:library`, `feature:settings` and `:app` have no unit tests; the pipeline is covered
end to end by an instrumented test.

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

Clear `cache/patches` (`adb shell run-as com.smnexstudio.panelglass rm -rf cache/patches`) before a timing or memory
check: a cached page never calls the engine. The first page load after opening the reader can stay blank on some
sites; ⋮ › *Reload* loads it.

## Not covered

- **The real LiteRT-LM runtime, GPU and CPU**, and the real manga-ocr / detector sessions: only on a phone.
- **Live provider APIs** (Gemini): MockWebServer only; the emulator's HTTPS to providers is intercepted on the
  development machine, so real calls need a phone.
- **Compose UI**: no UI tests yet. Settings, the reader and the site dialogs are checked by hand.
- **`mt.js` / `pt.js`**: the page scripts have no JavaScript tests; their Kotlin sides (anchoring, change detection)
  are unit-tested.
- **CPU timing on a low-end phone**: not measured.

## Adding a test

- Put it in the module of the code under test, next to its neighbours (`src/test/kotlin/…`); use Robolectric
  (`@RunWith(RobolectricTestRunner::class)`) only when an Android class is needed.
- Name tests as the behaviour in a sentence (`aModelBusyWhenTheUiIsHiddenIsReleasedAfterItsLastCall`).
- Provider code: MockWebServer, never a live API. On-device code: a scripted `LlmRunner`, never LiteRT-LM.
- Update the counts and tables here when you add a class.
