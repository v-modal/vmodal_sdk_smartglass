# Glasses voice search

An independent Android companion: glasses speech → VModal search → the first-ranked image on a native glasses display. Five modules isolate orchestration, Meta DAT, the Maven VModal SDK, Android image preparation, and the phone UI.

**Status:** implemented example; physical glasses and live collection acceptance remain pending. Simulator success establishes SDK integration, not real microphone accuracy, visible hardware rendering, retrieval relevance, or production eligibility. See [capabilities](docs/capability_matrix.md) and [acceptance](docs/acceptance.md).

## Build

Use JDK 17, Android SDK 36/build tools 36.0.0, and the checked-in Gradle 8.14.1 wrapper. Kotlin is 2.2.21, AGP 8.11.1, and every direct Meta DAT artifact is 1.0.1. Minimum Android is API 31 for the companion.

The VModal Android SDK **must be installed as a separate Maven package** (`com.vmodal:vmodal-sdk-android:2.0.3`). There is no SDK source/project/composite dependency. Set `-PvmodalMavenRepo=/absolute/path/to/repository` for an installed repository and optionally `-PvmodalSdkVersion=...` for a compatible version. Maven Central is also configured, but this coordinate returned 404 during implementation.

For a reproducible package installation before Central publication:

```bash
git clone https://github.com/v-modal/vmodal_sdk_android /tmp/vmodal-android-package
git -C /tmp/vmodal-android-package checkout --detach 22b28fb477d720a0abf771ac83a91cb7659819f1
/tmp/vmodal-android-package/gradlew -p /tmp/vmodal-android-package --no-daemon publishAllPublicationsToCompatibilityRepository

# From this example directory:
bash test.sh test -PvmodalMavenRepo=/tmp/vmodal-android-package/build/compat-repo
ANDROID_SERIAL=emulator-5556 bash test.sh mock -PvmodalMavenRepo=/tmp/vmodal-android-package/build/compat-repo
```

CI installs that pinned package separately, then tests the consumer through its Maven coordinates. The existing streaming SDK and old simulator retain their original DAT pin.

## Phone and glasses

Create a Meta Wearables developer app, configure its `vmodal-voice-search` registration callback scheme, enable the required experimental speech/display access, and pair supported display glasses through Meta AI. Developer Mode registration can use empty app metadata. Outside Developer Mode, provide the assigned `-PmetaApplicationId=...` and `-PmetaClientToken=...` manifest settings through your local Gradle configuration. Do not commit their values. Build/install:

```bash
bash test.sh device -PmetaApplicationId=YOUR_META_APP_ID -PvmodalMavenRepo=/tmp/vmodal-android-package/build/compat-repo
```

Open **VModal Glasses Voice Search**. Authenticate with a runtime VModal API key, choose a visible indexed video collection, enter its stream, register Meta glasses, allow the glasses microphone, and Connect. Tap Listen on the phone or New search on the glasses. Speak “Find white shirt in my collection.” The complete final transcript is the query; the selected phone collection determines its scope. Credentials remain in memory. No upload or indexing occurs.

The current collection profile is `vid_file` with `vid_img` lookup. Standalone image collections are gated pending live contract proof. Camera/audio-only glasses are rejected. Use Retry for the same accepted query, New search for a new turn, and Dismiss to clear. Backgrounding, locking, disconnecting, or logout cancels work and stops the shared session. Rotation retains the ViewModel owner. After returning to foreground, explicitly Connect again.

## Tests

`test.sh test` runs native JVM contracts, Android lint, release/mock builds, and dependency/import boundaries. `test.sh mock` requires an unlocked API 35 emulator/device and exercises real Meta Mock Device Kit speech/display, a real SDK against a local mocked VModal HTTP endpoint, image preparation, rotation, and 20 sequential searches. Production variants exclude the mock kit. Reports live in each module's ignored `build/reports` and `build/outputs/androidTest-results` directories. Tests shut down their mock servers and sessions; stop a locally started emulator with `adb -s emulator-5556 emu kill`.

`test.sh live` installs the same real-device variant as `device`; it does not run an unauthenticated smoke check or certify acceptance. Follow [the explicit live procedure](docs/acceptance.md), supplying credentials only in the foreground UI. No physical device, credentials, or backend are needed by offline/mock tests.
