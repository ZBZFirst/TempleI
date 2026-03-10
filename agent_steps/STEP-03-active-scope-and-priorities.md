# STEP 03 — Active Scope and Priorities

> **Completion protocol:** check off each item when complete, and update the **Codebase Reflection** section with file/line evidence showing where completion is represented.

## Objective
Keep delivery focused on Screen 2 reliability and operator clarity.

## Checklist (How to accomplish)
- [x] Route engineering effort to Screen 2 ingest/setup/control diagnostics.
- [x] Treat Screen 3/4 changes as deferred unless explicitly requested.
- [x] When in conflict, prioritize stream stability over non-core enhancements.

## Codebase Reflection (What proves it is done)
- [x] AGENTS guidance states Screen 2 priority.
- [x] Runtime launch paths to Screen 3/4 were removed from active nav and manifest registrations.
- [x] Deferred non-core work remains isolated to archive-only files.

## Archive status update
- Screen 3/4 entry points were archived from active runtime flow by:
  - removing Screen 3/4 buttons from main menu and top navigation layouts,
  - removing Screen 3/4 destinations from `TopNavigation`,
  - removing Screen 3/4 activity declarations from `AndroidManifest.xml`.

## Verification notes
- Use `git diff --name-only` and confirm scope concentration under Screen 2/export pipeline files.
