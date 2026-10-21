# Code Review

Date: 2026-10-21

Task: web-cal-edit - Calendar event editing

Scope: the task's committed changes, second review round. The parent named base 411c8953 and HEAD ba6fb0a3. The branch has since been rebased: the merge-base with origin/main is now 3d1422e1 and HEAD is ba7bd23e. `git diff --stat ba6fb0a3 HEAD -- web` is empty, so the code is the same as the revision the parent named. The task commits are ae22298b (implementation), 8f8ca9f4 (e2e), fcbf3ca1 (Vitest specs, Cancel closes with undefined) and ba7bd23e (fixes for the first review). Reviewed: production code and specs in `web/src/app/features/calendar/edit/` and `web/src/app/features/calendar/views/`, and `web/e2e/calendar-edit/edit.spec.ts`. Uncommitted files from the parallel test-writer were left out. The other files in `git diff 411c8953..HEAD` (cal-google, task files of other agents) come from main and are not part of this task.

Requirements:
- tasks/web-cal-edit.md (acceptance criteria 1-4 and the three recorded assumptions)
- SPEC.md §6.2 (conflict policy, recurring scopes), §6.3 (UI), §6.4 (API)
- AGENTS.md project notes, docs/QUALITY.md artifact contract
- The server contract in `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventController.kt` (list overlaps [from, to); an override stands for its occurrence, for every scope)
- The previous review `reviews/web-cal-edit/review.md` (round 1) and `reviews/web-cal-edit/fixes.md`

Git range:
- Branch: main (local clone; task commits not yet on origin/main)
- HEAD: ba7bd23e6f3c9765729dbbd875d7a94c67079f7e (same web code as ba6fb0a3)
- Base/start: 3d1422e1454636a18ab4a2eb9cca1927878ebb03 (current merge-base; originally 411c8953)
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 1 P2, 0 P3

Status of the round-1 findings (checked in the current code):
- CR-1 (stale times after load/Reload): resolved. `event-dialog.ts:168-209`. A single event or an override now takes its times, zone and version from the fetched `EventResponse`. A generated occurrence is read again with `EventEditService.reread`, which also picks up an occurrence that has since become an override. Covered by the dialog specs "shows the current times of the event, not those of the view, also after Reload", "reads a generated occurrence again…" and "edits the override a generated occurrence became…". There is one leftover dead end, see CR-1 below.
- CR-2 (no Vitest tests): resolved. Committed specs for the scope prompt (`scope-dialog.spec.ts`; series cases in `event-dialog.spec.ts`, including FOLLOWING/ALL only for a rule change), 409/412 to the reload banner (dialog save and delete) and to the snackbar Reload (`event-editor.spec.ts`), revert on cancel or failure, and the service's target and version choice.
- CR-3 (override FOLLOWING/ALL with a just-fetched master version): resolved. `event-edit.service.ts:117-130` addresses an override by its own id with `occurrence.version` for every scope, with no extra GET. This matches the controller contract ("Addressing an override stands for its occurrence").
- CR-4 (guests sent to a synced calendar): resolved. `createRequest(form, withAttendees)` (`event-form.ts:133-147`) gets `attendeesEditable()` from `event-dialog.ts:284`.
- CR-5 (skipped DST time west of UTC): resolved. Checked `event-times.ts:34-39` by hand for New York 2026-03-08 02:30 (gives 07:30Z = 03:30 EDT) and Zurich 2026-03-29 02:30 (gives 01:30Z = 03:30 CEST). The ambiguous-time step still prefers the earlier instant.
- CR-6 ("New event" rounded in the browser zone): resolved. `nextFullHour` (`event-times.ts:47-53`) is used by `calendar-view.ts:170-175` with the settings zone.

Also checked and found correct: drag create/move/resize and revert (`calendar-view.ts:177-208`); read-only calendars can't be dragged (`calendar-views.ts` `editable`); the dialog fields match criterion 1; `scopesFor` hides THIS for a rule or calendar change; save is blocked while a conflict is shown; the dialog can't be closed while a write runs; strings use i18n ids. The e2e spec covers criterion 4 (moving one occurrence of a weekly event, with the browser in a different zone from the settings).

Evidence note (not a finding): `python tasks.py verify web-cal-edit` shows no evidence recorded yet for criteria 1-4. The fix commit changed `load()`, and the e2e test opens the moved override in the dialog. So the Playwright run for criterion 4 must be done on this final code, against the e2e stack.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A generated occurrence whose series changed its times can no longer be opened or reloaded, and nothing refreshes the view**
    - File: `web/src/app/features/calendar/edit/event-dialog.ts:175-179`, `web/src/app/features/calendar/edit/event-edit.service.ts:70-80,136-145`, `web/src/app/features/calendar/edit/event-editor.ts:47-49`
    - Category: Correctness
    - Problem: `reread` matches the occurrence on `seriesId` plus its original start (`occurrence.occurrence`), and only within ±1 day of that start. Suppose the series was changed elsewhere with ALL (for example moved from 09:00 to 10:00), or with FOLLOWING (the occurrence now belongs to a new series with a new id). The original-start key of every generated occurrence then changes, so `reread` returns `undefined`. The same happens when an override was moved more than a day away. `load()` then shows "The event could not be loaded" with a "Try again" button, and every retry fails the same way. When the user cancels, the dialog closes with `undefined`, `EventEditor.open` returns `false`, and the view is not refreshed. The stale occurrence stays on screen, and opening it again fails again. This is the normal outcome of the conflict flow in criterion 2: a remote change to a series makes the save fail with 412, and Reload then always fails. The user only gets out by navigating away.
    - Why it matters: The 'changed remotely, reload' path (criterion 2, SPEC.md §6.2) reaches a screen it can't recover from for one of its main causes, a series changed elsewhere. The retry the screen offers can never succeed.
    - Suggested fix: Treat "the series no longer has this occurrence" as a case of its own. For example, close the dialog with a result that makes the view refresh (or have `EventEditor.open` refresh after any close that followed a failed load), and tell the user the event changed and the calendar was refreshed. Or show a message without "Try again" and refresh the view when the dialog closes. Add a dialog/editor spec for a reread that returns `undefined` after a 412, and check that the view's occurrences are refreshed.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.
