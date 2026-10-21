# Test Report

Date: 2026-10-21

Task: web-capture - Quick capture dialog

Scope: task commits 368b8e94, ef8659ac, b009c1e7, b8e3f934 on `main`; base `814f916218670ae23183382e41c9241a72dd706a`, measured HEAD `b8e3f93498cd65b2ad92c999ddf66996d6926765` plus one uncommitted test-file change (see Coverage evidence). Production scope: `web/src/app/features/capture`.

Requirements:
- `tasks/web-capture.md` acceptance criteria and recorded assumptions (single-line text field, Ctrl/Cmd+Enter in notes, attachment placeholder never sent, in-feature UUIDv7 helper, 250 ms silent preview)
- SPEC.md §4.5 (quick capture) as referenced by the task
- `docs/ARCHITECTURE.md` "Coverage"
- parent instructions (Vitest only; Playwright e2e is run by the parent)

## Test files changed

- `web/src/app/features/capture/capture-dialog.spec.ts`

## Tests added or updated

Count: 1

The existing tests from the task commits already covered preview (debounce, silent errors, out-of-order and stale answers, clearing), save (Enter/Shift+Enter/button/notes shortcuts, IME, blank text), double-submit protection, retry with the same id, the attachment placeholder, lazy opening through `CAPTURE_DIALOG` with the route injector, the service and the UUIDv7 helper. The one remaining gap was that the dialog injects `MatDialogRef` optionally, and no test covered it outside a MatDialog. Added: `CaptureDialog outside a MatDialog > saves on Enter and Cancel does not throw without a dialog ref`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: text field with live, debounced syntax preview (`capture/preview`) | `CaptureDialog > preview > *` (13 tests: debounce, silent context, rendering of title/list/project/contexts/due/waiting, failure shows hint, blank text, out-of-order older answer, failing older answer, cleared text, edited text, after Shift+Enter, whitespace-only change); `CaptureService > preview posts...`, `a failed preview rejects without a toast` | `cd web && npx ng test --watch=false --include='src/app/features/capture/*.spec.ts'` | Pass |
| AC1: optional notes | `Enter saves text and notes...`, `leaves out empty notes`, `leaves out whitespace-only notes`, `Enter in the notes adds a line, Ctrl+Enter saves`, `Cmd+Enter in the notes saves on macOS`, `Ctrl+Shift+Enter in the notes saves and keeps the dialog open` | same | Pass |
| AC1: paste/drop attachment placeholder (files not sent) | `attachments placeholder > *` (5 tests) | same | Pass |
| AC1: Enter saves to Inbox and closes; Shift+Enter saves and keeps open | `Enter saves text and notes with a UUIDv7 id, then closes with the task`, `Shift+Enter saves, clears the fields and stays open...`, `the save button saves and closes`, `Cancel closes without saving`, `CaptureDialog outside a MatDialog > saves on Enter and Cancel does not throw without a dialog ref`; `provideCapture > *` (lazy open, focus, route injector) | same | Pass |
| AC2: client UUIDv7 id, retried submit cannot duplicate | `keeps the draft after a failed save and retries with the same id`, `a failed Shift+Enter keeps the draft, notes and files for the retry`, `Shift+Enter ... new id for the next capture`, `uuidv7 > *` (6 tests), `CaptureService > save posts the client id...` | same | Pass (client side only; the server's idempotent handling of a repeated id belongs to gtd-capture-api) |
| AC3: Vitest tests cover preview, save, double-submit; `npm run check` passes | `ignores a second submit while the first is running`, `submit() reports whether a capture was saved`, plus the rows above | `cd web && npm run check` | Pass (exit 0: api:check, lint "All files pass linting.", e2e:typecheck, 60 files / 1120 tests passed, build; only the pre-existing initial bundle budget warning) |
| AC4: Playwright `e2e/capture/capture.spec.ts` against the e2e stack | none by test-writer | - | Not run: the parent instructed that it runs the Playwright e2e itself |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/capture/*.spec.ts'`

Result:

Pass - 4 test files, 49 tests passed.

## Full test execution

Command:

`cd web && npm run check` (also the full web suite under `npm run coverage`)

Result:

Pass - exit 0; 60 test files, 1120 tests passed; lint clean; build succeeded.

Server and Android suites were not run: the task does not change them.

## .taskcheck

Result:

Not run: the sandbox refused `sh scripts/check.sh` for this helper. The parent must run it. The web part of it (`npm run check`) passed above.

## Bugs found

None.

## Coverage

Status: verified (whole-file, web part, fresh run on the snapshot below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately with a diff intersection. Because the provider reports every executable line of every scoped file as covered (lines covered = total), no added or modified executable line can be uncovered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-capture`; base `814f916218670ae23183382e41c9241a72dd706a` and commits 368b8e94, ef8659ac, b009c1e7, b8e3f934 supplied by the parent; HEAD `b8e3f93498cd65b2ad92c999ddf66996d6926765`; file scope from `git diff --stat <base>..HEAD`, matching the task's `touches` (`web/src/app/features/capture`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD above plus the uncommitted `capture-dialog.spec.ts` (sha256 `56a2cc3a43743f348e3014bc06c0adda183f8d60a0c6b25a95e032f1eecf81c8`). Production sha256: `capture-dialog.ts` `b15d8cd0...6b73948`, `capture-dialog.html` `9e0b3d63...3ede1c6`, `capture.service.ts` `3040e113...b31a`, `capture.providers.ts` `6b45cca9...9884`, `uuidv7.ts` `096b8a77...7d4a`. The hashes were the same before and after the coverage run.
- Exact included files: `web/src/app/features/capture/capture-dialog.ts`, `capture-dialog.html`, `capture.service.ts`, `capture.providers.ts`, `uuidv7.ts`. Excluded: `capture-placeholder.ts` (deleted by the task, cannot be instrumented at HEAD); `capture-dialog.scss` (styles, no executable behavior); `*.spec.ts`, `web/src/app/app.spec.ts`, `web/e2e/capture/capture.spec.ts` (tests); `reviews/web-capture/*` (docs).
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with `@vitest/coverage-v8` (^5.0.5, "Coverage enabled with v8"); `npm run coverage` in `web/`; full web unit suite (60 files, 1120 tests).
- Run start/completion time and exit status: started 2026-10-21 02:07:36 +0200, reports written 02:08:08 +0200; all tests passed and the summary was printed. The exit code was not captured separately for this run (output piped through `tail`). The following `npm run check` on the same snapshot exited 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 `c9bc2788ff1c88fffe4cd36ab43b0dff3de527f8e323c304db4c101fcc9bbdf6`), `web/coverage/web/coverage-final.json` (sha256 `7d49b0f124b1b28a4310aeb2089b010b22d4e2620a65a8b7fe31dee75b343675`), `lcov.info`; all Git-ignored.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtimes come from this run (02:08:08), and the source hashes were the same before and after it.
- Final run after latest test additions and production fixes: yes. The run includes the added test. There were no production changes after HEAD.

Values below use covered/total (percentage), from `coverage-summary.json`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/capture/capture-dialog.ts` | 75/75 (100%) | 46/46 (100%) | 21/21 (100%) | 62/62 (100%) |
| `web/src/app/features/capture/capture-dialog.html` | 128/128 (100%) | 18/18 (100%) | 11/11 (100%) | 77/77 (100%) |
| `web/src/app/features/capture/capture.service.ts` | 7/7 (100%) | 5/5 (100%) | 2/2 (100%) | 4/4 (100%) |
| `web/src/app/features/capture/capture.providers.ts` | 7/7 (100%) | 5/5 (100%) | 3/3 (100%) | 4/4 (100%) |
| `web/src/app/features/capture/uuidv7.ts` | 13/13 (100%) | 2/2 (100%) | 3/3 (100%) | 11/11 (100%) |
| **Aggregate (summed counters, 5 files; none missing)** | 230/230 (100%) | 76/76 (100%) | 40/40 (100%) | 158/158 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | - | No uncovered lines or branches in scope |

Coverage numbers do not cover the AC4 browser flow (Q shortcut, three captures appear in the inbox against the real server). That needs the Playwright run.

## Unable to test

- AC4 (Playwright `e2e/capture/capture.spec.ts` against the e2e stack): not run, by parent instruction. The parent must run it and record the result.
- `.taskcheck` (`sh scripts/check.sh`): the sandbox refused it for this helper. The parent must run it.
- Server-side deduplication of a retried id: belongs to `gtd-capture-api`. Here only the client behavior is tested (the same id is resent).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. One test was added (dialog without a `MatDialogRef`). The focused capture tests (49) and the full web check (1120 tests, lint, build) pass. Playwright e2e and `.taskcheck` are left to the parent.
