# Test Report

Date: 2026-11-09

Task: web-bookmarks-search-edit - Web bookmarks: search, edit, undo, to inbox

Scope: third (final) round. Base 6ab70e99ef395cc9016b8074cc796d4ff5278cc1 (`git merge-base HEAD origin/main`; the task was rebased since round two, whose base was a73c68c4a). Task commits on top of it: dfa1830bf, 723294670, b17d7ca70, 8d2bf7271, 78905b744, ba7b2ec33, 2d12fed81 (`git log 6ab70e99e..HEAD`). Measured HEAD 2d12fed818db54cf935dd92f22ce841c481dea6d on `main`. Since round two the fixer commit 2d12fed81 changed `bookmarks-page.ts`, `bookmarks-page.html`, `bookmarks-page.spec.ts` and both locale files (Round 2 in `reviews/web-bookmarks-search-edit/fixes.md`): "Show more" renders whenever `nextCursor` is set, a failed To Inbox shows its own message `bookmarks.taskFailed`, and the `bookmarks.edit.conflict` message only appears while the row's editor is open. This round checks that behavior; earlier tests are unchanged and still run. Uncommitted inputs: only `web/src/app/features/bookmarks/bookmarks-page.spec.ts` (4 tests added by this round, hash below).

Requirements:
- `tasks/web-bookmarks-search-edit.md` acceptance criteria
- SPEC.md §8.4 (bookmark search), §4.5 (bookmark to Inbox task), §4.2 (If-Match / optimistic locking)
- docs/ARCHITECTURE.md "Coverage"
- `reviews/web-bookmarks-search-edit/review.md` (second review CR-1, CR-2) and the fixer's Round 2 description of the intended behavior
- Parent instructions for this round

## Test files changed

- `web/src/app/features/bookmarks/bookmarks-page.spec.ts` (this round, uncommitted)
- Earlier rounds (committed): `bookmark-display.spec.ts`, `bookmarks.service.spec.ts`, `bookmarks-page.spec.ts`

## Tests added or updated

Count: 4 added this round. No existing test was changed or weakened. The fixer's 3 new tests (two "Show more" tests, "does not ask to save again after a conflict when the editor was closed meanwhile") and its updated assertion in "retries with the same task id after a failure, and a new one after success" (now expects the `bookmarks.taskFailed` text, which is what the finding requires) were reviewed and kept.

