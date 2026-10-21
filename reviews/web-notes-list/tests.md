# Test Report

Date: 2026-10-21

Task: web-notes-list - Notes: list and organization

Scope: base 37a3c6cc5f3abd1b7bf6a1488e2fec43fce3c1e8 .. HEAD 07ddc501b115482e7d1ef4995e20d8b910001ea1 (task commits 7fc1d346, 07ddc501; given by the parent worker and confirmed with `git log`/`git diff --name-only 37a3c6cc..HEAD`), branch `main`, plus uncommitted test-only changes from this run.

Requirements:
- `tasks/web-notes-list.md` acceptance criteria and recorded assumptions (client-side search over all pages, inline composer, grid default stored in `cockpit.notes.view`, composer hidden in archived view)
- `SPEC.md` §8.1 (through the task goal), `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Existing conventions in `web/src/app/features/notes/list/*.spec.ts` and `core/notifications` (Storage spies)

## Test files changed

- `web/src/app/features/notes/list/notes-page.spec.ts`
- `web/src/app/features/notes/list/notes.service.spec.ts`
- `web/src/app/features/notes/list/notes-filter.spec.ts`

## Tests added or updated

Count: 37 added (26 component, 4 service, 7 pure filter); 0 existing tests changed. Notes specs: 29 -> 66 tests.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Masonry grid/list toggle (default grid, remembered) | existing `switches between grid and list and remembers the choice`; new `starts in list view when the list view was chosen before`, `falls back to the grid for an unknown stored view`, `works with the grid and still switches views when storage is unavailable` | `npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts'` | Pass |
| Pinned first | existing `shows the pinned notes first...`, `moves a note to the top when it is pinned...`, `sortNotes` tests; new `keeps a pinned note above a more recently changed unpinned one`, `handles an empty list and a single note`, `searches across pinned and other notes and keeps pinned first`, `shows a server error without reloading and lets the change be tried again` | same | Pass |
| Tag filter | existing `filters by tag on the server...`; new `shows the newest tag filter when an older response arrives last`, `ignores the failure of an outdated load`, `says when no note has the chosen tag`, `still loads the notes when the tags cannot be loaded`, `keeps the search when the tag filter changes` | same | Pass |
| Archived toggle | existing `shows the archived notes and moves a note back`, `archives a note out of the list`; new `does not reload when the active view is chosen again`, `does not put a note into the archived view when its pin finishes after switching`, `clears the error of an action when switching to the archived notes` | same | Pass |
| Search within notes | existing search tests; new `matchesQuery edge cases` (lone `#`, whitespace, no match across field boundary), `filterNotes` empty query/empty list | same | Pass |
| Create note | existing composer tests; new `saves with Ctrl+Enter and Cmd+Enter, but not an empty draft`, `sends a note only once when saved twice quickly`, `discards the draft with Escape...`, `focuses the text when the composer opens`, `cancels the composer and clears its error`, `keeps typed tags...`, `does not add a new note to a tag-filtered list it does not belong to`, `matches the tag filter regardless of case for a new note`, `parseTags` duplicate spelling | same | Pass |
| Pin/archive error paths (404/409/412/500/non-API) and busy state | new `drops a note that was deleted elsewhere when pinning it (404)`, `reloads after a conflict (409) as well`, `shows a server error without reloading...`, `shows a generic message for an unexpected (non-API) failure`, `disables the actions of a note while its change runs and sends it only once` | same | Pass |
| Service: paging, errors, If-Match, silent errors | existing service tests; new `follows the cursor across three pages...`, `fails as a whole when a later page fails...`, `rejects a change of a note deleted elsewhere with a 404 ApiError`, `passes a failed create on with its problem detail` | same | Pass |
| Loading / load failure / retry | existing `offers to try again...`; new `shows a loading state until the notes arrive` | same | Pass |
| `cd web && npm run check` passes | whole web suite, lint, api check, e2e typecheck, build | `cd web && npm run check` | Pass (lint clean, 82 files / 1500 tests, build OK) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts'`

Result:

Pass - 3 files, 66 tests (one new test first failed because of a wrong expectation in the test: two pinned notes are ordered by `updatedAt`; the test was corrected, production behaviour matched the spec).

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (exit 0): `api:check`, `ng lint --max-warnings=0` (all files pass), `e2e:typecheck`, 82 test files / 1500 tests passed, `ng build` succeeded.

## .taskcheck

Result:

Pass - `sh scripts/check.sh` exit 0 (tfp gate tests, server `gradlew check`, web check; Android SKIPPED by the machine's `COCKPIT_NO_ANDROID=1`).

## Bugs found

None that violate the acceptance criteria.

Observation for the parent (not a confirmed defect, no failing test added because the expected behaviour is not specified): if a note is created while the list is reloading (e.g. a tag filter was just changed, so `notes` is `null`), `create()` cannot insert it (`notes-page.ts:150`, `notes ? [note, ...notes] : notes`) and the reload that started before the POST may not contain it; the note is saved and "Note saved" is shown, but it may be missing from the list until the next reload. Low impact; the parent may decide whether to record an assumption or reload after create.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not separately measured; all scoped files except `notes.routes.ts` were added by the task, so every executable line in them is a changed line (lines 273/273 in scope); `notes.routes.ts` changed lines are within its 2/2 covered lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-notes-list`; base 37a3c6cc5f3abd1b7bf6a1488e2fec43fce3c1e8; HEAD 07ddc501b115482e7d1ef4995e20d8b910001ea1; commits 7fc1d346, 07ddc501 supplied by the parent and confirmed by `git diff --name-only 37a3c6cc..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): SHA-256 of the per-file SHA-256 list of `web/src/app/features/notes/list/*.ts`, `*.html` and `notes/notes.routes.ts` (production + the 3 modified specs) = `7a5db104d2e65af6393ddfdf4d0f1325f87ed1cc2569c0310a972d1463dbfa2c`, identical before and after the run. Uncommitted: only the 3 spec files listed above.
- Exact included files: `web/src/app/features/notes/list/notes-filter.ts`, `notes.service.ts`, `notes-page.ts`, `notes-page.html`, `web/src/app/features/notes/notes.routes.ts`. Excluded: `notes-page.scss` (styles, not executable), `*.spec.ts` and `app.spec.ts` (tests). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` (node v24.21.0), `npm run coverage`, `web/`, full web suite.
- Run start/completion time and exit status: 2026-10-21T03:15:57Z - 03:16:14Z, exit 0 (82 files, 1500 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 a3b2359b84bdf41d7a3f38f975324d1353a607c39dbf19cf4e3f0b8a1f1204f6), `web/coverage/web/coverage-summary.json` (sha256 fc94d7910df852226998085d91e73cfcbd4f326882829810971c056ed48d662f), `lcov.info`, `lcov-report/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes, files rewritten by this run (mtime 05:16 local), snapshot hash unchanged.
- Final run after latest test additions and production fixes: yes (no production changes in this run).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/notes/list/notes-filter.ts` | 25/25 (100%) | 17/17 (100%) | 10/10 (100%) | 20/20 (100%) |
| `web/src/app/features/notes/list/notes.service.ts` | 18/18 (100%) | 9/9 (100%) | 7/7 (100%) | 15/15 (100%) |
| `web/src/app/features/notes/list/notes-page.ts` | 129/130 (99.2%) | 65/72 (90.3%) | 42/42 (100%) | 105/105 (100%) |
| `web/src/app/features/notes/list/notes-page.html` | 209/209 (100%) | 27/28 (96.4%) | 18/18 (100%) | 131/131 (100%) |
| `web/src/app/features/notes/notes.routes.ts` | 2/2 (100%) | N/A (0/0) | 1/1 (100%) | 2/2 (100%) |
| **Aggregate (5 files, summed counters; scss excluded, none missing)** | 383/384 (99.7%) | 118/126 (93.7%) | 78/78 (100%) | 273/273 (100%) |

Before the new tests (focused run, same files): `notes-page.ts` branches 76.4%, `notes-page.html` functions 61.1%.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `notes-page.ts:226` (statement + if-true) | second pin/archive of a busy note | Unreachable from the UI: the buttons are disabled while busy; covered by `disables the actions of a note while its change runs and sends it only once` |
| `notes-page.ts:123#1` | composer opened with draft tags already set | Unreachable: closing the composer always clears the draft; filter change with an open composer tested (`keeps typed tags...`) |
| `notes-page.ts:150#1` | note created while the list is reloading | See observation in "Bugs found"; expected behaviour unspecified |
| `notes-page.ts:64#1, 172#1, 221#1, 233#1` | `?? []`/`?? null` fallbacks when `notes` is null during a reload | Defensive null handling; the archive-view switch race is tested (`does not put a note into the archived view...`); no further test added |
| `notes-page.html:160#1` | template `@if` fallthrough branch | Compiler-generated branch; both visible states (with and without pinned group) are tested |

## Unable to test

- Masonry visual layout (CSS columns in `notes-page.scss`): jsdom has no layout; only the `list` class toggle is verified. Needs e2e/visual check if required.

## Summary

Tests added and all validation passed.
