# Test Report

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: task base `0dfb50ade0c3aad53e945cde3a853dc8ec8c6e4b` .. HEAD `7298fcb29b3651c33327a8d06d56a57a1cba94fe` on `main`. Task commits `4e3ca0ec` .. `7298fcb2` (12 commits, `git log --oneline 0dfb50ad..HEAD`). The parent worker supplied the base and the commit range; `git diff --stat 0dfb50ad..HEAD` matches them. Hashes in earlier reports are from before a rebase. This run re-checks the P3 CR-1 fix in `7298fcb2`: `leave()` in `note-editor-page.ts` now calls `flushAll()`, which keeps saving while edits remain and the state is not `conflict` or `error`. It also re-checks all acceptance criteria on the final code.

Production files changed by the task:
- `web/src/app/features/notes/editor/{markdown-editor,note-editor-page,note-editor.service,note-markdown,note-markdown-view,note-revisions-dialog}.ts` (+ `.html`/`.scss`)
- `web/src/app/features/notes/notes.routes.ts`
- `web/package.json` (dependency line only)

Measured state: HEAD `7298fcb2` plus the uncommitted test file below. There are no uncommitted production changes. `reviews/web-notes-editor/review.md` is modified in the working tree by the parallel code-reviewer; this report does not cover it.

Requirements:
- `tasks/web-notes-editor.md`: acceptance criteria 1-5 and the recorded assumptions:
  - 1 s debounce; a 409/412 shows the conflict banner; toggles save immediately; pending edits are flushed when leaving.
  - Leaving a note in conflict discards its edits. Switching notes during an upload waits for the upload.
  - (07:26) If the save on leaving fails during an in-app switch, the editor stays on the note with its draft and the error banner, and puts the address back.
  - (07:36) History entry duplication is accepted.
- `SPEC.md` §8.1 (editor, images, checklists, history) and §4.3 (inline links, chips, Linked items panel)
- `reviews/web-notes-editor/review.md` (P3 CR-1) and the "CR-1 fix (fourth fix cycle)" section of `fixes.md`
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `web/src/app/features/notes/editor/note-editor-page.spec.ts`: 4 tests and 1 helper (`typeDuringSaveOnLeaving`) added. Existing tests are unchanged.

## Tests added or updated

Count: 4 added (editor page). The editor specs now hold 107 tests in 5 files (103 at `7298fcb2`).

All 4 target the termination of `flushAll()` and how it interacts with errors, conflicts and uploads:
- **`stays on the note when saving the edits typed during the save on leaving fails`**
  - Setup: the first save on leaving succeeds and text is typed meanwhile. The second round (`one two`, If-Match `"2"`) fails with 503.
  - Checks: the loop stops at the error and note B is never requested. The address goes back to `/notes/A`, the draft `one two` stays, and the page shows `Not saved` with the retry banner.
  - Also checks that nothing else is sent afterwards, so there is no tight retry loop.
- **`opens the other note when saving the edits typed during the save on leaving hits a conflict`**
  - Setup: the second round gets a 409.
  - Checks: the loop stops without another PATCH, and B is opened (the edits are dropped, as the recorded assumption says). B shows `Saved` with no conflict banner, and no autosave fires later.
- **`keeps saving on leaving as long as edits arrive during each save, then opens the other note`**
  - Setup: three rounds. Each of the first two saves has text typed during it.
  - Checks: the third PATCH sends `one two three` with If-Match `"3"`. Only then is B requested, and no PATCH follows.
  - Shows that the loop runs more than 2 rounds and ends when there is nothing left to send.
- **`saves edits typed during the save of an uploaded image on leaving before opening another note`**
  - Covers the second `flushAll()` in `leave()`, after `finishUploads()`.
  - Setup: switch during an upload. The text without the placeholder is saved, then the upload ends. Text is typed while the note with the image is being saved.
  - Checks: a third PATCH with the image plus the new text (If-Match `"3"`) goes out before B is requested.
  - With the previous `flush()` here, the typed text would be lost. This was reasoned from the pre-fix code; the production code was not reverted.

