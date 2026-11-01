# Test Report

Date: 2026-11-01

Task: web-mail-read-archive-spam - Mail read view: Archive and Spam buttons

Scope: `d2364d7b2f1fbdc7a1bbe53c70f40b69a8d922fe..3d341d70b5d73079c8808d2021cd708b1476e48b` (task commits
`29599aad09b5b33d6d90a51f29ab47060e792bc0` implementation, `3d341d70b5d73079c8808d2021cd708b1476e48b` extra tests
from an earlier interrupted test-writer run), plus the uncommitted test additions of this run in
`web/src/app/features/mail/read/mail-read-page.spec.ts`. Branch `main`.

Requirements:
- `tasks/web-mail-read-archive-spam.md` acceptance criteria and notes
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`
- Existing test conventions in `web/src/app/features/mail/read/mail-read-page.spec.ts` (Vitest via `ng test`,
  fake `MailService`, deferred promises, `RouterTestingHarness`)

## Test files changed

- `web/src/app/features/mail/read/mail-read-page.spec.ts`

## Tests added or updated

Count: 3 added in this run (on top of the 25 Archive/Spam tests already present in commits `29599aad0` and
`3d341d70b`, which were reviewed against the criteria and kept unchanged).

Added in the `Archive and Spam` describe block:
- `reports a refusal without a reason, or a missing result, in general words` (ok:false without `detail` for
  Spam, empty result list for Archive -> "Something went wrong.", no navigation)
- `can mark as spam after archiving failed, and then returns to the list` (NO_ROLE_FOLDER on Archive, then `!`
  succeeds: the buttons/shortcuts work again after a failure and navigate exactly once)
- `does not navigate when the page was left while marking as spam ran` (component destroyed mid-request)

## Requirement coverage

Command for all rows: `cd web && npx ng test --watch=false --include src/app/features/mail/read` (5 files,
132 tests passed), also run inside `cd web && npm run check` (4018 tests passed).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: after load, action bar shows Archive and Spam (next to Delete) | `sit next to Delete in the action bar, with their shortcuts in the title`; `are not offered before the message has loaded`; `are not offered when the message could not be loaded` | see above | Pass |
| AC1: call the mail actions API with `ARCHIVE` / `SPAM` like Delete | `Archive > calls the ARCHIVE mail action and returns to the list`; `Spam > calls the SPAM mail action and returns to the list` | see above | Pass |
| AC1: all three disabled while one runs | `Archive/Spam > disables all three buttons while it runs, so nothing runs twice` (clicks and `e`/`!`/`#` ignored while pending, re-enabled after) | see above | Pass |
| AC2: success returns to the list it was opened from | harness `Archive/Spam > returns to the folder the message was opened from`, `returns to the unified Inbox when opened from another page`, `replaces the message in the history, so Back does not open it again`; unit `calls the ... mail action and returns to the list` | see above | Pass |
| AC2: failure stays open and shows the server error (NO_ROLE_FOLDER) | `stays open with the server reason when the account has no Archive folder`; `... no Spam folder`; `reports a failed request with the API error`; `reports a refusal without a reason, or a missing result, in general words` (new); `can mark as spam after archiving failed, and then returns to the list` (new); harness `Archive/Spam > stays on the message when the action fails` | see above | Pass |
| AC2 (guards): no navigation if the user moved on / left the page | `does not leave a message the user moved to while the archive ran`; `does not navigate when the page was left while marking as spam ran` (new) | see above | Pass |
| AC3: `e` archives, `!` marks as spam | `Archive > runs with the e key, as in the list`; `Spam > runs with the ! key, as in the list`; `also marks as spam when ! is typed with Shift` | see above | Pass |
| AC3: not while typing in an input or editor (nor chords, repeats, dialogs, loading) | `Archive/Spam > ignores e/! while typing in an input or editor, or as a chord or repeat`; `ignores e and ! while a dialog is open`; `ignores e and ! while the next message loads` | see above | Pass |
| AC4: unit tests for both buttons, both shortcuts, success navigation, error case | all of the above | see above | Pass |
| AC4: `cd web && npm run check` passes | whole web check (api:check, lint, e2e:typecheck, test:ci, ng build) | `cd web && npm run check` | Pass (exit 0; 185 files / 4018 tests; only the pre-existing initial-bundle budget warning) |
| Note: reuse Delete's action code | existing Delete tests (`Delete` describe, `#` key, harness Delete tests) still pass against the shared `triage()` | see above | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/mail/read`

Result:

