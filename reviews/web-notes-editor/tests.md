# Test Report

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: task base `7ce21cc072525cedb46378ee80989f449382cd1a` .. HEAD `487477689fa70a68488a7794e53978e0ef946afe` on `main`. Task commits: `e226f13b`, `929434c3`, `b3238500`, `e5d93fa6`, `8b8e7b7f`, `b29cfa12`, `40f66bdc`, `48747768`. The parent worker supplied them, and `git log 7ce21cc0..HEAD` confirms them. The hashes in earlier reports are from before a rebase. This run re-checks the TW-2 fix in `48747768` (`note-editor-page.ts`, +6 lines).

Production files changed by the task:
- `web/src/app/features/notes/editor/{markdown-editor,note-editor-page,note-editor.service,note-markdown,note-markdown-view,note-revisions-dialog}.ts` (+ `.html`/`.scss`)
- `web/src/app/features/notes/notes.routes.ts`
- `web/package.json` (dependency line only)

Measured state: HEAD plus the uncommitted test file below. There are no uncommitted production changes. Another helper modified `reviews/web-notes-editor/review.md` in the working tree. It is not part of this report.

Requirements:
- `tasks/web-notes-editor.md`: acceptance criteria and recorded assumptions:
  - 1 s debounce;
  - 409/412 conflict banner;
  - toggles save immediately;
  - pending edits flushed when leaving;
  - leaving in conflict discards edits;
  - switching notes during an upload waits for it.
- `SPEC.md` §8.1 (editor, images, checklists, history) and §4.3 (inline links, chips, Linked items panel)
- `reviews/web-notes-editor/fixes.md`: TW-2 section, the fix behavior to re-check
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `web/src/app/features/notes/editor/note-editor-page.spec.ts`: 2 tests added after the TW-2 test. The existing tests are unchanged.

## Tests added or updated

Count: 2 added (editor page). The editor specs now hold 95 tests in 5 files (93 at `40f66bdc`).

Added:
- `opens only the last note when the route changes twice while leaving waits for an upload`
  - Scenario: A→B→C quick switching while leaving A waits for an image upload.
  - Checks: B is never requested and only C is loaded. The image is still saved into A exactly once, with If-Match `"2"`. The page shows C under `/notes/C`, and the next autosave goes to C with C's version.
- `ends on the other note when the route goes there, back and there again while leaving waits`
  - Scenario: A→B→A→B while leaving waits.
  - Checks: the page ends on B and A is not reloaded. The image is saved into A, and autosave then targets B with B's version.

Regression value: without the TW-2 fix, `open(B)` would continue after its `leave()` and request B. The first new test then fails at `expectNone` for B (reasoned from the pre-fix code at `40f66bdc`; I did not revert production code to demonstrate it). The existing TW-2 test (`stays on the first note when the route returns to it while leaving waits for an upload`) now passes.

## Requirement coverage

