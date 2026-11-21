# LAN endpoint revision: security and verification

Cloud Custom AI remains HTTPS-only with a required key. LAN / Private Custom AI has separate endpoint, model and key identities; its key is optional and no Authorization header is sent when absent. No cloud configuration or credential is inherited.

LAN HTTP uses a dedicated non-proxying client. DNS results must all be RFC1918 or loopback IPv4, or unique-local or loopback IPv6. Empty and mixed public/private answers are rejected. The connected peer is checked before sending HTTP request headers/body, including IP-literal URLs. Link-local, unspecified, multicast, public and CGNAT addresses are not allowed. HTTPS retains standard certificate validation. Redirects remain disabled and response caps remain enabled.

Reader callbacks explicitly reject HTTP downgrade navigation and initial HTTP resources independently of ad blocking. Popup URL probes cannot load network resources. These callback tests do not prove redirect-hop safety.

## Remaining release blocker

The Android base configuration still permits cleartext. WebView HTTPS-to-HTTP main-frame, POST, subresource and service-worker redirect safety remains unverified. The revision is NOT ready for merge until this security requirement is resolved. Do not describe Robolectric callback tests or successful APK packaging as device verification. Per the project owner’s instruction, Android runtime testing, emulator setup, system-image downloads and device installation are out of scope for this work; do not resume them.

Additional pending work includes translation review, complete settings-flow regression coverage and full CI-equivalent execution. PRIVACY.md and architecture NFR-2 now document the LAN HTTP exception and unresolved WebView redirect risk; maintainer review is still required. Local endpoint normalization also rejects non-local HTTP host names and addresses before saving, while transport DNS and peer checks remain authoritative. The input helper now rejects link-local and CGNAT addresses, matching the transport policy, and accepts expanded IPv6 loopback notation. DNS and connected-peer validation remain necessary for accepted hostnames.

## Local verification status (2026-11-21)

For commit 53e3110, fresh JVM runs completed separately across six modules: model 79, engine 162, data 103, browser 69, UI 22 and settings 58: 493 tests with zero failures, errors or skips. These were separate module runs, not a full-project CI-equivalent execution. This paragraph records those earlier runs; the documentation edit itself did not rerun tests. CI completion and Android runtime behavior are not established by these results.

The standalone incremental `:app:assembleDebug` retry completed successfully in 19 seconds. The resulting APK passed ZIP-entry integrity checks and APK Signature Scheme v2 verification with the Android Debug certificate. Size: 359,198,786 bytes. SHA-256: `b75c51210c9805f40625fce087ea11d027fcb128f11ce98c59d35f0d616ba98e`. No installation or Android runtime verification was performed.

## Follow-up settings regression run

A focused JVM run of SettingsViewModelTest after adding three LAN configuration tests completed with 16 tests: 14 passed, 2 failed, zero errors or skips. All three new LAN tests passed. Existing tests swapTradesTheLanguagesAndRemembersThePair and savingTheKeyFromThatSheetCompletesTheSelection failed with a Dispatchers.Main setup/teardown concurrency error. This follow-up is not green and does not replace the historical 493-test evidence above. Test-scope cleanup requires investigation. No emulator or device was used.

After updating TestViewModels.clear() to await cancelled ViewModel jobs before database closure and Main dispatcher reset, a fresh focused rerun completed successfully: 16 tests, zero failures, errors or skips (2026-11-21T12:50:01Z report). This includes all three new LAN tests and both previously failing tests. This single successful rerun does not establish long-term flake elimination or full settings-suite coverage.

The subsequent full settings JVM suite completed with 61 tests: 60 passed, 1 failed, zero errors or skips. All 16 ViewModel tests passed. SettingsScreenTest.theBlockListsCanBeUpdatedNow timed out in the shared cancellation wait with queued Robolectric main-looper work; the initial cleanup fix therefore requires adjustment for Compose tests.

After making the cancellation wait drain queued Robolectric main-looper work, the full settings JVM suite passed: 61 tests, zero failures, errors or skips. Reports span 2026-11-21T12:53:25Z to 12:54:09Z; Gradle completed successfully in 1m 22s. This includes the three added LAN settings tests, all 16 ViewModel tests and all 25 SettingsScreen tests. This is settings-module JVM evidence only, not Android runtime verification or a fresh full-project CI run.
