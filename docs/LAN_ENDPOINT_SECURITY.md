# LAN endpoint revision: security and verification

Cloud Custom AI remains HTTPS-only with a required key. LAN / Private Custom AI has separate endpoint, model and key identities; its key is optional and no Authorization header is sent when absent. No cloud configuration or credential is inherited.

LAN HTTP uses a dedicated non-proxying client. DNS results must all be RFC1918 or loopback IPv4, or unique-local or loopback IPv6. Empty and mixed public/private answers are rejected. The connected peer is checked before sending HTTP request headers/body, including IP-literal URLs. Link-local, unspecified, multicast, public and CGNAT addresses are not allowed. HTTPS retains standard certificate validation. Redirects remain disabled and response caps remain enabled.

Reader callbacks explicitly reject HTTP downgrade navigation and initial HTTP resources independently of ad blocking. Popup URL probes cannot load network resources. These callback tests do not prove redirect-hop safety.

## Remaining release blocker

The Android base configuration still permits cleartext. No connected device or installed emulator/system image was found in the inspected VPS locations. Real Android tests must demonstrate that HTTPS-to-HTTP main-frame, POST, subresource and service-worker redirects never contact an HTTP destination. The revision is NOT ready for merge until that requirement is resolved. Do not describe Robolectric callback tests as device verification.

Additional pending work includes translation review, complete settings-flow regression coverage, full CI-equivalent execution and a standalone APK build. Coordinate PRIVACY.md and architecture NFR-2 updates with the maintainer after the design is accepted.
