# Test Report

Date: 2026-10-21

Task: web-mail-list — Mail list and triage

Scope: base `c87a2bd5ac5cc0bc93edc7055add70779c96f8c2` .. HEAD `11d5d088c7272347913c54484643e49cece4a353` (branch `main`, task commits 917dd57a, 4a62e908, 12351f98, ada715b6, 8fe04749, 3e69239b, 11d5d088, all "web-mail-list: ..."), excluding `tasks/` and `reviews/`. The focus is the web list feature `web/src/app/features/mail/list/` and the round-2 fixes CR-1..CR-3 (commit 11d5d088). The uncommitted inputs are the two spec files this report adds tests to (hashes under "Coverage").

Requirements:
- `tasks/web-mail-list.md` acceptance criteria and recorded assumptions
- `SPEC.md` §7.3 (via the task)
- `docs/ARCHITECTURE.md` "Coverage"
- `reviews/web-mail-list/review.md` and `fixes.md` "Round 2" (intent of CR-1..CR-3)
- Parent instructions: do not run Playwright e2e; measure coverage for `web/src/app/features/mail/list`

## Test files changed

- `web/src/app/features/mail/list/mail-list-store.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`

## Tests added or updated

Count: 4 (all new, no existing test changed)

- `MailListStore > live updates > appends a next page when the overlapping refetch failed and left the list as it was` (CR-1: only a refetch that actually replaces the list may drop a "load more" page. A failed refetch must not drop it.)
- `MailListStore > live updates > appends a next page when the overlapping refetch was dropped for a running action` (CR-1: a stale refetch that was discarded because an action ran must not bump the list generation and drop a valid next page.)
- `MailListStore > conversation actions > sends conversation ids in list order when their lookups finish in reverse order` (CR-2: `mapWithLimit` keeps results in item order when lookups resolve out of order, and single-message items mixed in do no lookup. The existing concurrency test resolved lookups in start order only.)
- `MailListPage > deletes with # typed with AltGr (Ctrl+Alt), but ignores letters typed with it` (CR-3 end to end through the page's document keydown handler: `#` with Ctrl+Alt deletes the cursor item, removes it from the list and prevents the default. Ctrl+Alt+e does nothing and is not prevented. The existing CR-3 specs only tested the pure `mailShortcutFor` function.)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: unified inbox + folder tree, threaded/flat toggle, infinite scroll, multi-select, actions, shortcuts j/k, e, #, u, v | Existing page/store/folder-tree/folders-store/move-dialog/shortcuts specs, plus the new AltGr `#` page spec and the two CR-1 "load more" specs | `cd web && npx ng test --watch=false --include='src/app/features/mail/list/**/*.spec.ts'` and `cd web && npm run check` | Pass |
| AC2: live updates via SSE | Existing store "live updates" specs and page "live updates (SSE)" specs, plus the two new CR-1 refetch/load-more overlap specs | same | Pass |
| AC3: Vitest tests cover selection, shortcuts, optimistic actions with rollback; `npm run check` passes | Existing specs and the 4 new ones. `npm run check` (api:check, lint, e2e typecheck, 91 test files / 1706 tests, build) | `cd web && npm run check` | Pass |
| AC4: Playwright `e2e/mail-list/list.spec.ts` against the e2e stack (GreenMail) | `web/e2e/mail-list/list.spec.ts` (not changed by me) | — | Not run: the parent asked me not to run the Playwright e2e suite. The parent runs it. |
| CR-1 fix (no gap after a refetch overtakes a load more) | existing `drops a next page that arrives after a refetch replaced the list, leaving no gap`; new `appends a next page when the overlapping refetch failed ...`, `... dropped for a running action` | focused command above | Pass |
| CR-2 fix (bounded thread lookups, order, stop on failure) | existing `looks up at most 6 conversations at a time`, `stops looking up conversations after a failed lookup ...`; new `sends conversation ids in list order when their lookups finish in reverse order` | focused command above | Pass |
| CR-3 fix (`#`/`!` via AltGr/Option) | existing `mailShortcutFor` specs; new page spec `deletes with # typed with AltGr (Ctrl+Alt), but ignores letters typed with it` | focused command above | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/mail/list/**/*.spec.ts'`

Result:

Pass: 7 test files, 186 tests (182 before plus the 4 new ones). Prettier check on the changed specs: "All matched files use Prettier code style!"

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass: `api:check` OK, "All files pass linting.", `tsc -p e2e/tsconfig.json` OK, 91 test files / 1706 tests passed, `ng build` complete. The existing warning that the initial bundle exceeds its budget (651.12 kB vs 500 kB) is unchanged and not caused by this task.

The server suite was not run by me. The only server change in scope is the e2e-profile hook `mail.testsupport.MailTestSupportController` with its own `MailTestSupportControllerTests`, and the parent limited this run to the web list.

## .taskcheck

Result:

Not run. Only web spec files changed, and the web part of `scripts/check.sh` (`npm run check`) passed above. The parent runs the full `.taskcheck` (its notes mention an earlier server-test timeout under machine load).

## Bugs found

None. All 4 new tests passed against the current production code on the first run.

## Coverage

Status: verified (web part of the task scope). The server hook was not measured (see exclusions).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-mail-list`; base `c87a2bd5ac5cc0bc93edc7055add70779c96f8c2` and HEAD `11d5d088c7272347913c54484643e49cece4a353`, both supplied by the parent. The file scope comes from `git diff --name-only c87a2bd5..HEAD -- web/src server/src/main`, restricted to non-spec files.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 11d5d088 plus uncommitted `mail-list-store.spec.ts` sha256 `25a743d73d1a745ad61fa56266d7a087721e843e9becd52f412bc5b6ef59feff` and `mail-list-page.spec.ts` sha256 `668a673edcb0bfdeffd96f96cc31e5de84385fda1e65feb9f0547b91af5cd7b4`. The production files have no uncommitted changes (`git diff --stat` shows only these two specs, plus `reviews/web-mail-list/review.md`, which another helper changed and which is not a test input).
- Exact included files: `web/src/app/features/mail/list/{mail-folder-tree,mail-folders-store,mail-list-page,mail-list-store,mail-move-dialog,mail-shortcuts,mail.service}.ts`, `web/src/app/features/mail/mail.routes.ts`. Excluded: `mail-testing.ts` (test doubles, not production code; it measured 20/24 statements); `mail-list-page.html`, `*.scss` (templates/styles, not instrumented by V8 coverage; the template is exercised through the page specs); `server/.../mail/testsupport/MailTestSupportController.kt` (server, e2e-profile-only test hook, outside the web coverage run the parent asked for; it has `MailTestSupportControllerTests`; JaCoCo not run). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5, Node v24.21.0; `npm run coverage` in `web/`; full web suite (91 files, 1706 tests).
- Run start/completion time and exit status: 2026-10-21T07:40:24+02:00 to 07:41:18+02:00. All tests passed and the report was generated (the summary was printed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 `9873888b5d9130865e6e83d3d12337f24456e18a923a143d32b662722c7638a8`, `web/coverage/web/coverage-summary.json` sha256 `70f666a7465892b527741b24710d600989f8aa0dc853f3ad8f981dcdd871c243`, `lcov.info`. Files are timestamped 07:41, from this run.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The spec hashes and `git status` were the same before and after the run.
- Final run after latest test additions and production fixes: yes. It ran after all 4 new tests and on HEAD 11d5d088, which includes the round-2 fixes.

Values below use covered/total (percentage) from the provider's `coverage-summary.json`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/list/mail-folder-tree.ts` | 49/49 (100%) | 21/22 (95.45%) | 3/3 (100%) | 35/35 (100%) |
| `web/src/app/features/mail/list/mail-folders-store.ts` | 62/62 (100%) | 42/43 (97.67%) | 19/19 (100%) | 52/52 (100%) |
| `web/src/app/features/mail/list/mail-list-page.ts` | 131/132 (99.24%) | 84/92 (91.30%) | 38/39 (97.43%) | 108/108 (100%) |
| `web/src/app/features/mail/list/mail-list-store.ts` | 272/272 (100%) | 132/143 (92.31%) | 67/67 (100%) | 217/217 (100%) |
| `web/src/app/features/mail/list/mail-move-dialog.ts` | 53/53 (100%) | 18/19 (94.73%) | 10/10 (100%) | 35/35 (100%) |
| `web/src/app/features/mail/list/mail-shortcuts.ts` | 17/17 (100%) | 27/27 (100%) | 4/4 (100%) | 12/12 (100%) |
| `web/src/app/features/mail/list/mail.service.ts` | 19/19 (100%) | 15/16 (93.75%) | 8/8 (100%) | 15/15 (100%) |
| `web/src/app/features/mail/mail.routes.ts` | 8/8 (100%) | 6/6 (100%) | 2/2 (100%) | 7/7 (100%) |
| **Aggregate (summed counters, 8 files above)** | 611/612 (99.84%) | 345/368 (93.75%) | 151/152 (99.34%) | 481/481 (100%) |

The aggregate leaves out `mail-testing.ts` (test helper), the template/styles (not instrumented) and the server hook `MailTestSupportController.kt` (not measured in this run).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-list-store.ts`: branches at 157, 159, 194, 208, 231, 255, 286, 337, 357 (`this.items() ?? []` null fallbacks), 359 (`CHANGED_FIELDS[action] ?? []`), 436 (`after[0]?.id ?? null` after both loops) | Defensive fallbacks. Each guard runs only when `items` is already non-null, or for actions that always have fields. | Non-material, no test added. The behavior around them (load more, refresh, rollback, cursor moves) is covered. |
| `mail-list-page.ts`: 87 (the function the `viewChild` sentinel query creates), 39 (decorator metadata branch), 65/69/76 (`?? ''`/`?? 0`/`?? null` fallbacks), 182 (`x` without a cursor), 221 (`threadUnread ?? 0`), 233 (empty `displayName` fallback), 254 (`address.address \|\| ''`) | Display fallbacks and the `x` key on an empty list | Non-material: these are defensive fallbacks or instrumentation artifacts. The empty-list behavior (actions disabled) is covered by "says so when the folder is empty and disables the actions". |
| `mail-folder-tree.ts:55`, `mail-folders-store.ts:45`, `mail-move-dialog.ts:94`, `mail.service.ts:81` (one branch each) | Fallback branches | Non-material, no test added |
| `mail-list-page.html` (infinite scroll after a dropped page, see CR-1's known limitation) | After a dropped page, the IntersectionObserver does not fire again while the sentinel stays in view. The "Load more" button and `j` remain. | Documented limitation of the CR-1 fix, not a defect against the criteria. The store-level spec shows the next load continues from the refetch cursor. |

## Unable to test

- AC4 (Playwright `e2e/mail-list/list.spec.ts` against the e2e stack with GreenMail): not run, as the parent instructed. The parent must run `cd web && npm run e2e -- e2e/mail-list/list.spec.ts` and record the result.
- Real AltGr/Option behavior in actual browsers and keyboard layouts (CR-3): jsdom only simulates the modifier flags and `getModifierState('AltGraph')`. Real-layout behavior needs a manual check or a browser test. This is optional follow-up and not required by the criteria.
- Server hook coverage (`MailTestSupportController.kt`): not measured with JaCoCo in this run. It has its own server tests, which the full `.taskcheck` runs.

## Summary

Tests added and all validation passed: 4 new specs close gaps around the round-2 fixes CR-1..CR-3, and `npm run check` passes. Task-scoped web coverage is high, and the remaining gaps are justified defensive fallbacks. I found no production defects. The Playwright e2e criterion (AC4) was not run by me and is left to the parent.
