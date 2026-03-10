# STEP 04 — Operator Workflow Enforcement

> **Completion protocol:** check off each item when complete, and update the **Codebase Reflection** section with file/line evidence showing where completion is represented.

## Objective
Deliver a predictable operator sequence from OBS listener setup through successful stream start.

## Checklist (How to accomplish)
- [ ] Preserve the sequence: OBS listener first → configure host/port in app → copy/paste input URL → start stream.
- [ ] Ensure Screen 2 presents enough context to execute this sequence without external tribal knowledge.
- [ ] Ensure error states explain the next operator action.

## Codebase Reflection (What proves it is done)
- [ ] Screen 2 strings and dialogs present workflow guidance.
- [ ] Start/stop controls map to clear session states.
- [ ] Diagnostics/status include actionable hints.

## Verification notes
- Manual walkthrough on Screen 2 plus logs for start/stop transitions.
