# Test Report

Date: 2026-10-21

Task: web-cal-edit - Calendar event editing

Scope: task commits c622626c, 8bc57227, bbcb7d26, e4f89d8e, 2a34cb24, 26d4b7d2 on base
`ae67cd27b0102a5f259a3f3839789c0b150bafb9`, measured at HEAD
`26d4b7d23552c8aa60aef4dcc5a4f5421cb9696f` (branch main) plus one uncommitted test addition
(`web/src/app/features/calendar/edit/event-dialog.spec.ts`). Paths:
`web/src/app/features/calendar/edit/`, `web/src/app/features/calendar/views/`,
`web/e2e/calendar-edit/edit.spec.ts`.

Requirements:
- `tasks/web-cal-edit.md` acceptance criteria and recorded assumptions (409/412 shown as
  "changed elsewhere" with Reload; presets plus custom rule; FOLLOWING/ALL only for rule or calendar
  changes; attendees editable only for local calendars)
- SPEC.md §6.2/§6.3 (as referenced by the task), docs/ARCHITECTURE.md "Coverage"
- Parent instructions: check scope prompt, 409/412 conflict handling with reload, and the latest fix
  (reread finds the occurrence gone -> dialog closes 'changed', view refreshes); do not run Playwright

## Test files changed

- `web/src/app/features/calendar/edit/event-dialog.spec.ts`

## Tests added or updated

Count: 1

- `EventDialog > create > creates the event once when the form is submitted again while saving`.
  It submits the form a second time while the first create request is still pending, as Enter in a
  field would, and checks that only one request is sent and the dialog closes once as `saved`. Before
  this test the `saving()` guard in `EventDialog.save()` (event-dialog.ts:282) never ran.

The existing task specs (written by the implementer and an earlier test-writer pass, committed in
2a34cb24/26d4b7d2) already cover every testable criterion. I added nothing else, so the suite has
no redundant tests.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 drag to create/move/resize | `calendar-view.spec.ts` editing: `makes events of writable calendars draggable and ranges selectable`, `saves a dropped event through the editor...`, `puts a dropped event back when nothing was saved...`, `saves a resized event the same way`, `puts back a dragged event it cannot map...`, `creates an event for a selected range`, `creates a one-hour event at the next full hour...`; `event-editor.spec.ts` `move`, `movePatch`, `timesOf` | `npm --prefix web run check` (test:ci) | Pass |
| AC1 event dialog fields (title, time/all-day, tz, recurrence, location, description, reminders, attendees read-only for external) | `event-dialog.spec.ts` create/edit/series/read-only groups (e.g. `creates an all-day event...`, `needs a known time zone`, `keeps a custom repeat rule it cannot edit`, `adds and removes reminders`, `lets guests of a local event be added and removed`, `shows guests of a synced event read-only and keeps it in its calendar`, `sends no guests of a new event moved to a synced calendar`); `event-form.spec.ts`, `event-times.spec.ts`, `recurrence.spec.ts` | same | Pass |
| AC2 recurring edits ask THIS / FOLLOWING / ALL | `scope-dialog.spec.ts` (all); `event-dialog.spec.ts` `asks which occurrences a change is for...`, `keeps the dialog open ... when the scope prompt is cancelled`, `does not offer this occurrence alone for a new repeat rule`, `moves a series only to local calendars...`, `asks a scope for a delete...`; `event-editor.spec.ts` `asks which occurrences of a series the move is for...`, `saves nothing when the scope prompt is cancelled`; `event-edit.service.spec.ts` scope routing (master/override/single); `scopesFor` | same | Pass |
| AC2 'changed remotely' conflicts (409 and 412) shown with reload | `event-dialog.spec.ts` `shows a 409/412 conflict with Reload, which loads the current version`, `shows the current times of the event ... also after Reload`, `offers a retry when the Reload after a conflict fails...`, `shows other save failures without a Reload...`, `shows a conflict of a delete with Reload`, `shows a conflict of a series change with Reload`; `event-editor.spec.ts` `shows a 409/412 conflict with a Reload action that calls reload`, `shows a conflict of a series move...`; `calendar-view.spec.ts` `passes a reload to the editor that refetches the range`; `event-edit.service.spec.ts` `isConflict`, `reports a stale version as a conflict, silently` | same | Pass |
| Latest fix (CR-1): reread finds the occurrence gone -> dialog closes 'changed', view refreshes, user told | `event-dialog.spec.ts` `closes as changed when the series no longer has the occurrence`, `closes as changed when Reload after a 412 finds the occurrence gone`; `event-editor.spec.ts` `asks for a refetch and says why when the occurrence changed elsewhere`, `EventEditor with the real dialog, occurrence gone after a 412 > closes the dialog on Reload and makes the view refetch`; `calendar-view.spec.ts` `opens a clicked event in the editor and refetches after a change` (true -> refetch); `event-edit.service.spec.ts` `reread > is undefined when the series no longer has the occurrence` | same | Pass |
| Same write submitted twice | `event-dialog.spec.ts` `creates the event once when the form is submitted again while saving` (new), `cannot be closed while the event is being saved` | `npm --prefix web run test:ci -- --include src/app/features/calendar/edit/event-dialog.spec.ts`; then `npm --prefix web run check` | Pass |
| AC3 Vitest covers scope prompt and conflict handling; `cd web && npm run check` passes | all of the above | `npm --prefix web run check` | Pass |
| AC4 Playwright `e2e/calendar-edit/edit.spec.ts` against the e2e stack | `web/e2e/calendar-edit/edit.spec.ts` | not run | Not run: the parent said not to run Playwright (the parent runs it). Only its typecheck ran (`npm run e2e:typecheck` inside `npm run check`: Pass), and a typecheck is not execution evidence |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include src/app/features/calendar/edit/event-dialog.spec.ts`

Result:

Pass (1 file, 43 tests)

## Full test execution

Command:

`npm --prefix web run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass. 114 files, 2275 tests passed, lint clean, build succeeded. The build prints an initial-bundle
budget warning (657 kB > 500 kB). It concerns the initial chunks only (calendar-view is a lazy
chunk) and is not caused by this task.

