# Memory usage

How much RAM Panelglass uses with each offered engine, measured on a phone in four situations: the app running,
translating, in the background while the camera is open, and cleared from Recents. The architecture behind the
numbers is in [ARCHITECTURE.md](ARCHITECTURE.md#concurrency-and-memory).

Measured 2026-09-27. Re-measure when LiteRT-LM, a model file or the trim policy changes.

## Contents

- [Summary](#summary)
- [Test device](#test-device)
- [Method](#method)
- [Results: Gemma 4 E2B](#results-gemma-4-e2b)
- [Results: Qwen 2.5 1.5B](#results-qwen-25-15b)
- [Results: Google Translate](#results-google-translate)
- [Results: Qwen 2.5 1.5B on the CPU](#results-qwen-25-15b-on-the-cpu)
- [Going to the background mid-translation](#going-to-the-background-mid-translation)
- [Re-run, 2026-10-17](#re-run-2026-10-17)
- [Leak check, 2026-10-30](#leak-check-2026-10-30)
- [Comparison](#comparison)
- [Observations](#observations)
- [Reproducing](#reproducing)

## Summary

| | Google Translate | Gemma 4 E2B (GPU) | Qwen 2.5 1.5B (GPU) | Qwen 2.5 1.5B (CPU) |
|---|---|---|---|---|
| Reader open, idle (app PSS) | 416 MB | 2,766 MB | 3,086 MB | 2,258 MB |
| Of which pinned (private dirty) | 325 MB | 2,422 MB | 2,786 MB | 597 MB |
| Translating, average / peak (app PSS) | 580 / 723 MB | 2,859 / 3,081 MB | 3,327 / 3,425 MB | 2,504 / 2,598 MB |
| Of which GPU (peak) | 119 MB | 1,998 MB | 2,563 MB | 130 MB |
| WebView renderer (separate process) | ~210 MB | ~180 MB | ~180 MB | ~200 MB |
| Lowest device `MemAvailable` while translating | 2,665 MB | 1,303 MB | 732 MB | 2,578 MB |
| In background, camera open (app PSS) | 514 MB | 497 MB | 570 MB | 492–2,337 MB¹ |
| Model load time | – | 5.7 s (11.8 s cold) | 14.1 s | 0.9 s (5.7 s cold) |
| Generation, same 4-bubble group | – | – | 3.7 s | 12.6 s |
| Cleared from Recents | killed, all memory returned | killed, all memory returned | killed, all memory returned | killed, all memory returned |

¹ Home was pressed while a generation was still running, so the model was not released; see
[Going to the background mid-translation](#going-to-the-background-mid-translation).

- **The on-device LLMs dominate.** Loaded, either model takes the app from ~230 MB to ~2.8–3.1 GB, most of it GPU
  memory holding the weights.
- **Qwen is heavier than Gemma 4** despite the smaller file (1.6 GB against 2.6 GB): its GPU allocation is ~2.5 GB
  against ~1.9 GB. The q8 Qwen weights appear to be expanded on the GPU, while Gemma 4's mixed-precision weights stay
  compact.
- **On the CPU, Qwen pins a quarter of the memory** (597 MB against 2,786 MB): its weights are a memory-mapped file
  the kernel can drop and read back, where the GPU copy is locked. The price is speed: generation is ~3× slower.
- **A model is released as soon as the app leaves the screen**, so in the background every engine costs about the
  same (~500–600 MB), and the camera runs without killing Panelglass. Pressing Home mid-translation used to keep the
  model loaded for about a minute (3.2 GB on the GPU); it is now released 5 s after its last call (fixed
  2026-09-27, [below](#going-to-the-background-mid-translation)).
- **Qwen picks its backend by RAM.** Settings › Models › *Qwen runs on* defaults to Automatic: the CPU on phones
  under 6 GB, the GPU otherwise; GPU and CPU can be forced.

## Test device

| | |
|---|---|
| Phone | Motorola edge 60 pro (XT2507-2) |
| SoC | MediaTek MT6897, 8 cores, arm64-v8a |
| GPU | ARM Mali-G615 MC6, OpenGL ES 3.2 (driver r44p1) |
| RAM | 7.26 GiB (`MemTotal` 7,616,204 kB), plus 5.4 GiB zRAM swap |
| Display | 1220 × 2712, 450 dpi |
| Android | 16 (API 36), build W1VVS36H.7-108-8-8 |
| Android System WebView | 153.0.8010.39 |
| Panelglass | 0.1.0 (versionCode 100), debug build |
| Models | `gemma-4-e2b-it.litertlm` (2.59 GB), `qwen2.5-1.5b-instruct-q8.litertlm` (1.60 GB), in app-specific external storage; ML Kit ZH↔EN pack installed |

Both LiteRT-LM models ran on the GPU backend (`LiteRT-LM loaded on gpu`) in every run.

## Method

**Page:** a Bilibili Manga chapter (paged reader, pages drawn on a `<canvas>`), Chinese → English, opened with the
debug launch extras `--es url <chapter> --es engine <ENGINE_ID>`.

**Steps**, driven over adb for each engine, the app force-stopped first:

| Step | What happens | Wait |
|---|---|---|
| 0 | App not running | – |
| 1a | App launched to the home screen (Sites) | 10 s |
| 1b | Reader opened on the chapter; the reader pre-warms detector, OCR and engine | model load + 20 s |
| 2 | **Start** pressed, then three page turns (tap on the right half) 35 s apart; sampled every ~2 s for 150 s | 150 s |
| 3a | Home pressed; Panelglass in the background | 5 s |
| 3b / 3c | Camera app opened (`STILL_IMAGE_CAMERA`), Panelglass still in the background | 15 s / 45 s |
| 3d | Panelglass brought back, one page turn | 50 s |
| 4a | Home pressed again | 5 s |
| 4b / 4c | Panelglass swiped away in Recents | 3 s / 23 s |

**Metrics** (from `dumpsys meminfo com.smnexstudio.panelglass` and `/proc/meminfo`; MB = 1,024 kB):

- **App PSS**: the app's total proportional set size, the usual "how much RAM does it use" figure. It does not
  include the WebView renderer, which is a separate sandboxed process; that is listed on its own.
- **GPU**: `Graphics` in the App Summary (`GL mtrack` + `EGL mtrack`), where LiteRT-LM's GPU weights and buffers land.
- **Private dirty**: the part of PSS that only this app uses and the kernel cannot reclaim except by swapping it to
  zRAM. A memory-mapped model file is *private clean* instead: counted in PSS, but droppable and re-readable from
  storage, so it costs far less under pressure than its PSS suggests.
- **Native**: native heap (ONNX Runtime, LiteRT-LM CPU side, bitmaps).
- **Swap**: `TOTAL SWAP PSS`, app memory the kernel has compressed into zRAM.
- **Device available**: `MemAvailable`, what the whole phone could still hand out.

Other apps were running as on any day-to-day phone, so device-wide figures move by a few hundred MB between runs;
the app's own PSS is the figure to compare. Pages translated in an earlier run can come from the patch cache,
which skips the engine call (it does not unload a model already loaded).

## Results: Gemma 4 E2B

`GEMMA4_LOCAL`. Model loaded on the GPU in 5.7 s (11.8 s in a first, cold run of the day).

| Step | App PSS | GPU | Native | Swap | Device available |
|---|---|---|---|---|---|
| 0 Not running | – | – | – | – | 3,389 MB |
| 1a Home screen | 230 MB | 36 MB | 25 MB | 0 | 3,417 MB |
| 1b Reader, model loaded, idle | 2,766 MB | 1,912 MB | 429 MB | 55 MB | 1,644 MB |
| 2 Translating (avg / peak) | 2,859 / 3,081 MB | 1,998 MB peak | 591 MB peak | 192 MB peak | 1,303 MB lowest |
| 3a Background, 5 s | 538 MB | 56 MB | 259 MB | 104 MB | 3,226 MB |
| 3b Camera open, 15 s | 497 MB | 46 MB | 231 MB | 103 MB | 2,259 MB |
| 3c Camera open, 45 s | 497 MB | 46 MB | 231 MB | 103 MB | 2,240 MB |
| 3d Back, one page turned | 559 MB | 129 MB | 210 MB | 97 MB | 3,297 MB |
| 4a Background again | 489 MB | 64 MB | 208 MB | 97 MB | 3,174 MB |
| 4b Cleared, 3 s | process killed | | | | 3,565 MB |
| 4c Cleared, 23 s | process killed | | | | 3,703 MB |

WebView renderer: 150 MB idle, 178 MB while translating. Translation: 12 regions in 3 groups of 4, ~7–10 s per
group, 35 s for a dense page.

At step 3d the page turned after returning came from the patch cache, so the model was not needed and stayed
unloaded. In an earlier manual run where the page was new, the next translation reloaded Gemma 4 in 6.5 s and the
app went back to 3,068 MB. That run also peaked higher while translating (3,256 MB, device down to 907 MB
available): the peak depends on the page and on what else the phone holds.

## Results: Qwen 2.5 1.5B

`QWEN15_LOCAL`. Model loaded on the GPU in 14.1 s.

| Step | App PSS | GPU | Native | Swap | Device available |
|---|---|---|---|---|---|
| 0 Not running | – | – | – | – | 3,656 MB |
| 1a Home screen | 230 MB | 36 MB | 25 MB | 0 | 3,419 MB |
| 1b Reader, model loaded, idle | 3,086 MB | 2,495 MB | 261 MB | 249 MB | 1,200 MB |
| 2 Translating (avg / peak) | 3,327 / 3,425 MB | 2,563 MB peak | 453 MB peak | 439 MB peak | 732 MB lowest |
| 3a Background, 5 s | 588 MB | 54 MB | 101 MB | 334 MB | 3,304 MB |
| 3b Camera open, 15 s | 575 MB | 43 MB | 101 MB | 333 MB | 2,148 MB |
| 3c Camera open, 45 s | 570 MB | 43 MB | 86 MB | 347 MB | 1,853 MB |
| 3d Back, one page turned | 3,316 MB | 2,504 MB | 255 MB | 406 MB | 1,348 MB |
| 4a Background again | 637 MB | 60 MB | 99 MB | 374 MB | 3,623 MB |
| 4b Cleared, 3 s | process killed | | | | 4,086 MB |
| 4c Cleared, 23 s | process killed | | | | 3,853 MB |

WebView renderer: 150 MB idle, 180 MB while translating. Translation: ~10–20 s per group of 4 bubbles (a dense page
of 13 regions took 45 s). At step 3d the new page needed the model again: reloaded in 11.6 s.

## Results: Google Translate

`GOOGLE` (ML Kit on-device translation, ZH→EN pack already installed). No LLM is loaded; the reader pre-warms the
detector and OCR only.

| Step | App PSS | GPU | Native | Swap | Device available |
|---|---|---|---|---|---|
| 0 Not running | – | – | – | – | 3,413 MB |
| 1a Home screen | 233 MB | 36 MB | 25 MB | 0 | 3,383 MB |
| 1b Reader, idle | 416 MB | 104 MB | 88 MB | 0 | 3,006 MB |
| 2 Translating (avg / peak) | 580 / 723 MB | 119 MB peak | 321 MB peak | 0 | 2,665 MB lowest |
| 3a Background, 5 s | 571 MB | 43 MB | 304 MB | 1 MB | 2,577 MB |
| 3b Camera open, 15 s | 514 MB | 29 MB | 299 MB | 78 MB | 1,851 MB |
| 3c Camera open, 45 s | 514 MB | 29 MB | 299 MB | 61 MB | 1,874 MB |
| 3d Back, one page turned | 591 MB | 110 MB | 249 MB | 124 MB | 2,954 MB |
| 4a Background again | 519 MB | 42 MB | 150 MB | 221 MB | 2,815 MB |
| 4b Cleared, 3 s | process killed | | | | 3,291 MB |
| 4c Cleared, 23 s | process killed | | | | 3,540 MB |

WebView renderer: 205 MB idle, 212 MB while translating. Translation: 0.5–0.8 s engine time, 4–5 s per screen in
all (detection and OCR dominate).

## Results: Qwen 2.5 1.5B on the CPU

`QWEN15_LOCAL` with the GPU backend skipped. Measured with a debug switch that has since become the setting
**Settings › Models › Qwen runs on › CPU** (`Settings.qwenBackend`; `LiteRtLmRunner` then tries only the CPU).
Automatic, the default, takes the CPU on phones under 6 GB (`DeviceMemory.qwenOnCpu`), so this phone used the GPU.
The patch cache was cleared first, so every page was really generated. Model loaded on the CPU in 0.9 s (5.7 s the
first time after install).

| Step | App PSS | Private dirty | GPU | Native | Swap | Device available |
|---|---|---|---|---|---|---|
| 0 Not running | – | – | – | – | – | 3,502 MB |
| 1a Home screen | 196 MB | | 36 MB | 24 MB | 0 | 3,497 MB |
| 1b Reader, model loaded, idle | 2,258 MB | 597 MB | 120 MB | 418 MB | 0 | 2,940 MB |
| 2 Translating (avg / peak) | 2,504 / 2,598 MB | | 130 MB peak | 738 MB peak | 339 MB peak | 2,578 MB lowest |
| 3a Background, 5 s (mid-generation) | 2,322 MB | | 44 MB | 218 MB | 465 MB | 2,720 MB |
| 3b Camera open, 15 s | 1,500 MB | | 29 MB | 233 MB | 415 MB | 1,907 MB |
| 3c Camera open, 45 s | 2,337 MB | | 29 MB | 224 MB | 387 MB | 2,209 MB |
| 3d Back, one page turned | 512 MB | | 106 MB | 42 MB | 254 MB | 3,251 MB |
| 4a Background again | 446 MB | | 43 MB | 42 MB | 254 MB | 3,105 MB |
| 4b Cleared, 3 s | process killed | | | | | 3,504 MB |
| 4c Cleared, 23 s | process killed | | | | | 3,602 MB |

- **Where the memory is.** The weights are `Other mmap`, 1.54 GB of private *clean* pages: the file mapped from
  storage. The pinned part is ~600 MB (native heap: KV cache and runtime). Device available memory stayed at
  2.6–2.9 GB while translating, against 0.7–1.2 GB for the same model on the GPU.
- **Under pressure the file pages go first.** With the camera open (3b) the kernel dropped ~800 MB of the mapped
  weights; they were read back as generation touched them again (3c).
- **Speed.** The same group of 4 bubbles took 12.6 s on the CPU against 3.7 s on the GPU; a dense group took up to
  21 s. A 4-bubble screen took 18–21 s in all.
- **Steps 3a–3c** caught a generation still running when Home was pressed, so the model stayed loaded (next section).
  At step 3d it had been released and the page turned after returning was not re-translated within the wait.

## Going to the background mid-translation

`PanelglassApp.onTrimMemory` releases models with `releaseIfIdle`, which does nothing while a model is loading or
generating (`tryLock` fails). A second test, with the patch cache cleared, pressed Home at two moments:

| | CPU backend | GPU backend | GPU backend, after the fix |
|---|---|---|---|
| **A.** Home after the page finished translating | 2,522 → 446 MB within 5 s | 3,250 → 443 MB within 5 s | 3,264 → 462 MB within 5 s |
| **B.** Home while the model was loading / generating | held 2.4–2.5 GB for 65 s | held 3.2 GB for 60 s | released 5 s after its last call (~15 s after Home) |

Before the fix, in B the `onTrimMemory 20` at Home found the model busy and skipped it. The pipeline kept going in
the background (the GPU model even finished loading there, 11.4 s, then translated the page), and nothing released
the model when it finished: it stayed loaded until the system sent `onTrimMemory 40` (`TRIM_MEMORY_BACKGROUND`) about
a minute later — on the GPU, ~2.5 GB the kernel cannot reclaim, exactly while the user had switched to another app.

**The fix** (`LocalEngines.onHidden`): a hidden UI releases an idle model at once, as before; a busy one is released
5 s after its last call ends (`releaseGraceMs`). The grace lets the next group of bubbles from the same screen reuse
the loaded model instead of reloading it. An activity starting again (`onVisible`) cancels the pending release. In
the re-run of B the model finished loading and translating in the background, and the log shows
`UI hidden: releasing the model after its last call` 5.0 s after the last generation; the app was at 519 MB 4 s later.

## Re-run, 2026-10-17

The same steps, run by a script (`dumpsys meminfo` every ~2 s while translating), on the build with the PP-OCRv6
second pass, glossary and notes. **Not directly comparable with the tables above:**

| | 2026-09-27 (above) | 2026-10-17 |
|---|---|---|
| Android | 16 | 17 |
| Page | Bilibili, Chinese → English, page turns | rawkuma, **Japanese** → English, scroll steps |
| Recognizer | ML Kit (Chinese) | **manga-ocr** (installed; ~140 MB of sessions) |
| Second detection pass | – | PP-OCRv6 tiny, every capture |
| Device available before start | 3.4 GB | 2.7–3.0 GB |
| Qwen | measured | measured (GPU, Automatic on this phone) |
| Camera steps (3b / 3c) | `dumpsys meminfo` | Gemma 4 and Google: not readable¹; Qwen: read from `/proc/<pid>/smaps_rollup` |

**Gemma 4 E2B** (GPU, loaded in 5.0 s; 4 screens, 12.5 s median, 16.6 s at most):

| Step | App PSS | Private dirty | GPU | Native | Swap | Device available |
|---|---|---|---|---|---|---|
| 0 Not running | – | – | – | – | – | 2,696 MB |
| 1a Home screen | 205 MB | 119 MB | 36 MB | 25 MB | 0 | 2,941 MB |
| 1b Reader, model loaded, idle | 2,954 MB | 2,641 MB | 1,893 MB | 690 MB | 5 MB | 1,306 MB |
| 2 Translating (avg / peak) | 3,209 / 3,346 MB | 2,944 MB peak | 1,988 MB peak | 905 MB peak | 544 MB peak | 824 MB lowest |
| 3a Background, 5 s | 622 MB | 250 MB | 49 MB | 158 MB | 307 MB | 2,606 MB |
| 3b / 3c Camera open | not readable¹ | | | | | 1,760 / 1,805 MB |
| 3d Back, one step | 632 MB | 94 MB | 75 MB | 8 MB | 469 MB | 2,588 MB |
| 4a Background again | 601 MB | 90 MB | 45 MB | 9 MB | 445 MB | 2,513 MB |
| 4b / 4c Cleared | process killed | | | | | 2,947 / 2,967 MB |

**Google Translate** (4 screens, 5.5 s median, 8.4 s at most):

| Step | App PSS | Private dirty | GPU | Native | Swap | Device available |
|---|---|---|---|---|---|---|
| 0 Not running | – | – | – | – | – | 2,986 MB |
| 1a Home screen | 200 MB | 119 MB | 36 MB | 25 MB | 0 | 2,966 MB |
| 1b Reader, idle | 597 MB | 474 MB | 62 MB | 354 MB | 0 | 2,571 MB |
| 2 Translating (avg / peak) | 930 / 1,019 MB | 882 MB peak | 106 MB peak | 698 MB peak | 0 | 2,147 MB lowest |
| 3a Background, 5 s | 667 MB | 535 MB | 33 MB | 444 MB | 0 | 2,340 MB |
| 3b / 3c Camera open | not readable¹ | | | | | 1,641 / 1,587 MB |
| 3d Back, one step | 671 MB | 81 MB | 56 MB | 12 MB | 451 MB | 2,511 MB |
| 4a Background again | 639 MB | 76 MB | 25 MB | 12 MB | 426 MB | 2,383 MB |
| 4b / 4c Cleared | process killed | | | | | 2,913 / 2,909 MB |

**Qwen 2.5 1.5B** (GPU, loaded in 11.5 s; 4 screens, 13.5 s median, 28.7 s at most):

| Step | App PSS | Private dirty | GPU | Native | Swap | Device available |
|---|---|---|---|---|---|---|
| 0 Not running | – | – | – | – | – | 2,794 MB |
| 1a Home screen | 187 MB | 119 MB | 36 MB | 25 MB | 0 | 2,832 MB |
| 1b Reader, model loaded, idle | 3,340 MB | 3,140 MB | 2,465 MB | 632 MB | 66 MB | 817 MB |
| 2 Translating (avg / peak) | 3,593 / 3,685 MB | 2,897 MB peak | 2,539 MB peak | 332 MB peak | 579 MB peak | 426 MB lowest |
| 3a Background, 5 s | 644 MB | 198 MB | 46 MB | 120 MB | 393 MB | 3,131 MB |
| 3b Camera open, 15 s (kernel) | 31 MB | 5 MB | – | – | 532 MB | 1,928 MB |
| 3c Camera open, 45 s (kernel) | 31 MB | 5 MB | – | – | 532 MB | 1,849 MB |
| 3d Back, one step | 653 MB | 95 MB | 72 MB | 8 MB | 502 MB | 2,676 MB |
| 4a Background again | 625 MB | 91 MB | 42 MB | 9 MB | 478 MB | 2,592 MB |
| 4b / 4c Cleared | process killed | | | | | 3,006 / 2,968 MB |

¹ The app was **not** killed: the same process ran from step 1 to step 4a, and the only kills in the logs are the
script's force-stop before step 0 and the `remove task` of step 4. On Android 17 a cached app behind the camera is
frozen (cached-app freezer), and a frozen process does not answer `dumpsys meminfo`. The Qwen run read the kernel's
totals instead (`run-as … cat /proc/<pid>/smaps_rollup`): **frozen behind the camera, Panelglass kept ~31 MB resident
and ~530 MB compressed in zRAM**, and came back on its own when brought to the front (3d).

What changed against 2026-09-27:

- **Native memory is up ~250–380 MB while translating** (Gemma 4: 905 against 591 MB; Google: 698 against 321 MB).
  Most of it is manga-ocr, which a Japanese page loads and the Chinese page never did; the PP-OCRv6 pass (a 1.8 MB
  model, 960 px input) is the smaller share. Separating the two needs an A/B run on the same page.
- **The GPU footprint is unchanged** (Gemma 4: 1,893 MB idle, 1,988 MB peak, against 1,912 / 1,998 MB; Qwen:
  2,465 / 2,539 MB, against 2,495 / 2,563 MB).
- **Qwen is still the heaviest**: 3,685 MB at peak, and the phone went down to **426 MB available** while it
  translated (732 MB on 2026-09-27, from a start ~0.9 GB lower this time). Nothing was killed, but this is the closest
  to the limit of any run; on phones of this size, Automatic could reasonably take the CPU for Qwen too.
- **Model loads are quicker**: Gemma 4 5.0 s (5.7 s), Qwen 11.5 s (14.1 s).
- **Release in the background still works**: `onTrimMemory 20: releasing idle models` at Home, GPU memory back to
  ~50 MB within 5 s, and the app at ~600–670 MB in the background whatever the engine.
- **Clearing from Recents frees everything**, as before.
- **The second pass costs ~300 ms per capture** (median 334 ms with Gemma 4 loaded, 290 ms with Google; 512 ms at most).
- **The WebView renderer is smaller**: 54–80 MB against 150–212 MB, likely a newer WebView on Android 17.
- **Tightest point**: with Gemma 4 translating, the phone dropped to 824 MB available (from a lower start than on
  2026-09-27); nothing was killed.

## Leak check, 2026-10-30

Whether a screen used over and over leaves anything behind. Same phone, now on Android 17, debug build 0.3.0 with
Gemma 4 E2B (GPU) as the engine. Each reading follows two forced garbage collections (`SIGUSR1` to the app's process)
and takes the object counts and heaps from `dumpsys meminfo`.

| Step | Activities | WebViews | Java heap | Native heap | Graphics | Total PSS |
|---|---|---|---|---|---|---|
| Sites, after launch | 1 | 1 (the spare) | 30.7 MB | 25.8 MB | 34.7 MB | 189 MB |
| Reader opened and closed 10 times | 1 | 1 | 30.4 MB | 668 MB | 1,938 MB | 2,975 MB |
| … 15 times | 1 | 2 | 33.4 MB | 402 MB | 1,944 MB | 2,971 MB |
| Home pressed (UI hidden) | – | – | – | – | – | 169 MB |
| Reader open, activity recreated 6 times (system font size) | **2** | 2 | 28.6 MB | 27.7 MB | 1,929 MB | 2,834 MB |
| … then the reader closed | 1 | 2 | 30.5 MB | 29.5 MB | 1,931 MB | 2,806 MB |
| Studio chapter screen | 1 | 1 | 30.4 MB | 80.1 MB | 134 MB | 475 MB |
| Review editor opened, a page turned, closed 6 times | 1 | 1 | 30.5 MB | 81.3 MB | 117 MB | 452 MB |

- **No leak that grows**: activities, windows, WebViews and the Java heap stay flat over 15 reader visits and 6 editor
  visits; the total is the same after 10 and 15 reader visits.
- **The on-device model stays loaded after the reader closes.** The reader pre-warms the selected engine when it
  opens, and Gemma 4 on the GPU holds ~2.8 GB (the graphics and native jump on the first visit). Leaving the reader for
  Sites or the Studio keeps it; it goes only when the app is hidden (`onTrimMemory`, here 2,971 → 169 MB) or under
  memory pressure. Not a leak (it does not grow), but held while no screen needs it. A fix would release it a few
  seconds after the reader closes, unless a Studio run uses it, at the cost of a reload on the next visit.
- **The reader's WebView keeps one earlier activity after a recreation**, until the reader closes: 2 activities after 6
  recreations, never more. Off screen, `ReaderScreen` only pauses its WebView, whose `MutableContextWrapper` still
  points at the activity that showed it; `ReaderViewModel.hostedWebView` re-points it only when the reader shows again.
  A fix would point the wrapper at the application context when the reader leaves the screen.

## Comparison

App PSS in MB (WebView renderer not included):

| Step | Google Translate | Gemma 4 E2B | Qwen 2.5 1.5B (GPU) | Qwen 2.5 1.5B (CPU) |
|---|---|---|---|---|
| 1a Home screen | 233 | 230 | 230 | 196 |
| 1b Reader, idle | 416 | 2,766 | 3,086 | 2,258 (597 pinned) |
| 2 Translating, peak | 723 | 3,081 | 3,425 | 2,598 |
| 3b Background, camera open | 514 | 497 | 575 | 1,500 (mid-generation) |
| 3d Back, translating again | 591 | 559 (cache hit) / 3,068 (reload) | 3,316 | 512 |
| 4 Cleared from Recents | 0 | 0 | 0 | 0 |

## Observations

1. **GPU weights are most of an LLM's footprint.** `GL mtrack` is ~1.9 GB for Gemma 4 and ~2.5 GB for Qwen; native
   heap adds 250–600 MB. The file size on disk is not a guide: the smaller Qwen file costs ~600 MB more RAM.
2. **An 8 GB-class phone is tight while an LLM translates.** With Qwen the device dropped to 732 MB available and
   the kernel moved ~440 MB of the app into zRAM; with Gemma 4 the lowest was 1.3 GB (0.9 GB in the manual run).
   Nothing was killed during any run, but a heavy app in the foreground alongside would compete for it.
3. **Models are released on every trip to the background.** `PanelglassApp.onTrimMemory` releases idle models from
   `TRIM_MEMORY_RUNNING_LOW` (10) upwards, and `TRIM_MEMORY_UI_HIDDEN` (20), sent whenever the app's UI leaves the
   screen, goes to `LocalEngines.onHidden` (`onTrimMemory 20: releasing idle models` in every run). The GPU memory is
   gone within 5 s of pressing Home. The benefit: in the background Panelglass costs ~500–600 MB whatever the engine, and was
   still cached (not killed) after 45 s of camera use. The cost: the next translation reloads the model (6.5 s for
   Gemma 4, 11.6 s for Qwen). Raising the threshold to `TRIM_MEMORY_BACKGROUND` (40) would keep the model across a
   quick app switch, at the price of ~2.5–3 GB held in the background and a higher chance of being killed.
4. **Clearing from Recents frees everything.** The app process and its WebView renderer are both killed ("remove
   task"); device available memory rises by the app's PSS within seconds.
5. **Google Translate is light.** ~0.6 GB while translating, no swap pressure, and translation itself is under a
   second; detection and OCR are the cost.
6. **The CPU backend trades speed for memory.** Qwen on the CPU pins ~600 MB instead of ~2.8 GB and leaves the
   phone 2.6 GB free while translating (0.7 GB on the GPU), but generates ~3× slower. On a phone with less RAM, or
   when the GPU backend is unavailable, it is the gentler choice; it is also why PSS alone overstates its cost. So
   Automatic takes it under 6 GB. The CPU speed on a low-end chip is not measured yet: at 3× this phone's times a
   dense group could approach the 50 s stage watchdog.
7. **The release used to be skipped mid-translation** (see [above](#going-to-the-background-mid-translation)): a
   model busy when the app was hidden stayed loaded about a minute. It now goes 5 s after its last call.

## Reproducing

With the phone attached over adb and a debuggable build installed:

```bash
P=com.smnexstudio.panelglass.debug                                 # the debug build's id (release: com.smnexstudio.panelglass)
adb shell am force-stop $P
adb shell am start -n $P/com.smnexstudio.panelglass.MainActivity --es url <chapter-url> --es engine GEMMA4_LOCAL   # or QWEN15_LOCAL / GOOGLE
adb logcat -s LocalLlmEngine:I | grep "LiteRT-LM loaded"           # wait for the model
adb shell dumpsys meminfo $P                                        # App Summary: TOTAL PSS, Graphics, Native Heap
adb shell grep MemAvailable /proc/meminfo
adb shell "ps -A -o PID,NAME | grep sandboxed_process"              # WebView renderers; the new one is ours
adb shell dumpsys meminfo <renderer-pid>
```

Then press Start, turn pages, press Home, open the camera
(`adb shell am start -a android.media.action.STILL_IMAGE_CAMERA`), and swipe the app away in Recents, taking a
`dumpsys meminfo` at each step. For the CPU backend, pick Settings › Models › Qwen runs on › CPU first. Clear
`cache/patches` (`adb shell run-as com.smnexstudio.panelglass.debug rm -rf cache/patches`) so pages are really generated.
The first page load after a launch can stay black on Bilibili: ⋮ › Reload loads it. The phone's default 256 KiB log
buffer loses pipeline lines within a minute; raise it for the run with `adb logcat -G 8M` and put it back afterwards
(`adb logcat -G 256K`).

For a leak check, repeat a screen (open and close it) many times, then collect garbage twice and read the counts:

```bash
PID=$(adb shell pidof $P)
adb shell run-as $P kill -10 $PID; sleep 2; adb shell run-as $P kill -10 $PID; sleep 2   # SIGUSR1: a full GC
adb shell dumpsys meminfo $P | grep -E "Activities|Views|WebViews|Java Heap|Native Heap|Graphics|TOTAL PSS"
adb shell settings put system font_scale 1.15; adb shell settings put system font_scale 1.0   # recreates the activity
```

A count that grows with the repetitions is a leak. A frozen background app answers `dumpsys meminfo $P` with nothing:
read its total from the system's list instead (`adb shell dumpsys meminfo | grep $P`).
