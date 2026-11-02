# Code Review

Date: 2026-11-02

Task: and-cal-delete - Android calendar: delete events (incl. repeating series scope)

Scope: implementation commit 61c23fd4d on top of task base a891e7545 (`git diff a891e7545..61c23fd4d`), committed changes only

Requirements:
- tasks/and-cal-delete.md acceptance criteria and recorded assumptions (red text Delete button in the top bar; DELETE_FAILED message for other refusals; 409 maps to the conflict message)
- AGENTS.md project notes (lint, strings EN/DE, conventions)
- Web reference: web/src/app/features/calendar/edit/event-dialog.ts (delete), event-edit.service.ts (delete, target), scope-dialog.ts
- Existing Android editor conventions (EventEditorViewModel, EventEditRepository, CalendarWithEditor, CalendarRoute)

Git range:
- Branch: main
- HEAD: 61c23fd4de3594922600d9e428f1ae656c2737bb
- Base/start: a891e7545ce38c91bc3a680b0ec45cb87aa3361d
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## Verification notes

- Addressing matches the web `target()`: a single event goes to its own id with no scope or occurrence; an override goes to its own id with `scope` (THIS by default) and no occurrence; a generated occurrence goes to the series master id (eventId equals seriesId) with `scope` and its original start as `occurrence`. `If-Match` is the quoted version of the loaded event (override or master), set in `load()` together with `form`, so a delete can never run with a stale version from a previous event (the button requires a form).
- State: `delete()` is guarded by `canDelete` (not new, not read-only, not loading, not saving, not after CONFLICT/GONE) and by an open prompt. A series opens the shared scope prompt with all three scopes and `deleting = true`; `chooseScope` routes to `sendDelete`, `dismissScope` clears `deleting`; a single event opens the confirmation, `confirmDelete` re-checks `canDelete`; `load()` clears both prompts. `close()` is ignored while the delete runs, as for a save. 412/409 lead to CONFLICT (Reload), 404 to GONE, IOException/5xx to OFFLINE, other refusals to DELETE_FAILED; on failure nothing local changes.
- Views after a successful delete: `send` calls `reset()`, `CalendarWithEditor` re-composes `CalendarRoute`, whose `LifecycleResumeEffect` runs again on entering composition while the activity is resumed and calls `CalendarViewModel.refresh()`; `load()` replaces the occurrences and stores the agenda cache. Same mechanism as an existing save.
- Strings: the four new keys exist in `values` and `values-de`; scope prompt title for a delete matches the web wording ("Delete a repeating event").
- Tests: ViewModel tests cover single, each scope, cancel (confirmation and scope prompt), 412 plus reload, 404, offline, other refusal, and guards; repository tests cover method, path, query and If-Match for single, generated and override, plus status mapping; Compose tests cover button visibility/enabled state, confirmation, delete scope title, failure message and German texts. Gradle results were supplied by the parent (could not be run by the reviewer).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | No test shows the calendar views dropping a deleted event after the editor closes**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditorTest.kt:132`
    - Category: Tests
    - Problem: Criterion 2 requires that after a successful delete "the agenda/day/week views no longer show the event (cache updated, no manual refresh)". The new tests only assert `open == false` in the ViewModel (`EventEditorViewModelDeleteTest.kt:128`). Nothing exercises the integration path (editor closes, `CalendarRoute` re-enters composition, `LifecycleResumeEffect` calls `refresh()`, the occurrence disappears). `CalendarWithEditorTest` has no case for this after a save either, so the behaviour this criterion depends on has no test at all.
    - Why it matters: The criterion depends on an implicit side effect of composition (the resume effect firing again when the views come back). A future change, for example keeping `CalendarRoute` composed under the editor or keying the effect differently, would silently break "no manual refresh", and no unit test would catch it.
    - Suggested fix: Add a `CalendarWithEditorTest` case: open the `holiday` occurrence, stub `editRepo.delete` to return Ok and make `calendarRepo.occurrences` return the list without the event after the delete, click Delete and then confirm, and assert that the editor is gone and that `CalendarTags.entry(holiday.key, today)` no longer exists (optionally verify `occurrences` was called again).

## P3 Low

None.

## Review conclusion

No material issues found. The implementation meets the acceptance criteria and matches the web reference. CR-1 is a non-blocking test gap for criterion 2; the parent decides whether to close it in this task.
