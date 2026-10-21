# Test Report

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: task base `993bc56e05989364b984f3fc983c70db1594a500` .. HEAD `966e827ebe6104016e5858ca55656aa67079588c` on `main`. Task commits: `05c1d4e9`, `c60013f8`, `c32b8c7a`, `e6c00d41`, `4ffce46f`, `48e278b6`, `4f8d270c`, `161c5b5b`, `9a3d8b0b`, `966e827e`. The parent worker supplied the base and the commits; `git diff --stat 993bc56e..HEAD` matches them. Hashes in earlier reports are from before a rebase. This run re-checks the CR-1/CR-2 fix in `966e827e` (`note-editor-page.ts`: `openSeq` counter, `destroyed` check, stay on the note whose save fails) and all acceptance criteria on the final code.

Production files changed by the task:
- `web/src/app/features/notes/editor/{markdown-editor,note-editor-page,note-editor.service,note-markdown,note-markdown-view,note-revisions-dialog}.ts` (+ `.html`/`.scss`)
- `web/src/app/features/notes/notes.routes.ts`
- `web/package.json` (dependency line only)

Measured state: HEAD `966e827e` plus the uncommitted test file below. There are no uncommitted production changes. `reviews/web-notes-editor/review.md` is modified in the working tree by the parallel code-reviewer; it is not part of this report.

Requirements:
- `tasks/web-notes-editor.md`: acceptance criteria 1-5 and the recorded assumptions:
  - 1 s debounce, 409/412 conflict banner, toggles save immediately, pending edits flushed when leaving;
  - leaving in conflict discards edits; switching notes during an upload waits for it;
  - (07:26) when the save on leaving fails during an in-app note switch, stay on the note with draft and error banner and put the address back.
- `SPEC.md` §8.1 (editor, images, checklists, history) and §4.3 (inline links, chips, Linked items panel)
- `reviews/web-notes-editor/review.md` (CR-1, CR-2) and the latest section of `fixes.md`
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `web/src/app/features/notes/editor/note-editor-page.spec.ts`: 4 tests and 1 helper (`stayAfterFailedSwitch`) added. Existing tests are unchanged.

## Tests added or updated

Count: 4 added (editor page). The editor specs now hold 101 tests in 5 files (97 at `966e827e`).

Added:
- `opens the other note on the next try once the edits of the first one can be saved`
  - After the CR-1 "stay" (two failed saves), the chip to note B is used again and the save works: B is requested only after the PATCH of A (`mine`, If-Match `"1"`), the address becomes `/notes/B`, B is shown as `Saved` with no banner. Checks that staying does not leave the page stuck.
- `stays on the note again when the save fails on a second try to open another note`
  - A second switch attempt with another failure (500) stays on A again: B never requested, address `/notes/A`, draft `mine`, `Not saved`; no autosave fires on its own afterwards.
- `stays on the note with its edits and the image when every save before opening another note fails`
  - Switch while an image uploads: the first save fails, leaving waits for the upload, the second save (with the image Markdown in place of the placeholder) fails too. The page stays on A with the image in the draft, `Not saved` and the retry banner; address `/notes/A`.
- `requests the other note only once when the route goes there, back and there again while leaving waits`
  - CR-2 sequence counter: A→B→A→B while leaving waits for an upload sends exactly one GET of B. With the previous id-based `requested` check both pending `open(B)` calls would continue and send two GETs (reasoned from the pre-fix code at `161c5b5b`; production code was not reverted). The existing A→B→A→B test used `http.match` and did not catch a double GET.

## Requirement coverage

