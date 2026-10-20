# Capability matrix

Implementation date: 2026-10-21. Candidate DAT: 1.0.1. Official public reference reviewed at [Meta Android DAT](https://github.com/facebook/meta-wearables-dat-android/tree/f4371bc9f90249d51eb07d7f8a61e8d829294f16).

| Gate | Evidence / state |
| --- | --- |
| Independent build | Gradle 8.14.1, AGP 8.11.1, Kotlin 2.2.21, JDK 17, compile SDK 36; no composite SDK dependency |
| Android SDK artifact | Separate Maven `com.vmodal:vmodal-sdk-android:2.0.3`, built into isolated repository; SDK Kotlin 1.9.24 metadata consumed by Kotlin 2.2.21 |
| Maven distribution | Central coordinate returned 404; README and CI install pinned public SDK source as a separate Maven package |
| DAT artifacts | Core, speech, display, mock-device 1.0.1 resolved from Maven Central; mock only in mock variant/tests |
| Coroutines | Core pins 1.10.2; DAT transitively resolves Android graph to 1.11.0; core stays vendor-free |
| Simulator | Android API 35 Google APIs arm64-v8a locally; CI API 35 x86_64; simulated META_RAYBAN_DISPLAY |
| Speech | MDK final/partial transcript and completion, en-US, stop, errors and locale behavior covered by native tests |
| Native display | MDK accepts local bitmap payload; content read from official test server; real native button callback tested |
| Session ownership | One shared DAT session, explicit teardown/recreation; unsupported audio-only models rejected |
| DAT display ACK race | Native ACK can arrive before SDK waiter registration; bounded same-operation retry requires a real accepted response, with no reflection or captured-payload success fallback |
| Real phone/model/firmware | Pending: no physical phone/glasses attached |
| Meta AI app/account access | Pending: account registration, firmware compatibility, locale and deployment eligibility require physical proof |
| Real collection/ranking/image contract | Pending: no live authenticated collection selected; SDK wire contracts tested using deterministic responses |
| `img_file` profile | Not enabled; no live proof |
| Display limits | 600-side/2 MiB preview are conservative app policies, not verified hardware limits |
| Release constraints | Meta Speech is experimental; public source availability does not establish eligibility for a Meta production release channel |

P0 hardware gate remains open. Record exact phone OS, glasses firmware/model, Meta AI version, account/channel eligibility, locale, visible local bitmap, and speech end-of-utterance behavior before claiming complete glasses acceptance. DAT1.0.1 exposes no public utterance-completion event: its native completion leaves public speech state STARTED. Following Meta's sample, this example accepts the first nonblank final as a complete utterance. If target hardware emits segmented finals, full-utterance support is blocked until a reliable public completion contract is available. No unverified assembler or phone microphone fallback is used.
