# TempleI Codebase Problems Inventory (Streaming + Related)

This document lists the currently known problems in the codebase, with emphasis on the Screen 2 OBS/SRT path.

## 1) Native FFmpeg runtime is still scaffolded, not full mux/send
- `templei_ffmpeg_stub.cpp` still identifies itself as a stub/pending scaffold.
- Runtime probe validates symbol presence, but there is no complete `libavformat` packetization/write pipeline yet.
- Effect: app can reach started/streaming state without emitting a usable MPEG-TS stream for OBS.

## 2) No confirmed native packet-write path in active FFmpeg bridge
- Stats include packet/byte counters, but codepath still centers on ingest diagnostics.
- The planned `avformat_write_header` / `av_interleaved_write_frame` / trailer lifecycle is still a pending target.
- Effect: OBS Media Source may fail open/probe even when app reports streaming.

## 3) Control-path state can overstate true transport health
- Session state can report `Streaming` while media ingress/packet output remains zero.
- Health hints now expose some of this, but state naming still risks operator confusion.
- Effect: false confidence in stream health until counters are inspected.

## 4) CaptureCoordinator TODO indicates capture-path implementation is still iterative
- Coordinator comments still describe incomplete camera/mic attachment expectations.
- Effect: known risk that parts of the camera->encoder->backend path remain fragile across devices.

## 5) Video encoder ingress can still stall under device/runtime variance
- Added queue/drop diagnostics improved visibility, but root-cause handling remains limited.
- Backpressure/availability conditions at encoder input can still produce zero encoded output.
- Effect: no AUs reach backend, resulting in OBS no-feed symptoms.

## 6) A/V alignment is diagnostic-first, not production-stable
- PTS normalization and A/V delta metrics exist, but full timebase design/tuning is still incomplete.
- Effect: potential drift, timing corrections, and interop instability over longer runs.

## 7) Screen 2 transport internals still marked in-progress in service/export boundaries
- Service and export modules include TODO notes about pending native mux/srt integration.
- Effect: architecture boundaries are present, but internals are not at final runtime maturity.

## 8) Legacy/native transport archive nodes still report pending runtime
- `TsMuxerNode` and `SrtTransportNode` include runtime-pending messages and are not final active runtime.
- Effect: historical/archival path remains in tree and can confuse readiness interpretation.

## 9) Build/runtime dependency fragility (SDK/NDK/libsrt)
- Android SDK path is required for Gradle Android tasks and is missing in some environments.
- `libsrt.so` availability remains a hard requirement for sender runtime and can fail builds/runs when absent.
- Effect: validation/build reliability depends on local environment correctness.

## 10) End-to-end OBS interoperability acceptance is not fully closed
- Current state still requires planned follow-through for real FFmpeg mux/send and sustained OBS ingest tests.
- Effect: functional gap remains between diagnostics-rich scaffold and production-ready streaming.

## 11) Operator-facing diagnostics are dense and can be hard to triage quickly
- Status output includes many fields in one block.
- Effect: useful technically, but difficult for rapid operator decisions without a simplified health tier.

## 12) Documentation acknowledges unresolved implementation phases
- Existing planning docs explicitly list pending work for native runtime replacement and tuning.
- Effect: confirms that additional PR rounds are expected before stable OBS streaming.
