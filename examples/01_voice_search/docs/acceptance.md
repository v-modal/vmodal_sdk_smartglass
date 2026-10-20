# Acceptance and measurements

The implementation covers P1–P5 and reproducible P6 local/mock commands. P0 and P6 physical/live acceptance remain pending. Do not infer hardware success from emulator tests.

| Block | Delivered | Remaining gate |
| --- | --- | --- |
| P0 | Independent Maven consumer, resolved DAT artifacts, real MDK probes | Physical microphone/display, account eligibility, live collection contract |
| P1 | Neutral contracts, controller and deterministic race/deadline tests | None for the offline core |
| P2 | SDK session/search/image adapters and transport-backed wire tests | Explicit authenticated live collection proof |
| P3 | Bounded Android decoding, EXIF orientation, fit and native tests | Hardware memory measurement |
| P4 | Shared DAT session, renewed per-turn speech, native display/actions and MDK tests | Real firmware/locale and whole-utterance final semantics |
| P5 | Foreground phone setup, collection choice, lifecycle and real-adapter composition | Wearer-visible hardware workflow |
| P6 | Offline/mock/device/live commands, CI and 20 simulated searches | 20 hardware turns, visible latency/memory measurements |

## Recorded local verification — 2026-10-21

| Check | Result |
| --- | --- |
| New example offline | Pass: 32 core + 14 VModal + 5 Meta mapping/submission tests in each flavor (56 JVM executions), lint, APKs and dependency boundaries |
| New Meta simulator | Pass: 6 MDK tests, including identical final-only consecutive turns within one session |
| Image instrumentation | Pass: 4 Android tests |
| App instrumentation | Pass: 3 Android tests, including native Listen and 20 sequential rank-1 searches |
| Existing Smartglass offline | Pass: existing lint/JVM suite |
| Existing camera simulator | Pass: full MDK lifecycle instrumentation |
| Existing Rust core | Pass: 28 native tests including ffprobe and encrypted SRT integration |
| Existing Android SDK | Pass: `bash test.sh test`, including routes, regression/compatibility/transport suites, cookbook and docs |
| Workflow/shell checks | Pass: actionlint, shellcheck, diff whitespace checks |
| Public candidate secret scan | Pass: zero verified or unverified findings |

Local instrumentation used Android 15/API 35 Google APIs arm64-v8a on AVD `VmodalVoice35`. The Maven consumer used SDK 2.0.3 installed from pinned public commit `22b28fb477d720a0abf771ac83a91cb7659819f1`. No live VModal service or physical glasses were contacted by these tests.

Run from the example directory with a separately installed VModal Maven package:

```bash
bash test.sh test -PvmodalMavenRepo=/absolute/path/to/maven-repo
ANDROID_SERIAL=emulator-5556 bash test.sh mock -PvmodalMavenRepo=/absolute/path/to/maven-repo
bash test.sh device -PmetaApplicationId=YOUR_META_APP_ID -PvmodalMavenRepo=/absolute/path/to/maven-repo
```

Offline checks exercise frozen identity/scope, blank/partial/duplicate finals, stale actions/results/render, cancellation, deadlines, empty versus malformed rank 1, SDK request fields, timestamp/item aliases, strict bulk indices, expiry refresh and byte limits. Android tests exercise EXIF orientation, fit, corrupt/oversized input, cancellation, actual MDK capability/permission/session behavior, speech/display/actions, app rotation, and a complete composition with the real SDK HTTP transport. The VModal endpoint alone is mocked in the full flow. Twenty sequential simulated utterances assert exactly twenty searches, rank-1-only image lookups and no retained microphone listening.

Physical/live checklist:

1. Record phone/Android, glasses model/firmware, Meta AI version, DAT1.0.1 account/channel eligibility and supported locale. Confirm speech/display can coexist in one session.
2. Prepare an indexed collection with distinguishable shirt/unrelated content through existing upload/index tools. Enter runtime credentials on the phone and select its visible collection/stream. Record advertised version and expected backend rank order.
3. Confirm Ready on glasses. Use its native New search action, speak “Find white shirt in my collection”, and confirm a glasses-microphone final transcript on the phone. Establish whether hardware finals are whole utterances. DAT1.0.1 has no public completion event; if it emits final segments, this build cannot claim full-utterance support and the physical gate fails.
4. Confirm one SDK request with offset0/limit1 and frozen identity/scope/version. Confirm the displayed image is that response's first hit. Do not collect credentials, signed URLs, or full raw backend payloads as evidence.
5. Repeat with no result, denied microphone, unsupported locale, corrupt/missing rank-1 image, expired image URL, network loss, disconnect, rapid new turn, background/lock, foreground reconnect and logout. Old results/actions must never overwrite a new screen.
6. Perform 20 sequential hardware turns. Record session/collector count, sustained memory growth, stage latency and final-transcript→visible-result P50/P95. Initial P95 target is 5 seconds on a documented good-network setup; no unmeasured latency guarantee is made.

Measurements pending: physical speech quality, native bitmap transfer sizing/lifetime, visible rendering, live retrieval relevance/order, 20-turn hardware memory, latency percentiles and deployment eligibility. `device`/`live` install the real variant and print this procedure; their successful installation is not an acceptance pass.
