# Test Report

Date: 2026-11-08

Task: cal-invitation-respond-web - Respond to invitations in the web event dialog

Scope: second pass on the final code. Base `git merge-base HEAD origin/main` = 54924e3fd196833064725e3138480d2e542345d6; HEAD 2408b6be845f467f99add8be3ee6aadf15b8226d. Task commits: 3fa882e7f (Accept/Maybe/Decline in the event dialog), 7e53fe01a (tests), acace40ee (first test-writer pass and review reports), 2408b6be8 (fixer: CR-1, any close after an answer reports 'saved'). `git diff 54924e3fd..HEAD` touches only `web/src/app/features/calendar/edit/`, `web/src/locale/` and `reviews/cal-invitation-respond-web/`. Uncommitted input: this test-writer's additions to `event-dialog.spec.ts` only.

Requirements:
- tasks/cal-invitation-respond-web.md acceptance criteria and its assumption (answering keeps the dialog open, updates version and guest statuses; closing afterwards returns 'saved' so the view refetches; 412 -> conflict banner with Reload; 409 and other errors -> server message inline)
- Assumption cal-invitation-respond #1 (Accept/Maybe/Decline for non-read-only GOOGLE/MICROSOFT calendars with attendees; own status = attendee matching a mail account address)
- SPEC.md §6.2 (POST /api/v1/calendar/events/{id}/respond, If-Match), docs/ARCHITECTURE.md "Coverage"
- reviews/cal-invitation-respond-web/review.md finding CR-1 and reviews/cal-invitation-respond-web/fixes.md (final behavior to cover)

## Test files changed

- `web/src/app/features/calendar/edit/event-dialog.spec.ts` (uncommitted additions in this pass, in the 'answering invitations' block)

