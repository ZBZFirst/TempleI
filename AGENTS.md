# AGENTS.md

Project guidance for contributors working in this repository.

## Scope
This file applies to the entire repository rooted at `/workspace/TempleI`.

## Current project state
- The app is currently XML-first and screen-oriented.
- `activity_main.xml` acts as the launcher menu.
- `activity_screen1.xml` through `activity_screen4.xml` are shell UIs intended for iterative build-out.
- Activity classes are intentionally light and should remain easy to read while screens are scaffolded.

## Working conventions
- Prefer small, incremental commits with clear messages.
- Keep UI strings in `app/src/main/res/values/strings.xml` (avoid hardcoded text when practical).
- Add short XML/Kotlin comments for placeholders and TODO sections so shell code is self-explanatory.
- If introducing new activities, ensure they are declared in `AndroidManifest.xml`.
- Preserve existing package namespace: `com.example.templei`.

## Validation guidance
- Run Gradle checks where environment permits.
- If Android SDK is unavailable, still perform static review and clearly report the limitation.

## Obsidian file index (wikilinks)
Use the entries below for Obsidian graph/linking. Paths are repo-relative and intentionally nested.

- [[AGENTS.md]]
- [[build.gradle.kts]]
- [[settings.gradle.kts]]
- [[gradle.properties]]
- [[gradlew]]
- [[gradlew.bat]]
- [[gradle/libs.versions.toml]]
- [[gradle/wrapper/gradle-wrapper.properties]]
- [[gradle/wrapper/gradle-wrapper.jar]]
- [[app/build.gradle.kts]]
- [[app/proguard-rules.pro]]
- [[app/src/main/AndroidManifest.xml]]
- [[app/src/main/java/com/example/templei/MainActivity.kt]]
- [[app/src/main/java/com/example/templei/Screen1Activity.kt]]
- [[app/src/main/java/com/example/templei/Screen2Activity.kt]]
- [[app/src/main/java/com/example/templei/Screen3Activity.kt]]
- [[app/src/main/java/com/example/templei/Screen4Activity.kt]]
- [[app/src/main/java/com/example/templei/feature/camera/CameraFeature.kt]]
- [[app/src/main/java/com/example/templei/feature/export/ExportFeature.kt]]
- [[app/src/main/java/com/example/templei/feature/export/StreamSessionService.kt]]
- [[app/src/main/java/com/example/templei/feature/export/CaptureCoordinator.kt]]
- [[app/src/main/java/com/example/templei/feature/export/VideoEncoderNode.kt]]
- [[app/src/main/java/com/example/templei/feature/export/AudioEncoderNode.kt]]
- [[app/src/main/java/com/example/templei/feature/export/TsMuxerNode.kt]]
- [[app/src/main/java/com/example/templei/feature/export/SrtTransportNode.kt]]
- [[app/src/main/java/com/example/templei/feature/export/ObsEndpointSpec.kt]]
- [[app/src/main/java/com/example/templei/feature/export/StreamState.kt]]
- [[app/src/main/java/com/example/templei/ui/components/PulseButton.kt]]
- [[app/src/main/java/com/example/templei/ui/components/UiPaletteBar.kt]]
- [[app/src/main/java/com/example/templei/ui/navigation/NavGraph.kt]]
- [[app/src/main/java/com/example/templei/ui/navigation/Routes.kt]]
- [[app/src/main/java/com/example/templei/ui/navigation/TopNavigation.kt]]
- [[app/src/main/java/com/example/templei/ui/state/HomeEvent.kt]]
- [[app/src/main/java/com/example/templei/ui/state/HomeUiState.kt]]
- [[app/src/main/java/com/example/templei/ui/theme/Color.kt]]
- [[app/src/main/java/com/example/templei/ui/theme/Theme.kt]]
- [[app/src/main/java/com/example/templei/ui/theme/Type.kt]]
- [[app/src/main/res/layout/activity_main.xml]]
- [[app/src/main/res/layout/activity_screen1.xml]]
- [[app/src/main/res/layout/activity_screen2.xml]]
- [[app/src/main/res/layout/activity_screen3.xml]]
- [[app/src/main/res/layout/activity_screen4.xml]]
- [[app/src/main/res/layout/view_top_navigation.xml]]
- [[app/src/main/res/values/strings.xml]]
- [[app/src/main/res/values/colors.xml]]
- [[app/src/main/res/values/themes.xml]]
- [[app/src/main/res/xml/backup_rules.xml]]
- [[app/src/main/res/xml/data_extraction_rules.xml]]
- [[app/src/main/res/drawable/ic_launcher_background.xml]]
- [[app/src/main/res/drawable/ic_launcher_foreground.xml]]
- [[app/src/main/res/mipmap-anydpi/ic_launcher.xml]]
- [[app/src/main/res/mipmap-anydpi/ic_launcher_round.xml]]
- [[app/src/main/res/mipmap-mdpi/ic_launcher.webp]]
- [[app/src/main/res/mipmap-mdpi/ic_launcher_round.webp]]
- [[app/src/main/res/mipmap-hdpi/ic_launcher.webp]]
- [[app/src/main/res/mipmap-hdpi/ic_launcher_round.webp]]
- [[app/src/main/res/mipmap-xhdpi/ic_launcher.webp]]
- [[app/src/main/res/mipmap-xhdpi/ic_launcher_round.webp]]
- [[app/src/main/res/mipmap-xxhdpi/ic_launcher.webp]]
- [[app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp]]
- [[app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp]]
- [[app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp]]
- [[app/src/test/java/com/example/templei/ExampleUnitTest.kt]]
- [[app/src/androidTest/java/com/example/templei/ExampleInstrumentedTest.kt]]
- [[docs/screen2-obs-streaming-plan.md]]


