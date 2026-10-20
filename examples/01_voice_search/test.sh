#!/usr/bin/env bash
set -euo pipefail
help='
  Voice-search verification. Run from this directory with JDK17 and Android SDK36.
  sdk_android must be installed as a separate Maven package.

  Examples:
    bash test.sh test -PvmodalMavenRepo=/absolute/path/to/maven-repo
    ANDROID_SERIAL=emulator-5556 bash test.sh mock -PvmodalMavenRepo=/absolute/path/to/maven-repo
    bash test.sh device -PmetaApplicationId=YOUR_META_APP_ID -PvmodalMavenRepo=/absolute/path/to/maven-repo
    bash test.sh live -PmetaApplicationId=YOUR_META_APP_ID -PvmodalMavenRepo=/absolute/path/to/maven-repo

  test: JVM contracts, all module lint, mock APK and production release build.
  mock: real DAT1.0.1 Mock Device Kit, real SDK against a local mock HTTP endpoint,
        image instrumentation, app wiring and 20 sequential voice searches.
  device/live: install production-debug APK for the manual acceptance procedure.
        Supply credentials and collection only through the foreground phone UI.
        These commands do not certify physical/live acceptance automatically.
'
voice_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

voice_gradle() {
  local help='
    ## Usage:
      voice_gradle :core:test -PvmodalMavenRepo=/absolute/path/to/maven-repo
  '
  (cd "$voice_dir" && ./gradlew --no-daemon "$@")
}

voice_test() {
  local help='
    ## Usage:
      bash test.sh test -PvmodalMavenRepo=/absolute/path/to/maven-repo
  '
  bash -n "$voice_dir/test.sh"
  voice_gradle "$@" :core:test :adapters:vmodal:test \
    :adapters:meta:testProductionDebugUnitTest :adapters:meta:testMockDebugUnitTest \
    :adapters:meta:lintProductionDebug :adapters:meta:lintMockDebug \
    :adapters:image_android:lintDebug :app:lintProductionDebug :app:lintMockDebug \
    :app:testProductionDebugUnitTest :app:testMockDebugUnitTest \
    :app:assembleProductionRelease :app:assembleMockDebug verifyBoundaries
}

voice_mock() {
  local help='
    ## Usage:
      ANDROID_SERIAL=emulator-5556 bash test.sh mock -PvmodalMavenRepo=/absolute/path/to/maven-repo
  '
  voice_gradle "$@" :adapters:meta:connectedMockDebugAndroidTest \
    :adapters:image_android:connectedDebugAndroidTest :app:connectedMockDebugAndroidTest
}

voice_device() {
  local help='
    ## Usage:
      bash test.sh device -PmetaApplicationId=YOUR_META_APP_ID -PvmodalMavenRepo=/absolute/path/to/maven-repo
  '
  voice_gradle "$@" :app:installProductionDebug
  printf 'Installed. Open VModal Glasses Voice Search and follow docs/acceptance.md.\n'
}

command="${1:-test}"
[[ $# -eq 0 ]] || shift
case "$command" in
  test) voice_test "$@" ;;
  mock) voice_mock "$@" ;;
  device|live) voice_device "$@" ;;
  help|-h|--help) printf '%s\n' "$help" ;;
  *) printf 'Unknown command: %s\n%s\n' "$command" "$help" >&2; exit 2 ;;
esac
