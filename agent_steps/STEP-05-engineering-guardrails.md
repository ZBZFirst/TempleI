# STEP 05 — Engineering Guardrails

> **Completion protocol:** check off each item when complete, and update the **Codebase Reflection** section with file/line evidence showing where completion is represented.

## Objective
Prevent regressions in ingest semantics and keep streaming UX explicit.

## Checklist (How to accomplish)
- [x] Avoid introducing local-file semantics in UI/copy.
- [x] Keep OBS paste-target discoverable in Screen 2 UX.
- [x] Emit explicit and stage-specific diagnostics whenever feasible.

## Codebase Reflection (What proves it is done)
- [x] Resource strings and status text align to input URL semantics.
- [x] Export/diagnostic logic yields actionable state detail.
- [x] PR notes call out guardrail-sensitive decisions.

## Step 05 completion status
- Step 05 is complete for current baseline.
- Reflection evidence:
  - Screen 2 strings explicitly keep `Local File = Off` and `Input URL` wording visible in summary + dialog text.
  - Interop status text now guides operators to copy **Input URL** into OBS Media Source.
  - Streaming diagnostics continue to emit stage-oriented health detail (`stage{...} firstFailedStage=... reasonCode=...`).

## Verification notes
- Use `rg -n "local file|input|interop|diagnostic|fault" app/src/main`.
