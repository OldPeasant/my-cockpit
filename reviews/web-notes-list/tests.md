# Test Report

Date: 2026-10-21

Task: web-notes-list - Notes: list and organization (second pass, final code after fixes CR-2/3/4 and the `NoteCard` extraction)

Scope: base 61709e7c22e55f81443a4f5a6b6bac646098d079 .. HEAD 0214f8659dbb5b4c37e737bbe1d7b915ce147315 on `main` (task commits e5d6a4fe, 248c986a, bdd9f206, 0214f865, all prefixed `web-notes-list:`; supplied by the parent worker and confirmed with `git log --oneline 61709e7c..HEAD` / `git diff --stat 61709e7c..HEAD`), plus the uncommitted new test file from this run.

Requirements:
- `tasks/web-notes-list.md` acceptance criteria: (1) masonry grid/list toggle, pinned first, tag filter, archived toggle, search within notes, create note; (2) Vitest tests cover filtering and pin ordering; `cd web && npm run check` passes
- Task assumptions recorded by the parent (client-side search, inline composer, view stored in `cockpit.notes.view`, composer hidden in the archived view)
- `SPEC.md` §8.1, `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`, `reviews/web-notes-list/fixes.md`
- Existing conventions: `web/src/app/features/notes/list/*.spec.ts`, `web/src/app/features/gtd/ui/task-row.spec.ts` (`componentRef.setInput` for presentational components)

## Test files changed

- `web/src/app/features/notes/list/note-card.spec.ts` (new, uncommitted)

The other notes specs (`notes-page.spec.ts`, `notes-filter.spec.ts`, `notes.service.spec.ts`) were committed earlier (first pass, fixer) and are unchanged in this pass.

## Tests added or updated

Count: 8 added (all in `note-card.spec.ts`); 0 existing tests changed. Notes specs: 71 -> 79 tests in 4 files.

The new spec tests the extracted `NoteCard` component on its own: title/excerpt/tags/date/editor link, absence of text and tag list, pin/unpin and archive/unarchive labels and `aria-pressed`, label update on input change, `pin`/`archive` outputs, busy disabling (no emits), and the `compact` host class for the list view.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Masonry grid/list toggle | page: `switches between grid and list and remembers the choice`, `starts in list view when the list view was chosen before`, `falls back to the grid for an unknown stored view`, `works with the grid and still switches views when storage is unavailable`; card: `is enabled and not compact by default, compact for the list view` | `cd web && npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts'` | Pass |
| Pinned first | page: `shows the pinned notes first under their own heading...`, `moves a note to the top when it is pinned and back...`, `keeps a pinned note above a more recently changed unpinned one`; filter: `sortNotes` tests; card: `labels the buttons to pin and archive an unpinned, active note`, `labels the buttons to unpin and unarchive a pinned, archived note`, `emits pin and archive when their buttons are pressed` | same | Pass |
| Tag filter | page: `filters by tag on the server...`, `shows the newest tag filter when an older response arrives last`, `ignores the failure of an outdated load`, `says when no note has the chosen tag`, `still loads the notes when the tags cannot be loaded`; card: `shows the title, plain-text excerpt, tags and last change, linking to the editor` | same | Pass |
| Archived toggle | page: `shows the archived notes and moves a note back`, `archives a note out of the list`, `does not reload when the active view is chosen again`, `does not put a note into the archived view when its pin finishes after switching`; card: unarchive label test | same | Pass |
| Search within notes | page search tests, `searches across pinned and other notes and keeps pinned first`; filter: `matchesQuery`/`filterNotes`/`excerpt` tests incl. CR-4 `leaves out the line the title is derived from when the text starts with an image or link`, `treats a blank title as no title` | same | Pass |
| Create note | page composer tests incl. CR-3 `cannot cancel or escape while a note is saved, so a failed save keeps the draft`, CR-2 `shows a note created while the list reloads by loading it again`, `sends a note only once when saved twice quickly` | same | Pass |
| Pin/archive busy state and errors | page: `disables the actions of a note while its change runs and sends it only once`, 404/409/412/500 tests; card: `disables both buttons and emits nothing while busy` | same | Pass |
| Vitest tests cover filtering and pin ordering | all of the above (`notes-filter.spec.ts`, `notes-page.spec.ts`) | same | Pass (4 files, 79 tests) |
| `cd web && npm run check` passes | api check, lint, e2e typecheck, full web suite, build | `cd web && npm run check` | Pass (exit 0) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts'`

Result:

Pass - 4 files, 79 tests (all new tests passed on first run).

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (exit 0): "All files pass linting", 83 test files / 1513 tests passed, `ng build` succeeded. The only build warning is the pre-existing initial bundle budget (650.24 kB vs 500 kB); there is no longer a `notes-page.scss` style budget warning after the card extraction.

## .taskcheck

Result:

