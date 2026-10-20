# Test Report

Date: 2026-10-20

Task: web-links-panel - Linked items panel (shared)

Scope: base `2b4cd20bd437e180065cdc600de408452acd2401` .. HEAD `8569b14fab8e4f3c46a4ccd0df894a8d034a4a49` (single task commit `8569b14f`, branch `main`), plus the uncommitted test changes listed below. Task ownership: the task commit supplied by the parent worker; its diff touches only `web/src/app/shared/links/`, which matches the task's `touches`.

Requirements:
- `tasks/web-links-panel.md` acceptance criteria and recorded assumptions (SYNC links show "In text"; create-linked slot via `appLinkedCreate` + public `reload()`)
- `SPEC.md` §4.3 (links), §4.4 (search), as referenced by the task and the code
- `docs/ARCHITECTURE.md` "Coverage"
- Parent instructions (tests only inside `web/src/app/shared/links`, no edit of `messages.xlf`)

## Test files changed

- `web/src/app/shared/links/linked-items.spec.ts` (extended; the existing `type()` helper now waits for change detection before the debounce wait, which removed a timing flake seen under full-suite load; no assertion was weakened)
- `web/src/app/shared/links/item-type.spec.ts` (new)

`web/src/app/shared/links/links.service.spec.ts` was reviewed and left unchanged (it already covers all four service calls, silent errors and the search limit).

## Tests added or updated

Count: 42 added (28 in `linked-items.spec.ts`, 14 in `item-type.spec.ts`), 1 helper updated. Focused suite now: 59 tests (40 + 14 + 5).

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/shared/links/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 lists links with icon, title, subtitle, deep link, both directions | `lists the other end of links in both directions...`, `shows "Searching…"...` (subtitles), `ItemTypeIcon` / `itemTypeLabel` tests (all 11 types) | F | Pass |
| AC1 unavailable items greyed out | `greys out unavailable items without a deep link, but keeps them removable` | F | Pass |
| AC1 loading / empty / load error + retry | `shows a loading hint and no count until the links arrive`, `shows an empty state`, `shows a load error with a retry` | F | Pass |
| AC1 list follows the item; stale responses ignored | `loads the links again when the item changes`, `ignores a list response for the previous item...`, `ignores a failure of the previous item load...` | F | Pass |
| AC1 list not overwritten by an older load of the same item | `keeps the newer links when an older load of the same item answers last` | F | **Fail** (TW-1) |
| AC1 remove link | `removes a link`, `shows the error when removing fails and keeps the link`, `disables the remove button while the removal runs`, `drops a link that was already removed elsewhere (404)...`, `shows an inline (SYNC) link without a remove button` | F | Pass |
| AC1 focus after removal (accessibility) | `moves the focus to the next row...`, `...previous row after removing the last row`, `...heading after removing the only link`, `leaves the focus alone when the user moved it elsewhere...` | F | Pass |
| AC2 search as you type over all types (search API) | `searches as you type and links the chosen item`, `shows "Searching…" while pending...`, `does not search for a blank query`, `says nothing matches when every hit is filtered out`; service: `searches all types with the link search limit` | F | Pass |
| AC2 search errors and request ordering | `shows the search error and recovers with the next query`, `shows a generic message when the search fails unexpectedly`, `ignores an older search that answers after a newer query`, `ignores a failing older search...`, `shows no results when the query is cleared while a search is pending` | F | Pass |
| AC2 creates the link (click, keyboard) | `searches as you type and links the chosen item`, `links the active result with the keyboard`, `links a result chosen with Enter on the option itself`, `moves through the results with the arrow keys, wrapping around`, `ignores Enter and arrows when there are no results`, `closes the search with Escape`, `closes the search with Cancel...` | F | Pass |
| AC2 create errors, double submit, 409, item change | `creates the link only once when a result is chosen twice`, `keeps the search open and shows the error when linking fails`, `reloads the list when the link already exists (409)`, `does not show a link created for the previous item after the item changed`, `clears an earlier action error when adding starts` | F | Pass |
| AC2 "create linked…" slot | `shows the create-linked slot and reloads on request`, `LinkedItems without create-linked actions > hides the "Create linked" row` | F | Pass |
| AC3 Vitest tests cover listing, adding via search, removal | all of the above | F | Pass except TW-1 |
| AC3 `cd web && npm run check` passes | whole web check | `cd web && npm run check` | **Fail**: lint and e2e typecheck pass; `test:ci` fails on TW-1 (417 passed, 1 failed), so `ng build` is not reached |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/shared/links/**/*.spec.ts'`

