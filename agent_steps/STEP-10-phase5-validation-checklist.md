# STEP 10 — Phase 5 Validation Checklist

## Objective
Provide a single operator + engineering checklist to validate Phase 5 outcomes for FFmpeg egress reliability after Phases 1-4.

## Scope
- Canonical write-counter parsing and health gating behavior.
- Runtime diagnostics completeness for lifecycle and write failure evidence.
- Device-level OBS ingest verification for Input URL + `mpegts` workflow.

## A) Local test checklist (engineering)
- [ ] Run unit tests for export feature package.
- [ ] Confirm parser precedence uses `writePacketsSucceeded` before legacy aliases.
- [ ] Confirm stream health remains **not healthy** when lifecycle flags are missing.
- [ ] Confirm stream health can become **healthy** only with:
  - `connState=connected`
  - `writePacketsSucceeded > 0`
  - `consecutiveWriteFailures=0`
  - `outputOpened=true`
  - `headerWritten=true`

## B) Diagnostics snapshot checklist (engineering/operator)
Generate a diagnostics snapshot during startup and verify presence of:
- [ ] `runtimeMode`
- [ ] `connectionState`
- [ ] `packetsWritten`
- [ ] `muxPacketsProduced`
- [ ] `writePacketsFailed`
- [ ] `consecutiveWriteFailures`
- [ ] `outputOpened`
- [ ] `headerWritten`
- [ ] `lastNativeError`
- [ ] `obsInputUrl`
- [ ] `transportCallerUrl`

## C) Device + OBS validation checklist (operator)
1. In OBS Media Source:
   - [ ] `Local File = Off`
   - [ ] Paste Screen 2 Input URL into **Input URL**
   - [ ] Set Input Format to `mpegts`
2. In TempleI Screen 2:
   - [ ] Validate endpoint host/port
   - [ ] Start stream
3. Observe runtime panel and interop line:
   - [ ] Runtime mode is non-stub
   - [ ] Connection shows connected
   - [ ] Packet/write counters progress
   - [ ] Interop status reaches `STREAM HEALTHY`
4. Failure behavior:
   - [ ] If write failures occur, health degrades (not healthy/faulted)
   - [ ] Diagnostics snapshot captures failure fields and `lastNativeError`

## D) Exit criteria for Phase 5
Phase 5 is considered complete when:
- [ ] Local tests covering parser + health gate scenarios pass.
- [ ] Diagnostics snapshot consistently includes lifecycle/write evidence fields.
- [ ] Device/OBS session confirms real moving video ingest and healthy transitions only with write evidence.
- [ ] At least one induced-failure run confirms non-healthy/faulted behavior and actionable diagnostics.
