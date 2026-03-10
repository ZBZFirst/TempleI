# STEP 02 — OBS Input Contract (Non-Negotiable)

> **Completion protocol:** check off each item when complete, and update the **Codebase Reflection** section with file/line evidence showing where completion is represented.

## Objective
Guarantee operator flow uses OBS Media Source **Input URL** (not local file mode).

## Checklist (How to accomplish)
- [ ] Ensure user-facing labels describe the ingest value as **Input URL** or **input string**.
- [ ] Prevent wording that implies local file playback.
- [ ] Keep generated ingest value in SRT URL shape (`srt://host:port?...`).

## Codebase Reflection (What proves it is done)
- [ ] Screen 2 string resources reference input URL workflow.
- [ ] Screen 2 dialogs/status show copy/paste input guidance, not file path guidance.
- [ ] URL-building code outputs SRT URL contract.

## Verification notes
- Use `rg -n "Local File|Input|input string|URL|srt://" app/src/main/res app/src/main/java`.
