# Test Report

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: task base `4f49a66e73d13ed0f53676cab4e82a98a521ffd7` .. HEAD `0001772c47600a9989a5f4733737e888a0c4cd87` on `main`; task commits `20c33dcc`, `32dcfdf4`, `e51eb73b`, `5f140ee9`, `20c64a4d`, `0001772c` (supplied by the parent worker, confirmed with `git log 4f49a66e..HEAD`; the hashes in the earlier reports are pre-rebase). This run re-checks the fixes of `0001772c` (TW-1, CR-1..CR-3). Production files changed by the task: `web/src/app/features/notes/editor/{markdown-editor,note-editor-page,note-editor.service,note-markdown,note-markdown-view,note-revisions-dialog}.ts` (+ `.html`/`.scss`), `web/src/app/features/notes/notes.routes.ts`, `web/package.json` (dependency line only). Measured state: HEAD plus the uncommitted test file below. No uncommitted production changes. `reviews/web-notes-editor/review.md` was also modified in the working tree by another helper during this run; it is not part of this report.

Requirements:
- `tasks/web-notes-editor.md` acceptance criteria and recorded assumptions (1 s debounce, 409/412 conflict banner, toggles save immediately, pending edits flushed when leaving, leaving in conflict discards edits, switching notes during an upload waits for it)
- `SPEC.md` §8.1 (editor, images, checklists, history) and §4.3 (inline links, chips, Linked items panel)
- `reviews/web-notes-editor/review.md` (CR-1..CR-3) and `reviews/web-notes-editor/fixes.md` (fix behavior to re-check)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `web/src/app/features/notes/editor/note-editor-page.spec.ts` (5 tests added; the existing tests are unchanged, and the only other change is adding `Router` to the existing `@angular/router` import)

## Tests added or updated

Count: 5 added (editor page). The editor specs now hold 93 tests in 5 files (88 at `0001772c`).

Added:
- `retries a failed save before opening another note` (CR-3 through a chip, not only on page destroy)
- `when leaving during several uploads, saves every image in place of its placeholder` (uploads ending out of order after the page is destroyed)
- `when the save on leaving fails, saves again with the image once the upload ends` (leave plus error plus upload)
- `asks before closing the browser tab while an image is still uploading` (beforeunload while an upload is pending and the text around it is saved)
- `stays on the first note when the route returns to it while leaving waits for an upload` (ordering: fails, TW-2)

## Requirement coverage

