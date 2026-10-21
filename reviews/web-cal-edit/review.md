# Code Review

Date: 2026-10-21

Task: web-cal-edit - Calendar event editing

Scope: the task's committed changes, third review round. Base ae67cd27b0102a5f259a3f3839789c0b150bafb9, task commits c622626c (implementation), 8bc57227 (e2e), bbcb7d26 (Vitest specs, Cancel closes with undefined), e4f89d8e (round-1 fixes), 2a34cb24 (round-2 report, extra specs from the test-writer) and 26d4b7d2 (fix for round-2 CR-1). Reviewed: production code and specs in `web/src/app/features/calendar/edit/` and `web/src/app/features/calendar/views/`, and `web/e2e/calendar-edit/edit.spec.ts`, with a focus on the round-2 fix and possible regressions from it. Uncommitted files in the working tree (from the parallel test-writer) were left out.

Requirements:
- tasks/web-cal-edit.md (acceptance criteria 1-4 and the three recorded assumptions)
- SPEC.md §6.2 (conflict policy, recurring scopes), §6.3 (UI)
- AGENTS.md project notes, docs/QUALITY.md artifact contract
- The previous review (round 2, overwritten by this report) and `reviews/web-cal-edit/fixes.md` "Round 2"

Git range:
- Branch: main (local clone; task commits not yet on origin/main)
- HEAD: 26d4b7d23552c8aa60aef4dcc5a4f5421cb9696f
- Base/start: ae67cd27b0102a5f259a3f3839789c0b150bafb9
- Commits reviewed: 6

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Status of the round-2 finding (checked in the current code):
- CR-1 (a generated occurrence whose series changed elsewhere can't be reread; "Try again" never works; the view isn't refreshed): resolved.
  - `event-dialog.ts:178-188`: when `EventEditService.reread` returns `undefined`, `load()` closes the dialog with the new result `'changed'` and does not show the load-failed banner. The same path runs on first open and on Reload after a 409/412. Other load failures (network, a 404 from `get`) still reach the `catch` and show "could not be loaded" with "Try again", where a retry can work.
  - `event-editor.ts:46-58`: `open` shows an info message (`@@calendar.edit.goneElsewhere`, `NotificationService.info` exists) for `'changed'` and returns `true`. `calendar-view.ts` `onEventClick` then refetches the occurrences.
  - `EventEditor.create` still compares with `=== 'saved'`, so the new value can't change the create path. `'changed'` is only produced in edit mode, from `load()`.
  - Closing from `load()` while `finally` still sets `loading` is harmless. The close can't conflict with `disableClose`, because Reload only works after `run()` has finished and reset it, and `MatDialogRef.close` isn't blocked by `disableClose` anyway.
  - Dropping the unsaved edits when the dialog closes after Reload matches the recorded assumption ("the dialog's Reload discards the unsaved edits").
  - Specs: the dialog specs "closes as changed when the series no longer has the occurrence" (also checks there is no "Try again") and "closes as changed when Reload after a 412 finds the occurrence gone". The editor specs cover the `'changed'` mapping to `true` plus the info message, no message on cancel, and an end-to-end flow with the real `MatDialog` (save, 412, Reload, gone, `open` resolves `true`, the info message is shown once, no error).

Also checked again and found unchanged and correct: drag create/move/resize with revert and refetch (`calendar-view.ts`). Read-only and cancelled occurrences can't be dragged (`calendar-views.ts`). The dialog fields match criterion 1, and attendees are read-only for synced calendars. `scopesFor` offers only FOLLOWING/ALL for a rule or calendar change. 409/412 show the reload banner in the dialog and a snackbar with Reload after a drag. The service picks the target and version for single events, overrides and generated occurrences. The e2e spec covers criterion 4. The round-2 fix doesn't touch the e2e path: the moved occurrence is an override, so `load()` doesn't call `reread` for it.

Evidence note (not a finding): `python tasks.py verify web-cal-edit` shows no evidence recorded yet for criteria 1-4. The parent must still run `cd web && npm run check` and the Playwright suite `npm run e2e -- e2e/calendar-edit/edit.spec.ts` against the e2e stack on this final code, and record both.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
