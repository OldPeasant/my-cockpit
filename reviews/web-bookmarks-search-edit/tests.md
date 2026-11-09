# Test Report

Date: 2026-11-09

Task: web-bookmarks-search-edit - Web bookmarks: search, edit, undo, to inbox

Scope: second (final) round. Task commits 3e2e17cb8, 8fd1957c3, 3acdcdf3d, 85f6d104f, 66b577797 on top of base a73c68c4a47065c97cdfc02a2f001404e0b71ffe (`git merge-base HEAD origin/main`, from the parent); measured HEAD 66b5777978c1a554453ab1a260e6622d7aec1221 on `main`. Since round one the fixer commit 66b577797 changed `bookmarks-page.ts`, `bookmarks-page.html`, `bookmarks-page.spec.ts` and both locale files (CR-2 draft version / conflict message, CR-3 notes maxlength; see `reviews/web-bookmarks-search-edit/fixes.md`). This round focuses on that changed behavior; round-one tests are unchanged and still run. Uncommitted inputs: only `web/src/app/features/bookmarks/bookmarks-page.spec.ts` (4 tests added by this round, hash below).

Requirements:
- `tasks/web-bookmarks-search-edit.md` acceptance criteria
- SPEC.md §8.4 (bookmark search), §4.5 (bookmark to Inbox task), §4.2 (If-Match / optimistic locking)
- docs/ARCHITECTURE.md "Coverage"
- `reviews/web-bookmarks-search-edit/review.md` CR-2, CR-3 and the fixer's description of the intended behavior
- Parent instructions for this round

## Test files changed

- `web/src/app/features/bookmarks/bookmarks-page.spec.ts` (this round)
- Round one (already committed in 85f6d104f): `bookmark-display.spec.ts`, `bookmarks.service.spec.ts`, `bookmarks-page.spec.ts`

## Tests added or updated

Count: 4 added this round, in `describe('search, edit, undo and to inbox') > describe('edit')`. No existing test was changed or weakened. The fixer's 3 new tests and the extended conflict assertion were reviewed and kept as they are.

