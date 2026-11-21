# LAN endpoint revision: security and verification

Cloud Custom AI remains HTTPS-only with a required key. LAN / Private Custom AI has separate endpoint, model and key identities; its key is optional and no Authorization header is sent when absent. No cloud configuration or credential is inherited.

LAN HTTP uses a dedicated non-proxying client. DNS results must all be RFC1918 or loopback IPv4, or unique-local or loopback IPv6. Empty and mixed public/private answers are rejected. The connected peer is checked before sending HTTP request headers/body, including IP-literal URLs. Link-local, unspecified, multicast, public and CGNAT addresses are not allowed. HTTPS retains standard certificate validation. Redirects remain disabled and response caps remain enabled.

Reader callbacks explicitly reject HTTP downgrade navigation and initial HTTP resources independently of ad blocking. Popup URL probes cannot load network resources. These callback tests do not prove redirect-hop safety.

## Remaining release blocker

The Android base configuration still permits cleartext. WebView HTTPS-to-HTTP main-frame, POST, subresource and service-worker redirect safety remains unverified. The revision is NOT ready for merge until this security requirement is resolved. Do not describe Robolectric callback tests or successful APK packaging as device verification. Per the project owner’s instruction, Android runtime testing, emulator setup, system-image downloads and device installation are out of scope for this work; do not resume them.

Additional pending work includes translation review, complete settings-flow regression coverage and full CI-equivalent execution. PRIVACY.md and architecture NFR-2 now document the LAN HTTP exception and unresolved WebView redirect risk; maintainer review is still required. Local endpoint normalization also rejects non-local HTTP host names and addresses before saving, while transport DNS and peer checks remain authoritative. The input helper now rejects link-local and CGNAT addresses, matching the transport policy, and accepts expanded IPv6 loopback notation. DNS and connected-peer validation remain necessary for accepted hostnames.

## Local verification status (2026-11-21)

Existing JVM test reports contain 491 tests with zero failures, errors or skips: model 79, engine 160, data 103, browser 69, UI 22 and settings 58. This is a report inspection, not a fresh rerun or full CI-equivalent result.

The standalone incremental `:app:assembleDebug` retry completed successfully in 19 seconds. The resulting APK passed ZIP-entry integrity checks and APK Signature Scheme v2 verification with the Android Debug certificate. Size: 359,198,786 bytes. SHA-256: `b75c51210c9805f40625fce087ea11d027fcb128f11ce98c59d35f0d616ba98e`. No installation or Android runtime verification was performed.
