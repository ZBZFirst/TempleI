# Streaming Success Plan (Short PR Sequence)

This plan is a focused recovery path to get TempleI from **"session starts"** to **actual OBS playback** with minimal PRs.

## Why this plan exists
Current Screen 2 interop output and native diagnostics indicate:
- Control/session path can start.
- Media ingress is often idle (`videoAu=0`, `audioAu=0`).
- Native FFmpeg layer still reports bring-up/stub runtime behavior.

So the immediate problem is not just OBS config; it is **pipeline continuity + native mux/send completion**.

---

## Target outcome
A single Start action on device should produce:
1. non-zero encoded AU counters,
2. non-zero packet/byte write counters,
3. stable OBS Media Source playback (`mpegts` over SRT),
4. actionable on-screen status when any stage fails.

---

## Proposed PR slicing (4 PRs)

### Progress status
- [COMPLETED] PR 1 — Stage-gate diagnostics and failure taxonomy hardening
- [COMPLETED] PR 2 — Guarantee media ingress continuity from capture/encoder path
- [COMPLETED] PR 3 — Replace FFmpeg JNI stubbed runtime internals with real mux/send path
- [COMPLETED] PR 4 — Device/OBS validation matrix + release gate

## PR 1 — Stage-gate diagnostics and failure taxonomy hardening
**Goal**: Make failures obvious and localizable in <10 seconds.

### Changes
- Add a strict stage-gate model in status text:
  - `capture`, `videoEncode`, `audioEncode`, `nativeIngress`, `muxWrite`, `transport`.
- Add `firstFailedStage` + `reasonCode` fields to Screen 2 interop summary.
- Keep operator text concise; move verbose payload to logs only.
- Add rate-limited structured logs for stage transitions.

### Acceptance criteria
- When stream fails, Screen 2 reports one clear first failing stage.
- No giant single-line dump in interop text.
- Unit tests cover `firstFailedStage` derivation and reason priority.

---

## PR 2 — Guarantee media ingress continuity from capture/encoder path
**Goal**: Ensure video/audio AUs actually reach native ingress while streaming.

### Changes
- Audit and enforce start-order contracts:
  - capture ready before stream start,
  - encoder output listeners active before backend start.
- Add explicit counters and mismatch alarms:
  - `encodedAuOut` vs `nativeIngressCalls`.
- Add bounded queue pressure events to status (`drop`/`backlog` reasons).
- Validate stream-mode behavior (`VideoOnly`, `FullAv`, `AudioOnly`) with dedicated assertions.

### Acceptance criteria
- In active mode, AU counters and ingress-call counters both increase.
- If mismatch persists, status reports `firstFailedStage=nativeIngress` (or upstream stage).
- No silent flatline while state says `Streaming`.

---

## PR 3 — Replace FFmpeg JNI stubbed runtime internals with real mux/send path
**Goal**: Deliver real MPEG-TS over SRT writes to OBS.

### Changes
- Implement full FFmpeg runtime sequence in native layer:
  - output context init (`mpegts`),
  - stream configuration,
  - header write,
  - interleaved frame writes,
  - trailer/cleanup.
- Add explicit runtime state fields:
  - `runtimeMode=stub|active`,
  - `packetsWritten`, `bytesWritten`, `lastWriteErr`.
- Keep guardrails for codec config readiness and monotonic timestamps.

### Acceptance criteria
- `runtimeMode=active` once start succeeds.
- `packetsWritten` and `bytesWritten` increase during stream.
- OBS can open and continue playback with no immediate disconnect loop.

---

## PR 4 — Device/OBS validation matrix + release gate
**Goal**: Prove the path is production-usable and capture evidence.

### Changes
- Execute and record matrix:
  - modes: `VideoOnly`, `FullAv`, `AudioOnly`,
  - >=10 start/stop cycles,
  - >=15 min long run in primary mode.
- Capture troubleshooting evidence template:
  - Screen 2 status snapshot,
  - relevant logcat window,
  - OBS result.
- Add go/no-go gate in docs with pass/fail checkboxes.

### Acceptance criteria
- Matrix evidence committed in docs.
- All go/no-go checks pass before declaring streaming complete.

### PR 4 execution evidence matrix
| Scenario | Target | Result | Evidence | Notes |
|---|---|---|---|---|
| VideoOnly mode ingest | OBS playback starts and remains stable | Pending | TBD | Run on device + OBS host |
| FullAv mode ingest | A/V present and stable | Pending | TBD | Verify sync + drift |
| AudioOnly mode ingest | Audio present and stable | Pending | TBD | Confirm no video required |
| Start/Stop resilience | >=10 cycles with no terminal fault | Pending | TBD | Capture cycle index + failures |
| Long-run stability | >=15 minutes continuous stream | Pending | TBD | Record final counters and health |

### PR 4 troubleshooting evidence template
- **Run ID**:
- **Device / Android version**:
- **OBS version / host OS**:
- **Mode** (`VideoOnly` / `FullAv` / `AudioOnly`):
- **Endpoint** (`host:port`):
- **Screen 2 health line**:
- **Native stats snapshot** (`runtimeMode`, `packetsWritten`, `bytesWritten`, `consecutiveWriteFailures`):
- **Logcat excerpt** (TempleI + ffmpeg lines):
- **OBS outcome** (opened/failed + message):
- **Pass/Fail**:

### Release gate checklist (go/no-go)
- [ ] `runtimeMode=active` in native diagnostics while streaming.
- [ ] `videoAu`/`audioAu` counters increase in selected stream mode.
- [ ] `packetsWritten` and `bytesWritten` counters increase during active stream.
- [ ] No persistent `consecutiveWriteFailures` growth while OBS is reachable.
- [ ] OBS media source successfully opens and plays for all required modes.
- [ ] Start/Stop cycle target and long-run target both pass.

> Environment note: Android SDK and physical-device/OBS validation are not available in this container, so PR 4 adds the validation matrix + release gate structure and evidence template; execution rows remain Pending until run on hardware.

---

## Execution notes
- Keep each PR small and testable.
- Prioritize **failing-stage visibility first**, then runtime replacement.
- If Android SDK is unavailable in CI/container, still run static/unit checks and record limitation explicitly.

## Suggested operator log capture commands (device)
```bash
adb logcat -c
adb logcat | rg "TempleI|Ffmpeg|Stream|SRT|mpegts"
```

## Suggested quick triage rule
- `videoAu/audioAu == 0` -> upstream capture/encoder/ingress issue.
- `videoAu/audioAu > 0` and `packetsWritten == 0` -> native mux/send issue.
- `packetsWritten > 0` and OBS not playing -> endpoint/OBS configuration or transport compatibility issue.