- `keeps the search on a page loaded with "Show more" and offers the next one` (search describe level): with a search that matches nothing, "Show more" is disabled while the page loads; the loaded page holds no match either, so the no-match message stays and the button is offered again for the next cursor; clearing the search shows both pages and the button.
- `offers no "Show more" in an empty view without more pages`: the moved button still depends on `nextCursor` (no cursor, no button, empty message shown).
- `reports a failed change after a failed task as a change again` (to Inbox task): after a failed To Inbox (task message), a failed mark-read on the same row shows `bookmarks.changeFailed` again, so the `task` flag does not stick to the row; the other row has no error.
- `moves the task message to the bookmark whose task failed last`: a change error on one bookmark is replaced by a task error on another; the first row's error disappears and the second shows the task text.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'` -> 4 files, 132 tests passed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Search field matches title, URL, notes, tags (case-insensitive, every word) | display: `matchesQuery` tests; page: `filters the loaded bookmarks by title, ignoring case`, `filters by URL, notes and tag`, `needs every word to match`, `keeps the search when a bookmark it hides arrives from the server` | F | Pass |
| Search shows a no-match message and can be cleared | `says that nothing matches, then shows all again when the search is cleared`, `clears the search with Escape` | F | Pass |
| Search covers only loaded pages, so "Show more" stays available with no match / empty view (second review CR-1) | `keeps "Show more" when nothing loaded matches, so older pages can be searched` (fixer), `keeps the empty message of an empty view and offers "Show more" with it` (fixer), `keeps the search on a page loaded with "Show more" and offers the next one` (new), `offers no "Show more" in an empty view without more pages` (new), `loads more bookmarks from the cursor`, `toasts a failed "Show more" and keeps the button` | F | Pass |
| Edit title, tags and notes (PATCH with If-Match, blank -> null) | `opens the editor with the current title, tags and notes`, `saves title, tags and notes with the version and shows the result`, `sends a blank title and blank notes as null, to clear them`, `keeps the editor of another bookmark opened while a save was running`, `drops an edited bookmark that leaves the queue, and closes the editor of a deleted one` | F | Pass |
| Edit uses the version the editor opened with (SPEC §4.2) | `saves with the version the editor opened with, so a newer row is a conflict`, `keeps the editor version when the read toggle started from a newer row` | F | Pass |
| Edit conflict keeps the editor and values, shows `bookmarks.edit.conflict` only while that editor is open, retry uses the current version | `keeps the editor after a conflict and loads the current version`, `does not ask to save again after a conflict when the editor was closed meanwhile` (fixer), `leaves another editor opened during a conflict reload untouched`, `does not reopen an editor cancelled while its save ran into a conflict` | F | Pass |
| Read/queue toggles while editing advance the draft version only when they started from it | `keeps the editor current after marking the bookmark read while editing`, `keeps the editor version when the read toggle started from a newer row`, `leaves the editor version alone when another bookmark is marked read` | F | Pass |
| Notes limited to 2000 characters | `limits the notes to the 2000 characters the server accepts` | F | Pass |
| Edit cancel | `cancels without saving, with the button and with Escape` | F | Pass |
| Edit failure (editor and values stay, retry) | `keeps the editor and its values when saving fails, and saves on the next try` | F | Pass |
| Undo after delete restores via POST /api/v1/trash/BOOKMARK/{id}/restore and reloads | `restores the bookmark from the Trash and shows it again`, `restores nothing when the snackbar is dismissed`, `offers no undo when the delete fails`; service: `restores a bookmark from the Trash without toasting errors` | F | Pass |
| Undo failure is reported | `toasts a failed restore and leaves the bookmark out of the list`; service: `rejects when the restore fails` | F | Pass |
| "To Inbox task": task with client UUIDv7 id, title, URL notes, INBOX, link label "origin" | page: `creates an Inbox task linked to the bookmark`; service: `creates an Inbox task with the given id and links the bookmark as its origin`, `links to the id of the task the server answered with`, `caps the task title at the server limit`; display: `caps task titles at the server limit` | F | Pass |
| A failed To Inbox has its own message `bookmarks.taskFailed` (second review CR-2), other failures keep `bookmarks.changeFailed` | `retries with the same task id after a failure, and a new one after success` (fixer-updated), `reports a failed change after a failed task as a change again` (new), `moves the task message to the bookmark whose task failed last` (new), `shows a server error next to the bookmark without reloading it, and clears it on the next try` | F | Pass |
| To inbox when the link fails (linked=false) | page: `says when the task was created but the link failed`; service: `reports the task as unlinked when the link fails` | F | Pass |
| To inbox retry reuses the task id until success; separate ids per bookmark; no double create | `retries with the same task id after a failure, and a new one after success`, `uses a separate task id per bookmark`, `does not create a second task while one is being created`; service: `rejects without linking when the task cannot be created` | F | Pass |
| New UI strings in web/src/locale (en + de), incl. `bookmarks.edit.conflict` and `bookmarks.taskFailed` | Page tests assert the English source strings verbatim; `ng build` inside `npm run check` builds with the locale files without translation errors | `cd web && npm run check` | Pass for the build; translation wording is not unit-testable (reviewer) |
| `cd web && npm run check` passes | whole web check (api:check, lint, e2e:typecheck, test:ci, ng build) | `cd web && npm run check` | Pass |
| `sh scripts/check.sh` passes | - | - | Not run: it includes the Gradle server/android checks, which the parent told this helper not to run |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'`

Result:

Pass (4 files, 132 tests: 128 at HEAD plus the 4 added here). Also `npx prettier --check` on the changed spec (formatted) and `npx eslint src/app/features/bookmarks/` (clean).

## Full test execution

Command:

`cd web && npm run check` (api:check && lint && e2e:typecheck && test:ci && ng build)

Result:

Pass. The `&&` chain reached `ng build`, which completed ("Application bundle generation complete", output written to `web/dist/web`); it prints the existing warning "bundle initial exceeded maximum budget" (692.49 kB of 500 kB), unrelated to this task. `npm run coverage` (the full web suite with coverage: 194 files, 4681 tests) also passed.

## .taskcheck

Result:

Not run: `.taskcheck` is `sh scripts/check.sh`, which runs Gradle; the parent instructed this helper not to run Gradle. Its web part (`npm run check`) passed.

## Bugs found

None. No production defect was found in the final revision.

The round-two observation (the "Save again" conflict message shown after the editor was cancelled) is resolved by the fixer and now asserted by the fixer's test.

Observation (not a defect against any criterion, not changed by this round): a 404 answer to the To Inbox task creation goes through the shared `run()` 404 path and drops the bookmark from the list, although a 404 there would not mean the bookmark is gone. The server's task endpoint does not answer 404 for a create, so this is theoretical; the parent may decide whether it is worth a follow-up.

## Coverage

