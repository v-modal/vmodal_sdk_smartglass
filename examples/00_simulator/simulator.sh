#!/usr/bin/env bash
set -euo pipefail

help='
  Install and run the Meta Smart Glasses Android simulator example.

  Examples:
    bash simulator.sh simulator_install Greatwhite
    bash simulator.sh simulator_run Greatwhite window 5556
    bash simulator.sh simulator_run Greatwhite headless 5556
    bash simulator.sh simulator_stop 5556
    bash simulator.sh simulator_firmware_setup
    bash simulator.sh simulator_firmware_start ./greatwhite_sim Greatwhite 5556
    bash simulator.sh simulator_firmware_launcher 5556
    bash simulator.sh simulator_test
'

simulator_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk_dir="$(cd "$simulator_dir/../.." && pwd)"
firmware_sha="1efc12579b5060ce8340914d584b8b3ad298b66e"

simulator_sdk_root() {
  local help='
    ## Usage:
      simulator_sdk_root
  '
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    printf '%s\n' "$ANDROID_SDK_ROOT"
  elif [[ -n "${ANDROID_HOME:-}" ]]; then
    printf '%s\n' "$ANDROID_HOME"
  elif [[ "$(uname -s)" == "Darwin" ]]; then
    printf '%s\n' "$HOME/Library/Android/sdk"
  else
    printf '%s\n' "$HOME/Android/Sdk"
  fi
}

simulator_java_home() {
  local help='
    ## Usage:
      simulator_java_home
  '
  local path
  for path in \
    "${JAVA_HOME:-}" \
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    if [[ -n "$path" && -x "$path/bin/java" ]]; then
      printf '%s\n' "$path"
      return
    fi
  done
  return 1
}

simulator_export_env() {
  local help='
    ## Usage:
      simulator_export_env
  '
  local java_home root
  root="$(simulator_sdk_root)"
  java_home="$(simulator_java_home)"
  export JAVA_HOME="$java_home"
  export ANDROID_SDK_ROOT="$root"
  export ANDROID_HOME="$root"
  export PATH="$JAVA_HOME/bin:$root/platform-tools:$root/emulator:$PATH"
}

simulator_sdkmanager() {
  local help='
    ## Usage:
      simulator_sdkmanager
  '
  local root path
  root="$(simulator_sdk_root)"
  for path in "$root/cmdline-tools/latest/bin/sdkmanager" "$root/cmdline-tools/bin/sdkmanager"; do
    [[ -x "$path" ]] && { printf '%s\n' "$path"; return; }
  done
  if command -v sdkmanager >/dev/null 2>&1; then
    command -v sdkmanager
    return
  fi
  return 1
}

simulator_avdmanager() {
  local help='
    ## Usage:
      simulator_avdmanager
  '
  local root path
  root="$(simulator_sdk_root)"
  for path in "$root/cmdline-tools/latest/bin/avdmanager" "$root/cmdline-tools/bin/avdmanager"; do
    [[ -x "$path" ]] && { printf '%s\n' "$path"; return; }
  done
  if command -v avdmanager >/dev/null 2>&1; then
    command -v avdmanager
    return
  fi
  return 1
}

simulator_install_tools() {
  local help='
    ## Usage:
      bash simulator.sh simulator_install_tools
  '
  if ! simulator_java_home >/dev/null 2>&1; then
    [[ "$(uname -s)" == "Darwin" ]] || { printf 'Install JDK 17 before continuing.\n' >&2; return 1; }
    command -v brew >/dev/null || { printf 'Homebrew is required to install JDK 17.\n' >&2; return 1; }
    brew install openjdk@17
  fi
  if ! simulator_sdkmanager >/dev/null 2>&1; then
    [[ "$(uname -s)" == "Darwin" ]] || { printf 'Install Android SDK command-line tools before continuing.\n' >&2; return 1; }
    command -v brew >/dev/null || { printf 'Homebrew is required to install Android SDK tools.\n' >&2; return 1; }
    brew install --cask android-commandlinetools
  fi
}

