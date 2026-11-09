# Test Report

Date: 2026-11-09

Task: web-bookmarks-search-edit - Web bookmarks: search, edit, undo, to inbox

Scope: task commits 901e0d05a, 1053c95b9, cfa388267 (rebased versions of fe8f7acb3, 615ddc5d2, c578b8ad0; the rebase happened at 01:12 local, before this test work started) on top of base 4e2b5d5e44feb5bc3ec365ec2adb1fccd50cb14e; measured HEAD cfa38826709f9b251a3e3e091b13d97b5db5d350 on `main`. The other commits in `4e2b5d5e4..HEAD` only touch `tasks/*.md` (heartbeat/assume). Uncommitted inputs: only the three spec files this report adds tests to (hashes below).

Requirements:
- `tasks/web-bookmarks-search-edit.md` acceptance criteria
- SPEC.md §8.4 (bookmark search), §4.5 (bookmark to Inbox task), §4.2 (If-Match)
- docs/ARCHITECTURE.md "Coverage"
- Parent instructions (behavior list: search, edit, undo, to inbox, service restore/toInbox)

## Test files changed

- `web/src/app/features/bookmarks/bookmark-display.spec.ts`
- `web/src/app/features/bookmarks/bookmarks.service.spec.ts`
- `web/src/app/features/bookmarks/bookmarks-page.spec.ts`

## Tests added or updated

Count: 37 (7 in bookmark-display.spec.ts, 7 in bookmarks.service.spec.ts, 23 in bookmarks-page.spec.ts under `describe('search, edit, undo and to inbox')`). No existing test was changed.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'` -> 4 files, 118 tests passed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Search field matches title, URL, notes, tags (case-insensitive, every word) | display: `matchesQuery` blank query / title-URL-notes / display title without title / tags with or without # / every word / no cross-field match; page: `filters the loaded bookmarks by title, ignoring case`, `filters by URL, notes and tag`, `needs every word to match`, `keeps the search when a bookmark it hides arrives from the server` | F | Pass |
| Search shows a no-match message and can be cleared | `says that nothing matches, then shows all again when the search is cleared`, `clears the search with Escape` | F | Pass |
| Edit title, tags and notes (PATCH with If-Match, blank -> null) | `opens the editor with the current title, tags and notes`, `saves title, tags and notes with the version and shows the result`, `sends a blank title and blank notes as null, to clear them`, `keeps the editor of another bookmark opened while a save was running`, `drops an edited bookmark that leaves the queue, and closes the editor of a deleted one` | F | Pass |
| Edit cancel | `cancels without saving, with the button and with Escape` | F | Pass |
| Edit failure (editor and values stay, retry; conflict reloads version) | `keeps the editor and its values when saving fails, and saves on the next try`, `keeps the editor after a conflict and loads the current version` | F | Pass |
| Undo after delete restores via POST /api/v1/trash/BOOKMARK/{id}/restore and reloads | `restores the bookmark from the Trash and shows it again`, `restores nothing when the snackbar is dismissed`, `offers no undo when the delete fails`; service: `restores a bookmark from the Trash without toasting errors` | F | Pass |
| Undo failure is reported | `toasts a failed restore and leaves the bookmark out of the list`; service: `rejects when the restore fails` | F | Pass |
| "To Inbox task": task with client UUIDv7 id, title, URL notes, INBOX, link label "origin" | page: `creates an Inbox task linked to the bookmark`; service: `creates an Inbox task with the given id and links the bookmark as its origin`, `links to the id of the task the server answered with`, `caps the task title at the server limit`, display: `caps task titles at the server limit` | F | Pass |
| To inbox when the link fails (linked=false) | page: `says when the task was created but the link failed`; service: `reports the task as unlinked when the link fails` | F | Pass |
| To inbox retry reuses the task id until success; separate ids per bookmark; no double create | `retries with the same task id after a failure, and a new one after success`, `uses a separate task id per bookmark`, `does not create a second task while one is being created`; service: `rejects without linking when the task cannot be created` | F | Pass |
| New UI strings in web/src/locale (en + de) | Indirectly: page tests assert the English source strings; the xlf files are not unit-tested | - | Not testable by unit tests (i18n extraction/translation completeness is checked by the parent / reviewer) |
| `cd web && npm run check` passes | whole web check | `cd web && npm run check` | Pass (exit 0) |
| `sh scripts/check.sh` passes | - | - | Not run: it includes the Gradle server/android checks, which the parent told this helper not to run |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'`

Result:

Pass (4 files, 118 tests). Also `npx eslint src/app/features/bookmarks/` (clean) and `npx prettier --check` on the three spec files (clean).

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass (exit 0; 194 test files passed; "All files pass linting"; build complete). The build prints the existing warning "bundle initial exceeded maximum budget" (692.49 kB of 500 kB), not caused by this task's test-only changes. `npm run coverage` (the full web suite) also passed: 194 files, 4667 tests.

## .taskcheck

Result:

Not run: `.taskcheck` is `sh scripts/check.sh`, which runs Gradle; the parent instructed this helper not to run Gradle. The web part of it (`npm run check`) passed.

## Bugs found

None. No production defect was found for the reviewed scope.

## Coverage