Pass (5 test files, 132 tests)

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (exit 0; Vitest 185 files, 4018 tests; build succeeded with the existing "bundle initial exceeded
maximum budget" warning, unrelated to this task)

## .taskcheck

Result:

Not run to completion by the test-writer: `sh scripts/check.sh` (all parts: server, web, android) did not
finish within the 10 min tool limit of this helper session; the parent worker runs `.taskcheck` before
`done`. The web part (`npm run check`) passed above.

## Bugs found

None. No production defect was found for the task's behavior.

## Coverage

Status: verified (task-scoped run restricted to the read-view tests; see note on the full run below)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 31/31 changed executable lines covered (100%). Method: executable lines from the
provider's source-mapped `statementMap` in `coverage-final.json`, intersected with added/modified lines from
`git diff -U0 d2364d7b2..HEAD -- web/src/app/features/mail/read/mail-read-page.ts`; removed and
non-executable lines are not counted. Changed-branch coverage not separately measured (no uncovered branch
lies inside the changed lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-mail-read-archive-spam`;
  base `d2364d7b2f1fbdc7a1bbe53c70f40b69a8d922fe` (given by the parent); HEAD
  `3d341d70b5d73079c8808d2021cd708b1476e48b`; task commits `29599aad0`, `3d341d70b` (given by the parent,
  confirmed with `git diff --stat d2364d7b2..HEAD`, which shows only task files).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `3d341d70b` plus uncommitted
  `mail-read-page.spec.ts` sha256 `1bd673bd93083fd2c468b69de9f010ac485606127557c5699b2d55f971260dd3`;
  `mail-read-page.ts` sha256 `90c694af34d9865dfe9678776af89921652a8b4de2254e3cb52c83bd6bc9b0ab`;
  `mail-read-page.html` sha256 `6117864b68eb8f76e0e9c3f93a1f3a3e1e11f49100b55fbbfe6e9cc43dedc839`
  (hashes identical before and after the run).
- Exact included files; renamed/deleted/excluded files and reasons: included
  `web/src/app/features/mail/read/mail-read-page.ts`. Excluded: `mail-read-page.html` (template, no separate
  counters in the provider; its bindings are exercised through the component tests),
  `mail-read-page.spec.ts` (test), `web/src/locale/messages.xlf` and `messages.de.xlf` (i18n data, not
  executable; validated by `ng build` in `npm run check`). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8`
  (via `ng test --coverage`); `npm run coverage -- --include src/app/features/mail/read` in `web/`;
  selection: the 5 spec files under `src/app/features/mail/read`.
- Run start/completion time and exit status: 2026-11-01T08:00:02Z - 08:00:13Z, exit 0 (132 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256
  `77457b8f5276a72b8b2cea4e229ace3cd363bf8217a071f7967bd5ae6f63dbf3`), `coverage-summary.json`, `lcov.info`;
  all written 2026-11-01 09:00:13 +0100 by this run (the directory was regenerated).
- Evidence outputs are fresh and inputs stayed unchanged: yes (file times inside the run window; source/test
  hashes unchanged).
- Final run after latest test additions and production fixes: yes (includes the 3 tests added in this run; no
  production change happened).
- Earlier attempt: the unrestricted `cd web && npm run coverage` (2026-11-01T07:58:47Z - 07:59:55Z) exited 1:
  `src/app/app.spec.ts > App > builds every navigation section, so none shows the coming soon placeholder`
  timed out at 5000 ms under coverage instrumentation (it passes in `npm run check`), and no coverage report
  was written. That run is not used for any number here. The timeout is outside this task's scope; the parent
  may want to flag it as a flaky-under-coverage test.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/read/mail-read-page.ts` | 197/205 (96.1%) | 124/145 (85.5%) | 43/44 (97.7%) | 167/167 (100%) |

Single file in scope, so no aggregate; no files missing or unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-read-page.ts:70` (statement, 4 branches, function `MailReadPage_keydown_HostBindingHandler`) | Angular-generated host listener for `(document:keydown)` mapped onto the class line | Instrumentation artifact of compiled code: the handler's target `onKeydown` is fully covered and the `e`/`!`/`#` keyboard tests pass through real `document` keydown events. No test added. |
| `mail-read-page.ts:139, 168, 240, 272, 274` and the `if`/`binary-expr` branches at 82, 107-128, 157, 163, 302, 306, 369, 377 | Pre-existing guards and fallbacks (images, GTD, attachment download/save, nullish defaults) | Outside this task's changes (no changed line affected); left uncovered. |
| Changed lines (`triage()`, `onKeydown()`, `triageFailed()`, `TRIAGE_SHORTCUTS`) | AC1-AC3 | 31/31 changed executable lines covered; no uncovered branch inside them. |

## Unable to test

- Real-browser behavior against a running server (actual IMAP NO_ROLE_FOLDER answer) is not part of the
  criteria ("unit tests") and was not run; the server's error shape is reproduced in the fake `MailService`.
- German translations (`messages.de.xlf`) are only checked indirectly: `npm run check` builds the default
  locale; `npm run build:de` was not run by the test-writer. Follow-up optional for the parent.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
