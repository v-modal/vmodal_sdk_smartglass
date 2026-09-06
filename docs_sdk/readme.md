# VModal Smart Glass SDK quickstart

Build the Android library, connect `SmartGlassClient`, and stream a wearable
camera feed to a short-lived SRT ingest target.

[← Back to the Smart Glass SDK overview](../readme.md)

## Requirements

- Android **API 31+** and compile SDK **36**
- JDK **17**
- Android NDK
- Rust and `cargo-ndk`
- A compatible Meta smart-glasses registration and Meta Wearables DAT setup
- An authenticated backend that returns a fresh `LiveStreamTarget`

The project has two build flavors:

| Flavor | Purpose |
|---|---|
| `offline` | Local lint and JVM tests without Meta artifacts or device credentials |
| `meta` | Real-device integration with Meta Wearables DAT dependencies |

## Build from this repository

Run the fast offline checks first:

```bash
cd uinterface/sdk_smartglass
bash install.sh smartglass_check
bash test.sh smartglass_offline
```

Assemble the Meta-connected Android library with:

```bash
bash test.sh smartglass_assemble
```

The native core is built for `arm64-v8a` and `x86_64` and packaged with the
Meta release artifact.

## Start a stream in Kotlin

Create one client for the component that owns the logical stream—normally an
application-scoped session owner or foreground service, not a transient screen.

```kotlin
import com.vmodal.smartglass.LiveStreamTarget
import com.vmodal.smartglass.LiveStreamTargetProvider
import com.vmodal.smartglass.SmartGlassClient
import com.vmodal.smartglass.SmartGlassConfig
import com.vmodal.smartglass.TargetReason

val targetProvider = LiveStreamTargetProvider { reason: TargetReason ->
    // Ask your authenticated backend for a fresh, short-lived target.
    LiveStreamTarget(
        sessionId = "live-session-123",
        url = "srt://ingest.example.com:778",
        streamId = "provider-stream-id",
        passphrase = "a-short-lived-stream-passphrase",
        expiresAtEpochMs = System.currentTimeMillis() + 10 * 60 * 1000,
    )
}

val client = SmartGlassClient.create(
    context = applicationContext,
    targetProvider = targetProvider,
    config = SmartGlassConfig(),
    permissionRequester = activityPermissionRequester,
)

client.start()
```

`LiveStreamTargetProvider` is the boundary between the device and your
authenticated backend. Return an SRT URL as `srt://host:port`; keep the stream
ID and passphrase separate, short-lived, and out of logs.

## Connect lifecycle data to the UI

Collect state, events, and metrics in the Android lifecycle owner:

```kotlin
lifecycleScope.launch {
    client.state.collect(::renderStreamingState)
}

lifecycleScope.launch {
    client.events.collect(::showStreamingEvent)
}

lifecycleScope.launch {
    client.metrics.collect(::renderStreamingMetrics)
}
```

The stream progresses through explicit lifecycle states—from device setup and
codec startup to transport connection and `Streaming`. Use those states to
give the user precise feedback instead of one indefinite loading indicator.

## Stop and clean up

Release capture and native resources deterministically when the user stops or
the owning service ends:

```kotlin
client.stop()
client.closeAndJoin()
```

When `stopOnBackground` is enabled, forward the application background event:

```kotlin
client.onBackgrounded()
```

Do not run another camera session alongside the active `SmartGlassClient`.

## What `start()` puts in motion

1. Validates the streaming configuration and requests a target from your backend.
2. Opens one Meta DAT device session and starts the glasses camera stream.
3. Selects the Android hardware H.264 encoding path.
4. Starts the native SRT session and sends the live MPEG-TS stream.
5. Publishes state, events, and metrics for the host application.

## Integration boundaries

Your application owns:

- Authentication and the backend request for a live target
- Meta registration and user-facing camera permissions
- Screens, stream controls, and foreground-service policy
- Rendering state, events, and metrics

The SDK owns:

- One Meta DAT capture session while streaming
- Android hardware H.264 encoding
- JNI and Rust-core lifecycle
- Bounded queues, MPEG-TS muxing, SRT transport, and reconnect behavior

## Continue reading

- [Smart Glass SDK overview](../README.md)
- [VModal developer resources](https://www.v-modal.com/developers)
- [VModal developer community](https://discord.gg/XGxgBQqkaY)
