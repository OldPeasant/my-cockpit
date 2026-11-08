# Test Report

Date: 2026-11-08

Task: cal-invitation-respond-web - Respond to invitations in the web event dialog

Scope: task base 0cec3b4bab909270b22a0e32e7965555e46f97b5 (from the parent). After the parent's rebase onto main, the task commits are 3fa882e7f (Accept/Maybe/Decline in the event dialog) and 7e53fe01a (tests), HEAD 7e53fe01ab0ae851d2430cfe0add46f7ceb8aca7. `git diff 5e67f38ec 7e53fe01a` touches only `tasks/`, so the code is the same as the original commits e885db046 and 5e67f38ec. Uncommitted input: this test-writer's additions to `event-dialog.spec.ts` only.

Requirements:
- tasks/cal-invitation-respond-web.md acceptance criteria
- Assumption cal-invitation-respond #1 (Accept/Maybe/Decline shown for non-read-only GOOGLE/MICROSOFT calendars with attendees; own status = attendee matching a mail account address; server 409 shown as error), consistent with cal-invitation-respond-android #1 (412 -> conflict/reload, unsaved form edits kept)
- SPEC.md §6.2 (POST /api/v1/calendar/events/{id}/respond, If-Match), docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `web/src/app/features/calendar/edit/event-dialog.spec.ts` (uncommitted additions by the test-writer, in the 'answering invitations' block)

Already present in the task commits and reviewed: `event-dialog.spec.ts` ('answering invitations', 11 tests) and `event-edit.service.spec.ts` (3 tests for respond/ownAddresses).

## Tests added or updated

Count: 8 added (no existing test changed)

- reads no mail accounts for a read-only calendar and offers no answer for a new event
- highlights no answer when no guest has a mail account address
- blocks saving, deleting and closing while the answer is sent
- disables the choices while a change is saved
- shows the server's message when a refusal has no detail (404 without detail -> title shown, no conflict)
- closes as unchanged when the answer failed (close result undefined, not 'saved')
- hides the choices when the answered event comes back without guests (covers the `attendees ?? []` fallback)
- answers with the reloaded version after a 412 and Reload

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: dialog offers Accept / Maybe / Decline only for edits of writable GOOGLE/MICROSOFT events with guests | offers Accept, Maybe and Decline with no answer highlighted yet; offers no answer without guests, in local or read-only calendars; reads no mail accounts for a read-only calendar and offers no answer for a new event; hides the choices when the answered event comes back without guests | focused command below | Pass |
| AC1: current status shown (guest matching a mail account address, case-insensitive) | highlights the current answer of the guest with a mail account address; highlights no answer when no guest has a mail account address; still offers the choices when the mail accounts cannot be read; lists the addresses of the mail accounts in lower case (service) | focused command below | Pass |
| AC1: sending calls POST .../respond with If-Match, updates the status and the dialog's version | sends the answer, shows the new status and uses the new version; answers an invitation on the event holding the occurrence, with its version as If-Match (service); answers a generated occurrence through its series master (service); answers with the reloaded version after a 412 and Reload; tells the view the event changed when closed after an answer | focused command below | Pass |
| AC1: unsaved form edits kept after answering | keeps unsaved edits of the form after answering | focused command below | Pass |
| AC1: busy handling (no double send, no save/delete/close during an answer, no answer during a save) | disables the choices while the answer is sent; blocks saving, deleting and closing while the answer is sent; disables the choices while a change is saved | focused command below | Pass |
| AC1 / assumption: server 409/412 and other errors shown | offers a reload when the event changed since it was opened (412); shows the server's reason when it refuses the answer (409); shows the server's message when a refusal has no detail; says so when the answer could not be sent for another reason; closes as unchanged when the answer failed | focused command below | Pass |
| AC2: new strings in English and German | Not unit-tested here: translations are checked by `npm run build:de` (part of `npm run check` / `.taskcheck`), which fails on a missing target. The tests assert the English texts ('Your answer', 'Accept', 'Maybe', 'Decline', 'Your answer was sent.', 'Your answer could not be sent.') | n/a | Not run: build:de belongs to the parent's `.taskcheck` run |
| AC3: unit tests cover the new behavior | all of the above | focused + full coverage run below | Pass |
| AC3: `sh scripts/check.sh` passes | none | n/a | Not run: it runs Gradle, which the parent told the test-writer not to run; the parent runs `.taskcheck` |

## Focused test execution

Command:

`npm --prefix web exec -- ng test --include src/app/features/calendar/edit --watch=false`

Result:

Pass - 8 files, 190 tests passed (final run after the last added test). Prettier `--check` and ESLint on the changed spec file: clean.

## Full test execution

Command:

`npm --prefix web run coverage` (the whole web Vitest suite with coverage)

Result:

Pass - 193 files, 4575 tests passed. Server and Android suites not run (no changes there; Gradle not to be run by the test-writer).

## .taskcheck

Result:

Not run: `sh scripts/check.sh` runs Gradle, which the parent reserved for itself.

## Bugs found

None. No production defect was found; all new tests pass against the current implementation.

## Coverage

Status: verified (web part, whole-file coverage of the task-changed production TypeScript files)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured by intersecting diff lines separately; since both scoped files have 198/198 and 31/31 lines covered, every changed executable line is covered. Changed branches: the only uncovered branches on task-changed lines are the `respond()` guard (event-dialog.ts:425) and the create-mode arm of `occurrenceCalendar` (event-dialog.ts:520), see the gap table.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-invitation-respond-web; base 0cec3b4bab909270b22a0e32e7965555e46f97b5; HEAD 7e53fe01ab0ae851d2430cfe0add46f7ceb8aca7; task commits 3fa882e7f, 7e53fe01a (rebased copies of e885db046, 5e67f38ec), file scope from the parent and `git diff 0cec3b4b..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 7e53fe01a plus uncommitted `event-dialog.spec.ts` sha256 04239a007268d69e6bf65ca517bfb074b5ee8e9618fbcb726c1a23958057acd6 (unchanged before and after the run); `event-dialog.ts` sha256 a0ee0ac223b7b59c8c6edb21a4f0d9316ddf9a49fd6f19f50f563a6d6ac15df8, `event-edit.service.ts` sha256 7b9409c426b52a9b45d05fbe4d4744e0d459554b205c87c5f8ac42e7a194b4a4 (both as committed); `event-edit.service.spec.ts` unchanged from HEAD.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/calendar/edit/event-dialog.ts`, `web/src/app/features/calendar/edit/event-edit.service.ts`. Excluded: `event-dialog.html` and `event-dialog.scss` (template/styles, not instrumented by v8; the template's bindings are exercised by the DOM tests), `web/src/locale/messages*.xlf` (translation data), spec files. No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` (v8), `npm --prefix web run coverage` from the repository root (runs in `web/`), whole web suite.
- Run start/completion time and exit status: started 2026-11-08 23:28:26 +01:00, about 50 s; exit 0 (193 files, 4575 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 prefix 6c36d4276fbb7e73), `web/coverage/web/coverage-final.json` (sha256 prefix 3d851ed9435c8487), both rewritten by this run (mtime after the run start); per-file counters read with `.tmp/cov_scope.py`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (hashes above taken before and after; reports rewritten by this run; an earlier run at 23:26:58 before the last added test gave branches 167/178 for event-dialog.ts and is superseded).
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/edit/event-dialog.ts` | 236/241 (97.92%) | 168/178 (94.38%) | 55/55 (100%) | 198/198 (100%) |
| `web/src/app/features/calendar/edit/event-edit.service.ts` | 36/36 (100%) | 27/27 (100%) | 17/17 (100%) | 31/31 (100%) |
| Aggregate (summed counters, 2 files; html/scss/xlf excluded as above) | 272/277 (98.19%) | 195/205 (95.12%) | 72/72 (100%) | 229/229 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `event-dialog.ts:425` (`respond()` early return) | Guard against answering in create mode, when not answerable, busy or in conflict | Justified gap: the buttons are not rendered or disabled in exactly these states, which the tests assert (disabled while sending/saving, after 412, hidden for local/read-only/new events). Only a programmatic call could reach it. |
| `event-dialog.ts:520` (create-mode arm of `occurrenceCalendar`) | Defensive: the helper is only called in the edit branch of the constructor | Justified gap: unreachable from the dialog. |
| `event-dialog.ts:431` (`event.attendees ?? []`) | Server answer without guests | Covered now: 'hides the choices when the answered event comes back without guests'. |
| `event-dialog.ts:97, 215, 216, 244, 292, 294, 387, 402` | Pre-existing code (load guard, reminders, create defaults, delete/invite guards) outside this task's changes | Not in the task's behavior; left as is. |

## Unable to test

- AC2 German translations: verified by `npm run build:de` within the parent's `.taskcheck`, not by unit tests; no follow-up needed if `.taskcheck` passes.
- `sh scripts/check.sh` (AC3): not run by the test-writer (Gradle reserved for the parent); the parent must run it.
- Real provider round trip (Google/Microsoft accepting the answer) is server behavior, out of this web task's scope.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 8 tests were added to `event-dialog.spec.ts`; the focused suite (190 tests) and the full web suite with coverage (4575 tests) pass. `.taskcheck` remains for the parent to run.
