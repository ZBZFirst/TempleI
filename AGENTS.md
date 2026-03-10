# TempleI Agent Onboarding Guide

This file is the single source of truth for agent behavior in this repository.

## 1) Mission and product context
- Primary product goal: stream live media from an Android device into **OBS Studio**.
- Target transport: **SRT**.
- OBS ingest path: **Media Source** using **Input** with `input_format=mpegts`.

## 2) OBS input contract (non-negotiable)
- We are **not** using local file playback in OBS Media Source.
- The app must generate and display a copy/paste **input string (URL)** for operators.
- Use SRT URL shape such as:
  - `srt://<host>:<port>?mode=caller&timeout=<us>`
- User-facing copy/text should call it an **input string/URL**, not a local file path.

## 3) Active scope and priorities
- Current active feature surface: **Screen 2** (OBS ingest setup + stream controls + diagnostics).
- **Screens 3 and 4 are out of scope** for current iterations and may be archived/deprioritized.
- When tradeoffs are needed, prioritize reliability and operator clarity in Screen 2.

## 4) Expected operator workflow
1. Operator starts OBS as SRT listener.
2. App user configures host/port in Screen 2.
3. App displays copy/paste SRT input URL for OBS Media Source Input.
4. Operator pastes the URL into OBS Media Source with `input_format=mpegts`.
5. User starts stream from Screen 2 and verifies ingest health.

## 5) Engineering guardrails
- Keep architecture aligned to the product context above.
- Avoid introducing copy or UI labels that imply local-file ingest.
- Any change touching streaming UX should preserve a clear “what to paste into OBS” path.
- Prefer explicit diagnostics over generic errors in Screen 2.

## 6) Agent execution checklist (for every meaningful change)
- Confirm change supports Screen 2 / OBS-SRT ingest path.
- Verify strings and labels maintain "Input URL" framing.
- Run targeted checks/tests relevant to changed files.
- Summarize behavior impact and operator impact in PR description.

## 7) PR quality expectations
- Keep PRs scoped and reviewable.
- Include a short test/check list with exact commands run.
- If behavior changes are visible in UI, include updated copy rationale.
- Call out any deliberate deferrals with follow-up actions.

## 8) Rationale
For TempleI, SRT via OBS Media Source Input is the simplest supported operator workflow and should remain the default integration path unless explicitly re-scoped.


## 9) Step-by-step implementation playbooks
Use the following files for detailed "HOW" instructions and completion tracking:
- `agent_steps/STEP-01-mission-and-context.md`
- `agent_steps/STEP-02-obs-input-contract.md`
- `agent_steps/STEP-03-active-scope-and-priorities.md`
- `agent_steps/STEP-04-operator-workflow.md`
- `agent_steps/STEP-05-engineering-guardrails.md`
- `agent_steps/STEP-06-agent-execution-checklist.md`
- `agent_steps/STEP-07-pr-quality-expectations.md`
- `agent_steps/STEP-08-rationale-and-change-control.md`

Each step file contains a checkbox-based completion protocol and a "Codebase Reflection" section that defines how completion must be represented in the repository.