Pass - `sh scripts/check.sh` exit 0 ("all checks passed": tfp gate tests, server `gradlew check`, web check with 83 files / 1513 tests; Android SKIPPED by the machine's `COCKPIT_NO_ANDROID=1`).

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not separately measured; every scoped file except `notes.routes.ts` was added by the task, so all executable lines in them are changed lines (lines 291/291); the changed lines of `notes.routes.ts` lie within its 2/2 covered lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-notes-list`; base 61709e7c22e55f81443a4f5a6b6bac646098d079; HEAD 0214f8659dbb5b4c37e737bbe1d7b915ce147315; commits e5d6a4fe, 248c986a, bdd9f206, 0214f865 supplied by the parent and confirmed with `git diff --stat 61709e7c..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): SHA-256 of the per-file SHA-256 list of `web/src/app/features/notes/list/*.ts`, `*.html`, `*.scss` and `web/src/app/features/notes/notes.routes.ts` (production + specs, incl. the uncommitted `note-card.spec.ts`, sha256 433565b26280473fcf4aa7837e3e28de5eed22e2477fd685b09abbb7be3f8e41) = `db3d9c5dc9186f7afc573d722999818b694239d246a989966bb393fe33b56399`, identical before and after the coverage run.
- Exact included files: `web/src/app/features/notes/list/note-card.ts`, `notes-filter.ts`, `notes.service.ts`, `notes-page.ts`, `notes-page.html`, `web/src/app/features/notes/notes.routes.ts`. Excluded: `note-card.scss`, `notes-page.scss` (styles, not executable); `*.spec.ts`, `web/src/app/app.spec.ts` (tests); `reviews/` (docs). No renamed or deleted files; no scoped file missing from the report.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` ^5.0.5 (node v24.21.0), `npm run coverage` (json-summary/json/lcov/text-summary reporters), `web/`, full web suite.
- Run start/completion time and exit status: 2026-10-21T03:27:36Z - 03:27:53Z, exit 0 (83 files, 1513 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 204ab29dc6207d8f5c5252ec28631dd5dc3423cb446c8554874fb6de3f6c2154), `web/coverage/web/coverage-summary.json` (sha256 55719d92caf0608eee9f8279178557b6013cde2992d1f32401462d5f033c4a43), `lcov.info`, `lcov-report/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes; `web/coverage/web` was removed before the run and regenerated by it (mtime 05:27 local), snapshot hash unchanged.
- Final run after latest test additions and production fixes: yes (measures HEAD 0214f865 including fixes CR-2/3/4 and the `NoteCard` extraction, plus the new card spec).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/notes/list/note-card.ts` | 62/62 (100%) | 20/20 (100%) | 6/6 (100%) | 41/41 (100%) |
| `web/src/app/features/notes/list/notes-filter.ts` | 25/25 (100%) | 17/17 (100%) | 10/10 (100%) | 20/20 (100%) |
| `web/src/app/features/notes/list/notes.service.ts` | 18/18 (100%) | 9/9 (100%) | 7/7 (100%) | 15/15 (100%) |
| `web/src/app/features/notes/list/notes-page.ts` | 130/131 (99.2%) | 65/72 (90.3%) | 40/40 (100%) | 105/105 (100%) |
| `web/src/app/features/notes/list/notes-page.html` | 178/178 (100%) | 21/22 (95.5%) | 18/18 (100%) | 110/110 (100%) |
| `web/src/app/features/notes/notes.routes.ts` | 2/2 (100%) | N/A (0/0) | 1/1 (100%) | 2/2 (100%) |
| **Aggregate (6 files, summed counters; 2 scss files excluded, none missing)** | 415/416 (99.8%) | 132/140 (94.3%) | 82/82 (100%) | 293/293 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `notes-page.ts:219` (statement + if-true) | second pin/archive of a note already busy | Unreachable from the UI: the card disables both buttons while busy; covered by page test `disables the actions of a note while its change runs and sends it only once` and card test `disables both buttons and emits nothing while busy` |
| `notes-page.ts:124#1` | composer opened with draft tags already set | Unreachable: closing/saving always resets the draft (`resetComposer`) |
| `notes-page.ts:160#1` | `notes ? ... : notes` inside `update` | Unreachable: guarded by `this.notes() === null` just above (CR-2 fix); the null case is tested by `shows a note created while the list reloads by loading it again` |
| `notes-page.ts:64#1, 183#1, 214#1, 226#1` | `?? []` / `?? null` fallbacks when `notes` is null during a reload | Defensive null handling; the archive-view switch race is tested (`does not put a note into the archived view when its pin finishes after switching`); no further test added |
| `notes-page.html:167#1` | compiler-generated conditional for the others-group `aria-labelledby` label | Both labels are rendered in tests (with and without a pinned group); remaining branch is a generated-code mapping artifact |

## Unable to test

- Masonry visual layout (CSS columns in `notes-page.scss` / `note-card.scss`): jsdom has no layout; only the `list` class on the cards and the `compact` host class are verified. Needs an e2e/visual check if a human wants it confirmed; not required by the criteria' "Vitest tests" wording.

## Summary

Tests added and all validation passed.
