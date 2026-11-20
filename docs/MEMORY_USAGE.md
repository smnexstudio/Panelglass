# Memory Usage & Benchmarks

This document details the RAM consumption, GPU allocations, and heap behaviors of Panelglass across its supported translation engines. Benchmarks were conducted on physical hardware under active reading, continuous translating, background multi-tasking (camera stress test), and task clearance.

For architectural memory trimming and lifecycle policies, see [ARCHITECTURE.md](ARCHITECTURE.md#concurrency-watchdogs--memory-budgets).

---

## Contents

- [Executive Summary](#executive-summary)
- [Test Environment & Hardware](#test-environment--hardware)
- [Methodology & Metrics](#methodology--metrics)
- [Engine Benchmarks](#engine-benchmarks)
  - [Gemma 4 E2B (GPU Backend)](#gemma-4-e2b-gpu-backend)
  - [Qwen 2.5 1.5B (GPU Backend)](#qwen-25-15b-gpu-backend)
  - [Google ML Kit (On-Device)](#google-ml-kit-on-device)
  - [Qwen 2.5 1.5B (CPU Backend)](#qwen-25-15b-cpu-backend)
- [Background Lifecycle & Camera Stress Test](#background-lifecycle--camera-stress-test)
- [Second Pass & manga-ocr Benchmark (2026-10-17)](#second-pass--manga-ocr-benchmark-2026-10-17)
- [Leak Check & Activity Recreation (2026-10-30)](#leak-check--activity-recreation-2026-10-30)
- [Whole-Page Scan 96 MB Memory Budget](#whole-page-scan-96-mb-memory-budget)
- [Comparative Matrix](#comparative-matrix)
- [Key Engineering Observations](#key-engineering-observations)
- [Reproduction Protocol & ADB Commands](#reproduction-protocol--adb-commands)

---

## Executive Summary

| Metric | Google ML Kit | Gemma 4 E2B (GPU) | Qwen 2.5 1.5B (GPU) | Qwen 2.5 1.5B (CPU) |
|---|---|---|---|---|
| **Reader Open, Idle (App PSS)** | 416 MB | 2,766 MB | 3,086 MB | 2,258 MB |
| **Of Which Pinned (Private Dirty)** | 325 MB | 2,422 MB | 2,786 MB | 597 MB |
| **Translating, Avg / Peak (App PSS)** | 580 / 723 MB | 2,859 / 3,081 MB | 3,327 / 3,425 MB | 2,504 / 2,598 MB |
| **Of Which GPU (Peak Graphics)** | 119 MB | 1,998 MB | 2,563 MB | 130 MB |
| **WebView Renderer Process** | ~210 MB | ~180 MB | ~180 MB | ~200 MB |
| **Lowest Device `MemAvailable`** | 2,665 MB | 1,303 MB | 732 MB | 2,578 MB |
| **Background (Camera Open, App PSS)**| 514 MB | 497 MB | 570 MB | 492–2,337 MB¹ |
| **Model Load Time** | Instant | 5.7 s (11.8 s cold) | 14.1 s | 0.9 s (5.7 s cold) |
| **Generation Time (4-Bubble Batch)** | 0.5–0.8 s | 7–10 s | 3.7 s | 12.6 s |
| **Cleared from Recents** | Process killed, 100% memory reclaimed | Process killed, 100% memory reclaimed | Process killed, 100% memory reclaimed | Process killed, 100% memory reclaimed |

*(¹ When Home is pressed mid-generation, the model is released 5 s after the generation's last call; an idle model is released at once).*

### Key Takeaways
- **On-Device LLMs Dominate Memory:** Loading either Gemma 4 or Qwen elevates app PSS from ~230 MB to ~2.8–3.1 GB, primarily in GPU VRAM buffers.
- **Qwen GPU Memory Footprint:** Despite a smaller disk weight file (1.6 GB vs. 2.6 GB for Gemma 4), Qwen's quantized Q8 weights expand to ~2.5 GB on the GPU (vs. ~1.9 GB for Gemma 4).
- **CPU Backend Drastically Reduces Pinned RAM:** Running Qwen on CPU pins only 597 MB of private dirty memory (weights are memory-mapped clean pages), leaving over 2.5 GB free for the device. Generation speed is ~3× slower.
- **Prompt Background Unloading:** Releasing the model when the UI is hidden (at once if idle, 5 s after its last call if busy) drops memory to ~500 MB across all engines, allowing resource-heavy apps like the camera to run without killing Panelglass.

---

## Test Environment & Hardware

| Property | Details |
|---|---|
| **Hardware Device** | Motorola Edge 60 Pro (`XT2507-2`) |
| **SoC & Architecture**| MediaTek MT6897 (8 cores, arm64-v8a) |
| **GPU & Graphics** | ARM Mali-G615 MC6, OpenGL ES 3.2 (Driver r44p1) |
| **Total Memory** | 7.26 GiB Physical RAM (`MemTotal`: 7,616,204 kB) + 5.4 GiB zRAM Swap |
| **Display Resolution**| 1220 × 2712 px (450 dpi) |
| **Android Version** | Android 16 (API 36) / Android 17 (API 37 re-run) |
| **WebView Version** | Android System WebView 153.0.8010.39 |
| **Build Type** | Panelglass Debug Build (`com.smnexstudio.panelglass.debug`) |

---

## Methodology & Metrics

### Benchmarking Steps
1. **Force Stop:** App terminated; baseline device `MemAvailable` recorded.
2. **Cold Launch:** App opened to home screen (Sites).
3. **Reader Settle:** Reader opened; engine, OCR, and detector pre-warmed.
4. **Translation Run:** Reader translates 3 consecutive pages spaced 35 s apart.
5. **Background Stress:** Home pressed; device camera app launched and held for 45 s.
6. **Return to Front:** Panelglass restored to foreground; page translated.
7. **Task Clearance:** Swiped away from Android Recents; full reclamation verified.

### Metrics Recorded
- **App PSS (Proportional Set Size):** Comprehensive memory footprint of the main app process.
- **GPU (Graphics):** `GL mtrack` + `EGL mtrack`, where LiteRT-LM OpenCL buffers reside.
- **Private Dirty:** Pinned memory unique to Panelglass that the kernel cannot reclaim without swapping to zRAM.
- **Native Heap:** Memory allocated by native runtimes (ONNX Runtime, LiteRT-LM CPU side, Bitmaps).
- **Device Available (`MemAvailable`):** Total free memory remaining on the device.

---

## Engine Benchmarks

### Gemma 4 E2B (GPU Backend)
- **Model:** `gemma-4-e2b-it.litertlm` (2.59 GB)
- **Load Time:** 5.7 s warm, 11.8 s cold

| Step / State | App PSS | GPU (Graphics) | Native Heap | Swap (zRAM) | Device Available |
|---|---|---|---|---|---|
| **0. Not Running** | — | — | — | — | 3,389 MB |
| **1a. Home Screen** | 230 MB | 36 MB | 25 MB | 0 MB | 3,417 MB |
| **1b. Reader, Pre-warmed Idle** | 2,766 MB | 1,912 MB | 429 MB | 55 MB | 1,644 MB |
| **2. Translating (Avg / Peak)** | 2,859 / 3,081 MB | 1,998 MB | 591 MB | 192 MB | **1,303 MB** |
| **3a. Background (5 s)** | 538 MB | 56 MB | 259 MB | 104 MB | 3,226 MB |
| **3b. Camera Open (15 s)** | 497 MB | 46 MB | 231 MB | 103 MB | 2,259 MB |
| **3c. Camera Open (45 s)** | 497 MB | 46 MB | 231 MB | 103 MB | 2,240 MB |
| **3d. Restored (Page Turn)** | 559 MB | 129 MB | 210 MB | 97 MB | 3,297 MB |
| **4. Cleared from Recents** | Process Killed | — | — | — | 3,703 MB |

---

### Qwen 2.5 1.5B (GPU Backend)
- **Model:** `qwen2.5-1.5b-instruct-q8.litertlm` (1.60 GB)
- **Load Time:** 14.1 s

| Step / State | App PSS | GPU (Graphics) | Native Heap | Swap (zRAM) | Device Available |
|---|---|---|---|---|---|
| **0. Not Running** | — | — | — | — | 3,656 MB |
| **1a. Home Screen** | 230 MB | 36 MB | 25 MB | 0 MB | 3,419 MB |
| **1b. Reader, Pre-warmed Idle** | 3,086 MB | 2,495 MB | 261 MB | 249 MB | 1,200 MB |
| **2. Translating (Avg / Peak)** | 3,327 / 3,425 MB | 2,563 MB | 453 MB | 439 MB | **732 MB** |
| **3a. Background (5 s)** | 588 MB | 54 MB | 101 MB | 334 MB | 3,304 MB |
| **3b. Camera Open (15 s)** | 575 MB | 43 MB | 101 MB | 333 MB | 2,148 MB |
| **3c. Camera Open (45 s)** | 570 MB | 43 MB | 86 MB | 347 MB | 1,853 MB |
| **3d. Restored (Page Turn)** | 3,316 MB | 2,504 MB | 255 MB | 406 MB | 1,348 MB |
| **4. Cleared from Recents** | Process Killed | — | — | — | 3,853 MB |

---

### Google ML Kit (On-Device)
- **Model:** On-device neural translation pack (~30 MB)
- **Load Time:** Sub-second (Zero pre-load overhead)

| Step / State | App PSS | GPU (Graphics) | Native Heap | Swap (zRAM) | Device Available |
|---|---|---|---|---|---|
| **0. Not Running** | — | — | — | — | 3,413 MB |
| **1a. Home Screen** | 233 MB | 36 MB | 25 MB | 0 MB | 3,383 MB |
| **1b. Reader, Idle** | 416 MB | 104 MB | 88 MB | 0 MB | 3,006 MB |
| **2. Translating (Avg / Peak)** | 580 / 723 MB | 119 MB | 321 MB | 0 MB | **2,665 MB** |
| **3a. Background (5 s)** | 571 MB | 43 MB | 304 MB | 1 MB | 2,577 MB |
| **3b. Camera Open (45 s)** | 514 MB | 29 MB | 299 MB | 61 MB | 1,874 MB |
| **3d. Restored (Page Turn)** | 591 MB | 110 MB | 249 MB | 124 MB | 2,954 MB |
| **4. Cleared from Recents** | Process Killed | — | — | — | 3,540 MB |

---

### Qwen 2.5 1.5B (CPU Backend)
- **Model:** `qwen2.5-1.5b-instruct-q8.litertlm` (CPU Execution)
- **Load Time:** 0.9 s (weights memory-mapped)

| Step / State | App PSS | Private Dirty | GPU (Graphics) | Native Heap | Device Available |
|---|---|---|---|---|---|
| **1b. Reader, Pre-warmed Idle** | 2,258 MB | 597 MB | 120 MB | 418 MB | 2,940 MB |
| **2. Translating (Avg / Peak)** | 2,504 / 2,598 MB| ~600 MB | 130 MB | 738 MB | **2,578 MB** |
| **3b. Camera Open (15 s)** | 1,500 MB | — | 29 MB | 233 MB | 1,907 MB |
| **3d. Restored (Page Turn)** | 512 MB | — | 106 MB | 42 MB | 3,251 MB |

> [!TIP]
> **Why CPU Mode Conserves RAM:** While App PSS reads 2.5 GB, 1.54 GB consists of `Other mmap` (private clean pages). When other applications (like the Camera) request RAM, the Android kernel immediately discards these clean pages without swapping.

---

## Background Lifecycle & Camera Stress Test

### The Mid-Translation Background Race
When users press Home while an on-device model is actively translating:

| Scenario | Unpatched Behavior | Current Implemented Fix (`LocalEngines.onHidden`) |
|---|---|---|
| **Home pressed after translation** | Dropped to ~440 MB in <5 s | Idle model released at once: ~440 MB in <5 s |
| **Home pressed during generation** | Held 3.2 GB in VRAM for 60+ s | Finishes ongoing translation in background, waits 5 s grace window, then cleanly unloads model |

The 5-second grace window (`releaseGraceMs`) ensures that subsequent bubbles for the current screen finish rendering without triggering a costly reload, while guaranteeing memory is released promptly if the user remains outside the app.

---

## Second Pass & manga-ocr Benchmark (2026-10-17)

Testing on Android 17 with the full pipeline (Japanese → English, `manga-ocr` active, and `PP-OCRv6 tiny` second pass enabled):

- **manga-ocr Overhead:** Adds ~250–380 MB of native memory allocations for ONNX Runtime sessions.
- **PP-OCRv6 Second Pass:** Adds ~300 ms processing time per frame and ~1.8 MB footprint.
- **Peak Device Strain:** Qwen on GPU dropped device available memory to **426 MB** (the tightest point observed). Despite this, Android's LMK (Low Memory Killer) was never triggered.

---

## Leak Check & Activity Recreation (2026-10-30)

To verify that continuous reading does not leak memory over long sessions:

| Test Action | Activities | WebViews | Java Heap | Native Heap | Graphics | Total PSS |
|---|---|---|---|---|---|---|
| **Cold Start** | 1 | 1 | 30.7 MB | 25.8 MB | 34.7 MB | 189 MB |
| **Reader Opened/Closed 10×** | 1 | 1 | 30.4 MB | 668 MB | 1,938 MB | 2,975 MB |
| **Reader Opened/Closed 15×** | 1 | 2 | 33.4 MB | 402 MB | 1,944 MB | 2,971 MB |
| **Activity Recreated 6× (Font Scale)**| 2* | 2 | 28.6 MB | 27.7 MB | 1,929 MB | 2,834 MB |
| **Studio Chapter Review 6×** | 1 | 1 | 30.5 MB | 81.3 MB | 117 MB | 452 MB |

*(Java heap stays flat across repeated cycles. Two cases are known and open: the on-device model stays loaded after the reader closes (the Native and Graphics columns), and the off-screen reader WebView keeps one activity and one WebView alive (the 2s). Neither grows with further cycles.)*

---

## Whole-Page Scan 96 MB Memory Budget

During automated whole-page scanning:
- Normal 3-viewport pruning is suspended to keep patches visible when scrolling back.
- Patches are bounded by a strict **96 MB decoded bitmap budget** (`ReaderViewModel.PAGE_PATCH_BUDGET_BYTES`).
- When total patch memory exceeds 96 MB, patches farthest from the current viewport are pruned first.
- Bitmap pixels live in native memory, not the Java heap, so the budget does not show in the Java Heap column; the patches are dropped when the page changes or the reader exits.

---

## Comparative Matrix

| Execution Profile | Memory Impact (PSS) | Pinned Memory | Generation Speed | Recommended For |
|---|---|---|---|---|
| **Google ML Kit** | Minimal (~600 MB) | Low (~300 MB) | <1.0 s | Everyday reading, battery saving |
| **Gemma 4 E2B (GPU)** | High (~3.0 GB) | High (~2.4 GB) | ~7–10 s | High-end devices (8 GB+ RAM) |
| **Qwen 2.5 1.5B (GPU)**| Very High (~3.4 GB)| Very High (~2.8 GB)| ~3.7 s | Fast offline AI dialogue |
| **Qwen 2.5 1.5B (CPU)**| Moderate (~2.5 GB) | Minimal (~600 MB) | ~12–18 s | Mid-range devices (6 GB RAM) |

---

## Key Engineering Observations

1. **File Size Does Not Equal RAM Footprint:** Qwen's 1.6 GB model file consumes ~2.5 GB of GPU memory, whereas Gemma 4's 2.6 GB file uses ~1.9 GB.
2. **GPU Memory is Pinned:** Android cannot swap GPU allocations (`GL mtrack`); only native clean pages and CPU-mapped files can be reclaimed dynamically.
3. **Task Clearance Guarantees:** Swiping Panelglass away in Android Recents terminates both the main app process and the isolated WebView sandbox process, instantly freeing 100% of memory.

---

## Reproduction Protocol & ADB Commands

To re-run memory benchmarks on an attached Android device:

```bash
# Set package name
P=com.smnexstudio.panelglass.debug

# Force stop and launch reader with Gemma 4
adb shell am force-stop $P
adb shell am start -n $P/com.smnexstudio.panelglass.MainActivity \
    --es url "https://example.com/manga/chapter-1" \
    --es engine GEMMA4_LOCAL

# Monitor model loading
adb logcat -s LocalLlmEngine:I | grep "LiteRT-LM loaded"

# Sample memory snapshot
adb shell dumpsys meminfo $P
adb shell grep MemAvailable /proc/meminfo

# Trigger camera stress test
adb shell am start -a android.media.action.STILL_IMAGE_CAMERA

# Clear cached patches first (a cached page never calls the engine):
#   adb shell run-as $P rm -rf cache/patches

# Run garbage collection and check object counts
adb shell run-as $P kill -10 $(adb shell pidof $P)
adb shell dumpsys meminfo $P | grep -E "Activities|Views|WebViews|Java Heap|Native Heap|Graphics|TOTAL PSS"
```