- `keeps the editor version when the read toggle started from a newer row`: the row moves to version 2 by a change event while the editor (opened at version 1) is open; marking it read (If-Match "2") must not advance the draft, so Save still sends If-Match "1" and gets the conflict path with the editor values kept. Guards the `d.version === bookmark.version` condition in `change()`; without it the stale draft would overwrite the other device's title.
- `leaves the editor version alone when another bookmark is marked read`: marking a different bookmark read does not touch the open draft (Save sends If-Match "1" for the edited bookmark).
- `leaves another editor opened during a conflict reload untouched`: after a 412, the user opens another bookmark's editor before the reload answers; the reload callback neither changes that draft's version nor puts the conflict message back (opening the editor cleared the row error).
- `does not reopen an editor cancelled while its save ran into a conflict`: Cancel while the save is pending, then 412 + reload: the editor stays closed; reopening starts from the reloaded values and version (If-Match "5").

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'` -> 4 files, 125 tests passed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Search field matches title, URL, notes, tags (case-insensitive, every word) | display: `matchesQuery` tests; page: `filters the loaded bookmarks by title, ignoring case`, `filters by URL, notes and tag`, `needs every word to match`, `keeps the search when a bookmark it hides arrives from the server` | F | Pass |
| Search shows a no-match message and can be cleared | `says that nothing matches, then shows all again when the search is cleared`, `clears the search with Escape` | F | Pass |
| Edit title, tags and notes (PATCH with If-Match, blank -> null) | `opens the editor with the current title, tags and notes`, `saves title, tags and notes with the version and shows the result`, `sends a blank title and blank notes as null, to clear them`, `keeps the editor of another bookmark opened while a save was running`, `drops an edited bookmark that leaves the queue, and closes the editor of a deleted one` | F | Pass |
| Edit uses the version the editor opened with (CR-2, SPEC §4.2) | `saves with the version the editor opened with, so a newer row is a conflict` (fixer), `keeps the editor version when the read toggle started from a newer row` (new) | F | Pass |
| Edit conflict keeps the editor and values, shows `bookmarks.edit.conflict`, retry uses the current version | `keeps the editor after a conflict and loads the current version` (fixer-extended), `saves with the version the editor opened with...`, `leaves another editor opened during a conflict reload untouched` (new), `does not reopen an editor cancelled while its save ran into a conflict` (new) | F | Pass |
| Read/queue toggles while editing advance the draft version only when they started from it | `keeps the editor current after marking the bookmark read while editing` (fixer), `keeps the editor version when the read toggle started from a newer row` (new), `leaves the editor version alone when another bookmark is marked read` (new) | F | Pass |
| Notes limited to 2000 characters (CR-3) | `limits the notes to the 2000 characters the server accepts` (fixer) | F | Pass |
| Edit cancel | `cancels without saving, with the button and with Escape` | F | Pass |
| Edit failure (editor and values stay, retry) | `keeps the editor and its values when saving fails, and saves on the next try` | F | Pass |
| Undo after delete restores via POST /api/v1/trash/BOOKMARK/{id}/restore and reloads | `restores the bookmark from the Trash and shows it again`, `restores nothing when the snackbar is dismissed`, `offers no undo when the delete fails`; service: `restores a bookmark from the Trash without toasting errors` | F | Pass |
| Undo failure is reported | `toasts a failed restore and leaves the bookmark out of the list`; service: `rejects when the restore fails` | F | Pass |
| "To Inbox task": task with client UUIDv7 id, title, URL notes, INBOX, link label "origin" | page: `creates an Inbox task linked to the bookmark`; service: `creates an Inbox task with the given id and links the bookmark as its origin`, `links to the id of the task the server answered with`, `caps the task title at the server limit`; display: `caps task titles at the server limit` | F | Pass |
| To inbox when the link fails (linked=false) | page: `says when the task was created but the link failed`; service: `reports the task as unlinked when the link fails` | F | Pass |
| To inbox retry reuses the task id until success; separate ids per bookmark; no double create | `retries with the same task id after a failure, and a new one after success`, `uses a separate task id per bookmark`, `does not create a second task while one is being created`; service: `rejects without linking when the task cannot be created` | F | Pass |
| New UI strings in web/src/locale (en + de), incl. `bookmarks.edit.conflict` | Page tests assert the English source strings (the conflict message text is asserted verbatim); `ng build` inside `npm run check` builds with the locale files without translation errors | `cd web && npm run check` | Pass for the build; translation wording is not unit-testable (reviewer) |
| `cd web && npm run check` passes | whole web check (api:check, lint, e2e:typecheck, test:ci, ng build) | `cd web && npm run check` | Pass (exit 0) |
| `sh scripts/check.sh` passes | - | - | Not run: it includes the Gradle server/android checks, which the parent told this helper not to run |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'`

Result:

Pass (4 files, 125 tests; 121 after the fixer, plus the 4 added here). Also `npx eslint src/app/features/bookmarks/` (clean) and `npx prettier --write` on the changed spec (unchanged, already formatted).

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass (exit 0). The build prints the existing warning "bundle initial exceeded maximum budget" (692.49 kB of 500 kB), unrelated to this task. `npm run coverage` (the full web suite with coverage) also exited 0.

## .taskcheck

Result:

Not run: `.taskcheck` is `sh scripts/check.sh`, which runs Gradle; the parent instructed this helper not to run Gradle. Its web part (`npm run check`) passed.

## Bugs found

None. No production defect was found in the final revision.

Observation (not a defect, no requirement violated): when the user cancels the editor while its save is pending and the save then conflicts, the row error still reads "It was changed elsewhere in the meantime. Save again to replace it with your values." although no editor is open any more. The new test does not assert this message, so a later wording change stays possible. The parent may decide whether this is worth a follow-up.

## Coverage