Command F = `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` (5 files, 93 tests: 92 passed, 1 failed: TW-2).
Command C = `cd web && npm run check` (api:check, lint, e2e:typecheck pass; test:ci 88 files, 1606 tests: 1605 passed, 1 failed: TW-2; `ng build` not reached, passes on its own via `npx ng build`).
Command E = `cd web && npm run e2e -- e2e/notes/editor.spec.ts` (docker compose e2e stack, chromium; the runner also runs the 5 `phase-demos` tests).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 CodeMirror editor with preview toggle/split | page: `switches between write, side by side and preview, and remembers the choice`; editor: `shows the value, reports edits and applies values from outside without reporting them` | F | Pass |
| AC1 autosave debounced, If-Match | page: `autosaves once after the debounce, with the version as If-Match`, `saves edits made while a save runs after that save`, `removes the title with null when it is cleared`; service: `saves title and body with the version as a quoted If-Match` | F | Pass |
| AC1 conflict notice (412/409), keep mine / discard | page: `on a conflict stops autosaving and keeps my version on request`, `on a conflict discards my edits on request`, `treats a 409 like a 412: conflict banner, no further autosave`, `keeps the conflict when reloading the newer version fails` | F | Pass |
| CR-2 fix: stale conflict actions ignored after a note switch | page: `“Save my version” has no effect when another note was opened meanwhile`, `“Discard my edits” has no effect when another note was opened meanwhile`, `shows no error of a conflict action when another note was opened meanwhile` | F | Pass |
| CR-3 fix: retry on leave after a failed save | page: `retries a failed save when leaving the page`, `retries a failed save before opening another note` (new), `shows other save errors with a retry` | F | Pass |
| Flush when leaving (other note, other page, during a save) | page: `saves pending edits before opening another note`, `saves pending edits when leaving the page`, `when leaving during a save, saves the edits made meanwhile after it`, `saves nothing when leaving without edits`, `asks before closing the browser tab only while edits are unsaved`, `ignores a note that arrives after another one was opened` | F | Pass |
| TW-1/CR-1 fix: placeholders never saved | page: `does not leave an upload placeholder in the saved note when leaving during an upload` (TW-1), `does not autosave the placeholder of an image that is still uploading`, `when leaving during an upload that then fails, saves the note without its placeholder`, `when leaving during several uploads, saves every image in place of its placeholder` (new); editor: `withoutUploadPlaceholders` test, `passes the placeholder to the upload, and leaves uploads that end after it is gone alone` | F | Pass |
| TW-1/CR-1 fix: pending uploads applied after leaving / switching notes | page: `puts an image still uploading into the note before opening another note`, `when the save on leaving fails, saves again with the image once the upload ends` (new), `asks before closing the browser tab while an image is still uploading` (new) | F | Pass |
| Opening a note follows the route also while leaving waits (ordering) | page: `stays on the first note when the route returns to it while leaving waits for an upload` (new) | F | Fail (TW-2) |
| AC2 paste/drop image -> upload -> inserts Markdown; upload failures | editor: paste, drop, several files, out-of-order results, deleted placeholder, failure tests; page: `uploads pasted images to the note`, `shows a failed image upload`, `shows a generic message for an unexpected upload failure`; service: `uploads an image as multipart form data to the note` | F | Pass |
| AC2 rendered via the authenticated file URL | view: `shows cockpit-file images through the authenticated preview URL`; service: `builds the authenticated preview URL of a file`; `note-markdown.spec.ts` | F | Pass |
| AC2 preview checklists toggle and save | page: `toggles a checklist item in the preview and saves it right away`, `does not save a checklist toggle while in conflict`; view: checklist tests; `note-markdown.spec.ts` toggle tests | F | Pass |
| AC3 `[[` autocomplete over all item types inserts `[[type:id]]` | editor: `offers link targets after [[ in the editor and inserts the chosen one`, `linkCompletions` tests; page: `offers link targets of all types but the note itself, and remembers their titles` | F | Pass |
| AC3 chips, revisions dialog with restore, linked items panel | view chip tests; page: chip, restore, revisions and `shows the note’s linked items in the Linked items panel` tests; service revision tests | F | Pass |
| Security: trusted preview HTML cannot be injected from the source | view: `renders no script or event handler from the source`; `note-markdown.spec.ts` escaping tests | F | Pass |
| AC4 Vitest tests cover autosave, checklist toggle, link autocomplete; `npm run check` passes | all of the above | C | Fail: only TW-2 fails; lint, api:check, e2e:typecheck pass; `npx ng build` passes on its own (pre-existing initial bundle budget warning) |
| AC5 Playwright `e2e/notes/editor.spec.ts` against the e2e stack | `Notes editor › paste an image, toggle a checklist, reload: both persisted` | E | Pass (6 passed, including this spec) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'`

Result:

Fail: 5 files, 93 tests, 92 passed, 1 failed (`stays on the first note when the route returns to it while leaving waits for an upload`, TW-2, production defect). All fix checks for TW-1 and CR-1..CR-3 pass. `npx prettier --check src/app/features/notes/editor/` passes after the new tests were formatted.

## Full test execution

Command:

`cd web && npm run check`; `cd web && npx ng build` separately; `cd web && npm run e2e -- e2e/notes/editor.spec.ts`

Result:

Fail: test:ci 88 files, 1606 tests, 1605 passed, 1 failed (TW-2 only), so `ng build` was not reached inside `check`. Run on its own, `ng build` passes (only the pre-existing initial bundle budget warning). api:check, lint (`--max-warnings=0`) and e2e:typecheck pass. The Playwright run passed: 6 passed against the docker compose e2e stack (stack healthy after 53 s, torn down afterwards).

## .taskcheck

Result:

Fail: `sh scripts/check.sh` exited 1. The TFP gate tests and server `./gradlew check` pass; the web step fails on TW-2 only (1605 passed, 1 failed). The Android step was not reached because the script stops at the first failure.

## Bugs found

### Opening a note ignores a route change back to the previous note while leaving it is still running

Finding ID:

`TW-2`

Test:

`NoteEditorPage > stays on the first note when the route returns to it while leaving waits for an upload` (`web/src/app/features/notes/editor/note-editor-page.spec.ts`)