Commands:
- F = `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'`: 5 files, 95 tests, all passed.
- C = `cd web && npm run check`: api:check, lint, e2e:typecheck and test:ci pass (88 files, 1608 tests, all passed), and `ng build` passes.
- E = `cd web && npm run e2e -- e2e/notes/editor.spec.ts`: docker compose e2e stack, chromium. The runner also runs the 5 `phase-demos` tests.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 CodeMirror editor with preview toggle/split | page: `switches between write, side by side and preview, and remembers the choice`; editor: `shows the value, reports edits and applies values from outside without reporting them` | F | Pass |
| AC1 autosave debounced, If-Match | page: `autosaves once after the debounce, with the version as If-Match`, `saves edits made while a save runs after that save`, `removes the title with null when it is cleared`; service: `saves title and body with the version as a quoted If-Match` | F | Pass |
| AC1 conflict notice (412/409), keep mine / discard | page: `on a conflict stops autosaving and keeps my version on request`, `on a conflict discards my edits on request`, `treats a 409 like a 412: conflict banner, no further autosave`, `keeps the conflict when reloading the newer version fails` | F | Pass |
| CR-2: stale conflict actions ignored after a note switch | page: `“Save my version” has no effect when another note was opened meanwhile`, `“Discard my edits” has no effect when another note was opened meanwhile`, `shows no error of a conflict action when another note was opened meanwhile` | F | Pass |
| CR-3: retry on leave after a failed save | page: `retries a failed save when leaving the page`, `retries a failed save before opening another note`, `shows other save errors with a retry` | F | Pass |
| Flush when leaving (other note, other page, during a save) | page: `saves pending edits before opening another note`, `saves pending edits when leaving the page`, `when leaving during a save, saves the edits made meanwhile after it`, `saves nothing when leaving without edits`, `asks before closing the browser tab only while edits are unsaved`, `ignores a note that arrives after another one was opened` | F | Pass |
| TW-1/CR-1: placeholders never saved | page: `does not leave an upload placeholder in the saved note when leaving during an upload`, `does not autosave the placeholder of an image that is still uploading`, `when leaving during an upload that then fails, saves the note without its placeholder`, `when leaving during several uploads, saves every image in place of its placeholder`; editor: `withoutUploadPlaceholders` test, `passes the placeholder to the upload, and leaves uploads that end after it is gone alone` | F | Pass |
| TW-1/CR-1: pending uploads applied after leaving or switching notes | page: `puts an image still uploading into the note before opening another note`, `when the save on leaving fails, saves again with the image once the upload ends`, `asks before closing the browser tab while an image is still uploading` | F | Pass |
| TW-2: the latest route wins while leaving waits (A→B→A, A→B→C, A→B→A→B) | page: `stays on the first note when the route returns to it while leaving waits for an upload`, `opens only the last note when the route changes twice while leaving waits for an upload` (new), `ends on the other note when the route goes there, back and there again while leaving waits` (new) | F | Pass |
| AC2 paste/drop image -> upload -> inserts Markdown; upload failures | editor: paste, drop, several files, out-of-order results, deleted placeholder and failure tests; page: `uploads pasted images to the note`, `shows a failed image upload`, `shows a generic message for an unexpected upload failure`; service: `uploads an image as multipart form data to the note` | F | Pass |
| AC2 rendered via the authenticated file URL | view: `shows cockpit-file images through the authenticated preview URL`; service: `builds the authenticated preview URL of a file`; `note-markdown.spec.ts` | F | Pass |
| AC2 preview checklists toggle and save | page: `toggles a checklist item in the preview and saves it right away`, `does not save a checklist toggle while in conflict`; view: checklist tests; `note-markdown.spec.ts` toggle tests | F | Pass |
| AC3 `[[` autocomplete over all item types inserts `[[type:id]]` | editor: `offers link targets after [[ in the editor and inserts the chosen one`, `linkCompletions` tests; page: `offers link targets of all types but the note itself, and remembers their titles` | F | Pass |
| AC3 chips, revisions dialog with restore, linked items panel | view chip tests; page: chip, restore and revisions tests, `shows the note’s linked items in the Linked items panel`; service revision tests | F | Pass |
| Security: trusted preview HTML cannot be injected from the source | view: `renders no script or event handler from the source`; `note-markdown.spec.ts` escaping tests | F | Pass |
| AC4 Vitest tests cover autosave, checklist toggle, link autocomplete; `npm run check` passes | all of the above | C | Pass. The build shows only the existing initial bundle budget warning. |
| AC5 Playwright `e2e/notes/editor.spec.ts` against the e2e stack | `Notes editor › paste an image, toggle a checklist, reload: both persisted` | E | Pass: 6 passed, this spec included. The stack was healthy after 57 s and torn down afterwards. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'`

Result:

Pass: 5 files, 95 tests, all passed. `npx prettier --check src/app/features/notes/editor/` passes.

## Full test execution

Command:

- `cd web && npm run check`
- `cd web && npm run e2e -- e2e/notes/editor.spec.ts`

Result:

Pass.
- `npm run check`:
  - api:check: generated client up to date;
  - lint (`--max-warnings=0`): all files pass;
  - e2e:typecheck: passes;
  - test:ci: 88 files, 1608 tests passed;
  - `ng build`: passes, with only the existing initial bundle budget warning (651.19 kB > 500 kB).
