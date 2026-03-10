# STEP 05 — Engineering Guardrails

> **Completion protocol:** check off each item when complete, and update the **Codebase Reflection** section with file/line evidence showing where completion is represented.

## Objective
Prevent regressions in ingest semantics and keep streaming UX explicit.

## Checklist (How to accomplish)
- [ ] Avoid introducing local-file semantics in UI/copy.
- [ ] Keep OBS paste-target discoverable in Screen 2 UX.
- [ ] Emit explicit and stage-specific diagnostics whenever feasible.

## Codebase Reflection (What proves it is done)
- [ ] Resource strings and status text align to input URL semantics.
- [ ] Export/diagnostic logic yields actionable state detail.
- [ ] PR notes call out guardrail-sensitive decisions.

## Verification notes
- Use `rg -n "local file|input|interop|diagnostic|fault" app/src/main`.