## Current implementation snapshot (as of latest reviewed commit)
- `Screen1Activity` + `CameraFeature` currently provide camera preview, picture capture, and video recording (with microphone) and persist media into `Pictures/TempleI` and `Movies/TempleI`.
- `Screen2Activity` and `activity_screen2.xml` now implement OBS SRT ingest configuration and control wiring (host/port edit, validate/test, preset reset, URL display, profile toggle, start/stop), and bind to a foreground-capable stream session service boundary.
- Screen responsibility split is strict:
  - Screen 1 hosts camera preview/capture controls.
  - Screen 2 hosts configuration + start/stop stream commands only, with **no camera preview UI**.
- `feature/export/ExportFeature.kt` now holds Screen 2 config persistence, validation, session state, OBS URL generation, and a transport gateway that now routes through TS mux/SRT node contracts; Rounds 3-6 add capture-path coordination, video/audio/mux/transport endpoint contracts, and interop diagnostics.

## File structure snapshot and update targets
Use this as the practical "what exists now" map before editing:

- `app/src/main/java/com/example/templei/`
  - `Screen1Activity.kt` (working camera UI; avoid regressions)
  - `Screen2Activity.kt` (streaming setup/orchestration UI host)
  - `feature/camera/CameraFeature.kt` (existing capture pipeline)
  - `feature/export/ExportFeature.kt` (Screen 2 workflow state + transport boundary stub)
  - `feature/export/StreamSessionService.kt` (service/session command boundary for Screen 2)
  - `feature/export/CaptureCoordinator.kt` (video-path readiness coordinator for Screen 2 start flow)
  - `feature/export/VideoEncoderNode.kt` (H.264 encoder node contract placeholder)
  - `feature/export/AudioEncoderNode.kt` (AAC-LC audio encoder node contract placeholder)
  - `feature/export/TsMuxerNode.kt` (MPEG-TS mux contract placeholder)
  - `feature/export/SrtTransportNode.kt` (SRT sender contract placeholder)
  - `feature/export/ObsEndpointSpec.kt` (OBS endpoint URL contract)
  - `feature/export/StreamState.kt` (pipeline state contract for transport orchestration)
- `app/src/main/res/layout/`
  - `activity_screen1.xml` (active camera controls + preview)
  - `activity_screen2.xml` (OBS/SRT planning controls)
- `app/src/main/res/values/strings.xml`
  - Holds both Screen 1 camera strings and Screen 2 OBS-planning strings.
- `docs/screen2-obs-streaming-plan.md`
  - High-level phased plan for Android → OBS over LAN.

Expected near-term files to be created/expanded for OBS LAN implementation:
- `app/src/main/java/com/example/templei/ui/state/` additions for Screen 2 view state/events.

## OBS-over-LAN implementation plan (realistic iteration count)
Use small milestones and expect multiple back-and-forth cycles. A realistic path is **6 to 9 PRs** (or equivalent coding rounds), not one pass.

1. **Contract + config pass (1 round)**
   - Finalize endpoint schema, stream state enum, failure domain taxonomy, and Screen 2 configuration model.
2. **Service/session pass (1 to 2 rounds)**
   - Add foreground streaming service boundary and lifecycle-safe command channel from Screen 2.
3. **Video path pass (1 round)**
   - Camera frame path into H.264 encoder with stable timestamps and diagnostics.
4. **Audio path pass (1 round)**
   - Microphone ingest into AAC-LC encoder with A/V clock alignment decisions.
5. **Mux + transport pass (1 to 2 rounds)**
   - MPEG-TS packetization + SRT transport, endpoint handling, and reconnect policy.