## Requirement coverage

Commands:
- **F** = `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'`: 5 files, 107 tests, all passed.
- **C** = `npm --prefix web run check` (= `cd web && npm run check`): exit 0. It runs api:check, lint, e2e:typecheck, test:ci (88 files, 1620 tests passed) and `ng build`.
- **E** = `npm --prefix web run e2e -- e2e/notes/editor.spec.ts` (= `cd web && npm run e2e -- e2e/notes/editor.spec.ts`): docker compose e2e stack, chromium. The runner also runs the 5 `phase-demos` tests.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 CodeMirror editor with preview toggle/split | page: `switches between write, side by side and preview, and remembers the choice`; editor: `shows the value, reports edits and applies values from outside without reporting them` | F | Pass |
| AC1 autosave debounced, If-Match | page: `autosaves once after the debounce, with the version as If-Match`, `saves edits made while a save runs after that save`, `removes the title with null when it is cleared`; service: `saves title and body with the version as a quoted If-Match` | F | Pass |
| AC1 conflict notice (412/409), keep mine / discard | page: `on a conflict stops autosaving and keeps my version on request`, `on a conflict discards my edits on request`, `treats a 409 like a 412: conflict banner, no further autosave`, `keeps the conflict when reloading the newer version fails`, and the three "has no effect / shows no error when another note was opened meanwhile" tests | F | Pass |
| Flush when leaving (other note, other page, during a save), retry on leave | page: `saves pending edits before opening another note`, `saves pending edits when leaving the page`, `when leaving during a save, saves the edits made meanwhile after it`, `saves nothing when leaving without edits`, `retries a failed save when leaving the page`, `retries a failed save before opening another note`, `asks before closing the browser tab only while edits are unsaved`, `ignores a note that arrives after another one was opened` | F | Pass |
| P3 CR-1 fix (`flushAll`): edits typed during the save on leaving are saved; the loop ends on success, error and conflict | page: `saves edits typed during the save on leaving before opening another note`, `saves edits typed during the save on leaving when the page is left meanwhile` (fixer); new: `stays on the note when saving the edits typed during the save on leaving fails`, `opens the other note when saving the edits typed during the save on leaving hits a conflict`, `keeps saving on leaving as long as edits arrive during each save, then opens the other note`, `saves edits typed during the save of an uploaded image on leaving before opening another note` | F | Pass |
| Assumption 07:26: when the save on an in-app switch fails, stay with draft, error banner, address back | page: `stays on the note with its edits when the retry before opening another note fails too`, `opens the other note on the next try once the edits of the first one can be saved`, `stays on the note again when the save fails on a second try to open another note`, `stays on the note with its edits and the image when every save before opening another note fails`, `stays on the note when saving the edits typed during the save on leaving fails` (new) | F | Pass |
| Only the latest open continues; nothing loads after the page is destroyed | page: `does not open the other note when the page is left while leaving the first one waits`, `requests the other note only once when the route goes there, back and there again while leaving waits`, `stays on the first note when the route returns to it while leaving waits for an upload`, `opens only the last note when the route changes twice while leaving waits for an upload`, `ends on the other note when the route goes there, back and there again while leaving waits` | F | Pass |
| Upload placeholders never saved; pending uploads applied after leaving | page: `does not leave an upload placeholder in the saved note when leaving during an upload`, `does not autosave the placeholder of an image that is still uploading`, `puts an image still uploading into the note before opening another note`, `saves edits typed during the save of an uploaded image on leaving before opening another note` (new), `when leaving during an upload that then fails, saves the note without its placeholder`, `when leaving during several uploads, saves every image in place of its placeholder`, `when the save on leaving fails, saves again with the image once the upload ends`, `asks before closing the browser tab while an image is still uploading`; editor: `withoutUploadPlaceholders` and placeholder tests | F | Pass |
| AC2 paste/drop image -> upload -> inserts `![](cockpit-file:{id})`; upload failures | editor: paste, drop, several files, out-of-order results, deleted placeholder and failure tests; page: `uploads pasted images to the note`, `shows a failed image upload`, `shows a generic message for an unexpected upload failure`; service: `uploads an image as multipart form data to the note` | F | Pass |
| AC2 rendered via the authenticated file URL | view: `shows cockpit-file images through the authenticated preview URL`; service: `builds the authenticated preview URL of a file`; `note-markdown.spec.ts` | F | Pass |
| AC2 preview checklists toggle and save | page: `toggles a checklist item in the preview and saves it right away`, `does not save a checklist toggle while in conflict`; view checklist tests; `note-markdown.spec.ts` toggle tests | F | Pass |
| AC3 `[[` autocomplete over all item types inserts `[[type:id]]` | editor: `offers link targets after [[ in the editor and inserts the chosen one`, `linkCompletions` tests; page: `offers link targets of all types but the note itself, and remembers their titles` | F | Pass |
| AC3 chips, revisions dialog with restore, linked items panel | view chip tests; page chip tests, `restores a revision from the dialog`, `saves pending edits before restoring a revision, then restores on the saved version`, revision dialog tests, `shows the note’s linked items in the Linked items panel`; service revision tests | F | Pass |
| Security: trusted preview HTML cannot be injected from the source | view: `renders no script or event handler from the source`; `note-markdown.spec.ts` escaping tests | F | Pass |
| AC4 Vitest tests cover autosave, checklist toggle, link autocomplete; `npm run check` passes | all of the above | C | Pass. The build shows only the existing initial bundle budget warning (651.19 kB > 500 kB). |
| AC5 Playwright `e2e/notes/editor.spec.ts` against the e2e stack: paste an image, toggle a checklist, reload -> persisted | `Notes editor › paste an image, toggle a checklist, reload: both persisted` | E | Pass: 6 passed (this spec in `chromium`). The stack was healthy after 57 s and was torn down afterwards. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'`

