# STEP 01 — Mission and Product Context

> **Completion protocol:** check off each item when complete, and update the **Codebase Reflection** section with file/line evidence showing where completion is represented.

## Objective
Ensure implementation stays aligned to TempleI's core mission: Android → OBS live streaming over SRT via Media Source Input (`mpegts`).

## Checklist (How to accomplish)
- [ ] Confirm all newly introduced streaming features explicitly reference OBS + SRT + Media Source Input assumptions.
- [ ] Reject or re-scope changes that drift into non-OBS transport targets unless intentionally planned.
- [ ] Keep mission framing visible in onboarding/docs used by contributors.

## Codebase Reflection (What proves it is done)
- [ ] `AGENTS.md` states OBS/SRT/Media Source context.
- [ ] Screen 2 docs/copy do not conflict with mission assumptions.
- [ ] PR description explains mission alignment when streaming behavior changes.

## Verification notes
- Use `rg -n "OBS|SRT|Media Source|mpegts" AGENTS.md app/src/main` to verify consistency.
