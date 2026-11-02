# Code Review

Date: 2026-11-02

Task: and-cal-delete - Android calendar: delete events (incl. repeating series scope)

Scope: final task revision b0181e6c8 on top of task base a891e7545 (`git diff a891e7545..b0181e6c8`), committed changes only. Production code is 61c23fd4d (unchanged since; `git diff 61c23fd4d..b0181e6c8 -- android` touches only tests). First review pass was on 61c23fd4d; this pass re-reviews the final production code and all tests.

Requirements:
- tasks/and-cal-delete.md acceptance criteria and recorded assumptions (red text Delete button in the top bar; DELETE_FAILED message for other refusals; 409 maps to the conflict message)
- AGENTS.md project notes (lint, strings EN/DE, conventions)
- Web reference: web/src/app/features/calendar/edit/event-dialog.ts (delete), event-edit.service.ts (delete, target), scope-dialog.ts
- Existing Android editor conventions (EventEditorViewModel, EventEditRepository, CalendarWithEditor, CalendarRoute)

Git range:
- Branch: main
- HEAD: b0181e6c8894fe45ca601fc2f063ccab3a4bfa12
- Base/start: a891e7545ce38c91bc3a680b0ec45cb87aa3361d
- Commits reviewed: 2 (61c23fd4d implementation, b0181e6c8 tests and review reports)

Summary: 0 P0, 0 P1, 0 P2, 0 P3 (open). CR-1 from the first pass (P2, Tests) is resolved in b0181e6c8.

## Verification notes

- Addressing matches the web `target()`: a single event goes to its own id with no scope or occurrence; an override goes to its own id with `scope` (THIS by default) and no occurrence; a generated occurrence goes to the series master id (eventId equals seriesId) with `scope` and its original start as `occurrence`. `If-Match` is the quoted version of the loaded event (override or master), set in `load()` together with `form`, so a delete can never run with a stale version from a previous event (the button requires a form). A generated occurrence that became an override since it was shown is re-read and deleted as that override (new ViewModel test).
- State: `delete()` is guarded by `canDelete` (not new, not read-only, not loading, not saving, not after CONFLICT/GONE) and by an open prompt. A series opens the shared scope prompt with all three scopes and `deleting = true`; `chooseScope` routes to `sendDelete`, `dismissScope` clears `deleting`; a single event opens the confirmation, `confirmDelete` re-checks `canDelete`; `load()` clears both prompts. `close()` is ignored while the delete runs, as for a save; `save()` is ignored while the confirmation is shown. 412/409 lead to CONFLICT (Reload), 404 to GONE, IOException/5xx to OFFLINE, other refusals to DELETE_FAILED; on failure nothing local changes.
- Views after a successful delete: `send` calls `reset()`, `CalendarWithEditor` re-composes `CalendarRoute`, whose `LifecycleResumeEffect` runs again on entering composition and calls `CalendarViewModel.refresh()`; `load()` replaces the occurrences and stores the agenda cache.
- Strings: the four new keys exist in `values` and `values-de`; the delete scope title matches the web wording; German texts of the button, scope title and failure message are asserted in a Compose test.
- Tests in the final revision: ViewModel (single, each scope, override, cancel of confirmation and scope prompt, stray scope choice, 412 plus reload, 409, 404, offline, other refusal, guards, save/close during delete), repository (method, path, query, If-Match for single, generated and override; status mapping), Compose screen (visibility/enabled, confirmation, delete vs change scope title, failure message, German), and integration `CalendarWithEditorDeleteTest` (agenda, week and day views plus the agenda cache drop the event after a delete without a manual refresh; an offline delete keeps the event and shows "Can't reach the server"). Tests assert behaviour through the real CalendarViewModel/EventEditorViewModel with only the repositories mocked. Gradle results were supplied by the parent (reviewer cannot run Gradle): ktlintCheck and full testDebugUnitTest BUILD SUCCESSFUL, CalendarWithEditorDeleteTest 4/4, EventEditorDeleteScreenTest 7/7, EventEditorViewModelDeleteTest 13/13, EventEditRepositoryTest 14/14.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [x] **CR-1 | No test shows the calendar views dropping a deleted event after the editor closes (resolved)**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditorDeleteTest.kt:141`
    - Category: Tests
    - Problem (first pass, 61c23fd4d): criterion 2 ("the agenda/day/week views no longer show the event, cache updated, no manual refresh") had no test beyond the editor closing in the ViewModel.
    - Resolution (b0181e6c8): `CalendarWithEditorDeleteTest` drives the real `CalendarWithEditor` with a fake server that stops listing the event after the delete, and asserts that the entry is gone from the agenda (and the stored agenda cache), the week view and the day view after confirming the delete, with no refresh call from the test. An offline case asserts that the event and the cache stay unchanged. Verified by reading the test; pass results come from the parent.

## P3 Low

None.

## Review conclusion

No material issues found. The final revision b0181e6c8 meets the acceptance criteria and matches the web reference; the only finding from the first pass (CR-1) is resolved.