Result:

Pass: 5 files, 107 tests. Before that, `note-editor-page.spec.ts` alone passed with 61 tests. `npx prettier --write` left the spec file unchanged, and lint in `npm run check` passes.

## Full test execution

Commands:

- `npm --prefix web run check`
- `npm --prefix web run e2e -- e2e/notes/editor.spec.ts`

Result:

Pass.
- `npm run check` exited 0, with 88 files and 1620 tests passed. `ng build` passes with only the existing budget warning.
- Playwright passed 6 tests against the docker compose e2e stack (the `chromium` editor spec plus `phase-demos`), run 05:42:03Z-05:43:19Z.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (05:40:59Z-05:42:00Z) ended with `== all checks passed`. It ran the TFP gate tests, server ktlint + tests, and web eslint + tests + build. The script itself skipped Android (`COCKPIT_NO_ANDROID=1` on this machine).

## Bugs found

None. `flushAll()` behaves as `fixes.md` describes:
- It saves every round of edits typed during the saves on leaving.
- It stops at a failed save (stays on the note) and at a conflict (opens the other note, per the assumption).
- It runs after the uploads finish too.
- It leaves no autosave timer behind.

## Coverage

Status: verified. Whole-file coverage of all 7 task-changed executable production files on the final code (HEAD `7298fcb2` plus the new tests). The whole suite ran, with no tests deselected.

Metric: whole-file coverage of task-changed production files.

Changed-line coverage: not measured separately.
- The six `editor/*.ts` files were added entirely by this task, so for them whole-file coverage equals changed-line coverage.
- In `notes.routes.ts`, the task added the `:id` route entry. Its lazy `loadComponent` callback (line 12) is the uncovered line.
- The `flushAll()`/`leave()` lines of the CR-1 fix (`note-editor-page.ts:152-166`) have no uncovered statement or branch in the report.

