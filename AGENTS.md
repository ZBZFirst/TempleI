# TempleI Agent Handoff (Reviewed 2026-03-11 Night)

This file records the current execution baseline for the Android -> OBS SRT streaming workflow and the immediate TODO backlog.

## Mission (unchanged)
Deliver a reliable real-time video feed from Android into OBS using:
- SRT transport
- OBS Media Source Input URL
- `input_format=mpegts`

## Codebase Reality Snapshot
- Active operator flow is **Screen 1 first** (camera + OBS controls in one place).
- Screen 2 is retained as a fallback/wrapper host for continuity and verification.
- Stream mode defaults to **Video Only**; stored legacy `ConnectionOnly` values are coerced to `VideoOnly` on load.
- Startup failure UX separates **endpoint preflight failures** from **runtime start failures**.
- Runtime health and diagnostics are surfaced via:
  - native runtime mode/connection state,
  - packet counters,
  - startup phase timeline,
  - deterministic diagnostics snapshots (`runId` + adb filter command metadata).
- Health must not be marked "healthy" without packet evidence.

## Stabilization Steps A-F Status
All previously planned stabilization steps remain complete in code/tests:
- ✅ Step A: endpoint handshake validation + malformed endpoint block + last effective URL.
- ✅ Step B: operator continuity during start + start lock + timestamped startup progress.
- ✅ Step C: non-stub runtime gating + runtime health panel.
- ✅ Step D: packet-write readiness gate + ingress-vs-packet warning threshold.
- ✅ Step E: deterministic diagnostics snapshot + standardized adb filter metadata + copy action.
- ✅ Step F: focused tests around endpoint validation, start transitions, and packet-write gating scaffolding.

## Outstanding TODOs (from current source)
1. **On-device validation pass (highest priority):**
   - verify OBS ingest end-to-end for Video Only default, then Audio Only and Full AV.
   - confirm first packet-write evidence (`outputOpened=true`, `headerWritten=true`, packet counters > 0) before relying on healthy state.
   - capture one clean diagnostics snapshot from successful stream and one from a faulted stream for side-by-side comparison.
2. **Capture/export integration hardening:**
   - finish wiring/verification for camera + mic outputs across encoder/mux path boundaries where TODO scaffolding comments remain.
   - remove/refresh stale TODO comments once verified as complete to keep handoff truthful.
3. **Camera architecture cleanup:**
   - migrate callback-driven camera UI updates toward state-flow/ViewModel ownership.
   - evaluate whether recording/stream capture should run in a stronger foreground-service session for background resilience.
4. **Archived screens:**
   - Screen 3/4 remain placeholder/archive surfaces; either keep clearly marked as non-runtime or de-scope from nav in a future cleanup PR.
5. **Android backup config hygiene:**
   - resolve template TODO in `data_extraction_rules.xml` so backup/restore behavior is explicitly intentional.

## Guardrails for Future Edits
- Prefer incremental reliability changes over broad UI churn.
- Keep OBS instructions Input URL-centric for operators.
- Preserve deterministic diagnostics output shape so log-based triage stays scriptable.
- If startup/health logic changes, maintain the invariant: **no healthy state without packet evidence**.
