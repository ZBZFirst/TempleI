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

## 12 actionable implementation items (required changes)

Each item below names the exact element to change and the pass criteria needed for OBS ingest success.

### 1) Add camera-ingress counters in `CaptureCoordinator`
**Change required**
- Add per-session counters for:
  - `cameraFramesEnqueued`
  - `cameraFramesDropped`
  - `cameraFramesDequeued`
- Log startup burst + rate-limited periodic summary while streaming.

**Pass criteria**
- Counters move in `VideoOnly` and `FullAv` while stream is active.

### 2) Add video-encoder output counters in `VideoEncoderNode`
**Change required**
- Count encoded video AUs emitted by `setOutputListener`.
- Track keyframe count and last video PTS.
- Emit rate-limited diagnostics snapshot.

**Pass criteria**
- `videoEncodedAu > 0` within a few seconds of Start.

### 3) Add audio-encoder output counters in `AudioEncoderNode`
**Change required**
- Count encoded audio AUs emitted by `setOutputListener`.
- Track last audio PTS and config/header emission events.
- Emit rate-limited diagnostics snapshot.

**Pass criteria**
- `audioEncodedAu > 0` in `FullAv`/`AudioOnly` mode.

### 4) Instrument native ingress callsites in `ExportFeature` backend path
**Change required**
- Add counters around `pushVideoAccessUnit` and `pushAudioAccessUnit` calls.
- Capture failures by error domain (`ingress_rejected`, `backend_not_ready`, `native_error`).
- Include these counters in Screen 2 diagnostics text.

**Pass criteria**
- Ingress call counts track encoder output counts with no sustained flatline mismatch.

### 5) Replace JNI stub internals in `app/src/main/cpp/templei_ffmpeg_stub.cpp`
**Change required**
- Implement real FFmpeg runtime:
  - `avformat_alloc_output_context2` with `mpegts` and SRT URL.
  - stream creation for H.264 and AAC.
  - `avformat_write_header` on start.
  - `av_interleaved_write_frame` on AU push.
  - `av_write_trailer` + cleanup on stop.
- Keep JNI signatures unchanged.

**Pass criteria**
- Runtime info no longer identifies as stub and write counters increase.

### 6) Implement timestamp/timebase normalization for both streams
**Change required**
- Convert encoder microsecond PTS into stream timebase with `av_rescale_q`.
- Ensure per-stream monotonic DTS/PTS.
- Add correction counters for clamped/dropped out-of-order timestamps.

**Pass criteria**
- OBS opens reliably and logs show no persistent timestamp warnings.

### 7) Ensure codec configuration is muxer-ready
**Change required**
- Confirm H.264 SPS/PPS availability before/with first IDR packet.
- Confirm AAC codec config/extradata consistency for MPEG-TS.
- Fail fast with actionable error text if config is missing.

**Pass criteria**
- OBS stops reporting repeated "Failed to open media" once stream starts.

### 8) Add transport health counters in native runtime
**Change required**
- Add counters for:
  - connect attempts/success/failure
  - packets written
  - bytes written
  - consecutive write failures
  - last successful write time
- Expose summary in `nativeRuntimeInfo`/diagnostics.

**Pass criteria**
- Reachable OBS shows steadily increasing packet/byte counts.

### 9) Add bounded reconnect policy for SRT write/connect failures
**Change required**
- Add retry budget with bounded backoff.
- Transition to explicit terminal failure after budget exhaustion.
- Surface final failure reason to Screen 2.

**Pass criteria**
- Unreachable OBS produces clear retry + terminal-failure reporting.

### 10) Upgrade Screen 2 interoperability text for operator decisions
**Change required**
- Add concise status slices for:
  - media ingress health,
  - native packet-write health,
  - connection state (`connected` / `retrying` / `faulted`).
- Keep Screen 2 free of camera preview UI.

**Pass criteria**
- Operators can distinguish config, ingress, and transport failures from one status view.

### 11) Add automated verification coverage for counters and failure domains
**Change required**
- Add/expand unit tests for:
  - ingress counter movement,
  - stall detection,
  - failure-domain mapping,
  - status-text composition.
- Keep tests deterministic with fake clock/input where practical.

**Pass criteria**
- Local unit checks pass and protect against false-positive "Streaming" state.

