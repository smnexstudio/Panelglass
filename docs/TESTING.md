# Testing & Quality Assurance Guide

This document catalogs the test suites, verification procedures, CI reporting pipelines, and real-device testing protocols for Panelglass.

For architectural components under test, see [ARCHITECTURE.md](ARCHITECTURE.md). For pipeline execution stages, see [FLOW.md](FLOW.md).

---

## Contents

- [Test Suite Summary](#test-suite-summary)
- [Quick Execution Commands](#quick-execution-commands)
- [CI Reporting & Pull Request Integration](#ci-reporting--pull-request-integration)
- [Test Harness Architecture & Seams](#test-harness-architecture--seams)
- [Unit Test Catalog by Module](#unit-test-catalog-by-module)
  - [core:model (73 tests)](#coremodel-73-tests)
  - [core:data (100 tests)](#coredata-100-tests)
  - [core:engine (151 tests)](#coreengine-151-tests)
  - [core:ocr (45 tests)](#coreocr-45-tests)
  - [core:render (62 tests)](#corerender-62-tests)
  - [core:pipeline (27 tests)](#corepipeline-27-tests)
  - [feature:browser (67 tests)](#featurebrowser-67-tests)
  - [feature:studio (241 tests)](#featurestudio-241-tests)
  - [feature:settings (58 tests)](#featuresettings-58-tests)
  - [feature:library (16 tests)](#featurelibrary-16-tests)
  - [app (21 tests)](#app-21-tests)
  - [core:ui (18 tests)](#coreui-18-tests)
- [Instrumented Device Tests](#instrumented-device-tests)
- [Physical Device Verification Checklist](#physical-device-verification-checklist)
- [Non-Automated / Out-of-Scope Areas](#non-automated--out-of-scope-areas)
- [Known Flaky Tests](#known-flaky-tests)
- [Guidelines for Adding Tests](#guidelines-for-adding-tests)

---

## Test Suite Summary

```
┌────────────────────────────────────────────────────────────────────────┐
│  TOTAL JVM UNIT TESTS: 879 tests across 118 test classes (all passing) │
│  INSTRUMENTED TESTS:   2 on-device hardware tests                      │
│  CODE COVERAGE (from the last 690-test run, not yet re-measured):      │
│  56.9% Line / 41.7% Branch; logic 66.9%, Compose UI 41.8%              │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Quick Execution Commands

| Target | Command Line | Description |
|---|---|---|
| **All Unit Tests** | `./gradlew testDebugUnitTest :core:model:test` | Executes every JVM test across all Gradle modules (what CI runs). |
| **Single Test Class** | `./gradlew :core:engine:testDebugUnitTest --tests "*RegistryAndQwenTest*"` | Executes an isolated test class. |
| **Coverage Report** | `./gradlew jacocoUnitTestReport` | Run after the tests, in its own invocation: generates JaCoCo coverage for every module. |
| **HTML Test Report** | `python3 tools/test-report/report.py --root . --out build/test-report` | Produces interactive test report with expected vs. actual outputs. |
| **Device KeyStore Test**| `./gradlew :core:data:connectedDebugAndroidTest` | Tests hardware Android Keystore AES-GCM round-trip. |
| **Device Pipeline Test**| `./gradlew :core:pipeline:connectedDebugAndroidTest` | Tests end-to-end screen capture, OCR, and patch rendering on device. |

> [!NOTE]
> `:core:model` is a pure Kotlin module; its tests run under `test`, not `testDebugUnitTest`.

---

## CI Reporting & Pull Request Integration

- **Automated Workflow (`.github/workflows/ci.yml`):** Runs on every pull request and push to `main`. Executes all JVM tests with `--continue`, calculates JaCoCo coverage, generates the HTML report, and builds the debug APK.
- **Sticky Pull Request Comments:** A dedicated GitHub Actions job parses JUnit XML and posts a single unified comment summarizing verdicts, module-level coverage, and assertion deltas. Updates automatically on subsequent pushes.
- **Artifact Preservation:** The interactive `test-report.html` and raw JUnit/JaCoCo XML are archived as build artifacts (retained for 30 days).

---

## Test Harness Architecture & Seams

Panelglass adheres to strict testing constraints to keep unit tests deterministic and fast:

1. **Zero External Network Dependencies:**
   - Cloud AI engines are validated against **MockWebServer**, verifying headers, JSON bodies, status codes, and timeouts.
   - Update checks route GitHub API calls to local MockWebServer endpoints.
2. **Scripted LLM Runner (`LlmRunner`):**
   - LiteRT-LM binary weights are never loaded on the JVM.
   - `LocalLlmEngine.runnerFactory` is substituted with scripted fakes that record prompts, simulate generation delays, and test cancellation.
3. **In-Memory Storage Fakes (`Fakes.kt`):**
   - `MemoryDataStore`: Process-isolated in-memory DataStore (prevents file-lock issues on Windows).
   - In-Memory Room: DAOs and database migrations execute against volatile SQLite instances.
   - `PlainCrypto` (`BlobCrypto` fake): stands in for Android Keystore encryption, which is unavailable on the JVM.
4. **Virtual Time & Main Dispatcher:**
   - Tests employ `StandardTestDispatcher` and `UnconfinedTestDispatcher` to test debouncing (e.g., 400 ms typing pauses in review editor) without thread sleeps.
5. **Robolectric & Compose Semantics:**
   - UI tests run via Robolectric using Compose Semantics actions, validating TalkBack accessibility labels and screen states.

---

## Unit Test Catalog by Module

### core:model (73 tests)
| Test Class | Tests | Scope & Key Behaviors Verified |
|---|---|---|
| `BubbleFontTest` | 4 | Font identifier serialization; migration of legacy font names; Studio catalog ID mapping. |
| `ComicInfoTest` | 12 | Full `ComicInfo.xml` round-trip; Mihon publishing status tags; CDATA/XML entity sanitization; linear parsing of malformed 256 KB inputs. |
| `StageInvalidationTest` | 2 | Polygon reshaping and brush strokes invalidate cleaned art layers without resetting translated text. |
| `NaturalSortTest` | 6 | Natural filename sorting (`2` before `10`, `p9` before `p10`, numeric runs longer than `Long.MAX_VALUE`). |
| `SiteMatchTest` | 6 | URL hostname matching, subdomains, path prefixes, and series-scoped glossary resolution. |
| `TranslationNotesTest` | 5 | Character pronoun formatting, note composition, character caps (60 chars for terms, 500 for notes). |
| `PatchTest` | 2 | Immediate in-memory bitmap rendering and lazy background WebP disk encoding. |
| `ReadStateTest` | 3 | Region state transitions (`UNREAD`, `UNREADABLE`, `READ`); legacy state migration. |
| `WebUrlTest` | 6 | Strict HTTPS enforcement; HTTP auto-upgrade; rejection of `javascript:`, `file:`, `intent:`. |
| `CustomProviderTest` | 6 | Custom AI provider presets; host extraction; preservation of custom paths. |
| `CustomEndpointTest` | 18 | Custom endpoint normalization; strips `/chat/completions`; rejects insecure or credentialed URLs. |
| `ModelSizeHintTest` | 3 | Parses small-model parameter hints (e.g. `1.5B`, `0.5B`) from model identifiers. |

### core:data (100 tests)
| Test Class | Tests | Scope & Key Behaviors Verified |
|---|---|---|
| `AppBackupTest` | 9 | Full JSON backup and restore round-trip; safe merging (updates matches, inserts new, deletes nothing); schema validation and size bounds. |
| `Migration3To4Test` | 2 | Database migration from v3 to v4: preserves sites, history, glossary; drops legacy OCR caches. |
| `Migration4To5Test` | 2 | Database migration from v4 to v5: adds pronoun fields to glossary entries; creates series notes table. |
| `RetiredSfxModeTest` | 3 | Backward-compatibility migration of retired Gloss/Overlay SFX modes to Replace mode. |
| `StudioDaoTest` | 14 | Room operations for Studio library: chapter numbering, page reordering, cascade deletion of image files, orphan sweeps. |
| `PatchCacheTest` | 5 | Disk LRU cache round-trip; cache eviction under byte budgets; corrupted file recovery. |
| `ReleaseInfoTest` | 11 | GitHub release JSON parser; draft/prerelease filtering; SHA-256 asset verification; path traversal protection. |
| `AppUpdatesTest` | 20 | Update lifecycle against local server; update dialog states; APK checksum verification; daily cadence limits. |
| `SettingsRepositoryTest` | 19 | DataStore settings persistence; language pair history; model preferences; charging-only run states. |
| `SystemDownloadsQueueTest` | 7 | Android `DownloadManager` lifecycle wrapper; handles interrupted downloads and deduplication. |
| `UnmigratedVersionsTest` | 2 | Graceful handling of unmigrated database versions (rebuilds clean DB without crashing). |
| `SystemDownloadsIntegrityTest`| 5 | SHA-256 verification of downloaded models before moving to production directories. |
| `HttpsOnlyTest` | 1 | Interceptor gating preventing cleartext network transmissions. |

### core:engine (151 tests)
| Test Class | Tests | Scope & Key Behaviors Verified |
|---|---|---|
| `RegistryAndQwenTest` | 28 | Engine resolution; missing key errors; on-device prompt formatting; model switching; 5 s grace period background release. |
| `ProviderEngineTest` | 8 | MockWebServer tests for cloud engines; handles 401 unauthorized; model selection fallback. |
| `LlmContractTest` | 17 | JSON array contract parsing; output token budget caps (`300 + 120` tokens); source-echo stripping; foreign script purge. |
| `LlmContractTolerantParseTest`| 19 | Lenient JSON parsing for home servers (code blocks, wrapped objects, extra keys); partial salvage. |
| `PromptPartsTest` | 2 | Consistent formatting of character glossaries and previous lines across cloud and local engines. |
| `CustomOpenAiEngineTest` | 19 | Custom AI client; endpoint concatenation; temperature fallback; text-only payloads; self-contained retries. |
| `EngineHttpGuardTest` | 6 | Blocks redirects (prevents API key leakage); enforces 4 MB maximum response caps. |
| `GoogleTranslateEngineTest` | 9 | ML Kit translation integration; language pack download triggers; Japanese preprocessing. |
| `JaPrepTest` | 7 | Japanese text normalization: honorific extraction, loanword deduplication, romaji transliteration. |
| `PageTranslatorTest` | 5 | In-situ DOM webpage translation; language detection fallback; element order preservation. |
| `BatchingTranslatorTest` | 6 | Batching engine: batches up to 60 items for cloud; streams 4-bubble groups for local LLMs; cancellation. |
| `SfxDictionaryTest` | 4 | Onomatopoeia lookup dictionary: hiragana/katakana unification; prolonged sound normalization. |
| `LanguagePackStoreTest` | 6 | ML Kit language pack manager; auto-downloads CJK packs; protects English baseline. |
| `HttpFailureWireTest` | 8 | HTTP status code mapping (429 → `QuotaExceeded`, 503 → `Overloaded`, network drops → `Network`). |
| `OfflineEnginesTest` | 4 | Airplane mode verification: ensures ML Kit and local models never attempt network calls. |
| `ModelPinningTest` | 3 | Verifies hardcoded Hugging Face commit hashes and SHA-256 fingerprints for models. |

### core:ocr (45 tests)
| Test Class | Tests | Scope & Key Behaviors Verified |
|---|---|---|
| `HighContrastTest` | 3 | Otsu binarization and inversion for light lettering on dark art backgrounds. |
| `MissedTextTest` | 4 | PP-OCRv6 second pass: probability component clipping; adjacent character block merging (`타` + `닥`). |
| `UnsureFreeTextTest` | 2 | manga-ocr confidence filtering: drops free-text readings below 0.80 confidence to stop hallucinations. |
| `RegionClustererTest` | 9 | Merging adjacent vertical columns (RTL); top-to-bottom horizontal line merging; line spacing limits. |
| `RegionBuilderTest` | 7 | Assembles bounding boxes into semantic text regions; suppresses punctuation-only lines. |
| `RegionClassifierTest` | 6 | Outer ring pixel analysis: classifies `ENCLOSED`, `CAPTION`, `FREE`, `SFX`, `IN_SCENE`. |
| `CropGeometryTest` | 4 | Dynamic crop scaling; ±90° rotated shadow pass coordinate re-mapping. |
| `MangaOcrRepeatTest` | 3 | Deduplication of autoregressive repetition loops in manga-ocr output. |
| `UprightTiltTest` | 3 | Clamps vertical text orientation to ±45° of nearest axis to prevent sideways rendering. |
| `PanelCutterTest` | 2 | Comic panel boundary detection and reading order sequencing. |
| `ModelPinningTest` | 2 | Commit pinning and hash checks for manga-ocr and LaMa models. |

### core:render (62 tests)
| Test Class | Tests | Scope & Key Behaviors Verified |
|---|---|---|
| `FontCatalogueTest` | 5 | Bundled typography catalog: verifies TTF/OTF files, license entries, and language script coverage. |
| `FontNameTableTest` | 4 | Binary font header parser: extracts family/style names from OpenType `name` tables. |
| `SfxRendererTest` | 4 | Sound effect native canvas rendering: position transforms, rotation handles, typography presets. |
| `PageCleanerTest` | 7 | Background inpainting: flood-fill erasing for balloons; ray inpainting; LaMa neural cleanups. |
| `PolygonFitTest` | 4 | Text placement inside non-rectangular contours (ellipses, L-shapes, convex hulls). |
| `StudioRendererTest` | 9 | Studio typography rendering: stroke outlines, fill opacities, round corners, auto-wrap. |
| `BalloonFitTest` | 4 | Balloon paper boundary tracing; separates artwork spikes from usable bubble area. |
| `BalloonShapeTest` | 3 | Generates candidate rectangular bounds inside traced organic balloon geometry. |
| `TextEraserTest` | 8 | Glyph mask expansion (8% padding); gradient variance checking; dark outline elimination. |
| `LongestRunTest` | 5 | Calculates unbreakable text run lengths for hyphenation and wrapping calculations. |
| `PanelInkTest` | 3 | Contrast calculation for inpainting ink: selects black or white based on background luminance. |
| `RegionRendererTest` | 6 | End-to-end patch generation: creates WebP bitmaps formatted to exact region geometry. |

### core:pipeline (27 tests)
| Test Class | Tests | Scope & Key Behaviors Verified |
|---|---|---|
| `TranslationPipelineTest` | 10 | Stage gates, per-stage watchdog, patch-cache hits and misses, Re-translate bypassing the cache, streamed patches. |
| `DetectionStageTest` | 7 | Detect, build, classify and order; nested readings dropped; text boxes of one balloon merged; unreadable SFX handling. |
| `TileDetectionTest` | 8 | Overlapping tile plan for tall webtoon strips and merging of boxes seen in two tiles. |
| `SeriesResolverTest` | 2 | Series glossary and notes merged and hashed into the cache key before lookup. |

### feature:browser (67 tests)
| Test Class | Tests | Scope & Key Behaviors Verified |
|---|---|---|
| `BlockListTest` | 11 | Parses AdBlock and hosts formats; verifies exact host and subdomain blocking rules. |
| `PageScanPlanTest` | 17 | Whole-page scan planner: 70% step sizing; 96 MB patch cache memory budget; edge cases. |
| `PatchAnchorTest` | 8 | Pins patches to underlying DOM image keys; maintains alignment during zoom and scroll. |
| `SessionLanguagesTest` | 4 | Session language resolution: site overrides vs. DOM auto-detection vs. intent extras. |
| `SeriesMemoryTest` | 2 | Scoped series glossary updates; ensures writes commit before viewport retranslates. |
| `ContentChangeTest` | 4 | Detects page turns in non-scrolling readers via luminance thumbnail delta comparison. |
| `SameSiteTest` | 3 | Validates same-site popup rules using public suffix lists. |
| `NavigationPolicyTest` | 11 | WebView security policy: blocks non-web schemes; handles mailto/tel; prevents ad redirects. |
| `BlockListRepositoryTest`| 7 | Blocklist download, merging, and fallback mechanisms (AdGuard to StevenBlack). |

### feature:studio (241 tests)
| Test Class | Tests | Scope & Key Behaviors Verified |
|---|---|---|
| `CanvasTransformTest` | 10 | Review canvas zoom/pan maths (1×–8×); selection tracking; keyboard resize handling. |
| `PageCanvasGestureTest` | 8 | Touch gesture recognition: tap to select, double-tap zoom, long-press to edit, two-finger pinch. |
| `PolygonHitTest` | 6 | Accurate point-in-polygon collision detection for arbitrary speech bubble contours. |
| `ScrollbarTest` | 5 | Custom Compose scrollbar behavior for long chapter listings. |
| `StorageSpaceTest` | 2 | Enforces 64 MB minimum storage reserve before allowing chapter imports or exports. |
| `ExportNamesTest` | 6 | Sanitizes export filenames; generates CBZ structures and Mihon-compatible `ComicInfo.xml`. |
| `StudioExporterTest` | 21 | Chapter export pipeline: writes CBZ, ZIP, or image folders; handles clean/translated exports. |
| `CoverCropTest` | 3 | Calculates 2:3 aspect ratio cover crop windows from arbitrary comic pages. |
| `LibraryOrderTest` | 2 | Studio library search filtering and sorting (title A–Z, date modified). |
| `WidthClassTest` | 1 | Adaptive UI layout switching at 600 dp and 840 dp breakpoints. |
| `FolderChaptersTest` | 6 | Auto-detects multi-chapter archives and parses folder naming conventions. |
| `PeopleTest` | 2 | Parses author and artist metadata strings into distinct structured entities. |
| `ColorMathTest` | 5 | HSV color conversions; hex color validation (`#RGB`, `#RRGGBB`, `#AARRGGBB`). |
| `TileCacheTest` | 6 | Multi-resolution bitmap tile caching for ultra-smooth 8× zooming. |
| `StudioTranslatorTest` | 18 | Chapter background translation: page-by-page execution, crash resumption, progress tracking. |
| `RunGateTest` | 10 | Studio execution throttling: pauses on high thermal state, low battery, or active reader use. |
| `RunNotificationsTest` | 6 | Foreground service notifications: progress bar, Pause, Resume, and Cancel actions. |
| `GridReorderTest` | 2 | Drag-and-drop reordering of manga chapters and pages. |
| `ArchiveImporterTest` | 31 | Complex archive extractor: handles CBZ, ZIP, 7z; protects against zip-slip and decompression bombs. |
| `ImageHeaderTest` | 9 | Inspects magic bytes to detect image formats without full file decoding. |
| `ImageImporterTest` | 8 | Imports loose image selections; renders PDF pages at 2× scale. |
| `StudioImporterTest` | 11 | High-level import orchestrator: stages files and ensures clean rollback on cancellation. |
| `ReviewPanelsTest` | 22 | Review editor controls: typography styling, color pickers, bubble reshaping, brush tool. |
| `ExportViewModelTest` | 10 | View model state machine for chapter export workflows. |
| `ExportScreenTest` | 6 | Compose UI test for chapter export selection screens. |
| `ImportViewModelTest` | 14 | View model state machine for import flows and metadata assignment. |
| `FontsViewModelTest` | 6 | Custom user font import, validation, and role assignment. |
| `ReviewSavingTest` | 5 | Validates that bubble text edits auto-save 400 ms after typing pauses. |

### feature:settings (58 tests)
| Test Class | Tests | Scope & Key Behaviors Verified |
|---|---|---|
| `SettingsViewModelTest` | 13 | Engine selection flows; API key sheets; language swapping; storage measurement. |
| `SettingsBackupTest` | 5 | Backup & restore view model: file validation, error handling, transactional restore. |
| `SettingsScreenTest` | 25 | Compose UI testing for settings hub and sub-pages; accessibility TalkBack labels. |
| `MarkdownLiteTest` | 6 | In-app Markdown renderer: parses headings, links, bullets, and privacy documents. |
| `SettingsSearchTest` | 4 | In-app settings search indexing and fuzzy query matching. |
| `EnginePickerTest` | 5 | UI test for the engine selection dialog and API key entry prompts. |

### feature:library (16 tests)
| Test Class | Tests | Scope & Key Behaviors Verified |
|---|---|---|
| `LibraryScreenTest` | 11 | Saved sites and history screens; site creation dialog; TalkBack accessibility. |
| `LibraryViewModelTest` | 5 | Site persistence: auto-prepends HTTPS; pins bookmarks; tracks visit timestamps. |

### app (21 tests)
| Test Class | Tests | Scope & Key Behaviors Verified |
|---|---|---|
| `UpdateDialogTest` | 8 | In-app update dialog UI states (Checking, Available, Downloading, Verified, Error). |
| `StartupTasksTest` | 7 | Cold-start initialization: fetches default language packs; handles model queues. |
| `ManifestPolicyTest` | 6 | Validates committed AndroidManifest.xml: ensures zero unauthorized permissions or exposed components. |

### core:ui (18 tests)
| Test Class | Tests | Scope & Key Behaviors Verified |
|---|---|---|
| `ApiKeyValidationTest` | 11 | Validates API key formatting and prevents saving insecure HTTP endpoints. |
| `AppLocaleTest` | 7 | Dynamic per-app language switching across 16 supported UI translations. |

---

## Instrumented Device Tests

Run with a physical device or hardware emulator connected:

```bash
./gradlew :core:data:connectedDebugAndroidTest
./gradlew :core:pipeline:connectedDebugAndroidTest
```

| Test Class | Module | Verification Scope |
|---|---|---|
| **`SecureKeyStoreInstrumentedTest`** | `:core:data` | Verifies AES-GCM encryption/decryption using the real hardware-backed Android Keystore. |
| **`PipelineInstrumentedTest`** | `:core:pipeline` | Processes a synthetic comic page through detection, OCR, translation, and WebP patch rendering. |

---

## Physical Device Verification Checklist

Certain hardware-dependent behaviors (GPU runtimes, thermal throttling, real WebView surface compositing) require manual validation on a physical device.

```bash
# Compile debug build with speed optimizations
adb shell cmd package compile -m speed -f com.smnexstudio.panelglass.debug
```

### 1. Reader & Viewport Translation
- [ ] **Continuous Translation:** Open a live manga chapter; press Start; verify bubbles translate and patch overlays align smoothly during scrolling.
- [ ] **Zoom & Pan Stability:** Pinch-zoom to 200%; verify patches remain anchored to comic artwork.
- [ ] **Orientation Handling:** Rotate device between portrait and landscape; verify patches refresh to match the new layout after 1.2 s.
- [ ] **Whole-Page Scan:** Run **⋮ › Translate whole page**; confirm auto-scrolling, 30% overlap retention, and memory budget containment.

### 2. AI Translation Engines
- [ ] **Google Translate (ML Kit):** Translates offline without network; sub-second response times.
- [ ] **Google Gemini:** Verify direct image-crop translation mode with a valid API key.
- [ ] **Gemma 4 E2B:** Run on an 8 GB device; verify natural dialogue flow and character pronoun accuracy.
- [ ] **Qwen 2.5 1.5B (GPU vs CPU):** Toggle **Settings › Models & downloads › Qwen runs on**; verify execution on both OpenCL GPU and CPU backends.
- [ ] **Custom AI:** Configure self-hosted OpenAI-compatible endpoint; confirm translation over HTTPS.

### 3. Studio Chapter Pipeline
- [ ] **Archive Import:** Import a `.cbz`, `.zip`, `.7z`, and multi-image selection; confirm page ordering and `ComicInfo.xml` reading.
- [ ] **Background Chapter Translation:** Start translating a 20-page chapter; press Home; turn screen off; verify notification progress and completion.
- [ ] **Crash Resilience:** Force-kill app mid-translation (`adb shell am kill`); reopen Studio; verify translation resumes from first unfinished page.
- [ ] **Interactive Bubble Review:** Tap bubbles; edit original transcription and translation; verify auto-saving after 400 ms.
- [ ] **LaMa Inpainting:** Download LaMa; clean a sound effect over complex artwork; verify seamless background reconstruction.
- [ ] **Export Options:** Export chapter as CBZ, ZIP, and raw image folders; verify clean (text-free) export modes.

---

## Non-Automated / Out-of-Scope Areas

The following components are verified exclusively through manual physical device checks:
- **Real OpenCL GPU Acceleration:** Hardware-specific driver variations across Adreno, Mali, and PowerVR GPUs.
- **PixelCopy Surface Pipeline:** Hardware-protected DRM streams or canvas surfaces in third-party WebViews.
- **R8 Minification / ProGuard Verification:** Release APK shrinking is validated via smoke-testing release artifacts.

---

## Known Flaky Tests

- **`StudioImporterTest.discardDeletesStagedPages`:** Occasionally encounters transient `EPERM` or file-lock errors on Windows host environments when local antivirus scanners inspect newly written PNG files. Passes reliably on Linux CI workers and physical Android devices.

---

## Guidelines for Adding Tests

1. **Locate Tests Beside Code:** Place tests in `src/test/kotlin/` within the corresponding module.
2. **Use Robolectric Sparingly:** Prefer pure JUnit 5 tests. Use Robolectric (`@RunWith(RobolectricTestRunner::class)`) only when Android `Context`, Bitmaps, or Compose UI trees are required.
3. **Descriptive Sentence Naming:** Format test method names as readable sentences describing expected behavior:
   ```kotlin
   @Test fun aModelBusyWhenTheUiIsHiddenIsReleasedAfterItsLastCall()
   ```
4. **Never Call Live APIs:** Cloud engines must mock HTTP exchanges via `MockWebServer`. On-device neural models must use `LlmRunner` fakes.
5. **Update Documentation Counts:** When introducing new test classes, update the test inventory tables in this document.
