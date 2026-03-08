# FFmpeg-backed SRT Migration + Usage Guide

This guide defines the practical migration path from the current custom mux/sender pipeline to a FFmpeg-backed mux+send runtime while keeping the Screen 1 / Screen 2 user experience unchanged.

## Intent
- Keep **Screen 1** as camera preview/capture controls.
- Keep **Screen 2** as OBS endpoint/configuration + Start/Stop control surface.
- Keep transport target as OBS Media Source ingest over **SRT** with input format **mpegts**.
- Replace custom TS packetization/sender internals with proven FFmpeg mux/protocol runtime.

## Current status snapshot
- FFmpeg backend is the active Screen 2 transport path.
- Stream mode gating supports `FullAv`, `VideoOnly`, and `AudioOnly`.
- Native runtime remains bring-up oriented; full production mux/send internals are still in progress.
- Follow-up PR6/PR7 delivered validation-matrix docs and operator-status refinements (`media`, `packetWrite`, `conn`).

## Scope guardrails
- Do not add camera preview UI to Screen 2.
- Do not expand stream modes beyond:
  - `FullAv`
  - `VideoOnly`
  - `AudioOnly`
- Preserve package namespace `com.example.templei`.
- Keep status/diagnostics contract visible in Screen 2 so operators can debug startup/health.

---

## PR A — Backend abstraction + non-invasive wiring

### Objective
Create a runtime backend boundary that allows swapping internal transport implementation without changing Screen 2 UX or state flow.

### Deliverables
1. Add a backend contract in `feature/export` (for example `NativeStreamBackend.kt`) with explicit lifecycle APIs:
   - `prepare(endpoint, streamMode)`
   - `start()`
   - `pushVideoAccessUnit(...)`
   - `pushAudioAccessUnit(...)`
   - `stop()`
   - `availabilityMessage()`
2. Keep current `ExportFeature` and service/session behavior intact while delegating transport work through the abstraction.
3. Legacy node fallback is now removed; Screen 2 transport only reports FFmpeg backend readiness.

### Validation
- Unit/static checks for compile safety.
- Verify Screen 2 button flows and statuses remain unchanged.

---

## PR B — FFmpeg + libsrt build/packaging integration

### Objective
Add deterministic build path for FFmpeg runtime artifacts with SRT protocol support.

### Deliverables
1. Add build helper script (for example `scripts/build-ffmpeg-android.sh`) to produce per-ABI shared libs.
2. Enable FFmpeg SRT + MPEG-TS capabilities:
   - `--enable-protocol=srt`
   - `--enable-libsrt`
   - `--enable-muxer=mpegts`
3. Package generated libs under `app/src/main/jniLibs/<abi>/`.
4. Add Gradle verification tasks (similar to existing `verifySrtDependency`) to fail fast when required FFmpeg artifacts are missing.

### Validation
- Run build helper script in environment with NDK/network.
- Verify generated libs are packaged and loadable.

---

## PR C — FFmpeg runtime path (VideoOnly first) [completed]

### Objective
Implement FFmpeg-backed transport path for video-only mode to minimize bring-up complexity.

### Deliverables
1. Add JNI bridge (for example `FfmpegNativeBridge.kt` + native implementation) that:
   - initializes FFmpeg output context for `srt://...`
   - configures `mpegts` mux output
   - accepts H.264 access units from `VideoEncoderNode`
2. Route `VideoOnly` mode through FFmpeg backend as the initial accepted runtime mode for bring-up (completed in PR C).
3. Keep metrics and Screen 2 diagnostics updates flowing as today.

### Validation
- OBS Media Source receives video in `VideoOnly` mode.
- No regressions in Start/Stop lifecycle and session state handling.

---

## PR D — Full A/V FFmpeg path + clock alignment [completed/in-progress tuning]

### Objective
Extend FFmpeg path to include AAC audio with stable A/V timestamps.

### Deliverables
1. Feed AAC access units from `AudioEncoderNode` into FFmpeg mux backend (JNI bridge ingress + mode gating are implemented; full mux timing tuning continues).
2. Align stream timebase/PTS strategy for long-session stability.
3. Maintain stream mode gating behavior:
   - `FullAv` -> video + audio
   - `VideoOnly` -> video only
   - `AudioOnly` -> audio only (if operator requires)
4. Keep Screen 2 diagnostics text clear for operator troubleshooting.

### Validation
- OBS ingest verified for `FullAv`.
- Extended run checks for drift/stability.

---

## PR E — Cutover, cleanup, and operator usage docs [completed with follow-up PR6/PR7 refinements]

### Objective
Make FFmpeg path primary, retire legacy custom internals where safe, and provide final operator/developer usage documentation.

### Deliverables
1. Promote FFmpeg backend to default runtime path.
2. Remove or archive legacy custom mux/srt runtime paths after rollout confidence.
3. Update docs with final operational runbook (this section + Screen 2 plan).
4. Confirm diagnostics still indicate readiness/failure origin clearly.

### Validation
- End-to-end OBS ingest acceptance checklist passes.
- Failure messages are explicit and actionable.

---

## Usage guide (developer + operator)

## 1) Build prerequisites
- Android SDK configured (`ANDROID_HOME` or `ANDROID_SDK_ROOT`, or `local.properties sdk.dir`).
- Android NDK configured (`ANDROID_NDK_HOME`) for native builds.
- Network access for first-time upstream fetch/build when local cache is absent.

## 2) Build native dependencies
- Build/refresh SRT dependency (existing flow):
  - `./gradlew :app:buildSrtArm64`
- Build FFmpeg Android artifacts (new PR B flow):
  - `./gradlew :app:buildFfmpegArm64`
  - or directly `./scripts/build-ffmpeg-android.sh arm64-v8a`

## 3) Build and install app
- `./gradlew :app:assembleDebug`
- Install on physical device (arm64 preferred during early rollout).

## 4) Screen 2 OBS setup
1. Open Screen 2.
2. Set host/IP and port.
3. Validate endpoint.
4. Copy OBS input URL from Screen 2.
5. In OBS Media Source:
   - Local File: Off
   - Input: `srt://<host>:<port>?mode=listener...`
   - Input format: `mpegts`
6. Press Start in Screen 2.

## 5) Runtime verification
- Confirm Screen 2 state transitions to Streaming.
- Confirm diagnostics indicate transport readiness and active packet flow.
- Confirm OBS preview/video is stable.

## 6) Failure triage checklist
- If Start faults immediately:
  - verify host/port validity
  - verify native libs packaged for ABI
  - verify FFmpeg backend availability message
- If OBS fails probe/open:
  - capture Android logs and OBS log lines
  - verify startup burst/first-keyframe behavior and packet flow continuity

---

## Suggested acceptance criteria before final cutover
- `VideoOnly` stable for 15+ minute run.
- `FullAv` stable for 15+ minute run without drift spikes.
- Start/Stop/restart works across 10 consecutive attempts.
- No Screen 1 regressions in camera preview/capture behavior.
- Clear operator-facing diagnostics for all preflight failures.
