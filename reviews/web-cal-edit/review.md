# Code Review

Date: 2026-10-21

Task: web-cal-edit - Calendar event editing

Scope: the full task at HEAD, fourth review round. Base ae67cd27b0102a5f259a3f3839789c0b150bafb9, task commits b4b8f6ae (implementation), 0fcc3e6f (e2e), 4d60d1c9 (Vitest specs, Cancel closes with undefined), b5468d2d (round-1 fixes), a3f2679f (round-2 report, extra specs from the test-writer), f3c28139 (fix for round-2 CR-1), e26da551 (round-3 report) and 649bd3bd (one new spec plus `reviews/web-cal-edit/tests.md`). Round 3 reviewed the same code at 26d4b7d2 (pre-rebase hash). `git diff 26d4b7d2..HEAD` shows that the only code change since then is the new spec in `web/src/app/features/calendar/edit/event-dialog.spec.ts`. Everything else is in `tasks/` and `reviews/`. This round reviews that addition in detail and relies on round 3 for the production code and specs that did not change. The working tree was clean.

Final reviewed revision: 649bd3bd24b9af7f170bdc2d39049b6dea8ca895

Requirements:
- tasks/web-cal-edit.md (acceptance criteria 1-4 and the three recorded assumptions)
- SPEC.md §6.2 (conflict policy, recurring scopes), §6.3 (UI)
- AGENTS.md project notes, docs/QUALITY.md artifact contract
- Round-3 review (overwritten by this report) and `reviews/web-cal-edit/tests.md`

Git range:
- Branch: main (local clone; task commits not yet on origin/main)
- HEAD: 649bd3bd24b9af7f170bdc2d39049b6dea8ca895
- Base/start: ae67cd27b0102a5f259a3f3839789c0b150bafb9
- Commits reviewed: 8 (round 4 looks in detail only at 649bd3bd)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Round-4 addition

The new spec is `EventDialog > create > creates the event once when the form is submitted again while saving` (`event-dialog.spec.ts:222-236`):
- It holds the first `events.create` open with a deferred promise and dispatches a second `submit` on the `<form>`. That reaches `(ngSubmit)="save()"` (`event-dialog.html:11`), the same path Enter in a field takes. It then checks that `create` was called once and that, after the promise resolves, the dialog closed exactly once with `'saved'`.
- This is the first test of the `this.saving()` guard in `EventDialog.save()` (`event-dialog.ts:282`). Without the guard, `create` would be called twice, so the test asserts real behavior and does not just repeat the code. It covers the "double submit" risk from the review checklist.
- It uses the file's existing `deferred`, `type`, `stable` and `fakeEvent` helpers and follows the style of the other specs.
- I ran it: `cd web && npx ng test --watch=false --include src/app/features/calendar/edit/event-dialog.spec.ts`, 1 file, 43 tests passed.

## Round-3 conclusions (unchanged code, still valid at HEAD)

- Round-2 CR-1 is resolved. When `EventEditService.reread` returns `undefined`, `load()` closes the dialog with `'changed'`. This happens on first open and on Reload after a 409/412. `EventEditor.open` then shows the `goneElsewhere` info message and returns `true`, and `calendar-view.ts` refetches. Other load failures still show "could not be loaded" with "Try again". `create` still compares with `=== 'saved'`. Dialog and editor specs cover this path, including one flow with the real `MatDialog`.
- Criterion 1: drag create/move/resize revert and refetch on failure (`calendar-view.ts`). Read-only and cancelled occurrences can't be dragged (`calendar-views.ts`). The dialog has title, time/all-day, time zone, recurrence presets with a custom rule, location, description and reminders. Attendees are read-only for synced calendars.
- Criterion 2: the scope prompt offers THIS/FOLLOWING/ALL, and only FOLLOWING/ALL when the rule or calendar changes. 409/412 show the reload banner in the dialog and a snackbar with Reload after a drag. The service picks the target and version for single events, overrides and generated occurrences.
- Criterion 3: Vitest specs cover the scope prompt and the conflict handling (dialog, editor, service, views).
- Criterion 4: `web/e2e/calendar-edit/edit.spec.ts` moves one occurrence of a weekly event. The round-2 fix doesn't touch this path, because the moved occurrence is an override.

Evidence note (not a finding): `python tasks.py verify web-cal-edit` shows criterion 4 recorded (Playwright against the e2e compose stack at 26d4b7d2). Commit 649bd3bd changes only a spec, so that evidence still covers the production code. Criteria 1-3 have no evidence recorded yet. The parent must still run `cd web && npm run check` on this final revision and record criteria 1-3.

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