simulator_tune_avd() {
  local help='
    ## Usage:
      simulator_tune_avd Greatwhite
  '
  local avd="${1:-Greatwhite}" config data root tmp
  config="$HOME/.android/avd/$avd.avd/config.ini"
  data="$HOME/.android/avd/$avd.avd/userdata-qemu.img"
  root="$(simulator_sdk_root)"
  tmp="$config.vmodal-tmp"
  [[ -f "$config" ]] || { printf 'Missing AVD configuration: %s\n' "$config" >&2; return 1; }
  awk -F= '
    BEGIN { disk = 0; ram = 0 }
    /^disk.dataPartition.size[[:space:]]*=/ { print "disk.dataPartition.size = 2147483648"; disk = 1; next }
    /^hw.ramSize[[:space:]]*=/ { print "hw.ramSize = 2048"; ram = 1; next }
    { print }
    END {
      if (!disk) print "disk.dataPartition.size = 2147483648"
      if (!ram) print "hw.ramSize = 2048"
    }
  ' "$config" > "$tmp"
  mv "$tmp" "$config"
  if [[ ! -f "$data" ]]; then
    "$root/emulator/qemu-img" create -f raw "$data" 2G
    "$root/emulator/bin64/mkfs.ext4" -F "$data" >/dev/null
  fi
}

simulator_firmware_setup() {
  local help='
    ## Usage:
      bash simulator.sh simulator_firmware_setup
  '
  local work="$simulator_dir/.simulator/meta-rayban-firmware-android-emulator"
  mkdir -p "$(dirname "$work")"
  if [[ -d "$work/.git" ]]; then
    [[ "$(git -C "$work" rev-parse HEAD)" == "$firmware_sha" ]] || {
      printf 'Firmware tools already exist at a different revision: %s\n' "$work" >&2
      return 1
    }
  else
    git clone https://github.com/zhuowei/meta-rayban-firmware-android-emulator.git "$work"
    git -C "$work" checkout --detach "$firmware_sha"
  fi
  printf 'Experimental firmware tools: %s\n' "$work"
}

simulator_install() {
  local help='
    ## Usage:
      bash simulator.sh simulator_install Greatwhite
  '
  local avd="${1:-Greatwhite}" root manager avd_manager image status
  simulator_install_tools
  simulator_export_env
  root="$(simulator_sdk_root)"
  manager="$(simulator_sdkmanager)"
  image="system-images;android-34;google_apis;arm64-v8a"
  if [[ "$(uname -m)" != "arm64" && "$(uname -m)" != "aarch64" ]]; then
    image="system-images;android-34;google_apis;x86_64"
  fi
  mkdir -p "$root"
  set +o pipefail
  yes | "$manager" --sdk_root="$root" --licenses >/dev/null
  status="${PIPESTATUS[1]}"
  set -o pipefail
  [[ "$status" == "0" ]] || { printf 'Android SDK license acceptance failed.\n' >&2; return 1; }
  "$manager" --sdk_root="$root" \
    "cmdline-tools;latest" \
    "platform-tools" \
    "emulator" \
    "platforms;android-36" \
    "build-tools;36.0.0" \
    "$image"
  avd_manager="$(simulator_avdmanager)"
  if [[ ! -f "$HOME/.android/avd/$avd.ini" ]]; then
    printf 'no\n' | "$avd_manager" create avd --name "$avd" --package "$image" --device "pixel_6"
  fi
  simulator_tune_avd "$avd"
  simulator_check "$avd"
}

