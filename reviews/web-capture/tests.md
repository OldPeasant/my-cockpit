# Test Report

Date: 2026-10-21

Task: web-capture - Quick capture dialog

Scope: task commits 368b8e94, ef8659ac, b009c1e7, b8e3f934, a140641c, 5b4d21b1 on `main`; base `814f916218670ae23183382e41c9241a72dd706a`, measured HEAD `5b4d21b152aa6e148b72186450085892c7d669d4` (final code, including the CR-1 fix "keep input typed while a Shift+Enter save runs") plus the uncommitted test-file change below. Production scope: `web/src/app/features/capture`. This report replaces the earlier one for b8e3f934, which is stale.

Requirements:
- `tasks/web-capture.md` acceptance criteria and recorded assumptions (single-line text field, Ctrl/Cmd+Enter in notes, attachment placeholder never sent, in-feature UUIDv7 helper, 250 ms silent preview)
- SPEC.md §4.5 (quick capture) as referenced by the task
- `docs/ARCHITECTURE.md` "Coverage"
- `reviews/web-capture/fixes.md` (intent of the final fix: on a keep-open save, clear only what was submitted)
- parent instructions (Vitest only; the parent runs the Playwright e2e)

## Test files changed

- `web/src/app/features/capture/capture-dialog.spec.ts`

## Tests added or updated

Count: 2

I checked the final `capture-dialog.ts` against the fix intent. The fixer's test (`Shift+Enter keeps text, notes and files added while the save is running`) covers the case where the text changes during a save and the notes stay the same. It does not cover two other cases, so I added a test for each:

- `save > Shift+Enter keeps notes edited while the save is running and clears the saved text`: the opposite case. The notes change during the save and the text does not. The text must be cleared, the edited notes must stay, and the edited notes are sent with the next capture under a new UUIDv7 id.
- `attachments placeholder > Shift+Enter removes only the submitted files, one entry per name`: this tests the `without()` helper. Two files are submitted ("scan.jpg", "receipt.pdf"). While the save runs, the user removes "receipt.pdf" and pastes another "scan.jpg" and "invoice.pdf". After the save, `["scan.jpg", "invoice.pdf"]` must remain. A name-based filter (which would remove both "scan.jpg") or a full clear would fail this test.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: text field with live, debounced syntax preview (`capture/preview`) | `CaptureDialog > preview > *` (13 tests: debounce, silent context, rendering, failure shows hint, blank text, out-of-order/failing older answers, cleared/edited text, after Shift+Enter, whitespace-only change); `CaptureService > preview ...` | `cd web && npx ng test --watch=false --include='src/app/features/capture/*.spec.ts'` | Pass |
| AC1: optional notes | `Enter saves text and notes...`, `leaves out empty notes`, `leaves out whitespace-only notes`, `Enter in the notes adds a line, Ctrl+Enter saves`, `Cmd+Enter in the notes saves on macOS`, `Ctrl+Shift+Enter in the notes saves and keeps the dialog open`, `Shift+Enter keeps notes edited while the save is running and clears the saved text` (new) | same | Pass |
| AC1: paste/drop attachment placeholder (files not sent) | `attachments placeholder > *` (6 tests, including the new `Shift+Enter removes only the submitted files, one entry per name`) | same | Pass |
| AC1: Enter saves to Inbox and closes; Shift+Enter saves and keeps open | `Enter saves ... then closes with the task`, `Shift+Enter saves, clears the fields and stays open...`, `Shift+Enter keeps text, notes and files added while the save is running`, the two new tests, `the save button saves and closes`, `Cancel closes without saving`, `CaptureDialog outside a MatDialog > ...`; `provideCapture > *` | same | Pass |
| AC2: client UUIDv7 id, retried submit cannot duplicate | `keeps the draft after a failed save and retries with the same id`, `a failed Shift+Enter keeps the draft, notes and files for the retry`, new-id assertions in the Shift+Enter tests, `uuidv7 > *`, `CaptureService > save posts the client id...` | same | Pass (client side only; server deduplication belongs to gtd-capture-api) |
| AC3: Vitest tests cover preview, save, double-submit; `npm run check` passes | `ignores a second submit while the first is running`, `submit() reports whether a capture was saved`, plus the rows above | `cd web && npm run check` | Pass (exit 0: lint "All files pass linting.", 60 files / 1123 tests passed, build) |
| AC4: Playwright `e2e/capture/capture.spec.ts` against the e2e stack | none by test-writer | - | Not run: per the parent's instruction, the parent runs Playwright itself |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/capture/*.spec.ts'`

Result:

Pass: 4 test files, 52 tests passed.

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass: exit 0, 60 test files, 1123 tests passed, lint clean, build succeeded.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` exited 0 with "all checks passed". It ran the tfp gate tests, the server ktlint and tests, the web eslint, tests (1123 passed) and build, and the android ktlint, unit tests and lint. The run included the uncommitted test change.

## Bugs found

None.

## Coverage

