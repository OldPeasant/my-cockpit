# Test Report

Date: 2026-10-21

Task: web-settings-gtd (Settings: contexts and areas)

Scope: second test-writer run, on the final code after the fixes (TW-1, CR-1..3). The diff is `git diff 05c06f7510a27ce4f64d0b6a5fc0a45729bf823d..4dcbc19af9c40138ca6dcae409eee1c578a69cd2`. 05c06f75 is the merge-base with origin/main. The original task base was 3aa4bbbd3a9abc422a576d0cf520bbcdebc6fb81. Task commits: 8ff8cda2 (implementation), a08903ed (tests and reports), 407b1b96 (fixes), ca789d37 (follow-up task file only) and 4dcbc19a (tests from an earlier, interrupted second test-writer run). Branch `main`. The only uncommitted changes are the extended `gtd-settings.spec.ts` and this report. This report replaces the earlier one, which was stale: it still listed TW-1 as failing.

Requirements:
- `tasks/web-settings-gtd.md` acceptance criteria 1 and 2, plus the recorded assumptions (delete options, sequential client-side reassignment, CR-1 hint)
- SPEC.md §5.1 (contexts: name, optional color, position; areas: title, position) and §4.2 (If-Match, Problem Details)
- `reviews/web-settings-gtd/fixes.md` (TW-1, CR-1, CR-2, CR-3)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md

## Test files changed

- `web/src/app/features/settings/gtd/gtd-settings.spec.ts` (3 tests added in this run; no existing test changed)

Committed earlier in this task (reviewed here, not changed): `gtd-settings.service.spec.ts`, `delete-dialog.spec.ts`, and the rest of `gtd-settings.spec.ts`.

## Tests added or updated

Count: 3 added in this run. The settings suite went from 167 to 170 tests.

- `color > keeps the old color and version and shows the error when saving a color fails`: a failed color PATCH (400) shows the error, the swatch keeps its old color, nothing reloads, and the next change still sends `If-Match: "1"` and clears the error. This branch had no coverage before (`setColor`, `if (saved)` false).
- `color > reloads the list when the color change finds the context changed elsewhere`: a 412 on a color change reloads the contexts and shows the "changed elsewhere" message.
- `delete edge cases > starts one delete only for a double click before the buttons are disabled`: two clicks before change detection runs start a single assigned-task lookup and open a single dialog. This covers the double-submit guard in `remove()`, which had no coverage before.

## Requirement coverage

F = `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` (8 files, 170 tests, Pass). The same tests also ran in `npm run check`, `npm run coverage` and `sh scripts/check.sh`, all Pass.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Create a context (with or without a color, trimmed name) and an area | `creates a context with a color and renames an area`, `creates a context without a color`, `creates an area with a trimmed title and rejects a blank one`, service `creates and renames with If-Match`, `creates an area` | F | Pass |
| AC1 Create errors: blank name rejected, 409 duplicate shown, input kept | `does not send a blank context name and shows the field error`, `keeps the input and shows the error when the name already exists`, `keeps the area title and shows the error when creating fails`, `shows a generic message for an error that is not an API error`, service `rejects a duplicate context name with the 409 problem, without a toast` | F | Pass |
| AC1 Rename contexts and areas: save; blank, unchanged or Escape restores; Enter finishes; failure restores; 404 reloads | `saves a new context name`, `puts the old name back for a blank or unchanged name without saving`, `puts the old name back on Escape`, `finishes editing a name with Enter`, `puts the old name back and shows the error when saving fails`, `puts the old area title back and shows the error when saving fails`, `reloads the list when the context was deleted elsewhere` | F | Pass |
| AC1 Context color: set, clear (`null`), unchanged not saved, `#rgb` shown as `#rrggbb`, failure keeps the old color and version, 412 reloads | `sets a color picked for a context`, `clears the color with null`, `does not save the color a context already has`, `shows a short #rgb color in the picker as #rrggbb`, **new** `keeps the old color and version and shows the error when saving a color fails`, **new** `reloads the list when the color change finds the context changed elsewhere` | F | Pass |
| AC1/AC2 Reorder with the buttons and drag/drop; a failure restores the order; a second drop is ignored while saving | `reorder` block (5 tests), `restores the old context order when saving fails`, `ignores a second drop while the first order is being saved`, service `reorders contexts and areas with every id in the new order` | F | Pass |
| TW-1/CR-2 A failed reorder keeps a rename, color or title saved meanwhile (new version), and a list reloaded meanwhile (old order, new items last) | `keeps a rename saved while a failing reorder was pending`, `keeps a color change and its new version when a reorder fails meanwhile`, `keeps an area title and its new version when an area reorder fails meanwhile`, `keeps a list reloaded during a failing reorder, in the old order with new items last` | F | Pass (TW-1 fixed) |
| AC1/AC2 Delete asks what happens to assigned tasks or projects: counts, clear or move, no move without another target, cancel | `delete` block (5 tests), `moves the projects to another area when chosen, then deletes`, `offers no move when no other area exists`, `delete-dialog.spec.ts` (10 tests), service `gives the tasks the replacement context before deleting, without duplicates`, `moves the projects to the target area before deleting`, `deletes a context without touching its tasks when they just lose it`, `deletes an area without listing its projects when they just lose it`, `collects open and done tasks of a context over all pages`, `collects the projects of an area over all pages` | F | Pass |
| CR-3 A failed reassignment blames the tasks or projects, deletes nothing and reloads nothing; a failed DELETE itself reloads; non-API errors show the generic message | `keeps the context, does not delete it and blames the tasks when reassigning one fails`, `blames the projects when moving one fails because it was deleted elsewhere`, `shows the error of the move step itself when it is not a conflict`, `reloads the list when the delete itself finds the context changed elsewhere`, `keeps the area and shows the error when the delete itself fails`, `shows the generic message when moving fails with an error that is not an API error`, service `stops before deleting when moving an item fails, and does not toast`, `rejects with a ReassignError when the projects to move cannot be listed`, `rejects with the plain API error when deleting an area without a move fails`, `includes done tasks when replacing a context, and stops when one update fails`, `rejects with the plain API error when the delete itself fails after the move` | F | Pass |
| CR-1 mitigation: the dialog says that tickler/Trash tasks and trashed projects only lose the context or area when moving | `says that tasks in the tickler or the Trash only lose the context when moving`, `says that projects in the Trash are left without an area when moving` | F | Pass |
| Delete concurrency: list busy while deleting, other list usable, lookup failure opens no dialog, double click starts one delete | `waits with the delete buttons of a list while one of its deletes is running`, `shows the error and opens no dialog when the assigned tasks cannot be listed`, **new** `starts one delete only for a double click before the buttons are disabled` | F | Pass |
| Load: loading note, empty note, error and retry for each list | `shows a loading note until the lists arrive`, `shows an empty note for each list without items`, `shows the load error of one list and loads it again on retry`, `shows the area load error with its own retry` | F | Pass |
| Route `/settings/gtd` | `settings.routes.spec.ts` `opens Tasks & projects at /settings/gtd` | F | Pass |
| AC2 `cd web && npm run check` passes | whole web suite | `cd web && npm run check` | Pass (exit 0: api:check, lint "All files pass linting.", e2e:typecheck, 74 files / 1360 tests, build) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'`