Status: verified (web part; whole-file coverage of the task-changed TypeScript production files, final revision HEAD 2d12fed81 plus this round's tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: statements only, measured by intersecting v8 statement start lines from `coverage-final.json` with the added lines of `git diff -U0 6ab70e99e..HEAD -- {file}`: bookmark-display.ts 8/8 statements, bookmarks-page.ts 77/78 (missed: the `return` of the guard on line 323), bookmarks.service.ts 6/6; total 91/92. Changed-branch coverage is not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-bookmarks-search-edit; base 6ab70e99ef395cc9016b8074cc796d4ff5278cc1 (`git merge-base HEAD origin/main`, as the parent specified); HEAD 2d12fed818db54cf935dd92f22ce841c481dea6d; `git log 6ab70e99e..HEAD` lists exactly the seven task commits named above.
- Source/test snapshot identifier, sha256 before and after the coverage run (identical): bookmark-display.ts f8ea2d3b..., bookmarks-page.ts 826efe90..., bookmarks.service.ts 89d9fa1c..., bookmarks-page.html a173bc66..., messages.xlf 3777a2a4..., messages.de.xlf 098aec73..., bookmark-display.spec.ts 14783145..., bookmarks-page.spec.ts ca83d29e... (uncommitted, includes this round's tests), bookmarks.service.spec.ts 38b3d37a.... Production files have no uncommitted changes (`git status` was clean before this round).
- Exact included files: `web/src/app/features/bookmarks/bookmark-display.ts`, `web/src/app/features/bookmarks/bookmarks-page.ts`, `web/src/app/features/bookmarks/bookmarks.service.ts`. Excluded: `bookmarks-page.html` (Angular template; v8 gives no per-template counters; its behavior, including the moved "Show more" block and the `isTaskError` message switch, is covered by the DOM tests), `bookmark-edit.scss` (styles), `web/src/locale/messages.xlf` and `messages.de.xlf` (translation data). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5; `npm run coverage` in `web/`; full web suite (all spec files).
- Run start/completion time and exit status: input hashes taken 2026-11-09 01:34:25 local, run started 01:34:36, report written 01:35:27; all 194 test files passed and the summary was printed (passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 2cc65dbabce941f1ecae02be389773676f637231bdead8055e0663e51997df9a; `web/coverage/web/coverage-final.json` sha256 61dec1c13fe008bf207d0536d5b33500de8ec784bcfe2f37a2ec0e089c6bd36d (both differ from round two's hashes, and their mtime 01:35:27 falls inside the run).
- Evidence outputs are fresh and inputs stayed unchanged: yes.
- Final run after latest test additions and production fixes: yes (it covers fixer commit 2d12fed81 and the 4 tests added in this round).

Values below use covered/total (percentage); counts are provider-native from `coverage-summary.json`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/bookmarks/bookmark-display.ts` | 33/34 (97.05%) | 26/26 (100%) | 8/8 (100%) | 25/26 (96.15%) |
| `web/src/app/features/bookmarks/bookmarks-page.ts` | 275/279 (98.56%) | 167/179 (93.29%) | 72/72 (100%) | 231/231 (100%) |
| `web/src/app/features/bookmarks/bookmarks.service.ts` | 24/24 (100%) | 14/15 (93.33%) | 10/10 (100%) | 21/21 (100%) |
| Aggregate (summed counters, 3 files; template, scss and xlf excluded as listed above) | 332/337 (98.52%) | 207/220 (94.09%) | 90/90 (100%) | 277/278 (99.64%) |

Round two (HEAD 66b577797) measured bookmarks-page.ts at 273/277 statements and 162/174 branches. The fixer's Round 2 change added 2 statements, 5 branches and 1 function (`isTaskError`); all of them are covered (the 12 missed branches are the same guards and pre-existing code as before, shifted by 3 lines).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `bookmarks-page.ts:323` (statement and if-branch) `if (!draft \|\| draft.id !== bookmark.id) return;` in `saveEdit` | Defensive guard: the save form only exists for the bookmark being edited | Justified gap: not reachable through the UI |
| `bookmarks-page.ts:312` cond-expr `draft ? {...} : draft` in `setDraft` | Defensive: inputs only exist while a draft is open | Justified gap: not reachable through the UI |
| `bookmarks-page.ts:50,152,158,166,170,198,425,433,442,556` | Pre-existing code (load/loadMore/add guards, put/drop/insert null lists, reloadOne non-404 error) not changed by this task | Outside the task's changes; no test added |
| `bookmark-display.ts:29` (`normalizeUrl` catch) | Pre-existing | Outside the task's changes |
| `bookmarks.service.ts:141` (`?? ''` on a null snapshot body) | Pre-existing | Outside the task's changes |

## Unable to test

- `sh scripts/check.sh` / `.taskcheck`: not run because it runs Gradle (parent instruction). The parent must run it.
- Wording of the German translations, including the new `bookmarks.taskFailed` and `bookmarks.edit.conflict` targets in `web/src/locale/messages.de.xlf`: not unit-testable; reviewer.
- Real MatSnackBar timing (`UNDO_DURATION_MS` auto-dismiss): the page spec uses the FakeSnackBar double. No follow-up needed beyond optional e2e.
- No Playwright e2e run: the criteria do not name e2e tests.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. This round added 4 tests for the fixer's Round 2 behavior ("Show more" with a search and without a cursor, task error message not sticking to the row or to another bookmark); focused tests (132), `npm run check` (web) and `npm run coverage` passed. `.taskcheck` was not run here because it includes Gradle.