Status: verified (web part; whole-file coverage of the task-changed TypeScript production files, final revision plus this round's tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: statements only, measured by intersecting v8 statement start lines from `coverage-final.json` with the added lines of `git diff -U0 a73c68c4a..HEAD -- {file}`: bookmark-display.ts 8/8 statements, bookmarks-page.ts 73/74 (missed: the `return` of the guard on line 320), bookmarks.service.ts 6/6; total 87/88. Changed-branch coverage is not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-bookmarks-search-edit; base a73c68c4a47065c97cdfc02a2f001404e0b71ffe (`git merge-base HEAD origin/main`, as the parent specified); HEAD 66b5777978c1a554453ab1a260e6622d7aec1221; `git log a73c68c4a..HEAD --stat` lists exactly the five task commits named above.
- Source/test snapshot identifier, sha256 before and after the coverage run (identical): bookmark-display.ts f8ea2d3b..., bookmarks-page.ts bb8ea7cd..., bookmarks.service.ts 89d9fa1c..., bookmarks-page.html 165da056..., bookmark-display.spec.ts 14783145..., bookmarks-page.spec.ts 455ce79d... (uncommitted, includes this round's tests), bookmarks.service.spec.ts 38b3d37a.... Production files have no uncommitted changes.
- Exact included files: `web/src/app/features/bookmarks/bookmark-display.ts`, `web/src/app/features/bookmarks/bookmarks-page.ts`, `web/src/app/features/bookmarks/bookmarks.service.ts`. Excluded: `bookmarks-page.html` (Angular template; v8 gives no per-template counters; its behavior, including the new maxlength, is covered by the DOM tests), `bookmark-edit.scss` (styles), `web/src/locale/messages.xlf` and `messages.de.xlf` (translation data). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5; `npm run coverage` in `web/`; full web suite (all spec files).
- Run start/completion time and exit status: started after 2026-11-09 01:26:33 local (input hashes taken), report written 2026-11-09 01:27:36 local; exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 8d28dd13b45995551b9598c795eb2b3b87835715414074da2f74b4a876ad5c43; `web/coverage/web/coverage-final.json` sha256 c46abad0659522c04eb8ca373ac7a1fbd2fb6f95724aa140b8cb572bfc1419e5 (both differ from round one's hashes, which confirms regeneration).
- Evidence outputs are fresh and inputs stayed unchanged: yes (input hashes identical before and after the run; the report mtime falls within the run).
- Final run after latest test additions and production fixes: yes (it covers fixer commit 66b577797 and the 4 tests added in this round).

Values below use covered/total (percentage); counts are provider-native from `coverage-summary.json`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/bookmarks/bookmark-display.ts` | 33/34 (97.05%) | 26/26 (100%) | 8/8 (100%) | 25/26 (96.15%) |
| `web/src/app/features/bookmarks/bookmarks-page.ts` | 273/277 (98.55%) | 162/174 (93.1%) | 71/71 (100%) | 229/229 (100%) |
| `web/src/app/features/bookmarks/bookmarks.service.ts` | 24/24 (100%) | 14/15 (93.33%) | 10/10 (100%) | 21/21 (100%) |
| Aggregate (summed counters, 3 files; template, scss and xlf excluded as listed above) | 330/335 (98.51%) | 202/215 (93.95%) | 89/89 (100%) | 275/276 (99.64%) |

Round one (pre-fix revision cfa388267) measured bookmarks-page.ts at 264/268 statements and 154/166 branches. The fixer's change added 8 branches; all of them are covered after this round (162/174, the 12 missed branches are the same guards and pre-existing code as before).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `bookmarks-page.ts:320` (statement and if-branch) `if (!draft \|\| draft.id !== bookmark.id) return;` in `saveEdit` | Defensive guard: the save form only exists for the bookmark being edited | Justified gap: not reachable through the UI |
| `bookmarks-page.ts:309` cond-expr `draft ? {...} : draft` in `setDraft` | Defensive: inputs only exist while a draft is open | Justified gap: not reachable through the UI |
| `bookmarks-page.ts:50,149,155,163,167,195,411,419,428,540` | Pre-existing code (load/loadMore/add guards, put/drop/insert null lists, reloadOne non-404 error) not changed by this task | Outside the task's changes; no test added |
| `bookmark-display.ts:29` (`normalizeUrl` catch) | Pre-existing | Outside the task's changes |
| `bookmarks.service.ts:141` (`?? ''` on a null snapshot body) | Pre-existing | Outside the task's changes |

## Unable to test

- `sh scripts/check.sh` / `.taskcheck`: not run because it runs Gradle (parent instruction). The parent must run it.
- Wording of the German translations, including the new `bookmarks.edit.conflict` target in `web/src/locale/messages.de.xlf`: not unit-testable; reviewer.
- Real MatSnackBar timing (`UNDO_DURATION_MS` auto-dismiss): the page spec uses the FakeSnackBar double. No follow-up needed beyond optional e2e.
- No Playwright e2e run: the criteria do not name e2e tests.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. This round added 4 tests for the fixer's changed behavior (draft version, conflict reload callback, read toggle while editing); focused tests (125), `npm run check` (web) and `npm run coverage` passed. `.taskcheck` was not run here because it includes Gradle.