Status: verified (whole-file, web part, fresh run on the snapshot below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: I did not intersect it with the diff separately. The provider reports every executable line of every scoped file as covered (covered = total), so none of the added or modified executable lines is uncovered. Changed-branch coverage is not inferred from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-capture`; base `814f916218670ae23183382e41c9241a72dd706a` and final HEAD `5b4d21b152aa6e148b72186450085892c7d669d4` came from the parent. Commits are from `git log --oneline 814f9162..HEAD` (6 commits, all prefixed `web-capture:`). The file scope is from `git diff --stat 814f9162..HEAD` and matches the task's `touches` (`web/src/app/features/capture`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD above plus the uncommitted `capture-dialog.spec.ts` (sha256 `81c30a6050feecd5b85f3de875565c253925ab836d4f67a451205102c1790d20`). Production sha256: `capture-dialog.ts` `fe46b33f389371f1970fe8309e66007d2c74515229a47b0aa1eebceacc64b12c`, `capture-dialog.html` `9e0b3d63e66592dc30cbceb0c7336d9e7013163c6a3930caf882928548696a7c`, `capture.service.ts` `3040e113033e848e0a77ee6bc1808e8ac1a3c7dca8932191bc780b268bfeb31a`, `capture.providers.ts` `6b45cca9d2ceb37ea974920b03e9dfd5bf5565ee4bda652155a266b9b9b19884`, `uuidv7.ts` `096b8a7708c69c03502efe173d457a2ce999f98196f2e199c9e7bc1425b37d4a`. The hashes were the same before the coverage run and after the following `npm run check`. `reviews/web-capture/review.md` also showed as modified in the working tree. I did not change it, and it is not a test input.
- Exact included files: `web/src/app/features/capture/capture-dialog.ts`, `capture-dialog.html`, `capture.service.ts`, `capture.providers.ts`, `uuidv7.ts`. Excluded: `capture-placeholder.ts` (deleted by the task, so it cannot be instrumented at HEAD); `capture-dialog.scss` (styles, no executable behavior); `*.spec.ts`, `web/src/app/app.spec.ts`, `web/e2e/capture/capture.spec.ts` (tests); `reviews/web-capture/*` (docs).
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with `@vitest/coverage-v8` (^5.0.5, "Coverage report from v8"); `npm run coverage` in `web/`; the full web unit suite (60 files, 1123 tests).
- Run start/completion time and exit status: started 2026-10-21 02:13:27 +0200 (tests started 02:13:49), finished 02:14:21 +0200; exit code 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 `fe5a3f1b7c8f8959aaab8f164685ac089c1742133d6ff10a53c1f3c13e1d429e`, written 02:14:20), `web/coverage/web/coverage-final.json` (sha256 `c9fe5477d2ead0881b791e7a5f36369339bf40576842fbbb68676f207a6db564`, written 02:14:20), `web/coverage/web/lcov.info` (written 02:14:21). All are Git-ignored. The stdout log is `.tmp/web-capture-coverage.log`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The reports were rewritten during this run (their mtimes fall inside the run window), and the input hashes did not change.
- Final run after latest test additions and production fixes: yes. The run covers the final production code (5b4d21b1) and both added tests.

Values below are covered/total (percentage) from `coverage-summary.json`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/capture/capture-dialog.ts` | 87/87 (100%) | 52/52 (100%) | 24/24 (100%) | 70/70 (100%) |
| `web/src/app/features/capture/capture-dialog.html` | 128/128 (100%) | 18/18 (100%) | 11/11 (100%) | 77/77 (100%) |
| `web/src/app/features/capture/capture.service.ts` | 7/7 (100%) | 5/5 (100%) | 2/2 (100%) | 4/4 (100%) |
| `web/src/app/features/capture/capture.providers.ts` | 7/7 (100%) | 5/5 (100%) | 3/3 (100%) | 4/4 (100%) |
| `web/src/app/features/capture/uuidv7.ts` | 13/13 (100%) | 2/2 (100%) | 3/3 (100%) | 11/11 (100%) |
| **Aggregate (summed counters, 5 files; none missing; exclusions listed above)** | 242/242 (100%) | 82/82 (100%) | 43/43 (100%) | 166/166 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | - | No uncovered lines or branches in scope. Before my tests, the CR-1 fix's branches were already executed. The two new tests check behavior the earlier tests did not: notes edited during a save, and `without()` removing duplicates one by one. |

These coverage numbers do not include the AC4 browser flow (Q shortcut, three captures in the inbox against the real server). That needs the Playwright run.

## Unable to test

- AC4 (Playwright `e2e/capture/capture.spec.ts` against the e2e stack): not run, per the parent's instruction. The parent must run it and record the result.
- Server-side deduplication of a retried id belongs to `gtd-capture-api`. Here only the client behavior is tested: the same id is sent again.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I added two tests for the final keep-open save logic: notes edited during a save, and `without()` removing duplicate file names one by one. The focused capture tests (52), `npm run check` (1123 tests, lint, build) and `.taskcheck` pass. The parent still has to run the Playwright e2e for AC4.