Already committed and re-checked against the final code: `event-dialog.spec.ts` (first-pass tests and the fixer's 5 new CR-1 tests plus 1 changed assertion) and `event-edit.service.spec.ts` (3 tests for respond/ownAddresses). The fixer's changed assertion ('disables the choices while the answer is sent' now expects `disableClose` true after a successful answer) matches the CR-1 fix and the task assumption; it is not a weakening.

## Tests added or updated

Count: 3 added in this pass (no existing test changed)

- keeps reporting the first answer when a second answer is sent or fails: while a second answer is pending, Escape and backdrop clicks do nothing; after it fails, the inline error is shown, `disableClose` stays true and Escape closes exactly once with 'saved'
- closes only once when dismissed after two answers: the second answer is sent with the version returned by the first (5), and a backdrop click closes exactly once with 'saved' (guards `closeAsSavedOnDismiss` against subscribing twice, event-dialog.ts:458)
- closes with saved after an answer even when the answer is followed by a failed answer and Cancel

Earlier passes (still present, all pass): 8 test-writer tests from the first pass, the original task tests, and the fixer's CR-1 tests (Escape after an answer, ignoring other keys and modified Escape; backdrop click after an answer; Save without changes after an answer; Escape/backdrop left to MatDialog before an answer or after a failed one; no dismiss while a save after an answer runs, 'saved' after that save fails).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: dialog offers Accept / Maybe / Decline only for edits of writable GOOGLE/MICROSOFT events with guests | offers Accept, Maybe and Decline with no answer highlighted yet; offers no answer without guests, in local or read-only calendars; reads no mail accounts for a read-only calendar and offers no answer for a new event; hides the choices when the answered event comes back without guests | focused command below | Pass |
| AC1: current status shown (guest matching a mail account address, case-insensitive) | highlights the current answer of the guest with a mail account address; highlights no answer when no guest has a mail account address; still offers the choices when the mail accounts cannot be read; lists the addresses of the mail accounts in lower case (service) | focused command below | Pass |
| AC1: sending calls POST .../respond with If-Match, updates the status and the dialog's version | sends the answer, shows the new status and uses the new version; closes only once when dismissed after two answers (second answer uses version 5); answers an invitation on the event holding the occurrence, with its version as If-Match (service); answers a generated occurrence through its series master (service); answers with the reloaded version after a 412 and Reload | focused command below | Pass |
| AC1 / assumption: any close after an answer returns 'saved' (CR-1) | tells the view the event changed when closed after an answer; ... when dismissed by Escape after an answer; ... by a backdrop click after an answer; ... when saved without changes after an answer; keeps reporting the first answer when a second answer is sent or fails; closes only once when dismissed after two answers; closes with saved after an answer even when the answer is followed by a failed answer and Cancel; closes as unchanged when the answer failed | focused command below | Pass |
| AC1: Escape/backdrop before an answer or after a failed one stay with MatDialog; no dismiss while busy | leaves Escape and backdrop clicks to MatDialog before an answer or after a failed one; keeps the dialog open on Escape while a change after an answer is saved; keeps reporting the first answer when a second answer is sent or fails | focused command below | Pass |
| AC1: unsaved form edits kept after answering | keeps unsaved edits of the form after answering | focused command below | Pass |
| AC1: busy handling (no double send, no save/delete/close during an answer, no answer during a save) | disables the choices while the answer is sent; blocks saving, deleting and closing while the answer is sent; disables the choices while a change is saved | focused command below | Pass |
| AC1 / assumption: server 409/412 and other errors shown | offers a reload when the event changed since it was opened (412); shows the server's reason when it refuses the answer (409); shows the server's message when a refusal has no detail; says so when the answer could not be sent for another reason | focused command below | Pass |
| AC2: new strings in English and German | Not unit-tested: translations are checked by `npm run build:de` (part of `npm run check` / `.taskcheck`), which fails on a missing target. The tests assert the English texts ('Your answer', 'Accept', 'Maybe', 'Decline', 'Your answer was sent.', 'Your answer could not be sent.') | n/a | Not run: build:de belongs to the parent's `.taskcheck` run |
| AC3: unit tests cover the new behavior | all of the above | focused + full coverage run below | Pass |
| AC3: `sh scripts/check.sh` passes | none | n/a | Not run: it runs Gradle, which the parent told the test-writer not to run; the parent runs `.taskcheck` |

## Focused test execution

Command:

`npm --prefix web exec -- ng test --include src/app/features/calendar/edit --watch=false`

Result:

Pass - 8 files, 198 tests passed (final run after the last added test; 195 before this pass). Prettier `--check` and ESLint on the changed spec file: clean.

## Full test execution

Command:

`npm --prefix web run coverage` (the whole web Vitest suite with coverage)

Result:

Pass - 193 files, 4583 tests passed. Server and Android suites not run (no changes there; Gradle not to be run by the test-writer).

## .taskcheck

Result:

Not run: `sh scripts/check.sh` runs Gradle, which the parent reserved for itself.

## Bugs found

None. All new tests pass against the final implementation (HEAD 2408b6be8).

## Coverage

Status: verified (web part, whole-file coverage of the task-changed production TypeScript files, final revision)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured by intersecting diff lines separately; since both scoped files have all lines covered (207/207 and 31/31), every changed executable line is covered. Changed branches: the only uncovered branches on task-changed lines are the `respond()` guard (event-dialog.ts:426) and the create-mode arm of `occurrenceCalendar` (event-dialog.ts:541), see the gap table. All branches of the CR-1 code (`closeAsSavedOnDismiss`, its guard at :458, the busy check and the Escape/modifier check) are covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-invitation-respond-web; base 54924e3fd196833064725e3138480d2e542345d6 (`git merge-base HEAD origin/main`, from the parent); HEAD 2408b6be845f467f99add8be3ee6aadf15b8226d; task commits 3fa882e7f, 7e53fe01a, acace40ee, 2408b6be8; file scope from `git diff --stat 54924e3fd..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 2408b6be8 plus uncommitted `event-dialog.spec.ts` sha256 ebaa3f1dcacf93f8dc0e4a96bdae683e4d9d898573e4ad1062b547fbe8fbb3dd; `event-dialog.ts` sha256 fb9dce5ef23163890ed3ef50406bc3fe47cb501f513fbab6cf199ccd7f5ab75c and `event-edit.service.ts` sha256 7b9409c426b52a9b45d05fbe4d4744e0d459554b205c87c5f8ac42e7a194b4a4 (both as committed); `event-edit.service.spec.ts` sha256 e9d9bcd8b105907deee94ba43803580d5bc0f574395b746b6cd9efb0a2a2a8ee (as committed). Hashes identical before (23:35:45) and after (23:36:48) the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/calendar/edit/event-dialog.ts`, `web/src/app/features/calendar/edit/event-edit.service.ts`. Excluded: `event-dialog.html` and `event-dialog.scss` (template/styles, not instrumented by v8; the template's bindings are exercised by the DOM tests), `web/src/locale/messages*.xlf` (translation data), spec files, `reviews/` (reports). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` (v8), `npm --prefix web run coverage` from the repository root (runs in `web/`), whole web suite.
- Run start/completion time and exit status: started 2026-11-08 23:35:55 +01:00, about 50 s; exit 0 (193 files, 4583 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 prefix 395a655dde808019), `web/coverage/web/coverage-final.json` (sha256 prefix 690baf3d0a22ba1e), both rewritten by this run (mtime after the run start); per-file counters read with `.tmp/cov_scope.py`. An intermediate run at 23:34:25 (before the last added test) gave 246/252 statements and 177/188 branches for event-dialog.ts and is superseded.
- Evidence outputs are fresh and inputs stayed unchanged: yes (hashes above before and after; reports rewritten by this run).
- Final run after latest test additions and production fixes: yes (after the fixer's commit 2408b6be8 and after this pass's last added test).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/edit/event-dialog.ts` | 247/252 (98.02%) | 178/188 (94.68%) | 58/58 (100%) | 207/207 (100%) |
| `web/src/app/features/calendar/edit/event-edit.service.ts` | 36/36 (100%) | 27/27 (100%) | 17/17 (100%) | 31/31 (100%) |
| Aggregate (summed counters, 2 files; html/scss/xlf excluded as above) | 283/288 (98.26%) | 205/215 (95.35%) | 75/75 (100%) | 238/238 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `event-dialog.ts:426` (`respond()` early return) | Guard against answering in create mode, when not answerable, busy or in conflict | Justified gap: the buttons are not rendered or are disabled in exactly these states, which the tests assert (disabled while sending/saving, after 412, hidden for local/read-only/new events). Only a programmatic call could reach it. |
| `event-dialog.ts:541` (create-mode arm of `occurrenceCalendar`) | Defensive: the helper is only called in the edit branch of the constructor | Justified gap: unreachable from the dialog. |
| `event-dialog.ts:458` (`closeAsSavedOnDismiss` guard on a second answer) | A second successful answer must not subscribe again and close twice | Covered now: 'closes only once when dismissed after two answers'. |
| `event-dialog.ts:98, 216, 217, 245, 293, 295, 388, 403` | Pre-existing code (load guard, reminders, create defaults, delete/invite guards) outside this task's changes; same gaps as the first pass, shifted by the fix | Not in the task's behavior; left as is. |

## Unable to test

- AC2 German translations: verified by `npm run build:de` within the parent's `.taskcheck`, not by unit tests; no follow-up needed if `.taskcheck` passes.
- `sh scripts/check.sh` (AC3): not run by the test-writer (Gradle reserved for the parent); the parent must run it.
- Real MatDialog keyboard/backdrop wiring: the tests drive `backdropClick()` and `keydownEvents()` through a mocked `MatDialogRef`; the real MatDialog emitting these streams while `disableClose` is true is Angular Material behavior and not re-tested here.
- Real provider round trip (Google/Microsoft accepting the answer) is server behavior, out of this web task's scope.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The final code (HEAD 2408b6be8, CR-1 fix) is covered; 3 tests were added to `event-dialog.spec.ts` for repeated answers after the fix. The focused suite (198 tests) and the full web suite with coverage (4583 tests) pass. `.taskcheck` remains for the parent to run.
