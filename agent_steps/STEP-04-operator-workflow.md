# STEP 04 — Operator Workflow Enforcement

> **Completion protocol:** check off each item when complete, and update the **Codebase Reflection** section with file/line evidence showing where completion is represented.

## Objective
Deliver a predictable operator sequence from OBS listener setup through successful stream start.

## Checklist (How to accomplish)
- [x] Preserve the sequence: OBS listener first → configure host/port in app → copy/paste input URL → start stream.
- [x] Ensure Screen 2 presents enough context to execute this sequence without external tribal knowledge.
- [x] Ensure error states explain the next operator action.

## Codebase Reflection (What proves it is done)
- [x] Screen 2 strings and dialogs present workflow guidance.
- [x] Start/stop controls map to clear session states.
- [x] Diagnostics/status include actionable hints.

## Step 04 completion status
- Step 04 is complete for current baseline.
- Reflection evidence:
  - Section summaries and input dialog text now explicitly encode the expected OBS-first workflow and "press Start Stream" return step.
  - Screen 1 now hosts active runtime start/stop and status controls with Screen 2 control surface embedded below preview for continuous operation.
  - Screen 2 remains available as host-wrapper fallback while sharing the same stream-session behavior expectations.
  - Interop and validation text remain visible in operator status outputs to guide recovery.

## Verification notes
- Manual walkthrough on Screen 2 plus logs for start/stop transitions.