6. **OBS interoperability + tuning pass (1 to 2 rounds)**
   - Validate with OBS Media Source settings, latency tuning, error recovery behavior, and user-facing status reporting in Screen 2.

Risks that usually force extra back-and-forth:
- Device-specific encoder quirks (color format, bitrate behavior, thermal throttling).
- Audio/video drift under long sessions.
- SRT library/platform integration details and reconnect edge cases.
- Foreground service and permission behavior differences across Android versions.

## Completed work log (most recent first)
- Round 6 interoperability/tuning pass added: Screen 2 now surfaces explicit OBS interoperability readiness diagnostics tied to host/port validity and TS/SRT runtime availability.
- Round 5 mux/transport pass added: `TsMuxerNode` + `SrtTransportNode` + `ObsEndpointSpec`/`StreamState` contracts are now wired through `ExportFeature` gateway start/stop flow.
- Round 4 audio-path pass added: `AudioEncoderNode` contract and capture coordinator audio gating are now included before Screen 2 transport start.
- Round 3 video-path pass added: `CaptureCoordinator` + `VideoEncoderNode` contracts now gate Screen 2 Start flow and verify camera preview readiness before transport start.
- Foreground-capable service/session boundary added: `Screen2Activity` now lifecycle-binds to `StreamSessionService`, and Start/Stop route through the service binder command channel.
- Screen 2 OBS workflow now wires all eight existing buttons to host/port edit, validate/test, preset reset, input-string display, profile toggle, start, and stop actions.
- Screen 2 now renders required OBS outputs: setup summary, session state, validation message, last connection test, and last error.
- `ExportFeature` now persists OBS config, generates `srt://<host>:<port>?mode=listener`, and exposes a transport boundary with explicit native MPEG-TS + SRT unavailability messaging.
- Follow-up UX hardening completed:
  - Validate/Start now prompt for host when empty.
  - Host/port validation keeps session in `Idle` instead of forcing immediate `Faulted`.
  - `Faulted` is now reserved for attempted Start when native transport is unavailable.


## Round-by-round implementation checklist
- [COMPLETED] Round 1: Contract + config pass (endpoint model, Screen 2 validation/persistence, OBS URL generation).
- [COMPLETED] Round 2: Service/session pass (foreground stream service boundary and lifecycle-safe command channel).
- [COMPLETED] Round 3: Video path pass (camera encoded output routing into streaming pipeline).
- [COMPLETED] Round 4: Audio path pass (mic ingest + A/V clock alignment for stream path).
- [COMPLETED] Round 5: MPEG-TS mux + SRT transport pass (native mux/sender integration behind transport boundary).
- [COMPLETED] Round 6: OBS interoperability/tuning pass (latency, reconnect behavior, user-facing diagnostics).


## Runtime limitation and next implementation target
- Current status: **actual live OBS ingest is not fully connected yet**.
- Reason: `TsMuxerNode` and `SrtTransportNode` are contract placeholders and still report runtime unavailability until native mux/sender integration is implemented.
- Planned follow-up (next implementation target):
  1. Implement native MPEG-TS mux runtime behind `TsMuxerNode`.
  2. Implement native SRT sender runtime behind `SrtTransportNode`.
  3. Wire runtime availability checks to return ready states when native layers are loaded.
  4. Validate end-to-end ingest in OBS Media Source using Screen 2 Start/Stop flow.
  5. Update Screen 2 diagnostics text from "runtime pending" to live transport health states.

## Current problems (updated)
- SRT sender runtime still depends on a real `libsrt.so` at `app/src/main/jniLibs/arm64-v8a/libsrt.so` for device runtime.
- Auto-install/build of `libsrt.so` now requires two external prerequisites in local dev/CI:
  1. `ANDROID_NDK_HOME` configured.
  2. Network access to fetch upstream SRT source when local cache is absent.
- Android SDK path is still required for Gradle Android tasks (`ANDROID_HOME`/`ANDROID_SDK_ROOT` or `local.properties` with `sdk.dir`).

## Completion list (updated)
- [COMPLETED] Added sender dependency verification during `preBuild` to fail fast when `libsrt.so` is missing.
- [COMPLETED] Added automated sender dependency install path (`installSrtArm64` -> `buildSrtArm64`) so app builds attempt to install SRT library when absent.
- [COMPLETED] Added build helper script `scripts/build-libsrt-android.sh` to clone/build SRT and place `libsrt.so` under `jniLibs`.
- [COMPLETED] Updated docs (`app/src/main/jniLibs/README.md`, `docs/screen2-obs-streaming-plan.md`) to clarify sender obligations and build prerequisites.
- [NEXT] Provide stable CI-hosted/prebuilt `libsrt.so` artifacts for all required ABIs to remove network dependency during local/CI builds.