Evidence:
- **Task and scope:** task `web-notes-editor`. Immutable base `0dfb50ade0c3aad53e945cde3a853dc8ec8c6e4b`, measured HEAD `7298fcb29b3651c33327a8d06d56a57a1cba94fe`. The parent supplied the base and the commit range; `git diff --stat 0dfb50ad..HEAD` confirms the file list.
- **Source/test snapshot:** HEAD `7298fcb2` plus the uncommitted `note-editor-page.spec.ts` (sha256 `4974a85b535d733061224ff0884cf09798939a47f8ef70fc99b96f549f99214f`).
  - `.tmp/tw5-snapshot.txt` lists the sha256 of every `web/src/app/features/notes/editor/*.ts`, `note-editor-page.html`, `notes.routes.ts` and `web/e2e/notes/editor.spec.ts`. That file's own hash is `dbce08a7bc2e41f835f6be653ab65104ebe9600e49875fbc1142b8e3f244c2f2`.
  - It was taken before `npm run check`. `shasum -a 256 -c` after the coverage run, `.taskcheck` and the e2e run reported 0 mismatches.
- **Included and excluded files:** the 7 files in the table are included. There are no renames or deletions, and no scoped file is missing from the report. Excluded:
  - `*.html`/`*.scss`: templates and styles, exercised by the component tests;
  - `web/package.json`/`package-lock.json`: dependency config;
  - `docs/ARCHITECTURE.md`: docs;
  - `reviews/*`: reports;
  - `web/e2e/notes/editor.spec.ts` and `*.spec.ts`: tests.
- **Provider, command and selection:** Vitest 5.0.5 with `@vitest/coverage-v8` via `@angular/build:unit-test`. Command: `npm --prefix web run coverage` (= `cd web && npm run coverage`), working directory the clone root, whole suite.
- **Run time and exit status:** final run 05:40:25Z-05:40:43Z, exit 0. A run just before it (05:40:04Z-05:40:22Z) reported 88 files and 1620 tests passed.
- **Report paths and sha256 hashes (final run):**
  - `web/coverage/web/coverage-summary.json`: `2da0131770bdd5e22feef6d83b8c39e082b5f0499f708651b4a416f6fe15fbd4`
  - `web/coverage/web/coverage-final.json`: `6565e68f61ff8bd8e38de038fd30ac5b8c7faf9a90196c4b8bd3077a54cb244b`
  - `web/coverage/web/lcov.info`: `4fdb6caf02930113f01a42e0c87ac507506ddddbcd56f2473fef99ab2efa7028`
  - Per-file counters were read with `.tmp/tw3-cov.mjs`.
- **Fresh outputs, unchanged inputs:** yes.
  - All report files carry the run's time (07:40 local).
  - The totals reflect the `flushAll` code: `note-editor-page.ts` has 227 statements / 107 branches / 49 functions, up from 225 / 104 / 48 at `966e827e`.
  - The input snapshot did not change.
- **Final run after the latest test additions and production fixes:** yes.