Commands:
- F = `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'`: 5 files, 101 tests, all passed.
- C = `npm --prefix web run check` (= `cd web && npm run check`): exit 0. api:check, lint, e2e:typecheck, test:ci (88 files, 1614 tests passed) and `ng build` pass.
- E = `npm --prefix web run e2e -- e2e/notes/editor.spec.ts` (= `cd web && npm run e2e -- e2e/notes/editor.spec.ts`): docker compose e2e stack (Docker 27.5.1), chromium; the runner also runs the 5 `phase-demos` tests. Exit 0.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 CodeMirror editor with preview toggle/split | page: `switches between write, side by side and preview, and remembers the choice`; editor: `shows the value, reports edits and applies values from outside without reporting them` | F | Pass |
| AC1 autosave debounced, If-Match | page: `autosaves once after the debounce, with the version as If-Match`, `saves edits made while a save runs after that save`, `removes the title with null when it is cleared`; service: `saves title and body with the version as a quoted If-Match` | F | Pass |
| AC1 conflict notice (412/409), keep mine / discard | page: `on a conflict stops autosaving and keeps my version on request`, `on a conflict discards my edits on request`, `treats a 409 like a 412: conflict banner, no further autosave`, `keeps the conflict when reloading the newer version fails` | F | Pass |
| Stale conflict actions ignored after a note switch | page: `“Save my version” has no effect when another note was opened meanwhile`, `“Discard my edits” has no effect when another note was opened meanwhile`, `shows no error of a conflict action when another note was opened meanwhile` | F | Pass |
| Flush when leaving (other note, other page, during a save), retry on leave | page: `saves pending edits before opening another note`, `saves pending edits when leaving the page`, `when leaving during a save, saves the edits made meanwhile after it`, `saves nothing when leaving without edits`, `retries a failed save when leaving the page`, `retries a failed save before opening another note`, `asks before closing the browser tab only while edits are unsaved`, `ignores a note that arrives after another one was opened` | F | Pass |
| CR-1 (assumption 07:26): save on an in-app switch fails -> stay with draft, error banner, address back | page: `stays on the note with its edits when the retry before opening another note fails too` (fixer), `opens the other note on the next try once the edits of the first one can be saved` (new), `stays on the note again when the save fails on a second try to open another note` (new), `stays on the note with its edits and the image when every save before opening another note fails` (new) | F | Pass |
| CR-2: only the latest open continues; nothing loads after the page is destroyed | page: `does not open the other note when the page is left while leaving the first one waits` (fixer), `requests the other note only once when the route goes there, back and there again while leaving waits` (new), `stays on the first note when the route returns to it while leaving waits for an upload`, `opens only the last note when the route changes twice while leaving waits for an upload`, `ends on the other note when the route goes there, back and there again while leaving waits` | F | Pass |
| Upload placeholders never saved; pending uploads applied after leaving | page: `does not leave an upload placeholder in the saved note when leaving during an upload`, `does not autosave the placeholder of an image that is still uploading`, `when leaving during an upload that then fails, saves the note without its placeholder`, `when leaving during several uploads, saves every image in place of its placeholder`, `puts an image still uploading into the note before opening another note`, `when the save on leaving fails, saves again with the image once the upload ends`, `asks before closing the browser tab while an image is still uploading`; editor: `withoutUploadPlaceholders` and placeholder upload tests | F | Pass |
| AC2 paste/drop image -> upload -> inserts `![](cockpit-file:{id})`; upload failures | editor: paste, drop, several files, out-of-order results, deleted placeholder and failure tests; page: `uploads pasted images to the note`, `shows a failed image upload`, `shows a generic message for an unexpected upload failure`; service: `uploads an image as multipart form data to the note` | F | Pass |
| AC2 rendered via the authenticated file URL | view: `shows cockpit-file images through the authenticated preview URL`; service: `builds the authenticated preview URL of a file`; `note-markdown.spec.ts` | F | Pass |
| AC2 preview checklists toggle and save | page: `toggles a checklist item in the preview and saves it right away`, `does not save a checklist toggle while in conflict`; view checklist tests; `note-markdown.spec.ts` toggle tests | F | Pass |
| AC3 `[[` autocomplete over all item types inserts `[[type:id]]` | editor: `offers link targets after [[ in the editor and inserts the chosen one`, `linkCompletions` tests; page: `offers link targets of all types but the note itself, and remembers their titles` | F | Pass |
| AC3 chips, revisions dialog with restore, linked items panel | view chip tests; page: chip tests, `restores a revision from the dialog`, revision dialog tests, `shows the note’s linked items in the Linked items panel`; service revision tests | F | Pass |
| Security: trusted preview HTML cannot be injected from the source | view: `renders no script or event handler from the source`; `note-markdown.spec.ts` escaping tests | F | Pass |
| AC4 Vitest tests cover autosave, checklist toggle, link autocomplete; `npm run check` passes | all of the above | C | Pass. The build shows only the existing initial bundle budget warning (651.19 kB > 500 kB). |
| AC5 Playwright `e2e/notes/editor.spec.ts` against the e2e stack: paste an image, toggle a checklist, reload -> persisted | `Notes editor › paste an image, toggle a checklist, reload: both persisted` | E | Pass: 6 passed (this spec in `chromium`), stack healthy after 46 s, torn down afterwards. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'`

Result:

Pass: 5 files, 101 tests (97 before the new tests, all passing at HEAD as well). The spec file was formatted with `npx prettier --write`; lint in `npm run check` passes.

## Full test execution

Command:

- `npm --prefix web run check`
- `npm --prefix web run e2e -- e2e/notes/editor.spec.ts`

Result:

Pass.
- `npm run check` (exit 0): generated client up to date; all files pass linting; e2e typecheck passes; test:ci 88 files, 1614 tests passed; `ng build` passes with the existing budget warning only.
- Playwright (exit 0): 6 passed against the docker compose e2e stack (`chromium` editor spec plus `phase-demos`).

## .taskcheck

Result:

Pass. `sh scripts/check.sh` exit 0, ending with `== all checks passed`: TFP gate tests, server ktlint + tests, web eslint + tests (1614 passed) + build. Android was skipped by the script itself (`COCKPIT_NO_ANDROID=1` on this machine).

## Bugs found

None. The CR-1 and CR-2 fixes behave as the recorded assumption and the review describe, including the extra cases added here.

## Coverage

Status: verified. Whole-file coverage of all 7 task-changed executable production files on the final code (HEAD `966e827e` plus the new tests), whole suite, no tests deselected.

Metric: whole-file coverage of task-changed production files.

Changed-line coverage: not measured separately.
- The six `editor/*.ts` files were added entirely by this task, so for them whole-file coverage equals changed-line coverage.
- In `notes.routes.ts` the task added the `:id` route entry; its lazy `loadComponent` callback (line 12) is the uncovered line.
- The CR-1/CR-2 fix lines (`note-editor-page.ts:130-146`) have no uncovered statement or branch in the report.

Evidence:
- **Task and scope:** task `web-notes-editor`, immutable base `993bc56e05989364b984f3fc983c70db1594a500`, measured HEAD `966e827ebe6104016e5858ca55656aa67079588c`. Base and task commits supplied by the parent; `git diff --stat 993bc56e..HEAD` confirms the file list.
- **Snapshot:** HEAD `966e827e` plus the uncommitted `note-editor-page.spec.ts` (sha256 `e96147f5e7005340c54b6a6249b6f88abcd74f0f6c38db1ae0fbd0763238adf4`). The sha256 list of every `web/src/app/features/notes/editor/*.ts`, `note-editor-page.html`, `notes.routes.ts` and `web/e2e/notes/editor.spec.ts` is in `.tmp/tw4-snapshot.txt` (file hash `3c86c75308dc749ad6cc7168c10e2514ac3c7b010004436ad09032eaddabbd77`); `shasum -a 256 -c` after the coverage run and after `.taskcheck` reported 0 mismatches.
- **Included and excluded files:** the 7 files in the table are included; no renames or deletions. Excluded: `*.html`/`*.scss` (templates/styles, exercised by the component tests), `web/package.json`/`package-lock.json` (dependency config), `docs/ARCHITECTURE.md` (docs), `reviews/*` (reports), `web/e2e/notes/editor.spec.ts` and `*.spec.ts` (tests). None missing from the report.
- **Provider, command and selection:** Vitest 5.0.5 with `@vitest/coverage-v8` via `@angular/build:unit-test`; `npm --prefix web run coverage` (= `cd web && npm run coverage`), whole suite.
- **Run time and exit status:** started 2026-10-21 07:29:03 local (05:29:03Z), duration 14.70 s, exit 0; 88 files and 1614 tests passed.
- **Report paths and sha256 hashes:**
  - `web/coverage/web/coverage-summary.json`: `3fc856f1d76aa731aab19b23594d07e91779cf80e2893e943f7f62ed49b390cf`
  - `web/coverage/web/coverage-final.json`: `8c3539a7276a738f761df8f24471e4e605735fb8b9666412bb3b726e4ade3ef5`
  - `web/coverage/web/lcov.info`: `6ec4f03a7fd7a2619d8b66a3d95c0ca377b6cc8fa52e49444a99c1db7cd15e3e`
  - Per-file counters read with `.tmp/tw3-cov.mjs`.
- **Fresh outputs, unchanged inputs:** yes. All report files carry the run's time (07:29 local), the totals reflect the fixed code (`note-editor-page.ts` 225 statements / 104 branches, up from 221 / 98 at the previous run), and the input snapshot did not change.
- **Final run after the latest test additions and production fixes:** yes.