simulator_check() {
  local help='
    ## Usage:
      bash simulator.sh simulator_check Greatwhite
  '
  local avd="${1:-Greatwhite}" root
  simulator_export_env
  root="$(simulator_sdk_root)"
  [[ -x "$JAVA_HOME/bin/java" ]]
  [[ -x "$root/platform-tools/adb" ]]
  [[ -x "$root/emulator/emulator" ]]
  [[ -d "$root/platforms/android-36" ]]
  [[ -f "$HOME/.android/avd/$avd.ini" ]]
  "$JAVA_HOME/bin/java" -version
  "$root/emulator/emulator" -version | head -n 2
  printf 'Android simulator is ready: %s\n' "$avd"
}

simulator_build() {
  local help='
    ## Usage:
      bash simulator.sh simulator_build
  '
  simulator_export_env
  (cd "$sdk_dir" && ./gradlew --no-daemon :examples:00_simulator:app:assembleDebug)
}

simulator_wait() {
  local help='
    ## Usage:
      simulator_wait 5556
  '
  local port="${1:-5556}" pid="${2:-}" log="${3:-}" serial="emulator-${1:-5556}" count
  for ((count = 1; count <= 90; count++)); do
    if adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; then
      printf 'Android emulator booted: %s\n' "$serial"
      return
    fi
    if [[ -n "$pid" ]] && ! kill -0 "$pid" 2>/dev/null; then
      [[ -n "$log" && -f "$log" ]] && tail -n 30 "$log" >&2
      printf 'Android emulator exited before boot completed.\n' >&2
      return 1
    fi
    sleep 2
  done
  printf 'Timed out waiting for %s.\n' "$serial" >&2
  return 1
}

simulator_start() {
  local help='
    ## Usage:
      bash simulator.sh simulator_start Greatwhite window 5556
      bash simulator.sh simulator_start Greatwhite headless 5556
  '
  local avd="${1:-Greatwhite}" mode="${2:-window}" port="${3:-5556}" root serial log
  local -a window_args=()
  simulator_export_env
  root="$(simulator_sdk_root)"
  serial="emulator-$port"
  log="$simulator_dir/.simulator/emulator-$port.log"
  mkdir -p "$(dirname "$log")"
  if adb -s "$serial" get-state >/dev/null 2>&1; then
    printf 'Android emulator is already running: %s\n' "$serial"
    return
  fi
  [[ "$mode" == "window" || "$mode" == "headless" ]] || { printf 'Mode must be window or headless.\n' >&2; return 2; }
  [[ "$mode" == "headless" ]] && window_args=(-no-window -no-audio)
  nohup "$root/emulator/emulator" \
    -avd "$avd" \
    -port "$port" \
    -partition-size 2048 \
    -no-snapshot-load \
    -no-snapshot-save \
    "${window_args[@]}" >"$log" 2>&1 &
  local pid="$!"
  printf 'Starting %s; log: %s\n' "$serial" "$log"
  simulator_wait "$port" "$pid" "$log"
}

simulator_deploy() {
  local help='
    ## Usage:
      bash simulator.sh simulator_deploy 5556
  '
  local port="${1:-5556}" serial="emulator-${1:-5556}" pid
  simulator_export_env
  adb devices | grep -q "^${serial}[[:space:]]" || { printf 'Android emulator is not running: %s\n' "$serial" >&2; return 1; }
  simulator_build
  adb -s "$serial" install -r "$simulator_dir/app/build/outputs/apk/debug/app-debug.apk"
  adb -s "$serial" shell am start -n com.vmodal.smartglass.simulator/.MainActivity
  sleep 2
  pid="$(adb -s "$serial" shell pidof com.vmodal.smartglass.simulator | tr -d '\r')"
  [[ -n "$pid" ]] || { printf 'Simulator app exited during launch.\n' >&2; return 1; }
  adb -s "$serial" shell dumpsys activity activities | grep -q 'com.vmodal.smartglass.simulator/.MainActivity'
  printf 'Simulator app is installed and running (pid %s).\n' "$pid"
}