Result:

Pass: 8 files, 170 tests passed (167 before this run).

## Full test execution

Command:

`cd web && npm run check` (2026-10-21T03:51:34+02:00 to 03:52:24, exit 0), then `cd web && npm run coverage` (full suite, exit 0)

Result:

Pass. 74 test files and 1360 tests passed. Lint is clean and the build completed. The build prints the existing warning "bundle initial exceeded maximum budget" (647.31 kB against 500 kB). It is only a warning and does not fail the check. It is not specific to this task.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ran from 2026-10-21T03:53:34+02:00 to 03:55:20 with exit 0. All parts ran: TFP gate tests, server ktlint and tests, web eslint, tests and build (74 files / 1360 tests), and android ktlint, unit tests and lint. It ended with "== all checks passed".

## Bugs found

None in this run. TW-1 from the first run is fixed: its test `keeps a rename saved while a failing reorder was pending` passes unchanged, together with three related tests.

Observation (not a defect, for information): the server's reorder bumps the version of every context or area whose position changes (`ContextService.order`). A rename typed while a reorder is pending therefore sends the old `If-Match` and gets a 412 if the server handles the reorder first. The component then shows "changed elsewhere" and reloads, which is consistent with SPEC §4.2. No data is lost.

## Coverage

Status: verified. The run is fresh, exited 0, ran the full suite, and measured all scoped files.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with a separate tool. Four of the five measured files are new in this task (`git diff` shows them as added), so whole-file coverage equals changed-line coverage for them. The only modified file with behavior, `settings.routes.ts`, is fully covered (6/6 lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-settings-gtd`. Base 05c06f7510a27ce4f64d0b6a5fc0a45729bf823d (merge-base; original base 3aa4bbbd3a9abc422a576d0cf520bbcdebc6fb81). Measured HEAD 4dcbc19af9c40138ca6dcae409eee1c578a69cd2. Task-owned commits 8ff8cda2, a08903ed, 407b1b96, 4dcbc19a, as given by the parent. File scope: `git diff --name-only 05c06f75..HEAD -- web/src`, minus spec files.
- Source/test snapshot identifier (including uncommitted inputs): sha256 `delete-dialog.ts` d2c83049e4b66af4…, `gtd-settings.ts` 5fa54c72a8eac1b0…, `gtd-settings.html` 571f50c0cf0e487c…, `gtd-settings.service.ts` dc3085959436f235…, `settings.routes.ts` afc79a472d4cf1d3…, `gtd-settings.scss` 4cbc028c672c6339…. Specs: `gtd-settings.spec.ts` 503a87a0145a1101… (uncommitted, includes the 3 new tests), `gtd-settings.service.spec.ts` a6945ef4ceba497d…, `delete-dialog.spec.ts` 3410ab19ef99c8a5…. Production files are identical to HEAD; the only uncommitted input is `gtd-settings.spec.ts`.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/settings/gtd/delete-dialog.ts`, `gtd-settings.ts`, `gtd-settings.html`, `gtd-settings.service.ts` and `web/src/app/features/settings/settings.routes.ts`. Excluded: `gtd-settings.scss` (styles only, no executable code) and `security-settings.html` (formatting-only attribute line breaks with no behavior change). Spec files are excluded. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` (node 24.21.0). Command `npm run coverage` in `web/`, which runs the full suite with the reporters text-summary, json-summary, json and lcov. No configuration was changed.
- Run start/completion time and exit status: 2026-10-21T03:52:32+02:00 to 03:53:08+02:00, exit 0. The summary on stdout reported the full suite (7156/7816 statements repository-wide; not used for this task).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 bf474fc4daeff1fa5248fe7a33e2d78b5da06717ff810d232967d1aa7bd22df5) and `web/coverage/web/coverage-final.json` (sha256 e6f5b41402fd6fd2e1a0394d24ebf7f29a6ace4369cc6eaec9758c37116cf107), both written at 03:53:08.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/web/` during this run, and the file timestamps match the end of the run. Source and spec hashes were taken before `npm run check` and compared after the coverage run: they matched.
- Final run after latest test additions and production fixes: yes. The run includes all 3 new tests and the fix commit 407b1b96, and no production code changed afterwards.

Values below use covered/total (percentage), from provider-native counters in `coverage-summary.json`. Zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/settings/gtd/gtd-settings.ts` | 158/158 (100%) | 94/101 (93.07%) | 58/58 (100%) | 128/128 (100%) |
| `web/src/app/features/settings/gtd/gtd-settings.html` | 220/220 (100%) | 30/30 (100%) | 21/21 (100%) | 143/143 (100%) |
| `web/src/app/features/settings/gtd/gtd-settings.service.ts` | 49/49 (100%) | 15/15 (100%) | 19/19 (100%) | 46/46 (100%) |
| `web/src/app/features/settings/gtd/delete-dialog.ts` | 93/93 (100%) | 31/32 (96.88%) | 5/5 (100%) | 60/60 (100%) |
| `web/src/app/features/settings/settings.routes.ts` | 6/6 (100%) | N/A (0/0) | 5/5 (100%) | 6/6 (100%) |
| **Aggregate (summed counters, 5 files)** | 526/526 (100%) | 170/178 (95.51%) | 108/108 (100%) | 383/383 (100%) |

The aggregate excludes `gtd-settings.scss` (styles) and `security-settings.html` (formatting only). No scoped file is missing from the report. Before this run's tests, `gtd-settings.ts` was at 157/158 statements and 92/101 branches (focused diagnostic run at 03:49, same provider). The new tests covered the `remove()` busy guard (line 233) and the failure branch of `setColor` (line 145).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `gtd-settings.ts:101, 114` (`all ?? []` in `addContext` / `addArea`) | A create finishing while the list is still `null` | Defensive only. The add forms render only after their list has loaded, and a reload never sets the list back to `null`. Justified gap. |
| `gtd-settings.ts:236, 254, 303, 310` (`this.list(kind) ?? []` in `remove`, `restoreOrder`, `replace`) | The list being `null` during a delete, a reorder restore or a replace | Defensive only, for the same reason: these actions start from rendered rows of a loaded list. Justified gap. |
| `gtd-settings.ts:47`, `delete-dialog.ts:159` (`if` at the decorated class declaration) | Compiler-emitted decorator branch | Not behavior. Justified gap. |

## Unable to test

- Real drag gestures with the CDK pointer (mouse or touch) were not tested. The tests fire `cdkDropListDropped` directly, as the existing tests do, and the gesture itself belongs to the CDK library. No follow-up is needed unless an E2E task asks for it.
- Visual layout and SCSS (swatch rendering, spacing) were not tested, because jsdom does no layout. The tests check the swatch CSS variable and the picker value instead.
- Tasks in the tickler or Trash, and trashed projects, cannot be moved by the client (CR-1). This is a server limitation. The dialog hint is tested; the server-side fix is follow-up task `gtd-delete-reassign`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. This run added 3 tests: a failed or stale color change, and the double-click guard on delete. TW-1 is fixed and its test passes. The focused settings suite passes (170/170), as do `npm run check` (1360 tests) and `.taskcheck`. Task-scoped coverage: 526/526 statements, 170/178 branches, 108/108 functions and 383/383 lines. The 8 uncovered branches are defensive null fallbacks or compiler-emitted.