Values below use covered/total (percentage) from `coverage-summary.json`. Zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/notes/editor/markdown-editor.ts` | 80/82 (97.56%) | 47/50 (94.00%) | 23/24 (95.83%) | 72/73 (98.63%) |
| `web/src/app/features/notes/editor/note-editor-page.ts` | 223/227 (98.23%) | 95/107 (88.78%) | 48/49 (97.95%) | 186/187 (99.46%) |
| `web/src/app/features/notes/editor/note-editor.service.ts` | 13/13 (100%) | 5/5 (100%) | 8/8 (100%) | 10/10 (100%) |
| `web/src/app/features/notes/editor/note-markdown-view.ts` | 30/30 (100%) | 24/25 (96.00%) | 6/6 (100%) | 23/23 (100%) |
| `web/src/app/features/notes/editor/note-markdown.ts` | 109/109 (100%) | 56/57 (98.24%) | 20/20 (100%) | 100/100 (100%) |
| `web/src/app/features/notes/editor/note-revisions-dialog.ts` | 78/78 (100%) | 23/27 (85.18%) | 8/8 (100%) | 53/53 (100%) |
| `web/src/app/features/notes/notes.routes.ts` | 2/3 (66.66%) | N/A (0/0) | 1/2 (50.00%) | 2/3 (66.66%) |
| Aggregate (summed counters, all 7 files; none missing or excluded) | 535/542 (98.71%) | 250/271 (92.25%) | 114/117 (97.44%) | 446/449 (99.33%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `note-editor-page.ts:152-166` (`leave()`, `flushAll()`) | P3 CR-1: edits typed during saves on leaving are saved; the loop ends | Fully covered by the fixer tests and the 4 new tests (success, error, conflict, 3 rounds, after uploads). |
| `note-editor-page.ts:205` | toggle that changes nothing | Low risk: it returns without saving. |
| `note-editor-page.ts:226` (false side) | flush with nothing to save keeps `error` | Low risk: no PATCH, and the banner stays. |
| `note-editor-page.ts:253` (false side) | `useTheirs` error after a note switch | The same pattern is covered for `keepMine`. Low risk. |
| `note-editor-page.ts:323` (`schedule()` after a save when edits exist and no timer runs) | edits without their own timer during a save | Minor transition: leaving goes through `flushAll` instead, and neighbouring branches are covered. |
| `note-editor-page.ts:58,106,122,341,359-362,395,409-411` | viewChild arrow, route without id, stale load error, `??` fallbacks, INCOMING/unavailable links, stored mode `edit`, storage unavailable | Non-material fallbacks or Angular plumbing; the same gaps as the previous report, with lines shifted by the fix. |
| `markdown-editor.ts:88,168-169,188,218` | aborted completion request, unused `focus()`, override without search, null clipboard data | Defensive code or CodeMirror-internal timing. Low risk. |
| `note-markdown-view.ts:63`, `note-markdown.ts:127`, `note-revisions-dialog.ts:52,87,104,122` | `??` fallbacks, ordered list tag side, revision without title, non-ApiError load failure, disabled restore | Low risk; unchanged from the previous report. |
| `notes.routes.ts:12` | lazy loading of `/notes/:id` | Not run by Vitest. The Playwright spec runs it (AC5, passed). |

## Unable to test

- **Termination depends on the server echoing the draft.** `flushAll()` ends only when the saved note equals the draft. If a server normalized the title or body on save, leaving would re-send the same draft without a debounce until the save failed. The pre-fix code had a 1 s autosave loop in that case.
  - The current server (`NoteService.update`) stores title and body as sent, and the client already trims the title, so this cannot happen today.
  - No test was added, because that behavior is not required. It is not a defect; it is noted for the reviewer.
- **Real clipboard and drop events.** jsdom cannot produce a real OS clipboard paste or real drop coordinates. The drop tests stub `EditorView.posAtCoords`, and the Playwright spec covers the real browser paste path (AC5, passed). No follow-up needed.
- **History entry replacement.** `RouterTestingHarness` cannot show that `replaceUrl` replaces the history entry; the tests only assert the resulting `Router.url`. The duplicate history entry is an accepted assumption (07:36).
- **Regression value of the new tests.** For the 3-round test and the after-upload test, this was reasoned from the pre-fix code (`leave()` with single `flush()` calls) and not shown by reverting production code.

## Summary

Tests added and all validation passed.

- **`flushAll()` (P3 CR-1 fix):** verified for success over several rounds, a failure (stays on the note with draft and retry, no tight loop), a conflict (opens the other note, edits dropped per assumption) and the round after uploads finish.
- **Checks:** all pass:
  - focused specs (107 tests);
  - `npm run check` (1620 tests);
  - `.taskcheck`;
  - the Playwright spec against the docker e2e stack.
- **Coverage** of the 7 task files on the final code: 98.71% statements and 92.25% branches (summed counters).
- **Bugs:** no production defects found.

The test file and this report are left uncommitted for the parent worker.