Status: verified (web part; whole-file coverage of the task-changed TypeScript production files).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: statements only, measured by intersecting the v8 statement start lines from `coverage-final.json` with the added lines of `git diff -U0 4e2b5d5e4..HEAD -- {file}`: bookmark-display.ts 8/8, bookmarks-page.ts 60/61 (missed line 312), bookmarks.service.ts 6/6; total 74/75. Changed-branch coverage is not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-bookmarks-search-edit; base 4e2b5d5e44feb5bc3ec365ec2adb1fccd50cb14e (from parent); HEAD cfa38826709f9b251a3e3e091b13d97b5db5d350; task commits 901e0d05a, 1053c95b9, cfa388267 (from parent, rebased; `git log 4e2b5d5e4..HEAD --stat` shows the remaining commits only touch `tasks/`).
- Source/test snapshot identifier (including uncommitted inputs), sha256 before and after the final run (identical): bookmark-display.ts f8ea2d3b..., bookmarks-page.ts d8244297..., bookmarks.service.ts 89d9fa1c..., bookmarks-page.html 688f1a9c..., bookmark-display.spec.ts 14783145..., bookmarks-page.spec.ts 937b672b..., bookmarks.service.spec.ts 38b3d37a.... Production files have no uncommitted changes.
- Exact included files: `web/src/app/features/bookmarks/bookmark-display.ts`, `web/src/app/features/bookmarks/bookmarks-page.ts`, `web/src/app/features/bookmarks/bookmarks.service.ts`. Excluded: `bookmarks-page.html` (Angular template, compiled into the component; v8 gives no per-template counters, its behavior is covered through the DOM tests above), `bookmark-edit.scss` (styles, no executable behavior), `web/src/locale/messages.xlf` and `messages.de.xlf` (translation data). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5; `npm run coverage` in `web/`; full web suite (all spec files).
- Run start/completion time and exit status: started 2026-11-09 01:16:59 local, completed 2026-11-09T00:17:46Z; exit 0 (194 files, 4667 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 7c7cc2fcf20d681fcdc6c9a78cd3324bda153314739adb04562f69a5323f7b1a; `web/coverage/web/coverage-final.json` sha256 e3cbce089c69cae380c43508576f62f27f2d2e9f803300ca1266b5eb89b11f5f (Vitest rewrites `coverage/` per run; the hashes differ from the previous run's, which confirms regeneration).
- Evidence outputs are fresh and inputs stayed unchanged: yes (input hashes identical before and after the run).
- Final run after latest test additions and production fixes: yes (no production fixes were made; the run includes all 37 new tests).

Values below use covered/total (percentage); counts are provider-native from `coverage-summary.json`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/bookmarks/bookmark-display.ts` | 33/34 (97.05%) | 26/26 (100%) | 8/8 (100%) | 25/26 (96.15%) |
| `web/src/app/features/bookmarks/bookmarks-page.ts` | 264/268 (98.5%) | 154/166 (92.77%) | 68/68 (100%) | 221/221 (100%) |
| `web/src/app/features/bookmarks/bookmarks.service.ts` | 24/24 (100%) | 14/15 (93.33%) | 10/10 (100%) | 21/21 (100%) |
| Aggregate (summed counters, 3 files; template, scss and xlf excluded as listed above) | 321/326 (98.47%) | 194/207 (93.72%) | 86/86 (100%) | 267/268 (99.63%) |

Before the last added test (`keeps the editor of another bookmark opened while a save was running`), bookmarks-page.ts branches were 153/166; that test covered the `saveEdit` branch where the draft belongs to another bookmark.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `bookmarks-page.ts:312` (statement and if-branch) `if (!draft \|\| draft.id !== bookmark.id) return;` in `saveEdit` | Defensive guard: the save form only exists for the bookmark being edited | Justified gap: not reachable through the UI (the form is only rendered for the draft's bookmark) |
| `bookmarks-page.ts:306` cond-expr `draft ? {...} : draft` in `setDraft` | Defensive: inputs only exist while a draft is open | Justified gap: not reachable through the UI |
| `bookmarks-page.ts:147,153,161,165,193,381,389,398,498` and the v8 branch reported at line 48 | Pre-existing code (load/loadMore/add guards, belongs/put, insert, reloadOne) not changed by this task | Outside the task's changes; no test added |
| `bookmark-display.ts:29` (`normalizeUrl` catch) | Pre-existing | Outside the task's changes |
| `bookmarks.service.ts:141` (`?? ''` on a null snapshot body) | Pre-existing | Outside the task's changes |

## Unable to test

- `sh scripts/check.sh` / `.taskcheck`: not run because it runs Gradle (parent instruction). The parent must run it.
- Completeness and correctness of the German translations in `web/src/locale/messages.de.xlf`: not unit-testable; needs review (the parent / code-reviewer).
- Real MatSnackBar timing (`UNDO_DURATION_MS` auto-dismiss): the page spec uses the existing FakeSnackBar double; dismissal is simulated. No follow-up needed beyond optional e2e.
- No Playwright e2e run: the criteria do not name e2e tests. The parent may want to grep `web/e2e` for selectors changed by this task (e.g. the delete toast text, which moved from NotificationService to MatSnackBar).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. `npm run check` (web) passed; `.taskcheck` (`sh scripts/check.sh`) was not run here because it includes Gradle.
