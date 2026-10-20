# Test Report

Date: 2026-10-20

Task: web-links-panel - Linked items panel (shared)

Scope: final round. Base `c7a3abd6485be1183f42e295798f0bb3539dfc1a` .. HEAD `4e5d4d947f60fb0002db7929e6f5fdf3aaf9d931` on `main`; task commits `afe5faed` (implementation), `bf41efa0` (round-1 tests and reports), `31f1140b` (fixes CR-1/TW-1, CR-2), `4e5d4d94` (fix CR-4, round-2 tests), plus the uncommitted test change listed below. Task ownership: commit list supplied by the parent worker. `git diff --stat c7a3abd6..HEAD` touches only `web/src/app/shared/links/` and `reviews/web-links-panel/`, which matches the task's `touches`.

Requirements:
- `tasks/web-links-panel.md` acceptance criteria and recorded assumptions (SYNC links show "In text"; create-linked slot via `appLinkedCreate` + public `reload()`)
- `SPEC.md` §4.3 (links), §4.4 (search)
- `docs/ARCHITECTURE.md` "Coverage"
- `reviews/web-links-panel/review.md` (CR-4) and parent instructions (tests only inside `web/src/app/shared/links/`)

## Test files changed

- `web/src/app/shared/links/linked-items.spec.ts` (two tests added in this round, uncommitted)

`item-type.spec.ts` and `links.service.spec.ts` were reviewed and left unchanged.

## Tests added or updated

Count: 2 added in this round (`LinkedItems > adding`):
- `keeps showing "Loading links…" when a link is added during the first load`: covers the second half of CR-4 (while a load is pending, a created link must not replace the loading hint with a one-row list) and the first load case (the existing CR-4 test covers a `reload()`). Fails on the pre-fix append code.
- `replaces a load error with the reloaded list after a link is added`: "Add link" stays usable after a failed load. After a successful create the panel reloads, the error disappears and the new link shows. Fails on the pre-fix code, because the old append left `loadError` set.

The focused suite now has 70 tests: 51 in `linked-items.spec.ts`, 14 in `item-type.spec.ts`, 5 in `links.service.spec.ts`.

## Requirement coverage

F = `cd web && npx ng test --watch=false --include='src/app/shared/links/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 lists links with icon, title, subtitle, deep link, both directions | `lists the other end of links in both directions with icon, subtitle and deep link`, `ItemTypeIcon` / `itemTypeLabel` tests (all types) | F | Pass |
| AC1 unavailable items greyed out | `greys out unavailable items without a deep link, but keeps them removable` | F | Pass |
| AC1 loading / empty / load error + retry | `shows a loading hint and no count until the links arrive`, `shows an empty state`, `shows a load error with a retry` | F | Pass |
| AC1 load ordering (CR-1/TW-1) and item changes (CR-2) | `loading > ignores a list response for the previous item...`, `...ignores a failure of the previous item load...`, `keeps the newer links when an older load of the same item answers last`, `...fails last`, `changing the item > *` (7 tests) | F | Pass |
| AC1 remove link | `removes a link`, `shows the error when removing fails and keeps the link`, `disables the remove button while the removal runs`, `drops a link that was already removed elsewhere (404)...`, `shows an inline (SYNC) link without a remove button`, 4 focus-after-removal tests | F | Pass |
| AC2 search as you type over all types (search API) | `searches as you type and links the chosen item`, `searching > *` (11 tests); service `searches all types with the link search limit` | F | Pass |
| AC2 creates the link (mouse, keyboard, errors, 409, double submit) | `links the active result with the keyboard`, `adding > *` (links via Enter, only once, error keeps search open, 409 reload, item change, clears earlier error) | F | Pass |
| AC2 create during a pending or failed load (CR-4) | `keeps a link added while the list is reloading (CR-4)`, `keeps showing "Loading links…" when a link is added during the first load` (new), `replaces a load error with the reloaded list after a link is added` (new) | F | Pass |
| AC2 "create linked…" slot | `shows the create-linked slot and reloads on request`, `LinkedItems without create-linked actions > hides the "Create linked" row` | F | Pass |
| AC3 Vitest tests cover listing, adding via search, removal | all of the above | F | Pass (70/70) |
| AC3 `cd web && npm run check` passes | api check, lint, e2e typecheck, `test:ci`, `ng build` | `cd web && npm run check` | Pass (exit 0; 27 files, 429 tests) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/shared/links/**/*.spec.ts'`

Result:

Pass: 3 files, 70 tests passed. `npx prettier --check src/app/shared/links/` passes, and ESLint passes as part of `npm run check`.

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass: exit 0. `api:check` reports the generated client is up to date, `ng lint --max-warnings=0` reports all files pass, the e2e typecheck passes, `test:ci` runs 27 files with 429 tests (all passed), and `ng build` completes. The coverage run below ran the same 429 tests again and they passed.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` exited 0 ("all checks passed": server, web and android parts).

## Bugs found

None in this round. Round-1 TW-1 (an older load overwrites a newer one) is fixed by `loadSeq` in `31f1140b`, and its two tests pass. CR-4 is fixed in `4e5d4d94`. The two new tests confirm the fix also keeps the loading hint and clears a load error.

## Coverage

Status: verified (fresh `npm run coverage` run at HEAD `4e5d4d94` plus the uncommitted test change, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every production file in scope was added by this task (the diff from base to HEAD only adds files), so for this scope whole-file coverage equals changed-line coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-links-panel`; base `c7a3abd6485be1183f42e295798f0bb3539dfc1a`; HEAD `4e5d4d947f60fb0002db7929e6f5fdf3aaf9d931`; commits `afe5faed`, `bf41efa0`, `31f1140b`, `4e5d4d94` from the parent; files from `git diff --stat c7a3abd6..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `4e5d4d94` plus uncommitted `linked-items.spec.ts` sha256 `e28b0133e79a3ef2e8a5d47cda5f5b9777bbe3d5600d19d97a5a76804bc38c35`. Production sha256: `linked-items.ts` `9192688c4fe9...`, `linked-items.html` `c009a5f48768...`, `item-type.ts` `12e270a12869...`, `links.service.ts` `847e18649149...`, `linked-create.ts` `7a394c74f31e...`, `index.ts` `a5f1d7ef0641...`. Other specs: `item-type.spec.ts` `7d9b100035ba...`, `links.service.spec.ts` `9a1e90f06e96...`. The hashes were the same before and after the run.
- Exact included files: `web/src/app/shared/links/{links.service.ts, item-type.ts, linked-create.ts, linked-items.ts, linked-items.html}`. Excluded: `index.ts` (re-exports only, no executable statements; v8 emits no entry for it), `linked-items.scss` (styles), `*.spec.ts` (tests). Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` through `@angular/build:unit-test`; `npm run coverage` in `web/`; full web suite (27 files, 429 tests).
- Run start/completion time and exit status: 2026-10-20 17:57:08 to 17:57:37 +02:00, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 `0178bf8e6f58173c8e1af7533203b6338af30fd3739784b7d38096d316d31acc`; `web/coverage/web/coverage-final.json` sha256 `9c8d479fb29b5ff4018425c385383dcd74e57d66bcb93a56b5d4788925173be3`. Both have mtime 17:57:37 (written by this run).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote the report directory during this run, and the source hashes match before and after.
- Final run after latest test additions and production fixes: yes (it includes the CR-4 fix and the two new tests).

Values below use covered/total (percentage), read from the provider's `coverage-summary.json`. Uncovered locations come from `coverage-final.json`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/shared/links/links.service.ts` | 14/14 (100%) | 5/5 (100%) | 6/6 (100%) | 11/11 (100%) |
| `web/src/app/shared/links/item-type.ts` | 27/27 (100%) | 24/24 (100%) | 3/3 (100%) | 18/18 (100%) |
| `web/src/app/shared/links/linked-create.ts` | 3/3 (100%) | 5/5 (100%) | N/A (0/0) | 1/1 (100%) |
| `web/src/app/shared/links/linked-items.ts` | 168/169 (99.41%) | 75/79 (94.94%) | 50/51 (98.04%) | 135/135 (100%) |
| `web/src/app/shared/links/linked-items.html` | 149/149 (100%) | 24/24 (100%) | 8/8 (100%) | 93/93 (100%) |
| **Aggregate (summed counters, the 5 files above)** | 361/362 (99.72%) | 133/137 (97.08%) | 67/68 (98.53%) | 258/258 (100%) |

Not in the aggregate: `index.ts` (no executable code, no provider entry), `linked-items.scss` (styles). No scoped file with executable code is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `linked-items.ts:77` (statement + function) | Compiler-generated predicate of `contentChildren(LinkedCreate)` | Justified gap: Angular internal. The slot is tested with and without actions |
| `linked-items.ts:261` (`links ?? []` right side) | Append when the list is null | Justified gap: unreachable after the CR-4 fix, because the `else` branch only runs when `links()` is non-null. The null case is tested (reload path, 3 tests) |
| `linked-items.ts:296, 298` (`?? []`, `?? null`) | `dropLink` while the list is null | Justified gap: defensive. A removal needs a rendered row, and an item change during the removal is ignored through `itemSeq` (tested) |
| `linked-items.ts:305` (`?? []`) | Focus restore when the list element is absent | Justified gap: defensive. After a removal the list always renders |

## Unable to test

- Visual "greyed out" styling (opacity/colour in `linked-items.scss`): jsdom computes no styles. The tests check the `unavailable` class and that the deep link is missing. This needs follow-up only if a visual or E2E suite is wanted.
- Real deep-link navigation into feature routes: no feature route uses the panel yet (task assumption). The tests assert the `href` from `routerLink`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Two tests were added for the CR-4 paths (create during the first load, create after a failed load). The focused suite (70), `npm run check` (429 tests), `.taskcheck` and `npm run coverage` all pass at HEAD `4e5d4d94` plus the uncommitted spec change. The parent needs to commit `linked-items.spec.ts` with this report.