- Playwright: 6 passed against the docker compose e2e stack (chromium project plus phase-demos).

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ended with `== all checks passed`:
- TFP gate tests passed.
- Server `./gradlew check` passed.
- Web check passed.
- Android was skipped by the script (`COCKPIT_NO_ANDROID=1`, the script's own setting on this machine).

## Bugs found

None in this run. TW-2 from the previous run is fixed: its test and the two new ordering tests pass.

## Coverage

Status: verified. This is whole-file coverage of all 7 task-changed executable production files on the final code (HEAD `48747768` plus the new tests), with no tests deselected.

Metric: whole-file coverage of task-changed production files.

Changed-line coverage: not measured separately.
- The six `editor/*.ts` files were added entirely by this task, so for them whole-file coverage equals changed-line coverage.
- In `notes.routes.ts` the task added the `:id` route entry. Its lazy `loadComponent` callback (line 12) is the uncovered line.
- The TW-2 fix lines (`note-editor-page.ts:127-133`) are fully covered: none of their statements or branches appear in the uncovered list.

Evidence:
- **Task and scope:** task `web-notes-editor`. Immutable base `7ce21cc072525cedb46378ee80989f449382cd1a`, measured HEAD `487477689fa70a68488a7794e53978e0ef946afe`. The parent worker supplied the base and the commits; `git log`/`git diff --stat 7ce21cc0..HEAD` confirms them.
- **Snapshot:** HEAD `48747768` plus the uncommitted `note-editor-page.spec.ts` (sha256 `de4efc6a28723bcbb8f8cde0291b85cc5156abc3051fe53bdd2686fc3cdec9e2`). The sha256 list covers every `web/src/app/features/notes/editor/*.ts`, `note-editor-page.html` and `notes.routes.ts`. It is stored in `.tmp/tw3-snapshot-before.txt` (file hash `968d2b62296444c43e71413daa5c6a54c1ba078e5d1bebb8aa49db207681c3ba`) and was verified unchanged after the coverage run with `shasum -a 256 -c`.
- **Included and excluded files:** the 7 files in the table are included. No renames or deletions. Excluded:
  - `*.html`/`*.scss`: templates and styles; the component tests exercise the templates;
  - `web/package.json` and `package-lock.json`: dependency config;
  - `docs/ARCHITECTURE.md`: docs;
  - `reviews/*`: reports;
  - `web/e2e/notes/editor.spec.ts` and `*.spec.ts`: tests.
- **Provider, command and selection:** Vitest 5.0.5 with `@vitest/coverage-v8` via `@angular/build:unit-test`. Command `npm run coverage`, run in `web/`, on the whole suite without a filter.
- **Run time and exit status:**
  - Start 2026-10-21T05:16:26Z, end 05:16:45Z (test duration 14.29 s).
  - All 88 files and 1608 tests passed, and the coverage summary was printed.
  - The `echo` printed the pipe's `tail` exit status (0), not the coverage command's. The run printed no failures and rewrote all reports.
- **Report paths and sha256 hashes:**
  - `web/coverage/web/coverage-summary.json`: `5c8f77c397f4cc18e12f42a6f117891bc155a18bb9a5453010c47413227debe5`
  - `web/coverage/web/coverage-final.json`: `b8624cb7897e26a7ecea80f520ca0ddd60f98a18aaedd4000ecfdbb75cceeba9`
  - `web/coverage/web/lcov.info`: `b0ea23ea1ef29a10a63e0a9a79b7de1a562ca7df35f27386315884075f1a5855`
  - Per-file counters were read with `.tmp/tw3-cov.mjs`.
- **Fresh outputs, unchanged inputs:** yes.
  - All report files carry the run's timestamp (07:16 local).
  - The totals reflect the fixed code: `note-editor-page.ts` has 221 statements and 98 branches, up from 217 and 96 at `40f66bdc`.
  - The input snapshot did not change during the run.
- **Final run after the latest test additions and production fixes:** yes.

Values below use covered/total (percentage) from `coverage-summary.json`. Zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/notes/editor/markdown-editor.ts` | 80/82 (97.56%) | 47/50 (94.00%) | 23/24 (95.83%) | 72/73 (98.63%) |
| `web/src/app/features/notes/editor/note-editor-page.ts` | 217/221 (98.19%) | 86/98 (87.76%) | 47/48 (97.92%) | 181/182 (99.45%) |
| `web/src/app/features/notes/editor/note-editor.service.ts` | 13/13 (100%) | 5/5 (100%) | 8/8 (100%) | 10/10 (100%) |
| `web/src/app/features/notes/editor/note-markdown-view.ts` | 30/30 (100%) | 24/25 (96.00%) | 6/6 (100%) | 23/23 (100%) |
| `web/src/app/features/notes/editor/note-markdown.ts` | 109/109 (100%) | 56/57 (98.25%) | 20/20 (100%) | 100/100 (100%) |
| `web/src/app/features/notes/editor/note-revisions-dialog.ts` | 78/78 (100%) | 23/27 (85.19%) | 8/8 (100%) | 53/53 (100%) |
| `web/src/app/features/notes/notes.routes.ts` | 2/3 (66.67%) | N/A (0/0) | 1/2 (50.00%) | 2/3 (66.67%) |
| Aggregate (summed counters, all 7 files; none missing) | 529/536 (98.69%) | 241/262 (91.98%) | 113/116 (97.41%) | 441/444 (99.32%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `note-editor-page.ts:127-133` (TW-2 fix: `requested`, early return, `id !== this.requested`) | the latest route wins while leaving waits | Fully covered by the TW-2 test and the 2 new tests. |
| `note-editor-page.ts:208` (false side: no edits while in `error`) | flush keeps the error state when there is nothing to save | Low risk. The observable result (no PATCH, the banner stays) needs no extra test. |
| `note-editor-page.ts:235` (false side: `useTheirs` error after a note switch) | a stale "Discard my edits" error is suppressed | The same pattern is covered for `keepMine` (`shows no error of a conflict action when another note was opened meanwhile`). Low risk. |
| `note-editor-page.ts:305` (`schedule()` after a save when edits exist and no timer runs) | edits arriving without their own timer during a save | Minor state transition. The neighbouring branches (destroyed, timer pending) are covered. |
| `note-editor-page.ts:57,105,121,187,323,341-344,377,391-393` | viewChild arrow, route without id, stale load error, toggle that changes nothing, `??` fallbacks, INCOMING/unavailable links, stored mode `edit`, storage unavailable | Non-material fallbacks or Angular plumbing. These are the same gaps as in the previous report, at lines shifted by the 6-line fix. |
| `markdown-editor.ts:88,168-169,188,218` | aborted completion request, unused `focus()`, override without search, null clipboard data | Defensive code or CodeMirror-internal timing. Low risk. |
| `note-markdown-view.ts:63`, `note-markdown.ts:127`, `note-revisions-dialog.ts:52,87,104,122` | `??` fallbacks, ordered list tag side, revision without title, non-ApiError load failure, disabled restore | Low risk. Unchanged from the previous report. |
| `notes.routes.ts:12` | lazy loading of `/notes/:id` | Not run by Vitest. The Playwright e2e spec runs it (AC5, passed). |

## Unable to test

- jsdom cannot produce a real OS clipboard paste or real drop coordinates. The drop tests stub `EditorView.posAtCoords`, and the Playwright spec covers the real browser paste path (AC5, passed). No follow-up needed.
- Leaving a note while it is in conflict discards the unsaved edits. This is a recorded assumption, so it is not tested as a defect.
- I did not demonstrate the regression value of the new ordering tests by reverting the fix, because production code must not be modified. The failure mode without the fix was reasoned from the diff of `48747768`.

## Summary

Tests added and all validation passed.

- **TW-2 fix:** works as required. Once leaving the first note finishes, the latest route wins:
  - A→B→A stays on A;
  - A→B→C loads only C, and B is never requested;
  - A→B→A→B ends on B.

  In every case the pending image is still saved into the first note.
- **Earlier fixes:** TW-1 and CR-1..CR-3 still pass.
- **Checks:** `npm run check`, `.taskcheck` and the Playwright spec against the e2e stack pass.
- **Coverage** of the 7 task files on the final code is 98.69% statements and 91.98% branches.
- **Bugs:** no production defects found.

The test file and this report are left uncommitted for the parent worker.