Result:

Fail: 58 passed, 1 failed (`keeps the newer links when an older load of the same item answers last`, TW-1). Prettier `--check` and ESLint on `src/app/shared/links/` pass.

## Full test execution

Command:

`cd web && npm run check` (and `cd web && npm run coverage`, which runs the full web suite)

Result:

Fail: 27 files, 418 tests, 417 passed, 1 failed (TW-1 only). No other test in the web suite fails.

## .taskcheck

Result:

Fail: `sh scripts/check.sh` fails in the web part because of TW-1 (server ktlint + tests ran before it). It finished well inside the time limit.

## Bugs found

### An older list response overwrites a newer one for the same item

Finding ID:

`TW-1`

Test:

`LinkedItems > loading > keeps the newer links when an older load of the same item answers last` (`web/src/app/shared/links/linked-items.spec.ts:434`)

Production source:

`web/src/app/shared/links/linked-items.ts:270-279` (`load()`)

Requirement:

AC1 "Panel lists links of an item"; the ordering rule that results of older requests must not replace newer state.

Expected behavior:

When `reload()` (called by a feature after a "create linked…" action, by the 409 path of "Add link", or by "Try again") starts while an earlier load of the same item is still pending, the panel shows the result of the latest load, whatever order the responses arrive in.

Actual behavior:

`load()` only checks that the item is still the same (`sameItem`). If the older request answers last, its result replaces the newer one: the test shows `['Holiday ideas']` instead of `['Holiday ideas', 'Flight to Lisbon']`, so a just-created link vanishes from the panel until the next reload. The same applies to an older failing load, which would replace a fresh list with the load error. A fix could tag each load with a sequence number and ignore results from loads that are no longer the latest.

Severity:

Low. It needs overlapping loads of the same item, but then it silently hides a link that exists.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The official command `cd web && npm run coverage` exits 2 (TW-1 fails) and Vitest writes no coverage report on a failed run (`web/coverage/` did not exist afterwards). The numbers below come from a diagnostic run of the full web suite that deselects only the TW-1 test with `--filter`. They are diagnostic only and not verified task coverage. Rerun `npm run coverage` after the fix.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every production file in scope was added by this task (diff base..HEAD shows only added files), so whole-file coverage equals changed-line coverage for this scope.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-links-panel`; base `2b4cd20bd437e180065cdc600de408452acd2401`; HEAD `8569b14fab8e4f3c46a4ccd0df894a8d034a4a49`; task commit `8569b14f` as supplied by the parent; scope from `git diff --stat 2b4cd20b..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `8569b14f` plus uncommitted test files (sha256 prefixes): `linked-items.spec.ts` `05ccb68a1882`, `item-type.spec.ts` `7d9b100035ba`. Production files: `linked-items.ts` `a9bce5241232`, `linked-items.html` `c009a5f48768`, `item-type.ts` `12e270a12869`, `links.service.ts` `847e18649149`, `linked-create.ts` `7a394c74f31e`, `index.ts` `a5f1d7ef0641`. Hashes were the same before and after the runs.
- Exact included files: `web/src/app/shared/links/{links.service.ts, item-type.ts, linked-create.ts, linked-items.ts, linked-items.html, index.ts}`. Excluded: `linked-items.scss` (styles, no executable behavior; the provider reports 0/0), `*.spec.ts` (tests). Nothing renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5 through `@angular/build:unit-test`; cwd `web/`; `npx ng test --watch=false --coverage --coverage-include='src/app/shared/links/**' --coverage-reporters=json --coverage-reporters=json-summary --coverage-reporters=text --filter='^(?!.*keeps the newer links when an older load).*$'` (all 27 web spec files; 417 passed, 1 deselected).
- Run start/completion time and exit status: 2026-10-20 17:38:46 to 17:39:01 +02:00, exit 0. Official `npm run coverage` at 17:38:25: exit 2, no report.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 `f50194b94617a94af790b9968ec1bd9f688cc3a9c7143366171fa50b7073b740`; `web/coverage/web/coverage-summary.json` sha256 `5754dd2ae4836b62ceae607c4305965f8a086fed5c44e9ed22373e2b16ba2e97` (both written 17:39:01, i.e. by this run; the directory did not exist before it).
- Evidence outputs are fresh and inputs stayed unchanged: yes (directory created by the run, source hashes compared before and after).
- Final run after latest test additions and production fixes: yes for test additions. No production fix exists yet (TW-1 open), so this describes the pre-fix revision `8569b14f`.

