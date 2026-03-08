# OBS Streaming Next Buildout Plan (Post-JNI Stub)

This document defines the next implementation buildout needed to move TempleI from **"control-path started"** to **real media delivery** into OBS over SRT.

## Current state (baseline)
- Screen 2 can validate config and transition to `Streaming` state.
- FFmpeg backend JNI loads and accepts lifecycle commands.
- Native layer still reports a bring-up runtime (`ffmpeg JNI stub loaded ...`).
- Backend AU counters can remain at `videoAu=0` / `audioAu=0`, meaning no real stream reaches OBS.

---

## Buildout goal
Ship a working end-to-end media path:
1. Encoded H.264/AAC access units are produced continuously.
2. AUs are handed off to native FFmpeg backend.
3. Native runtime muxes MPEG-TS and sends over SRT (`mode=caller`) to OBS listener.
4. OBS Media Source opens reliably with `input_format=mpegts`.

---

## Execution plan

## Phase 1 — Media ingress observability (Kotlin side)
**Objective:** prove where data stops before touching FFmpeg internals.

### Tasks
- Add explicit counters/logging at each boundary:
  - Camera frame enqueue/dequeue (`CaptureCoordinator`).
  - Video encoder output callback (`VideoEncoderNode.setOutputListener`).
  - Audio encoder output callback (`AudioEncoderNode.setOutputListener`).
  - Backend ingress call sites (`NativeStreamBackends.pushVideoAccessUnit/pushAudioAccessUnit`).
- Add periodic summary log in streaming mode:
  - `cameraFrames`, `videoEncoded`, `audioEncoded`, `videoIngressCalls`, `audioIngressCalls`.
- Keep logs rate-limited (startup burst + every N events) to avoid log spam.

### Exit criteria
- In `VideoOnly`, `videoEncoded` and `videoIngressCalls` increase within a few seconds.
- In `FullAv`, both video and audio counters increase.

---

## Phase 2 — Replace JNI stub with real FFmpeg runtime
**Objective:** implement actual native mux/send behavior.

### Tasks
- Replace stub internals in `app/src/main/cpp/templei_ffmpeg_stub.cpp` with real FFmpeg/SRT runtime:
  - Create output context for `srt://<host>:<port>?mode=caller&latency=<...>`.
  - Select `mpegts` muxer.
  - Create video/audio streams with codec parameters matching encoded input (H.264/AAC).
  - Write container header (`avformat_write_header`).
  - On each AU push:
    - Wrap encoded data in `AVPacket`.
    - Convert/normalize timestamps to stream timebase.
    - Interleave/write (`av_interleaved_write_frame`).
  - On stop:
    - Write trailer.
    - Close I/O and free all contexts.
- Keep JNI signatures unchanged to avoid Kotlin API churn.
- Extend `nativeLastError()` to return first actionable FFmpeg/SRT error text.

### Exit criteria
- `nativeRuntimeInfo()` reports real runtime (not stub).
- `videoAu`/`audioAu` and packet-write counters increase during streaming.
- No silent success path: write failures surface via `lastErr`.

---

## Phase 3 — Timestamp/timebase correctness
**Objective:** avoid OBS probe failures, drift, and unstable playback.

### Tasks
- Define explicit timebase strategy:
  - Video: encoder PTS µs -> stream timebase conversion.
  - Audio: AAC PTS µs -> stream timebase conversion.
- Ensure monotonic DTS/PTS per stream.
- Handle codec-config behavior properly:
  - H.264 SPS/PPS availability before/with first keyframe.
  - AAC config consistency for muxer expectations.
- Add guard rails:
  - Drop or clamp out-of-order timestamps.
  - Count and report timestamp corrections.

### Exit criteria
- OBS opens stream consistently across repeated starts.
- 15+ minute run has no severe A/V drift or timestamp errors in logs.

---

## Phase 4 — SRT connection and transport health
**Objective:** make network behavior observable and resilient.

### Tasks
- Add native diagnostics counters:
  - connect attempts/successes/failures,
  - packets written,
  - bytes written,
  - consecutive write failures,
  - last successful write timestamp.
- Surface a compact transport health summary through existing diagnostics string.
- Add reconnect policy (if required by runtime behavior):
  - bounded retry window,
  - explicit terminal failure when retries exhausted.

### Exit criteria
- On unreachable OBS, app reports transport failure domain clearly.
- On reachable OBS, packet/byte counters move steadily.

---

## Phase 5 — Screen 2 operator clarity
**Objective:** make UI status reflect real stream health, not only state transitions.

### Tasks
- Extend interoperability status text to include:
  - media ingress status (AUs flowing vs stalled),
  - native packet-write status,
  - connection status (connected/retrying/faulted).
- Keep wording actionable and concise.
- Preserve current screen ownership (no camera preview added to Screen 2).

### Exit criteria
- `Streaming` + stalled ingress is explicitly called out.
- Operators can distinguish config issues, ingress issues, and transport issues.

---

## Phase 6 — Validation matrix and release gate
**Objective:** prevent regressions and confirm OBS interoperability.

### Device/runtime matrix
- Modes: `VideoOnly`, `FullAv`, `AudioOnly`.
- Start/Stop cycles: at least 10 consecutive cycles.
- Session duration: 15+ minutes per primary mode.
- Conditions: stable LAN + transient disconnect/reconnect test.

### Verification checklist
- OBS Media Source settings:
  - Input URL from Screen 2 (`srt://...mode=listener...` in OBS side usage pattern).
  - Input format: `mpegts`.
- App diagnostics show:
  - non-zero AU ingress,
  - non-zero packets/bytes sent,
  - no persistent write failures.
- OBS no longer reports repeated “Failed to open media” once stream starts.

### Release gate
- Do not mark runtime “ready” unless:
  - native runtime is non-stub,
  - AU ingress counters move,
  - packet-write counters move,
  - OBS ingest succeeds in at least one full validation pass.

---

## Suggested PR slicing
1. **PR A:** Kotlin-side ingress instrumentation + diagnostics surfacing.
2. **PR B:** Native FFmpeg runtime replacement (header/write/trailer path).
3. **PR C:** Timestamp alignment and drift hardening.
4. **PR D:** Transport health + reconnect diagnostics.
5. **PR E:** Validation docs and final operator wording updates.

---

## Risks and mitigations
- **Encoder variability across devices**
  - Mitigate with codec capability logging and fallback config profiles.
- **Timestamp drift over long sessions**
  - Mitigate with strict monotonic checks + correction counters.
- **SRT runtime edge behavior**
  - Mitigate with explicit retry/backoff and failure-domain reporting.
- **False-positive “Streaming” state**
  - Mitigate by coupling status text to real ingress and packet-write counters.

---

## Definition of done
TempleI can start from Screen 2, stream to OBS over SRT, and show diagnostics proving:
1. encoded media ingress is active,
2. native mux/write path is active,
3. OBS receives and plays the stream reliably.