Production source:

`web/src/app/features/notes/editor/note-editor-page.ts:124-131` (`open()`): `if (id === this.id()) return;` compares with `this.id()`, which is only changed after `await this.leave()`. While leaving note A for note B is still running, a navigation back to A finds `id() === A` and returns. The earlier `open(B)` then continues: it sets `id` to B and loads B.

Requirement:

Route `/notes/:id` shows note `:id` (SPEC.md §8.1 editor, the assumption that adds `/notes/:id`); edits and uploads go to the note shown. This adds to the CR-2 fix and the assumption "switching notes during an image upload waits for the upload".

Expected behavior:

When the route returns to note A (browser Back, or a chip/link to A) before leaving A for B has finished, the page stays on A. The pending saves and the image are still written to A, and B is not loaded (or its result is ignored).

Actual behavior:

The address is `/notes/A`, but the page shows note B ("Recipes"/"Soup"). From then on autosave, checklist toggles, uploads, revisions and the Linked items panel all act on B while the address shows A. Reloading the page then shows A. The window is as long as the leave step. Since `0001772c` that includes waiting for every pending image upload, which can take seconds for a large image. Before the fix it was only one save, but the same race existed with a slow save.

Severity:

Medium (wrong note shown and edited under the current URL in a realistic flow: paste a large image, click a chip, press Back. Edits go to a real note, just not the one in the address, so no data is lost).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: verified (whole-file coverage of all 7 task-changed executable production files). Measured with the failing TW-2 test deselected, because the provider writes no report when a test fails.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. The six `editor/*.ts` files were added entirely by this task (`git diff --stat 4f49a66e..HEAD`), so for them whole-file coverage equals changed-line coverage. In `notes.routes.ts` the task added the `:id` route entry; its lazy `loadComponent` callback (line 12) is the uncovered line.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-notes-editor`; base `4f49a66e73d13ed0f53676cab4e82a98a521ffd7`; HEAD `0001772c47600a9989a5f4733737e888a0c4cd87`; commits `20c33dcc`, `32dcfdf4`, `e51eb73b`, `5f140ee9`, `20c64a4d`, `0001772c`, supplied by the parent worker and confirmed with `git log`/`git diff --stat 4f49a66e..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `0001772c` plus the uncommitted `note-editor-page.spec.ts` (sha256 `ab0bb81f56c16600651676e507df2c9cf2623c654650bc4888f4e4c8867dab93`). The sha256 list of every `web/src/app/features/notes/editor/*.ts` and of `notes.routes.ts` (`.tmp/tw-snapshot-before.txt`, file hash `cfcb8072da6a8f12960e3828e4ffb330d1a4502c41b5fd8257126054549967b2`) was the same before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: the 7 files in the table are included. Excluded: `*.html`/`*.scss` (templates and styles; templates are exercised through the component tests), `web/package.json`/`package-lock.json` (dependency config), `docs/ARCHITECTURE.md` (docs), `reviews/*` (reports), `web/e2e/notes/editor.spec.ts` and `*.spec.ts` (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` via `@angular/build:unit-test`. Command `npm run coverage -- --filter="^(?!.*route returns to it while leaving)"` in `web/`. Whole suite, with only the TW-2 test deselected by the CLI filter (no configuration change).
- Run start/completion time and exit status: 2026-10-21T05:04:45Z start, report files written 07:05 local (05:05Z), duration 14.64 s. Result: 88 files passed, 1605 tests passed, 1 skipped (TW-2), with the coverage summary printed. The exit status was not captured separately, but the run completed without test failures and regenerated all reports.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 `0ab81476c14d2291f6606f53c7d392d416bbd6777669d753566d3fc908561519`; `web/coverage/web/coverage-final.json` sha256 `92251752118f0c0fdfb84919c44e7c5b0b1904bc4a631a0cbf3f0022146cb98e`; `web/coverage/web/lcov.info` sha256 `a5529ce3f007172325c2c4fbe467e783365189c55f4c30be36b2ed435c0cdd2f`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. All report files have the run's timestamp (07:05 local), and their per-file totals reflect the `0001772c` code (`note-editor-page.ts` now has 217 statements, up from 187 before the fix). The input snapshot was unchanged.
- Final run after latest test additions and production fixes: yes for the tests added here and the fixes in `0001772c`. TW-2 is open, so coverage must be measured again after its fix.

Values below use covered/total (percentage) from `coverage-summary.json`; zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/notes/editor/markdown-editor.ts` | 80/82 (97.56%) | 47/50 (94.00%) | 23/24 (95.83%) | 72/73 (98.63%) |
| `web/src/app/features/notes/editor/note-editor-page.ts` | 212/217 (97.70%) | 83/96 (86.46%) | 47/48 (97.92%) | 178/179 (99.44%) |
| `web/src/app/features/notes/editor/note-editor.service.ts` | 13/13 (100%) | 5/5 (100%) | 8/8 (100%) | 10/10 (100%) |
| `web/src/app/features/notes/editor/note-markdown-view.ts` | 30/30 (100%) | 24/25 (96.00%) | 6/6 (100%) | 23/23 (100%) |
| `web/src/app/features/notes/editor/note-markdown.ts` | 109/109 (100%) | 56/57 (98.25%) | 20/20 (100%) | 100/100 (100%) |
| `web/src/app/features/notes/editor/note-revisions-dialog.ts` | 78/78 (100%) | 23/27 (85.19%) | 8/8 (100%) | 53/53 (100%) |
| `web/src/app/features/notes/notes.routes.ts` | 2/3 (66.67%) | N/A (0/0) | 1/2 (50.00%) | 2/3 (66.67%) |
| Aggregate (summed counters, all 7 files; none missing) | 524/532 (98.50%) | 238/260 (91.54%) | 113/116 (97.41%) | 438/441 (99.32%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `note-editor-page.ts:125` (`id === this.id()` return) | re-navigation to the note already open; also the line behind TW-2 | TW-2 test exercises it (deselected for the coverage run); the fix is pending. |
| `note-editor-page.ts:202` (false side: no edits while in `error`) | flush keeps the error state when there is nothing to save | Low risk: the observable result (no PATCH, banner stays) needs no extra test. |
| `note-editor-page.ts:229` (false side: `useTheirs` error after a note switch) | stale "Discard my edits" error suppressed | The same pattern is covered for `keepMine` (`shows no error of a conflict action when another note was opened meanwhile`); low risk. |
| `note-editor-page.ts:299` (`schedule()` after a save when edits exist and no timer runs) | edits arriving without their own timer during a save | Minor state transition; the neighbouring branches (destroyed, timer pending) are covered. |
| `note-editor-page.ts:57,103,119,181,317,335-338,371,385-387` | viewChild arrow, route without id, stale load error, toggle that changes nothing, `??` fallbacks, INCOMING/unavailable links, stored mode `edit`, storage unavailable | Non-material fallbacks or Angular plumbing; unchanged from the previous report. |
| `markdown-editor.ts:88,168-169,188,218` | aborted completion request, unused `focus()`, override without search, null clipboard data | Defensive code or CodeMirror-internal timing; low risk. |
| `note-markdown-view.ts:63`, `note-markdown.ts:127`, `note-revisions-dialog.ts:52,87,104,122` | `??` fallbacks, ordered list tag side, revision without title, non-ApiError load failure, disabled restore | Low risk; unchanged from the previous report. |
| `notes.routes.ts:12` | lazy loading of `/notes/:id` | Executed by the Playwright e2e spec (AC5, passed), not by Vitest. |

## Unable to test

- Real OS clipboard paste and drop coordinates cannot be produced in jsdom. The drop tests stub `EditorView.posAtCoords`, and the real browser paste path is covered by the Playwright spec (AC5, passed). No follow-up needed.
- Leaving a note while it is in conflict discards the unsaved edits. This is recorded as an assumption and not tested as a defect.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. The fixes for TW-1, CR-1, CR-2 and CR-3 behave as required:
- placeholders are never saved;
- pending uploads are applied after leaving the page or switching notes, including uploads that end out of order and a save that fails on leaving;
- stale conflict actions are ignored;
- a failed save is retried both when leaving the page and before opening another note.

A new ordering defect, TW-2 (Medium), remains: if the route returns to the first note while leaving it is still running (most visibly while waiting for an upload), the page shows and edits the other note under the first note's address. Its failing test makes `npm run check` and `.taskcheck` fail. Coverage of the 7 task files is 98.50% statements and 91.54% branches. The Playwright spec passed against the e2e stack. The test file and this report are left uncommitted for the parent worker.