Values below use covered/total (percentage) from the provider's `coverage-summary.json`; zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/shared/links/links.service.ts` | 14/14 (100%) | 5/5 (100%) | 6/6 (100%) | 11/11 (100%) |
| `web/src/app/shared/links/item-type.ts` | 27/27 (100%) | 24/24 (100%) | 3/3 (100%) | 18/18 (100%) |
| `web/src/app/shared/links/linked-create.ts` | 3/3 (100%) | 5/5 (100%) | N/A (0/0) | 1/1 (100%) |
| `web/src/app/shared/links/linked-items.ts` | 152/153 (99.35%) | 69/73 (94.52%) | 50/51 (98.04%) | 122/122 (100%) |
| `web/src/app/shared/links/linked-items.html` | 149/149 (100%) | 24/24 (100%) | 8/8 (100%) | 93/93 (100%) |
| `web/src/app/shared/links/index.ts` | N/A (0/0) | N/A (0/0) | N/A (0/0) | N/A (0/0) |
| **Aggregate (summed counters, the 6 files above)** | 345/346 (99.71%) | 127/131 (96.95%) | 67/68 (98.53%) | 245/245 (100%) |

Excluded next to the aggregate: `linked-items.scss` (styles). No scoped file is missing from the report. Before the new tests (existing 17 tests only, focused run), the folder was at statements 91.32%, branches 77.09%, functions 92.64%, lines 92.65% (text reporter). That number is shown for context only.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `linked-items.ts:77` (function) | Compiler-generated predicate of `contentChildren(LinkedCreate)` | Justified gap: Angular-internal, the slot behavior is tested both ways |
| `linked-items.ts:245` (`links ?? []` right side) | Create response arriving while the list is still `null` (loading) | Justified gap: defensive fallback, not reachable through the UI because the item check applies first and the search is only usable after loading |
| `linked-items.ts:272,274` (`?? []`, `?? null`) | `dropLink` while the list is `null` | Justified gap: defensive. A removal needs a rendered row, so the list is non-null |
| `linked-items.ts:282` (`?? []`) | Focus restore when the list element is absent | Justified gap: defensive. After a removal the list always renders |

## Unable to test

- Visual "greyed out" styling (opacity/colour in `linked-items.scss`): jsdom computes no styles. The test checks the `unavailable` class and the missing deep link. Follow-up only if a visual/E2E suite is wanted.
- Real deep-link navigation into feature routes: no feature route uses the panel yet (task assumption). The `href` from `routerLink` is asserted.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-1 (Low) makes the focused suite, `npm run check`, `.taskcheck` and the official coverage command fail. All other acceptance-criteria behavior passes. Task-scoped coverage is diagnostic until a fresh `npm run coverage` succeeds after the fix.