## .taskcheck

Result:

Not run. The parent runs `.taskcheck` (`sh scripts/check.sh`) before `done`. The web part of it
(`npm run check`) passed above.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. Every task-scoped production file except calendar-view.ts,
calendar-views.ts and calendar-view.html was added by this task, so for those files whole-file
coverage equals changed-file coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-cal-edit;
  base `ae67cd27b0102a5f259a3f3839789c0b150bafb9` and task commits c622626c, 8bc57227, bbcb7d26,
  e4f89d8e, 2a34cb24, 26d4b7d2, both given by the parent. HEAD is
  `26d4b7d23552c8aa60aef4dcc5a4f5421cb9696f`. File list from
  `git diff --name-only ae67cd27..HEAD -- web/src`, which matches the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 26d4b7d2 plus the uncommitted
  `web/src/app/features/calendar/edit/event-dialog.spec.ts` (git blob
  `94055f85f95d0f9c431fae36d5e59ec3ede8e3fc`). `reviews/web-cal-edit/review.md` was also modified in
  the working tree during the run, by another helper, not by me. It is not a test or source input.
- Exact included files: `edit/event-dialog.ts`, `edit/event-dialog.html`, `edit/event-edit.service.ts`,
  `edit/event-editor.ts`, `edit/event-form.ts`, `edit/event-times.ts`, `edit/recurrence.ts`,
  `edit/scope-dialog.ts`, `views/calendar-view.ts`, `views/calendar-view.html`, `views/calendar-views.ts`
  (all under `web/src/app/features/calendar/`). Excluded: `*.spec.ts` (tests); `views/testing.ts`
  (test helper); `*.scss` (styles, no executable behavior); `web/e2e/calendar-edit/edit.spec.ts`
  (a Playwright test, not run here). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with
  `@vitest/coverage-v8` (via `ng test`); `npm --prefix web run coverage`, run from the repository
  root (npm runs it in `web/`); the full web suite. Per-file counters were read from
  `web/coverage/web/coverage-summary.json` and `coverage-final.json` by absolute path with a scratch
  script (`.tmp/cov.mjs`).
- Run start/completion time and exit status: build 2026-10-21T12:43:39Z, tests started 14:43:39
  +02:00, reports written 12:44:12Z. Exit 0, 114 files and 2275 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json`
  sha256 `9a5ad6e4937aa52731be9cfe44d766d652e2ffac0a8ef54abbaca27ee4a8b388`;
  `web/coverage/web/coverage-summary.json` sha256
  `2b805dacc4635caaaa7acaeed9bd594825d7df440bd56d48d5f3d4bcb09686a2`.
- Evidence outputs are fresh and inputs stayed unchanged: the report mtimes (12:44:12Z) fall inside
  this run. Vitest rewrote `coverage/` (the earlier pre-addition run had different hashes). The
  working tree (`git status`) was the same after the run as before it.
- Final run after latest test additions and production fixes: yes. It includes the new test and
  26d4b7d2, the latest production fix.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/edit/event-dialog.ts` | 174/177 (98.3%) | 113/120 (94.16%) | 40/40 (100%) | 145/145 (100%) |