## Streaming bottleneck isolation progress log (new)
Use this section as the current source of truth for the camera -> encoder -> mux -> SRT diagnostic effort.

### Recently completed diagnostics and findings
- [COMPLETED] Mapped all currently configured camera outputs in the active pipeline:
  - `Preview` (`PreviewView.surfaceProvider`)
  - `ImageCapture`
  - `VideoCapture<Recorder>`
  - `ImageAnalysis` (`YUV_420_888`, keep-latest backpressure)
- [COMPLETED] Verified `ImageProxy` lifecycle handling:
  - Current `ImageAnalysis` callback closes `ImageProxy` in `finally`, covering early-return paths.
- [COMPLETED] Confirmed synchronous callback chain currently couples critical stages:
  - Camera analyzer callback -> `VideoEncoderNode.queueFrame`
  - `VideoEncoderNode.drainOutput` -> mux ingest
  - mux packet drain callback -> `SrtTransportNode.sendPacket`
- [COMPLETED] Identified likely first backpressure point under load:
  - Network send path (`nativeSendPacket` / `srt_send`) can stall and currently runs inline with upstream stage callbacks.
- [COMPLETED] Confirmed queue policy gaps contributing to stall amplification:
  - Mux pending AU queues are unbounded pre-start.
  - Native mux packet queue uses unbounded `std::deque`.
  - No explicit drop-on-overflow policy is currently enforced end-to-end.

### Targeted completion items (Option B execution plan)
Implement the following in small PR increments; keep variable declarations explicit up front per approval workflow.

1. **Metrics model extraction (Option B foundation)**
   - Add a dedicated metrics holder for stage timings/counters (new file allowed in Option B).
   - Track camera-arrival, encoder-in/out, mux-drain, and SRT-send timings with monotonic clocks.
   - Include rolling aggregates needed to report where backpressure first appears.

2. **Bounded queue boundaries between stages**
   - Introduce explicit bounded queue between camera and encoder input path.
   - Introduce explicit bounded queue between encoder output and mux ingest path.
   - Introduce explicit bounded queue between mux packet output and SRT send path.
   - Enforce non-blocking producer semantics on camera/encoder critical threads.

3. **Overflow behavior hardening**
   - On queue-full events, drop frames/packets instead of blocking critical callbacks.
   - Record per-stage drop counters and expose in diagnostics.
   - Document drop policy priority (freshness-first for real-time path).

4. **Stream mode scope (current product direction)**
   - Keep stream mode support limited to the existing three modes only:
     - `FullAv`
     - `VideoOnly`
     - `AudioOnly`
   - Continue persisting the selected mode with existing Screen 2 configuration storage.
   - Do not add preview-only/encoder-only/local-sink/reduced-profile modes in this iteration.

5. **Stage-origin backpressure reporting**
   - Add periodic structured diagnostic snapshots for:
     - stage latency
     - queue depth
     - drop counts
   - Emit explicit origin field indicating first stage crossing frame-budget threshold.
   - Surface latest origin/result in Screen 2 status text for operator debugging.

6. **Validation and closeout**
   - Run static/unit validation where environment allows.
   - If Android SDK is unavailable, record environment limitation and include static verification summary.


### Suggested PR slicing for the current fix path
Use this slicing to keep changes reviewable and to avoid coupling docs/scope updates with runtime refactors:

1. **PR 1 — Scope + ownership docs alignment**
   - Confirm Screen 1 vs Screen 2 responsibility language is explicit and consistent.
   - Ensure stream mode scope is documented as `FullAv`, `VideoOnly`, `AudioOnly` only.

2. **PR 2 — Metrics model foundation**
   - Add a dedicated diagnostics/metrics holder for stage timings, queue depths, and drop counters.
   - Keep this PR non-invasive (data model + wiring hooks only, no behavior changes).

3. **PR 3 — Bounded queue boundaries**
   - Introduce bounded non-blocking queue handoffs between camera->encoder, encoder->mux, and mux->srt paths.
   - Add explicit freshness-first drop policy + counters when queues are full.

4. **PR 4 — Backpressure origin reporting to Screen 2**
   - Add periodic structured snapshots and first-origin threshold reporting.
   - Surface latest origin summary in existing Screen 2 status output (without adding preview UI).

5. **PR 5 — Validation + docs closeout**
   - Add/update tests for queue/drop behavior and diagnostics summaries where feasible.
   - Refresh docs/status notes and clearly report any Android SDK environment limitations encountered during checks.
