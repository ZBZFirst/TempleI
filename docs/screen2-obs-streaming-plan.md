# Screen 2 OBS Streaming Implementation Plan

This plan keeps **Screen 1 camera behavior unchanged** and uses **Screen 2** as the staging/configuration surface for Android-to-OBS LAN streaming.

## Scope guardrails
- Do not modify `Screen1Activity` or Screen 1 camera controls.
- Keep camera preview/capture UI on Screen 1 only.
- Keep Screen 2 as configuration + stream Start/Stop controls only (no camera preview UI).
- Build Screen 2 as a stream orchestration/configuration flow.
- Reword Screen 2 labels toward OBS/SRT terminology.

## Target pipeline (v1)
1. Camera + microphone capture on Android.
2. H.264 video encode + AAC audio encode.
3. MPEG-TS mux.
4. SRT transport over LAN.
5. OBS ingest via Media Source (`srt://...`, input format `mpegts`).

## Suggested architecture
- `CaptureCoordinator`: owns session lifecycle and transitions.
- `ObsEndpointSpec`: host, port, latency, stream id/mode options.
- `StreamState`: Idle → PermissionsReady → CaptureBound → EncodersReady → TransportReady → Streaming → Reconnecting → Stopping → Faulted.
- Failure domains tracked separately:
  - CameraFailure
  - MicFailure
  - EncoderFailure
  - MuxFailure
  - TransportFailure
  - ObsUnreachable


## Stream mode scope
- Supported stream path modes remain limited to: `FullAv`, `VideoOnly`, `AudioOnly`.
- Do not add preview-only / encoder-only / local-sink / reduced-profile modes in this iteration.

## Current Screen 2 wiring status
- Screen 2 buttons are now mapped to concrete OBS ingest workflow actions:
  - edit host/IP, edit port, validate/test, reset preset, show OBS input string, toggle profile, start, stop.
- Screen 2 persists host/port/profile locally via SharedPreferences through `ExportFeature`.
- Screen 2 now displays required outputs:
  - OBS setup summary (Media Source + Local File Off + Input + Input Format)
  - session state
  - validation result
  - last connection test result
  - last error text
- Start/Stop are wired through `StreamTransportGateway` to the FFmpeg backend abstraction; native runtime is now FFmpeg-first with incremental JNI bring-up diagnostics.

## Screen 2 implementation phases

### Phase 1 — UI conversation + contract placeholders
- Replace generic configuration labels with OBS/SRT-specific wording.
- Add sections that mirror transport contract decisions:
  - endpoint spec
  - muxing/timestamp rules
  - reconnect policy
  - state machine + failure domains
- Keep buttons as placeholders while wiring is introduced incrementally.

### Phase 2 — Configuration model
- Add a `Screen2ViewModel` and local state for endpoint + policy inputs.
- Persist settings with DataStore (or SharedPreferences for initial pass).
- Add validation rules (LAN host format, port range, latency min).

### Phase 3 — Streaming service boundary
- Introduce a foreground `StreamService` for long-running capture/streaming.
- Add binder or command interface from Screen 2 to service.
- Expose service state back to Screen 2 (Flow/LiveData).

### Phase 4 — Encoder + backend transport nodes
- Add `VideoEncoderNode` (`MediaCodec`, H.264, hardware preferred).
- Add `AudioEncoderNode` (AAC-LC).
- Route encoded access units into FFmpeg backend ingress (`FfmpegNativeBridge`).
- Keep `TsMuxerNode` / `SrtTransportNode` as archived legacy contracts until final removal.

### Phase 5 — Reliability + UX
- Implement reconnect/backoff policy in transport layer.
- Keep preview/render concerns decoupled from transport health.
- Add clear failure messages per failure domain in Screen 2.

### Phase 6 — OBS operator workflow docs
- Add in-app help and repo docs:
  - Add OBS Media Source
  - Disable local file
  - Set `srt://...`
  - Input format `mpegts`

## Proposed file map (new work centered on Screen 2 path)
- `app/src/main/java/com/example/templei/Screen2Activity.kt` (UI host)
- `app/src/main/java/com/example/templei/feature/export/` (streaming nodes + coordinator)
- `app/src/main/java/com/example/templei/ui/state/` (Screen 2 state/events)
- `app/src/main/res/layout/activity_screen2.xml` (Screen 2 controls)
- `app/src/main/res/values/strings.xml` (OBS-specific strings)

## Validation plan
- Build checks: `:app:compileDebugKotlin`, `testDebugUnitTest`.
- Manual Android validation:
  1. Configure endpoint on Screen 2.
  2. Start stream.
  3. Navigate across screens and verify stream continuity.
  4. Verify OBS receives A/V and reconnect behavior.


## Latest completion notes
- PR E cutover status: Screen 2 now uses FFmpeg backend as the active transport path; diagnostics report backend state + encoded ingress counters instead of legacy mux/srt runtime placeholders.
- Legacy `TsMuxerNode` / `SrtTransportNode` contracts are archived for reference only and are no longer in the active Screen 2 transport flow.
- Round 4 audio-path contract is now wired: `CaptureCoordinator` configures/starts `AudioEncoderNode` and stops it during session teardown to keep A/V path orchestration paired.
- Round 3 video-path contract is now wired: `StreamSessionService` calls `CaptureCoordinator`, which checks camera preview readiness and starts/stops video/audio encoder nodes before transport start/stop.
- Service/session boundary is now implemented with `StreamSessionService` and a binder command channel from `Screen2Activity` for Start/Stop stream actions.
- Validation no longer immediately faults Screen 2 for missing host/port; invalid endpoint input now keeps session state in `Idle` with explicit validation messages.
- Validate and Start now prompt for host input when empty to reduce dead-end `host missing` flows.
- Transport availability is now reported in endpoint test and Start path separately; Start transitions to `Faulted` on FFmpeg backend/runtime unavailability.



## PR 5 validation closeout notes
- Added/expanded unit coverage for diagnostics origin behavior (drop-priority, latency-over-budget, healthy-within-budget).
- Confirmed Screen 2 remains config/control only while diagnostics are surfaced through status text (no camera preview UI added).
- Environment limitation in this container: Android SDK path is missing, so Gradle Android tasks require `ANDROID_HOME`/`ANDROID_SDK_ROOT` or `local.properties` (`sdk.dir`).

## Native SRT dependency packaging note
- If the app itself publishes an SRT stream to OBS, Android must include native SRT support (`libsrt`) because `MediaMuxer` does not provide SRT transport.
- FFmpeg-based transport is valid for this case only when FFmpeg is built with `--enable-libsrt` and the APK packages `libsrt.so` for target ABI.
- Current sender runtime expects a loadable SRT shared library (`libsrt.so` or `libsrt.so.1`) at app runtime.
- Package per-ABI binaries under `app/src/main/jniLibs/<abi>/libsrt.so` (for example `arm64-v8a`).
- If missing, Screen 2 start diagnostics now surface ABI + attempted library names to speed setup troubleshooting.
- Preflight before Start now checks host, port, and native runtime availability so Screen 2 can fail early with explicit dependency guidance before transitioning to `Faulted`.
- App build now includes sender dependency install + verification (`:app:installSrtArm64`, `:app:verifySrtDependency`) requiring `app/src/main/jniLibs/arm64-v8a/libsrt.so`; helper task `:app:buildSrtArm64` builds/installs it from upstream SRT source when `ANDROID_NDK_HOME` and network are available.
