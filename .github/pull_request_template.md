<!-- Pull requests go into `main`. -->

## What this changes

<!-- One or two sentences: what the user or the code does differently now, and why. Link the issue: Closes #123 -->

## How it was checked

<!-- Device or emulator, Android version, engine and languages you tried. Screenshots for UI changes. -->

## Checklist

- [ ] The base branch is `main`, and the branch is up to date with it.
- [ ] `./gradlew assembleDebug testDebugUnitTest :core:model:test` passes locally.
- [ ] New test classes are listed in `docs/TESTING.md`.
- [ ] New behaviour has unit tests (provider calls against MockWebServer, never a live API).
- [ ] UI changes were checked in all four themes, and new text is in `strings.xml` (see CONTRIBUTING).
- [ ] Rendering changes bump `PIPELINE_VERSION`; schema changes bump `PanelglassDb.version`.
- [ ] Changes to on-device model loading or backends were re-measured on a phone (`docs/MEMORY_USAGE.md`).
- [ ] No keys, page URLs or page text in logs; no machine paths in the repository.
