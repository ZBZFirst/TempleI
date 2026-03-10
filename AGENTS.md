# TempleI Execution Plan (Reset)

This file replaces prior onboarding/checklist docs and focuses only on the work needed to achieve a stable Android -> OBS live feed.

## Goal
Deliver a reliable real-time video feed from Android into OBS using:
- SRT transport
- OBS Media Source Input URL
- `input_format=mpegts`

## Current Known State
- Screen 2 operator workflow and wording are in place.
- Screen 3/4 are archived from runtime flow.
- Transport/native runtime still needs production-grade integration validation.

## Immediate Next Steps (Required)

### Step A — Fix and validate endpoint handshake path
1. Add explicit URL validation before start:
   - ensure OBS Input URL uses `mode=listener` and Android transport uses `mode=caller`
   - ensure host/port are non-empty/valid
2. Surface a hard-blocking UI error if malformed endpoint is detected.
3. Add a visible "last effective URL" field in Screen 2 status to avoid copy/paste corruption.

### Step B — Keep operator on Screen 2 during start
1. Confirm start action does not trigger accidental navigation away from Screen 2.
2. Lock start button while `Starting` and unlock only on `Streaming` or `Faulted`.
3. Show a startup progress state with timestamped phase updates.

### Step C — Verify native runtime is truly active (not stub)
1. Gate "Streaming" success on runtime stats showing active non-stub behavior.
2. If runtime indicates `runtimeMode=stub`, force `Faulted` with explicit remediation text.
3. Add a concise runtime health panel in Screen 2:
   - runtime mode
   - connection state
   - packets written
   - last native error

### Step D — Enforce mux/write readiness before claiming healthy stream
1. Require non-zero packet output (`packetsWritten > 0`) before "healthy" status.
2. Keep interop stage-gate summaries visible and actionable.
3. Add threshold-based warning if ingress is non-zero but packet output stays zero.

### Step E — Add deterministic bring-up diagnostics for every run
1. Standardize adb logcat filters for transport/mux/encoder startup evidence.
2. Capture first 30 seconds of startup logs to file with run ID.
3. Add a "copy diagnostics snapshot" action in Screen 2 for quick triage.

### Step F — Add focused tests for start-path correctness
1. Unit tests: endpoint validation and malformed URL rejection.
2. Unit tests: state machine transitions (`Idle -> Starting -> Streaming/Faulted`).
3. Integration test scaffold: start session with fake backend and assert packet-write gate behavior.

## Definition of Done
The workflow is considered complete only when all are true:
1. OBS connects using app-provided Input URL without manual edits.
2. Android start action remains on Screen 2 with clear phase/status updates.
3. Runtime reports non-stub active path.
4. Packet output is non-zero and stable during session.
5. OBS no longer reports repeated SRT I/O open failures for valid endpoint runs.

## Guardrails
- Do not re-introduce Screen 3/4 into active runtime flow until feed reliability is complete.
- Do not label stream state as "healthy" unless packet write evidence is present.
- Keep all user-facing instructions explicitly in Input URL terms (not local file semantics).
