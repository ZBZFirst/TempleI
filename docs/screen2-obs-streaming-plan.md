# Screen 2 OBS Streaming Implementation Plan

This plan keeps **Screen 1 camera behavior unchanged** and uses **Screen 2** as the staging/configuration surface for Android-to-OBS LAN streaming.

## Scope guardrails
- Do not modify `Screen1Activity` or Screen 1 camera controls.
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

### Phase 4 — Encoder + mux + transport nodes
- Add `VideoEncoderNode` (`MediaCodec`, H.264, hardware preferred).
- Add `AudioEncoderNode` (AAC-LC).
- Add `TsMuxerNode` for packetized MPEG-TS output.
- Add `SrtTransportNode` to send TS packets to OBS endpoint.

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
