# Meta Smart Glasses Android simulator

This example provides two intentionally separate simulator paths:

1. **Meta Mock Device Kit (recommended):** a supported Android app that pairs a
   simulated Ray-Ban Meta device and drives its power, fold, and wear states.
2. **Firmware emulator (experimental):** helper commands around
   `zhuowei/meta-rayban-firmware-android-emulator` for an Apple Silicon Mac.

The Mock Device Kit simulates the DAT contract inside an Android application;
it does not emulate the glasses operating system. The firmware project attempts
that lower-level emulation, but its upstream README says boot is incomplete and
the initial setup app crashes. Use it only for firmware research.

## Requirements

- macOS or Linux for the Mock Device Kit path
- JDK 17
- Android SDK 36 build tools
- Android Emulator with an Android 14 Google APIs image
- A classic GitHub token with `read:packages` access to Meta's DAT packages,
  exported as `GITHUB_TOKEN` or stored as `github_token` in
  `sdk_smartglass/local.properties`
- Apple Silicon macOS for the experimental firmware path

The installer does not create a Python, Conda, or other project environment.
On macOS it uses existing Android Studio/Homebrew tools and installs missing
JDK or Android command-line tools with Homebrew.

## Install the emulator

From `uinterface/sdk_smartglass/examples/00_simulator`:

```bash
bash simulator.sh simulator_install Greatwhite
```

This installs `platform-tools`, Emulator, Android SDK/build tools 36, and an
Android 14 Google APIs system image. It then creates the `Greatwhite` AVD with
a compact 2 GB sparse data partition.

Verify an existing installation without changing it:

```bash
bash simulator.sh simulator_check Greatwhite
```

## Build and run the Mock Device Kit app

Run with a normal emulator window:

```bash
bash simulator.sh simulator_run Greatwhite window 5556
```

For CI or a machine without a display:

```bash
bash simulator.sh simulator_run Greatwhite headless 5556
```

The command builds the app, waits for Android to boot, installs the APK, and
opens `MainActivity`. The app enables `MockDeviceKit`, pairs a simulated
Ray-Ban Meta device, and exposes buttons for the supported lifecycle:

```text
enable -> pair -> power on -> unfold -> don
                              doff -> fold -> power off
```

Stop the dedicated emulator with:

```bash
bash simulator.sh simulator_stop 5556
```

## Experimental Meta firmware path

The public firmware helper repository does **not** contain Meta firmware. You
must lawfully obtain and extract your own firmware, perform the upstream Linux
repacking steps, and place the resulting `system.img` in a copied Android 14
system-image directory such as `greatwhite_sim/`.

Prepare the pinned helper checkout under the ignored `.simulator/` directory:

```bash
bash simulator.sh simulator_firmware_setup
```

After the repacked directory is ready and other emulators are closed, start it:

```bash
bash simulator.sh simulator_firmware_start "$PWD/greatwhite_sim" Greatwhite 5556
```

If the emulator reaches a blank screen, use another terminal to start the
glasses launcher as documented upstream:

```bash
bash simulator.sh simulator_firmware_launcher 5556
```

## Tests

Run the fast script/structure test:

```bash
bash simulator.sh simulator_test
```

Build the Android example:

```bash
bash simulator.sh simulator_build
```

The complete smoke test is the headless `simulator_run` command followed by
checking the launched activity:

```bash
adb -s emulator-5556 shell dumpsys activity activities | grep com.vmodal.smartglass.simulator
```

## Upstream references

- [Meta Mock Device Kit](https://wearables.developer.meta.com/docs/develop/dat/mock-device-kit/)
- [Meta Wearables DAT Android SDK](https://github.com/facebook/meta-wearables-dat-android)
- [Experimental Ray-Ban firmware Android emulator](https://github.com/zhuowei/meta-rayban-firmware-android-emulator/)