Values below use covered/total (percentage) from `coverage-summary.json`. Zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/notes/editor/markdown-editor.ts` | 80/82 (97.56%) | 47/50 (94.00%) | 23/24 (95.83%) | 72/73 (98.63%) |
| `web/src/app/features/notes/editor/note-editor-page.ts` | 221/225 (98.22%) | 92/104 (88.46%) | 47/48 (97.92%) | 185/186 (99.46%) |
| `web/src/app/features/notes/editor/note-editor.service.ts` | 13/13 (100%) | 5/5 (100%) | 8/8 (100%) | 10/10 (100%) |
| `web/src/app/features/notes/editor/note-markdown-view.ts` | 30/30 (100%) | 24/25 (96.00%) | 6/6 (100%) | 23/23 (100%) |
| `web/src/app/features/notes/editor/note-markdown.ts` | 109/109 (100%) | 56/57 (98.25%) | 20/20 (100%) | 100/100 (100%) |
| `web/src/app/features/notes/editor/note-revisions-dialog.ts` | 78/78 (100%) | 23/27 (85.19%) | 8/8 (100%) | 53/53 (100%) |
| `web/src/app/features/notes/notes.routes.ts` | 2/3 (66.67%) | N/A (0/0) | 1/2 (50.00%) | 2/3 (66.67%) |
| Aggregate (summed counters, all 7 files; none missing or excluded) | 533/540 (98.70%) | 247/268 (92.16%) | 113/116 (97.41%) | 445/448 (99.33%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `note-editor-page.ts:130-146` (CR-1/CR-2: `openSeq`, `destroyed`, stay on error) | latest route wins, nothing after destroy, stay with unsaved edits | Fully covered: fixer tests plus the 4 new tests. The `error && !dirty` combination is practically unreachable (any edit after a failure resets the state to `pending`); the observable effect is covered. |
| `note-editor-page.ts:217` (false side: nothing to save while in `error`) | flush keeps the error state without edits | Low risk; no PATCH and the banner stays, no extra test. |
| `note-editor-page.ts:244` (false side: `useTheirs` error after a note switch) | stale "Discard my edits" error suppressed | Same pattern covered for `keepMine`. Low risk. |
| `note-editor-page.ts:314` (`schedule()` after a save when edits exist and no timer runs) | edits without their own timer during a save | Minor transition; neighbouring branches covered. |
| `note-editor-page.ts:58,106,122,196,332,350-353,386,400-402` | viewChild arrow, route without id, stale load error, toggle that changes nothing, `??` fallbacks, INCOMING/unavailable links, stored mode `edit`, storage unavailable | Non-material fallbacks or Angular plumbing; same gaps as the previous report, lines shifted by the fix. |
| `markdown-editor.ts:88,168-169,188,218` | aborted completion request, unused `focus()`, override without search, null clipboard data | Defensive code or CodeMirror-internal timing. Low risk. |
| `note-markdown-view.ts:63`, `note-markdown.ts:127`, `note-revisions-dialog.ts:52,87,104,122` | `??` fallbacks, ordered list tag side, revision without title, non-ApiError load failure, disabled restore | Low risk; unchanged from the previous report. |
| `notes.routes.ts:12` | lazy loading of `/notes/:id` | Not run by Vitest; the Playwright spec runs it (AC5, passed). |

## Unable to test

- jsdom cannot produce a real OS clipboard paste or real drop coordinates; the drop tests stub `EditorView.posAtCoords`, and the Playwright spec covers the real browser paste path (AC5, passed). No follow-up needed.
- That `replaceUrl` replaces the `/notes/B` history entry (so Back does not return to B) is not observable through `RouterTestingHarness`; only the resulting `Router.url` (`/notes/A`) is asserted. Low risk, no follow-up needed.
- Leaving a note in conflict discards the unsaved edits; this is a recorded assumption, not tested as a defect.
- The regression value of the new CR-2 test was reasoned from the pre-fix code, not demonstrated by reverting production code.

## Summary

Tests added and all validation passed.

- **CR-1:** a failed save on an in-app note switch keeps note A with its draft, error banner and retry, and puts the address back to `/notes/A`, also with a pending image and on a repeated attempt; once the save works, the next switch opens B normally.
- **CR-2:** only the latest route change continues (B loaded exactly once for A→B→A→B), and nothing loads after the page is left.
- **Checks:** focused specs (101), `npm run check` (1614 tests), `.taskcheck` and the Playwright spec against the docker e2e stack pass.
- **Coverage** of the 7 task files on the final code: 98.70% statements, 92.16% branches (summed counters).
- **Bugs:** no production defects found.

The test file and this report are left uncommitted for the parent worker.