### 12) Execute release validation matrix before marking runtime ready
**Change required**
- Validate all three modes: `VideoOnly`, `FullAv`, `AudioOnly`.
- Run at least 10 Start/Stop cycles.
- Run at least one 15+ minute session.
- Verify OBS Media Source uses `mpegts` and ingest succeeds.

**Pass criteria**
- Runtime may be marked ready only when ingress, packet-write, and OBS playback all pass.

---

## Delivery order and required PR count

To reduce risk and keep review size manageable, this buildout is being executed in **7 PRs total**.

1. **PR 1:** Finalize actionable scope and acceptance language in this document, lock the 12-item checklist, and confirm release gating semantics.
2. **PR 2:** Items 1–2 (camera + video ingress counters and diagnostics wiring).
3. **PR 3:** Items 3–4 (audio ingress counters + backend ingress/failure-domain reporting in Screen 2).
4. **PR 4:** Items 5–7 (native FFmpeg runtime replacement, timestamp normalization, codec-config readiness checks).
5. **PR 5:** Items 8–10 (transport health counters, reconnect behavior, and Screen 2 operator clarity updates).
6. **PR 6:** Items 11–12 (unit validation expansion + full OBS validation matrix and runtime-ready gate evidence).
7. **PR 7:** Operator-status refinement + legacy-path documentation cleanup (post-validation polish).

Native runtime variability and follow-up operator clarity updates resulted in a 7-PR execution path for this plan.

---


## PR 5 implementation status (transport health + operator clarity)

Completed in this round:
- Added native transport-health counters in JNI diagnostics snapshot for:
  - `connectAttempts`, `connectSuccess`, `connectFailures`,
  - `consecutiveWriteFailures`,
  - `lastSuccessfulWriteMs`.
- Added bounded start retry policy on FFmpeg backend start attempts with explicit terminal failure wording when retry budget is exhausted.
- Surfaced connection state in backend diagnostics (`connState=connected|retrying|faulted|idle`) and propagated it into Screen 2 streaming health text (`conn=<state>`).

Validation note:
- Android Gradle checks remain environment-blocked in this container until SDK path is configured (`ANDROID_HOME`/`ANDROID_SDK_ROOT` or `local.properties` `sdk.dir`).

---


## PR 6 implementation status (validation expansion + release gate evidence)

Completed in this round:
- Expanded JVM unit coverage around health-hint behavior for start-state, warning precedence, and healthy packet-output cases.
- Added an explicit release validation matrix checklist and gate table so readiness decisions are recorded consistently.

### Release validation matrix (execution log)
| Item | Target | Evidence field | Status |
|---|---|---|---|
| Stream mode coverage | `VideoOnly`, `FullAv`, `AudioOnly` | mode + diagnostics snapshot | Pending (device/OBS run required) |
| Start/Stop stability | >= 10 consecutive cycles | cycle index + outcome | Pending (device/OBS run required) |
| Long-session stability | >= 15 minutes primary mode | duration + drift/errs summary | Pending (device/OBS run required) |
| OBS ingest | Media Source opens `mpegts` stream | OBS open/play result + timestamps | Pending (device/OBS run required) |

### Runtime-ready release gate (must all be true)
- [ ] Native runtime is non-stub and reports active transport path.
- [ ] AU ingress counters increase in active mode(s).
- [ ] Packet/byte counters increase with no persistent write-failure streak.
- [ ] OBS playback succeeds in at least one full matrix pass.

Validation note:
- In this container, Android SDK is unavailable, so Gradle Android tasks cannot be executed until `ANDROID_HOME`/`ANDROID_SDK_ROOT` or `local.properties` (`sdk.dir`) is configured.

---


## PR 7 implementation status (operator status refinement)

Completed in this round:
- Refined Screen 2 streaming status composition to include explicit operator-facing slices for:
  - `media=<flowing|stalled>`,
  - `packetWrite=<active|pending|faulted>`,
  - `conn=<connected|retrying|faulted|idle|unknown>`.
- Kept screen ownership unchanged (no camera preview UI added to Screen 2).
- Recorded plan closeout as a 7-PR execution path due post-validation operator-clarity polish.

---

## Definition of done
TempleI can start from Screen 2, stream to OBS over SRT, and show diagnostics proving:
1. encoded media ingress is active,
2. native mux/write path is active,
3. OBS receives and plays the stream reliably.