simulator_run() {
  local help='
    ## Usage:
      bash simulator.sh simulator_run Greatwhite window 5556
      bash simulator.sh simulator_run Greatwhite headless 5556
  '
  simulator_start "${1:-Greatwhite}" "${2:-window}" "${3:-5556}"
  simulator_deploy "${3:-5556}"
}

simulator_stop() {
  local help='
    ## Usage:
      bash simulator.sh simulator_stop 5556
  '
  local port="${1:-5556}"
  simulator_export_env
  adb devices | grep -q "^emulator-${port}[[:space:]]" || { printf 'Android emulator is not running: emulator-%s\n' "$port" >&2; return 1; }
  adb -s "emulator-$port" emu kill
}

simulator_firmware_start() {
  local help='
    ## Usage:
      bash simulator.sh simulator_firmware_start ./greatwhite_sim Greatwhite 5556
  '
  local sysdir="${1:?Pass the repacked greatwhite_sim directory}" avd="${2:-Greatwhite}" port="${3:-5556}"
  local root tools config
  [[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]] || {
    printf 'The experimental firmware emulator requires an Apple Silicon Mac.\n' >&2
    return 1
  }
  simulator_export_env
  root="$(simulator_sdk_root)"
  tools="$simulator_dir/.simulator/meta-rayban-firmware-android-emulator"
  config="$sysdir/custom_netsim_config.json"
  [[ -f "$sysdir/system.img" ]] || { printf 'Missing repacked firmware image: %s/system.img\n' "$sysdir" >&2; return 1; }
  [[ -f "$config" ]] || cp "$tools/files/custom_netsim_config.json" "$config"
  exec "$root/emulator/emulator" \
    -avd "$avd" \
    -port "$port" \
    -show-kernel \
    -sysdir "$sysdir" \
    -selinux permissive \
    -accel on \
    -prop qemu.sf.lcd_density=160 \
    -skin 600x600 \
    -netsim-args "--config $config"
}

simulator_firmware_launcher() {
  local help='
    ## Usage:
      bash simulator.sh simulator_firmware_launcher 5556
  '
  local port="${1:-5556}"
  simulator_export_env
  adb -s "emulator-$port" shell am start com.meta.smartglass.app.systemui
}

simulator_test() {
  local help='
    ## Usage:
      bash simulator.sh simulator_test
  '
  bash -n "$simulator_dir/simulator.sh"
  test -f "$simulator_dir/README.md"
  test -f "$simulator_dir/app/build.gradle.kts"
  test -f "$simulator_dir/app/src/main/AndroidManifest.xml"
  test -f "$simulator_dir/app/src/main/kotlin/com/vmodal/smartglass/simulator/MainActivity.kt"
  printf 'Simulator example structure and shell syntax are valid.\n'
}

case "${1:-help}" in
  simulator_install_tools) simulator_install_tools ;;
  simulator_install) simulator_install "${2:-Greatwhite}" ;;
  simulator_check) simulator_check "${2:-Greatwhite}" ;;
  simulator_build) simulator_build ;;
  simulator_start) simulator_start "${2:-Greatwhite}" "${3:-window}" "${4:-5556}" ;;
  simulator_deploy) simulator_deploy "${2:-5556}" ;;
  simulator_run) simulator_run "${2:-Greatwhite}" "${3:-window}" "${4:-5556}" ;;
  simulator_stop) simulator_stop "${2:-5556}" ;;
  simulator_firmware_setup) simulator_firmware_setup ;;
  simulator_firmware_start) simulator_firmware_start "${2:-}" "${3:-Greatwhite}" "${4:-5556}" ;;
  simulator_firmware_launcher) simulator_firmware_launcher "${2:-5556}" ;;
  simulator_test) simulator_test ;;
  help|-h|--help) printf '%s\n' "$help" ;;
  *) printf 'Unknown simulator command: %s\n%s\n' "$1" "$help" >&2; exit 2 ;;
esac