| `web/src/app/features/calendar/edit/event-dialog.html` | 309/311 (99.35%) | 67/68 (98.52%) | 9/9 (100%) | 179/179 (100%) |
| `web/src/app/features/calendar/edit/event-edit.service.ts` | 31/31 (100%) | 24/24 (100%) | 13/13 (100%) | 27/27 (100%) |
| `web/src/app/features/calendar/edit/event-editor.ts` | 47/47 (100%) | 27/27 (100%) | 8/8 (100%) | 41/41 (100%) |
| `web/src/app/features/calendar/edit/event-form.ts` | 49/49 (100%) | 79/80 (98.75%) | 13/13 (100%) | 42/42 (100%) |
| `web/src/app/features/calendar/edit/event-times.ts` | 46/46 (100%) | 17/17 (100%) | 10/10 (100%) | 42/42 (100%) |
| `web/src/app/features/calendar/edit/recurrence.ts` | 13/13 (100%) | 12/12 (100%) | 4/4 (100%) | 9/9 (100%) |
| `web/src/app/features/calendar/edit/scope-dialog.ts` | 45/45 (100%) | 21/23 (91.3%) | 2/2 (100%) | 29/29 (100%) |
| `web/src/app/features/calendar/views/calendar-view.ts` | 86/89 (96.62%) | 35/39 (89.74%) | 31/31 (100%) | 74/74 (100%) |
| `web/src/app/features/calendar/views/calendar-view.html` | 72/72 (100%) | 8/8 (100%) | 5/5 (100%) | 40/40 (100%) |
| `web/src/app/features/calendar/views/calendar-views.ts` | 22/22 (100%) | 26/26 (100%) | 7/7 (100%) | 19/19 (100%) |
| **Aggregate (summed counters, 11 files)** | 894/902 (99.11%) | 429/444 (96.62%) | 142/142 (100%) | 647/647 (100%) |

The aggregate covers all 11 included files, and none are missing from the report. Excluded files
are listed above.

Before the new test, event-dialog.ts had 173/177 statements and 112/120 branches. The run before
the addition had the same command and exit 0 (coverage-final sha256 `bdb5905c...`).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `edit/event-dialog.ts:282` (`saving()` guard of `save()`) | A double submit must not send the change twice | Test added: `creates the event once when the form is submitted again while saving`. Now covered |
| `edit/event-dialog.ts:156-157` (no default calendar / no writable calendar fallbacks) | A new event with no writable calendar | Justified gap: `EventEditor.create` refuses to open the dialog without a writable calendar (tested: `asks for a calendar first when every calendar is read-only`) |
| `edit/event-dialog.ts:172` (`load()` in create mode) | Defensive guard. `load()` is only reachable from edit-mode buttons | Justified gap |
| `edit/event-dialog.ts:220, 222[0]` (`addReminder(null)`, duplicate reminder) | The select emits null on reset, and choosing an already-listed preset | Justified gap, low risk. Reminder add/remove is tested |
| `edit/event-dialog.ts:315` (`delete()` guard while saving/read-only) | Defensive. The Delete button is hidden for read-only calendars and disabled while saving | Justified gap |
| `edit/event-dialog.ts:85[0]`, `views/calendar-view.ts:57[0]`, `edit/scope-dialog.ts:77[0]` | Branches V8 attributes to Angular decorator/class metadata | Not behavior |
| `edit/event-dialog.html:138` (`Choose a calendar.` error), `159[1]` | The calendar is always preselected, and a read-only chip has no remove button | Justified gap, unreachable in normal flows |
| `edit/event-form.ts:204[1]` (`b[i].name ?? ''` nullish side) | Attendee comparison with a missing name on the right side | Justified gap, low risk |
| `edit/scope-dialog.ts:40[1]` (`@switch` default) | Only the three scopes exist | Not reachable |
| `views/calendar-view.ts:172, 191, 212` (no settings yet / no occurrence on click) | Defensive guards before the settings load | Justified gap. The view renders no calendar until the settings exist |

## Unable to test

- AC4 (Playwright `e2e/calendar-edit/edit.spec.ts` against the e2e stack): not run here, by the
  parent's instruction. The parent must run `npm run e2e -- e2e/calendar-edit/edit.spec.ts` and
  record the result. Only its TypeScript typecheck passed here.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient, and testing found no
production defects. AC4 (Playwright) is still to be run by the parent.
