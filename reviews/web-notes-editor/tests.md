# Test Report

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: task base `588084ba57566f93f26f56fc26318761523841dd` .. HEAD `a6e793e16f4bdd55361fd7ddaefd70febfa31714` on `main` (16 task commits, `git log --oneline 588084ba..HEAD`, supplied by the parent worker; `git diff --name-status 588084ba..HEAD` matches the task's files). Hashes in earlier reports predate a rebase. This run re-checks the latest production change, `18cc7b99` "fix P3 CR-1 (flushAll ends when the server normalises the note)" in `note-editor-page.ts`:
- `persist()` adopts the title the server kept unless it was retyped during the save;
- on the destroyed path, `persist()` only saves again when the draft changed since it was sent;
- `flushAll()` ends when a round leaves the draft unchanged (`sameDraft`).

It also re-checks all acceptance criteria on the final code.

Production files changed by the task:
- `web/src/app/features/notes/editor/{markdown-editor,note-editor-page,note-editor.service,note-markdown,note-markdown-view,note-revisions-dialog}.ts` (+ `.html`/`.scss`)
- `web/src/app/features/notes/notes.routes.ts`
- `web/package.json` / `package-lock.json` (dependency line only)

Measured state: HEAD `a6e793e1` plus the uncommitted test file `note-editor-page.spec.ts` (see Coverage for its hash). No uncommitted production changes.

Requirements:
- `tasks/web-notes-editor.md`: acceptance criteria 1-5 and the recorded assumptions (1 s debounce, conflict banner on 409/412, immediate checklist save, flush on leaving, conflict on leaving drops edits, failed save on in-app switch stays on the note, and 08:34 "title adopted from the server unless retyped; leaving stops once a round sends nothing new; body never replaced")
- `SPEC.md` §8.1 and §4.3
- `reviews/web-notes-editor/fixes.md` "CR-1 fix (fifth fix cycle)", `reviews/web-notes-editor/review.md`
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `web/src/app/features/notes/editor/note-editor-page.spec.ts`: 12 tests and 2 helpers (`setTitle`, `titleValue`) added. Existing tests are unchanged.

## Tests added or updated

Count: 12 added. The editor specs now hold 122 tests in 5 files (110 at `a6e793e1`).

A previous, interrupted test-writer run left 10 of them uncommitted. I reviewed each against the code and the 08:34 assumption, kept all 10 unchanged (they are correct, deterministic with fake timers, and pass prettier and eslint), and added 2 for an uncovered termination guard.

Title normalised by the server (sent `'\u001FPacking'`, server returns `'Packing'`, as Kotlin `trim()` drops U+001C-U+001F):
- `sends a title the server trims further only once when opening another note`: exactly one PATCH, then the GET of the other note.
- `sends a title the server trims further only once when the page is left`: exactly one PATCH, nothing after 3 x debounce.
- `shows no title when the server drops the whole title, and does not save it again`: sent `'\u001F'`, server returns no title. The input becomes empty, `Saved`, no further PATCH.
- `keeps a title retyped during the save instead of the one the server kept, and saves it`: the retyped `Packing for Lisbon` stays, `Unsaved changes`, then saved with If-Match `"2"`.
- `adopts the title the server kept and saves the body typed during the save with it`: the second PATCH sends `{ title: 'Packing', bodyMd: '- [ ] Passport' }`, not U+001F again.
- `on leaving, saves the body typed during a save whose title the server trimmed, then opens the other note`: second round sent before the other note is requested; then the other note is shown and no PATCH follows.
- `when the page is left, saves the body typed during a save whose title the server trimmed`: destroyed path with an edit typed during the save: exactly one more PATCH.
- `stays on the note when the save after adopting the trimmed title fails on leaving`: 503 on the second round. Stays on `/notes/A` with adopted title, draft and `Not saved`, no retry loop.
- `opens the other note when the save after adopting the trimmed title hits a conflict on leaving`: 409 on the second round. The other note opens (`Saved`), per the conflict assumption.
- `keeps the untrimmed title when its save fails, and adopts the server title on retry`: no adoption on failure; the retry button sends the untrimmed title, then adopts and shows `Saved`.

Body stored differently by the server (new in this run; defensive, the server stores `bodyMd` as sent, `NoteService.kt:278`):
- `sends the body only once when the page is left and the server stores the body differently`: covers `note-editor-page.ts:341` (destroyed path, draft unchanged since sent -> `pending`, no re-save). This is the guard that prevents the persist -> flush -> persist loop; it was uncovered in the first coverage run.
- `opens another note after one save when the server stores the body differently`: the `flushAll()` `sameDraft` exit with a field that is not adopted; one PATCH, then the other note opens, and the leftover autosave timer is cleared by `show()` (covers `:342`).

Analysis without changing production code: removing the title adoption makes `shows no title ...`, `adopts the title ...`, the two `on leaving / page is left ... trimmed` body tests and the retry test fail (they assert the adopted title in the UI or in the next PATCH body). Removing the `sameDraft` guard on line 340-341 would make the first body test see endless PATCHes. I did not edit production code to confirm this; the fixer confirmed the earlier three tests fail on the pre-fix code.

## Requirement coverage

Commands:
- **F** = `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'`: 5 files, 122 tests, all passed.
- **C** = `npm --prefix web run check` (= `cd web && npm run check`): exit 0. It runs api:check, lint, e2e:typecheck, test:ci (96 files, 1835 tests passed) and `ng build`.
- **E** = `npm --prefix web run e2e -- e2e/notes/editor.spec.ts` (= `cd web && npm run e2e -- e2e/notes/editor.spec.ts`): docker compose e2e stack (via podman-compose on this machine), project `chromium` for the editor spec. The runner also runs the 15 `phase-demos` tests.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 CodeMirror editor with preview toggle/split | page: `switches between write, side by side and preview, and remembers the choice`; editor: `shows the value, reports edits and applies values from outside without reporting them` | F | Pass |
| AC1 autosave debounced, If-Match | page: `autosaves once after the debounce, with the version as If-Match`, `saves edits made while a save runs after that save`, `removes the title with null when it is cleared`; service: `saves title and body with the version as a quoted If-Match` | F | Pass |
| AC1 conflict notice (412/409), keep mine / discard | page: `on a conflict stops autosaving and keeps my version on request`, `on a conflict discards my edits on request`, `treats a 409 like a 412: conflict banner, no further autosave`, `keeps the conflict when reloading the newer version fails`, and the "another note was opened meanwhile" tests | F | Pass |
| Flush when leaving, retry on leave, `flushAll()` rounds/error/conflict/uploads (earlier CR-1 cycles) | page: `saves pending edits before opening another note`, `saves pending edits when leaving the page`, `saves edits typed during the save on leaving before opening another note`, `stays on the note when saving the edits typed during the save on leaving fails`, `opens the other note when saving the edits typed during the save on leaving hits a conflict`, `keeps saving on leaving as long as edits arrive during each save, then opens the other note`, `saves edits typed during the save of an uploaded image on leaving before opening another note`, `retries a failed save when leaving the page`, `asks before closing the browser tab only while edits are unsaved` | F | Pass |
| Fifth-cycle CR-1 fix / assumption 08:34: server-normalised title adopted unless retyped, leaving and autosave end, body never adopted | fixer: `opens another note when the server stores the title differently from the one sent`, `shows the title the server kept and does not save it again`, `sends the draft at most once more when the page is left and the server stores it differently`; plus the 12 new tests listed above | F | Pass |
| Assumption 07:26: failed save on in-app switch stays with draft, banner, address back | page: `stays on the note with its edits when the retry before opening another note fails too`, `stays on the note again when the save fails on a second try to open another note`, `stays on the note when the save after adopting the trimmed title fails on leaving` (new) | F | Pass |
| Only the latest open continues; nothing loads after destroy | page: `does not open the other note when the page is left while leaving the first one waits`, `requests the other note only once when the route goes there, back and there again while leaving waits`, `opens only the last note when the route changes twice while leaving waits for an upload` | F | Pass |
| Upload placeholders never saved; pending uploads applied after leaving | page: `does not leave an upload placeholder in the saved note when leaving during an upload`, `does not autosave the placeholder of an image that is still uploading`, `puts an image still uploading into the note before opening another note`, `when leaving during several uploads, saves every image in place of its placeholder` | F | Pass |
| AC2 paste/drop image -> upload -> `![](cockpit-file:{id})`; failures | editor paste/drop/out-of-order/failure tests; page: `uploads pasted images to the note`, `shows a failed image upload`; service: `uploads an image as multipart form data to the note` | F | Pass |
| AC2 rendered via the authenticated file URL | view: `shows cockpit-file images through the authenticated preview URL`; service: `builds the authenticated preview URL of a file` | F | Pass |
| AC2 preview checklists toggle and save | page: `toggles a checklist item in the preview and saves it right away`, `does not save a checklist toggle while in conflict`; view and `note-markdown.spec.ts` toggle tests | F | Pass |
| AC3 `[[` autocomplete over all item types inserts `[[type:id]]` | editor: `offers link targets after [[ in the editor and inserts the chosen one`, `linkCompletions` tests; page: `offers link targets of all types but the note itself, and remembers their titles` | F | Pass |
| AC3 chips, revisions dialog with restore, linked items panel | view chip tests; page: `restores a revision from the dialog`, `saves pending edits before restoring a revision, then restores on the saved version`, `shows the note’s linked items in the Linked items panel`; service revision tests | F | Pass |
| Security: preview HTML cannot be injected from the source | view: `renders no script or event handler from the source`; `note-markdown.spec.ts` escaping tests | F | Pass |
| AC4 Vitest tests cover autosave, checklist toggle, link autocomplete; `npm run check` passes | all of the above | C | Pass (only the existing initial bundle budget warning, 651.23 kB > 500 kB) |
| AC5 Playwright `e2e/notes/editor.spec.ts` against the e2e stack: paste an image, toggle a checklist, reload -> persisted | `Notes editor › paste an image, toggle a checklist, reload: both persisted` | E | Pass: 16 passed (this spec in `chromium` + 15 `phase-demos`), exit 0. Stack healthy after 171 s, torn down afterwards. Run 06:52:26Z-06:56:05Z. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'`

Result:

Pass: 5 files, 122 tests (120 before the 2 body tests were added, both runs green). `npx prettier --check` and `npx eslint --max-warnings=0` on the spec file pass.

## Full test execution

Commands:

- `npm --prefix web run check`
- `npm --prefix web run e2e -- e2e/notes/editor.spec.ts`

Result:

Pass.
- `npm run check` (06:48:09Z-06:49:00Z) exited 0: 96 files, 1835 tests passed; `ng build` passes with only the existing budget warning.
- Playwright: 16 passed against the docker compose e2e stack, exit 0.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (06:50:07Z-06:52:17Z, exit 0) ran the TFP gate tests, server ktlint + tests, web eslint + tests + build, and android ktlint + unit tests + lint, and ended with `== all checks passed`.

## Bugs found

None. The fifth-cycle fix behaves as `fixes.md` and the 08:34 assumption describe: the server's title is adopted only when not retyped and only on success; leaving ends after at most one save of an unchanged draft, also when a field (body) is stored differently and not adopted; edits typed during the save are still sent; error and conflict on the second round behave like the earlier cycles.

Observation (not a defect, no test failing): if the server ever stored the body differently without leaving the page, autosave would re-send the same draft every second (`persist()` -> `schedule()`), because the body is deliberately not adopted. The server stores `bodyMd` exactly as sent (`NoteService.kt:278`), so this is unreachable today; it is noted only for the parent to judge.

## Coverage

Status: verified. Whole-file coverage of all 7 task-changed executable production files on the final code (HEAD `a6e793e1` plus the new tests). The whole suite ran, no tests deselected.

Metric: whole-file coverage of task-changed production files.

Changed-line coverage: not measured separately. The six `editor/*.ts` files were added entirely by this task, so for them whole-file coverage equals changed-line coverage. In `notes.routes.ts` the task added the `:id` route; its lazy `loadComponent` callback (line 12) is the uncovered line. The lines changed by `18cc7b99` (`note-editor-page.ts:165-176`, `329-343`, `417-419`) have no uncovered statement or branch in the final report.

Evidence:
- **Task and scope:** task `web-notes-editor`; immutable base `588084ba57566f93f26f56fc26318761523841dd`; measured HEAD `a6e793e16f4bdd55361fd7ddaefd70febfa31714`; base and commit range supplied by the parent, file list confirmed with `git diff --name-status 588084ba..HEAD`.
- **Source/test snapshot:** HEAD `a6e793e1` plus uncommitted `web/src/app/features/notes/editor/note-editor-page.spec.ts` (sha256 `eda553e92159c4e36a568fe233aa7282a58358c8a8601b1d42a0730af4d4d602`). `.tmp/tw6-snapshot.txt` (sha256 `b47db1f4d3c1b5a3ce039c9df20856c46dd61f76f37c7cedcff71705c83a9b5d`) lists the sha256 of every `editor/*.ts`, `note-editor-page.html`, `notes.routes.ts` and `web/e2e/notes/editor.spec.ts`. Taken before the final `npm run check`; `sha256sum -c` after the coverage run, `.taskcheck` and the e2e run reported no mismatch.
- **Included and excluded files:** the 7 files in the table, no renames or deletions, none missing from the report. Excluded: `*.html`/`*.scss` (templates/styles, exercised by the component tests), `web/package.json`/`package-lock.json` (dependency config), `*.spec.ts` and `web/e2e/notes/editor.spec.ts` (tests), `reviews/*`, `tasks/*`.
- **Provider, command and selection:** Vitest 5.0.5 with `@vitest/coverage-v8` 5.0.5 via `@angular/build:unit-test`, Node v24.21.0. Command `npm --prefix web run coverage` (= `cd web && npm run coverage`), working directory the clone root, whole suite.
- **Run time and exit status:** final run 06:49:03Z-06:49:52Z, exit 0, 96 files / 1835 tests passed. A first run (06:46:16Z-06:46:57Z, 1833 tests, before the 2 body tests) showed `note-editor-page.ts:341-342` uncovered; it is superseded.
- **Report paths and sha256 (final run):**
  - `web/coverage/web/coverage-summary.json`: `53bf59f5e17833a5606d01b073bda06f39316db4605cb6ce9893b0c66868dbaf`
  - `web/coverage/web/coverage-final.json`: `368af6460fcda9d6b419042ea28db6d69bbe4566e85079020fdc3b2d6bc14cd9`
  - `web/coverage/web/lcov.info`: `e2f73dea2bc053f8c86d9096d9309eb16f1cb4c0baf4c6e60092f8fc5286a47b`
  - Per-file counters and uncovered locations read with `.tmp/tw6-cov.mjs`.
- **Fresh outputs, unchanged inputs:** yes. All report files were rewritten at 06:49:52Z (08:49 local); the hashes differ from the first run; `note-editor-page.ts` totals (234 statements / 120 branches / 50 functions) reflect the `18cc7b99` code; the input snapshot did not change.
- **Final run after the latest test additions and production fixes:** yes.

Values from `coverage-summary.json`, covered/total (percentage). Zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/notes/editor/markdown-editor.ts` | 80/82 (97.56%) | 47/50 (94.00%) | 23/24 (95.83%) | 72/73 (98.63%) |
| `web/src/app/features/notes/editor/note-editor-page.ts` | 231/234 (98.71%) | 110/120 (91.66%) | 49/50 (98.00%) | 194/195 (99.48%) |
| `web/src/app/features/notes/editor/note-editor.service.ts` | 13/13 (100%) | 5/5 (100%) | 8/8 (100%) | 10/10 (100%) |
| `web/src/app/features/notes/editor/note-markdown-view.ts` | 30/30 (100%) | 24/25 (96.00%) | 6/6 (100%) | 23/23 (100%) |
| `web/src/app/features/notes/editor/note-markdown.ts` | 109/109 (100%) | 56/57 (98.24%) | 20/20 (100%) | 100/100 (100%) |
| `web/src/app/features/notes/editor/note-revisions-dialog.ts` | 78/78 (100%) | 23/27 (85.18%) | 8/8 (100%) | 53/53 (100%) |
| `web/src/app/features/notes/notes.routes.ts` | 2/3 (66.66%) | N/A (0/0) | 1/2 (50.00%) | 2/3 (66.66%) |
| Aggregate (summed counters, all 7 files; none missing, none excluded among executable files) | 543/549 (98.91%) | 265/284 (93.31%) | 115/118 (97.46%) | 454/457 (99.34%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `note-editor-page.ts:165-176, 329-343` | Fifth-cycle CR-1: title adoption, destroyed-path guard, `flushAll()` `sameDraft` exit | Fully covered. `:341` (guard) and `:342` (`schedule()` after a save) were uncovered in the first run; covered by the 2 new body tests. |
| `note-editor-page.ts:215` | toggle that changes nothing returns | Low risk: no save. |
| `note-editor-page.ts:236` (false side) | flush with nothing to save keeps `error` | Low risk: no PATCH, banner stays. |
| `note-editor-page.ts:263` (false side) | `useTheirs` error after a note switch | Same pattern covered for `keepMine`. Low risk. |
| `note-editor-page.ts:58,106,122,360,378,380,381,432,434` | viewChild arrow, route without id, stale load error, `??` fallbacks, INCOMING/unavailable links, stored mode `edit`, storage unavailable | Non-material fallbacks or Angular plumbing; unchanged from earlier reports (lines shifted). |
| `markdown-editor.ts:88,168-169,188,218` | aborted completion request, unused `focus()`, override without search, null clipboard data | Defensive code or CodeMirror-internal timing. Low risk. |
| `note-markdown-view.ts:63`, `note-markdown.ts:127`, `note-revisions-dialog.ts:52,87,106,122` | `??` fallbacks, ordered-list tag side, revision without title, non-ApiError load failure, disabled restore | Low risk; unchanged from earlier reports. |
| `notes.routes.ts:12` | lazy loading of `/notes/:id` | Not run by Vitest; the Playwright spec runs it (AC5, passed). |

## Unable to test

- **Real clipboard and drop events in Vitest:** jsdom cannot produce a real OS paste or drop coordinates; the drop tests stub `EditorView.posAtCoords`. The real browser paste path is covered by the Playwright spec (AC5, passed). No follow-up needed.
- **Real server normalisation in unit tests:** the U+001F title trim is simulated by the mocked PATCH response; the real Kotlin `trim()` behaviour is not exercised end to end. Low risk; no follow-up needed.

## Summary

Tests added and all validation passed. The 10 uncommitted tests from the interrupted run were reviewed and kept; 2 tests were added for the previously uncovered destroyed-path termination guard. Focused specs (122), `npm run check` (1835 tests), `.taskcheck` and the AC5 Playwright spec on the docker compose e2e stack all pass. Task-scoped coverage is verified on the final code. No production defects found.
