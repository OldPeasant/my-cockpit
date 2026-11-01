# Test Report

Date: 2026-11-01

Task: web-mail-read-delete - Mail read view: Delete button

Scope: second pass on the final revision. Base 6ba9af35dab9cc0c621ddb0f737c04d16ad9adf5 (`git merge-base HEAD origin/main`), HEAD 0ec379e65509a175b8fed5629b8383e8dc8f5172 on branch main. Task commits (`git log origin/main..HEAD`): a6dd247fe (Delete button and # key), 1e14d105f (more Delete tests, first review evidence), 0ec379e65 (fix CR-1: # does nothing while a dialog is open). Production changes in `git diff 6ba9af35d..HEAD -- web/`: `web/src/app/features/mail/read/mail-read-page.ts`, `mail-read-page.html`, `web/src/locale/messages.xlf`, `web/src/locale/messages.de.xlf`. Nothing in `web/` was uncommitted during measurement. The only uncommitted file was `reviews/web-mail-read-delete/review.md`, which another helper changed. This pass changed no test files.

Requirements:
- `tasks/web-mail-read-delete.md` acceptance criteria and the three recorded assumptions: apply('DELETE',[id]) instead of act; origin from the router's previous navigation, with replaceUrl; error toast via NotificationService
- `reviews/web-mail-read-delete/fixes.md` (CR-1 fix) and `review.md`
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions: review the final code and tests, add tests only where something is still missing

## Test files changed

- `web/src/app/features/mail/read/mail-read-page.spec.ts` (changed earlier in the task, by the first test-writer pass and by the fixer; this pass changed nothing)

## Tests added or updated

Count: 0 in this pass. The fixer's new test, `the # key > does nothing while a dialog is open, and deletes again once it is closed`, was reviewed. It opens a real MatDialog and checks that `#` is not handled while the dialog is open: no preventDefault, no DELETE action, no navigation. It then checks that `#` deletes again after the dialog closes. Coverage shows that both branches of the new guard run (see below). The fixer also reports that the test fails when the guard is removed. Nothing was missing, so no further test was added.

## Requirement coverage

Focused command (F): `npm --prefix web run test:ci -- --include src/app/features/mail/read/mail-read-page.spec.ts --include src/locale/messages-de.spec.ts`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Delete button shown once the message has loaded | `Delete > is not offered before the message has loaded`, `is not offered when the message could not be loaded`, `deletes the message with the mail action and returns to the unified Inbox` | F | Pass |
| AC1: calls the mail actions API with DELETE and [id] | `deletes the message with the mail action ...`, `the # key > deletes, as in the list`, `back to the list > returns to the folder the message was opened from` | F | Pass |
| AC1: disabled while the request runs | `is disabled while the request runs, so it cannot run twice` | F | Pass |
| AC2: on success return to the originating list (folder / unified Inbox / unknown) | `returns to the folder the message was opened from`, `returns to the unified Inbox it was opened from`, `... when opened from another page`, `... when opened directly`, `... when opened from another mail page`, `returns to a folder whose id needs URL encoding`, `keeps the origin when moving on to another message` | F | Pass |
| AC2: Back does not reopen the deleted message (assumption: replaceUrl) | `replaces the deleted message in the history, so Back does not open it again` | F | Pass |
| AC2: "the message is no longer shown there" | Not tested in this spec. The list reloads its data when it is created after the navigation, and that list behavior belongs to web-mail-list, outside this task's touches. The navigation itself is covered above | - | Not run: list reload is outside the read view |
| AC2: on failure the view stays open with an error toast | `stays open and shows the server reason ...`, `reports a failed request with the API error ...`, `reports an unexpected failure, or a missing result, in general words`, `treats a result for another id only as a failure` | F | Pass |
| AC2: no navigation when the user left the message or page during the request | `does not leave a message the user moved to while the delete ran`, `does not navigate when the page was left while the delete ran` | F | Pass |
| AC3: `#` deletes (also with AltGr); not while typing; not other keys/chords/repeat; not before ready | `the # key > deletes, as in the list`, `also deletes when typed with AltGr`, `does nothing while typing in an input or editor`, `ignores other keys, chords and an auto-repeated #`, `does not delete with # while the next message loads` | F | Pass |
| AC3: "as in the list": `#` ignored while a dialog is open (CR-1 fix) | `the # key > does nothing while a dialog is open, and deletes again once it is closed` | F | Pass |
| AC4: i18n units translated (messages.de.xlf) | `src/locale/messages-de.spec.ts` | F | Pass |
| AC4: `cd web && npm run check` passes | Lint and the full unit suite were run (see below). `api:check`, `e2e:typecheck` and `ng build` were not run by the test-writer | `npm --prefix web run lint`, `npm --prefix web run test:ci` | Pass (partial; the parent must run the full `npm run check`) |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include src/app/features/mail/read/mail-read-page.spec.ts --include src/locale/messages-de.spec.ts`

Result:

Pass (2 files, 76 tests, started 2026-11-01 07:02:35 +0100)

## Full test execution

Command:

`npm --prefix web run test:ci` and `npm --prefix web run lint`

Result:

Pass. Unit suite: 3882 tests passed, started 07:03:28 +0100. Lint: "All files pass linting."

## .taskcheck

Result:

Not run. `sh scripts/check.sh` includes the Gradle server and Android parts, which helpers cannot run here. The parent runs it.

## Bugs found

None. This pass found no production defect. CR-1 is fixed and covered by a test. The parent accepted CR-2 (Back after Delete, replaceUrl) as recorded behavior.

## Coverage

Status: verified (web part, read-folder specs)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: the lines added in `git diff -U0 6ba9af35d..HEAD -- web/src/app/features/mail/read/mail-read-page.ts` (14, 16, 26-27, 46-47, 55, 61-65, 84-85, 175-213, 339-356), intersected with the executable lines (lcov `DA`) and the branch locations (`coverage-final.json` branchMap) of this run. Lines: 30/30 (100%). Branches that start on changed lines: 32/33 (97.0%). The one missed branch is the `?? []` fallback at line 354. Both branches of the new dialog guard (line 207) are covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-mail-read-delete; base 6ba9af35dab9cc0c621ddb0f737c04d16ad9adf5; HEAD 0ec379e65509a175b8fed5629b8383e8dc8f5172; commits a6dd247fe, 1e14d105f, 0ec379e65 from `git log origin/main..HEAD`, all prefixed with the task id.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 0ec379e65 with a clean `web/`. sha256 values: `mail-read-page.ts` 81076c5f3cb5c95ce1f3439ce63ff8b5c73d86315db5b0e32bf6622a1a657e83, `mail-read-page.html` 8b059d6ca4794ebc181d4116fcf7db4c4a17550cc6813907b681c725f5461d55, `mail-read-page.spec.ts` 6527268be1cee8d6d1f4fa406dc354d8a4a2e30582195553c86af0a60732d6af. The hashes were the same before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/read/mail-read-page.ts`. `mail-read-page.html` is compiled into the component, so it has no counters of its own; the DOM tests exercise it. `messages.xlf` and `messages.de.xlf` are translation data, not executable code; `messages-de.spec.ts` validates them. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` 5.0.5. Command: `npm --prefix web run coverage -- --include 'src/app/features/mail/read/**/*.spec.ts'`, run from the repo root. Selection: 5 spec files, 103 tests.
- Run start/completion time and exit status: started 2026-11-01 07:02:48 +0100, reports written 07:02:53 +0100. Exit 0, 103 of 103 passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 cca628bd01dcaf98538bf824c56526967aa3ac92da675e1230b3d74a13679b38), `web/coverage/web/coverage-final.json` (sha256 5a583c370c0c941b44b6e3c7de7daca402c6d5aee097bc8e03cd40d0c7b94179), `web/coverage/web/lcov.info` (sha256 8864ed7714111f057351c7c7995ef09bc1e08b758f2a2d9d7c3321d904bb74aa).
- Evidence outputs are fresh and inputs stayed unchanged: this run rewrote the reports (07:02:53, replacing the files from the previous pass at 06:57), and the hashes differ from the previous pass. The source hashes did not change during the run.
- Final run after latest test additions and production fixes: yes. The run measured HEAD 0ec379e65, which includes the CR-1 fix and its test.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/read/mail-read-page.ts` | 190/198 (95.96%) | 119/140 (85.0%) | 42/43 (97.67%) | 160/160 (100%) |

Only one file is in the measured scope, so the aggregate equals the row above. Not measured separately: `mail-read-page.html` (compiled into the component) and the two xlf files (not executable).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-read-page.ts:354` (right side of `?? []`) | Previous navigation URL without a primary outlet | Defensive. A real navigation to a mail URL cannot reach it. The case with no previous navigation is handled at line 345 and is covered. Left uncovered |
| `mail-read-page.ts:57` (function `MailReadPage_keydown_HostBindingHandler`: 3 statements, 4 branches) | Compiled host listener for `document:keydown` | Tooling artifact: v8 source maps attribute Angular's generated host-binding function to the class line. The listener logic runs (lines 206-210 and both branches of the guard at 207 are covered). No test added |
| `mail-read-page.ts:126, 155, 225, 257, 259` and binary-expression fallbacks at 69, 94, 97, 101, 105, 115, 144, 150, 287, 291, 362 | Existing load, image and preview code | Not changed by this task; outside scope |

## Unable to test

- "The message is no longer shown there" in the list after returning: this depends on the mail list reloading when it is created (web-mail-list, outside this task's touches). This spec does not cover it; a browser e2e flow would. Follow-up only if the parent wants e2e evidence.
- In jsdom, the Router does not follow `Location.back()` (popstate) with the SpyLocation the harness uses. The history test therefore asserts the Location path, not the routed component.
- The test-writer did not run the full `npm run check` (`api:check`, `e2e:typecheck`, `ng build`) or `.taskcheck`; the parent should run them.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
