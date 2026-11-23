# LAN endpoint revision: security and verification

## Current transport design (local revision)

Cloud Custom AI remains HTTPS-only with a required key. LAN / Private Custom AI has separate endpoint, model and key identities; its key is optional and no Authorization header is sent when absent. No cloud configuration or credential is inherited.

Android network-security configuration now blocks cleartext globally, without domain exceptions or debug trust overrides. Reader navigation callbacks, initial HTTP resource rejection, mixed-content blocking and network-disabled popup probes remain defense in depth. The LAN exception no longer relaxes the platform policy used by WebView, including paths not exposed by navigation callbacks. This is an architectural policy change, not a claim of Android runtime verification.

Only CUSTOM_LAN HTTP POSTs use LanSocketTransport, a narrow HTTP/1.1 JSON socket transport. HTTPS requests retain the existing Retrofit/OkHttp TLS path and standard certificate validation. Every DNS answer must be private/loopback; the transport connects directly to one validated address and checks the connected peer before sending headers or body. Public, link-local, unspecified, multicast, CGNAT and mixed DNS answers are rejected. No proxy, redirects, cookies, connection pooling or automatic request replay are used. The transport requests identity encoding; unsupported compressed responses and ambiguous or duplicate headers fail closed. Replies, request bodies and HTTP metadata are bounded. Cancellation closes the channel-backed socket; connect/read timeouts and a coroutine deadline are configured. Host DNS resolution still depends on the system resolver.

## Verification and review limits

Fresh full engine JVM suite after integration: 170 tests, zero failures/errors/skips, BUILD SUCCESSFUL in 1m 22s. This includes eight socket-transport tests and existing cloud/LAN engine regressions. The initial cancellation test failed because ClosedByInterruptException escaped as IO failure; the wrapper was corrected and all eight tests then passed, including in the full engine run.

Fresh ManifestPolicyTest run: seven tests, zero failures/errors/skips, BUILD SUCCESSFUL in 36s. This verifies committed-source policy shape, not WebView runtime behavior. No full-project fresh regression run or APK build for this redesign is established by these test results.

The new HTTP parser and Android compatibility still require review. These JVM results do not establish production readiness or eliminate the need for maintainer approval. Native-speaker/rendered translation review is not established. The historical results below predate this redesign and must not be combined into a new fresh test total.

Per the project owner's instruction, emulator downloads, installation, configuration and execution, Android runtime testing and device installation are out of scope and must not be resumed.

## Packaging status for this redesign

Debug assembly attempts hit bounded execution/connector time limits, including a 45-second configuration attempt, a 90-second retry whose saved log did not establish successful completion, and a 40-second incremental attempt. No successful complete APK build, ZIP-integrity check or APK signature verification is claimed for this redesign. An APK file exists in the build directory but is not accepted as verification without successful build evidence. Historical APK hashes below do not identify this redesign. Full-project CI, maintainer review and Android compatibility remain outstanding; this review commit is NOT MERGE-READY. No emulator or device work was performed.

## Local verification status (2026-11-21)

For commit 53e3110, fresh JVM runs completed separately across six modules: model 79, engine 162, data 103, browser 69, UI 22 and settings 58: 493 tests with zero failures, errors or skips. These were separate module runs, not a full-project CI-equivalent execution. This paragraph records those earlier runs; the documentation edit itself did not rerun tests. CI completion and Android runtime behavior are not established by these results.

The standalone incremental `:app:assembleDebug` retry completed successfully in 19 seconds. The resulting APK passed ZIP-entry integrity checks and APK Signature Scheme v2 verification with the Android Debug certificate. Size: 359,198,786 bytes. SHA-256: `b75c51210c9805f40625fce087ea11d027fcb128f11ce98c59d35f0d616ba98e`. No installation or Android runtime verification was performed.

## Follow-up settings regression run

A focused JVM run of SettingsViewModelTest after adding three LAN configuration tests completed with 16 tests: 14 passed, 2 failed, zero errors or skips. All three new LAN tests passed. Existing tests swapTradesTheLanguagesAndRemembersThePair and savingTheKeyFromThatSheetCompletesTheSelection failed with a Dispatchers.Main setup/teardown concurrency error. This follow-up is not green and does not replace the historical 493-test evidence above. Test-scope cleanup requires investigation. No emulator or device was used.

After updating TestViewModels.clear() to await cancelled ViewModel jobs before database closure and Main dispatcher reset, a fresh focused rerun completed successfully: 16 tests, zero failures, errors or skips (2026-11-21T12:50:01Z report). This includes all three new LAN tests and both previously failing tests. This single successful rerun does not establish long-term flake elimination or full settings-suite coverage.

The subsequent full settings JVM suite completed with 61 tests: 60 passed, 1 failed, zero errors or skips. All 16 ViewModel tests passed. SettingsScreenTest.theBlockListsCanBeUpdatedNow timed out in the shared cancellation wait with queued Robolectric main-looper work; the initial cleanup fix therefore requires adjustment for Compose tests.

After making the cancellation wait drain queued Robolectric main-looper work, the full settings JVM suite passed: 61 tests, zero failures, errors or skips. Reports span 2026-11-21T12:53:25Z to 12:54:09Z; Gradle completed successfully in 1m 22s. This includes the three added LAN settings tests, all 16 ViewModel tests and all 25 SettingsScreen tests. This is settings-module JVM evidence only, not Android runtime verification or a fresh full-project CI run.
